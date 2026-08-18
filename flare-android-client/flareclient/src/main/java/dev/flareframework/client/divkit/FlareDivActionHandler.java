package dev.flareframework.client.divkit;

import android.net.Uri;
import android.util.Log;

import com.yandex.div.core.DivActionHandler;
import com.yandex.div.core.DivViewFacade;
import com.yandex.div.core.expression.variables.DivVariableController;
import com.yandex.div.data.Variable;
import com.yandex.div.json.expressions.ExpressionResolver;
import com.yandex.div2.DivAction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  FlareDivActionHandler
 *
 *  Intercepts all DivKit actions with the scheme "flare://action"
 *  and routes them to FlareClientActivity via FlareActionCallback.
 *
 *  PRIMARY PATH — url query params (no reflection needed):
 *  ─────────────────────────────────────────────
 *  action.url is schema-typed as an expression-resolvable string on
 *  every DivKit platform. By the time handleAction() runs below,
 *  `action.url.evaluate(resolver)` has already resolved every @{...}
 *  inside it — including encodeUri()/function calls and item_builder
 *  loop scope — into a real android.net.Uri. Reading params back out
 *  is then just Uri.getQueryParameter(key), which also auto
 *  percent-decodes. No reflection, no variable lookups, nothing that
 *  can silently break on a DivKit internal-structure change.
 *
 *  This is why new screens should write actions as:
 *      "url": "flare://action?flare_action=save&name=@{encodeUri(local_name)}"
 *  rather than putting dynamic values inside "payload".
 *
 *  LEGACY PATH — payload resolution via reflection (fallback only):
 *  ─────────────────────────────────────────────
 *  payload is schema-typed as a raw, opaque JSONObject — DivKit never
 *  resolves @{...} strings inside it on any platform. Older screen
 *  JSON that hasn't been migrated to the url-param form yet still
 *  puts dynamic values there, e.g. "first_name": "@{local_first_name}",
 *  arriving as that literal string rather than the resolved value.
 *
 *  For those screens only, we resolve @{varName} ourselves by reading
 *  DivVariableController directly. DivKit Kotlin hides its internal
 *  variables map from Java (no public get(name) API in the version we
 *  use), so we use a 3-tier fallback strategy:
 *
 *    Tier 1 — Kotlin getter method (getVariables / get):
 *             Works if DivKit exposes a public accessor. Fastest, no
 *             reflection on fields, most forward-compatible.
 *
 *    Tier 2 — Reflected field on the declared class:
 *             Scans declared fields of DivVariableController for the
 *             first Map field. Works for all DivKit versions we've
 *             shipped. This is the current reliable path.
 *
 *    Tier 3 — Reflected field walking superclass chain:
 *             If DivKit moves the map into a superclass (possible in
 *             future major versions), this catches it.
 *
 *  If all three fail, we return "" and log a warning — the server
 *  receives an empty string for that field, which is safe (the Elixir
 *  side trims and validates input anyway).
 *
 *  On key collisions between the two paths, url params win — that's
 *  the field DivKit actually contracts to resolve. Once every screen
 *  JSON is migrated to url-param form, this whole legacy tier (and
 *  the resolvePayload/tryKotlinAccessor/tryReflectedMap methods below)
 *  can be deleted.
 * ═══════════════════════════════════════════════════════════════════
 */
public class FlareDivActionHandler extends DivActionHandler {

    private static final String TAG = "FlareActionHandler";
    private static final String SCHEME_FLARE = "flare";
    private static final String HOST_ACTION  = "action";

    // ── LOCAL ENGINE ADDITIONS ───────────────────────────────────────────
    // New URL hosts, routed BEFORE any query parameter or payload field is
    // parsed — see LOCAL_ENGINE_PROTOCOL.md §2. "clienttask" (synchronous,
    // no result) and "clientplugin" (async, native capability, structured
    // result) are deliberately NOT named "command"/"feature" to avoid
    // colliding, in a developer's head, with FlareCommandHandler (which
    // executes SERVER-sent commands) or with generic "app feature" talk.
    private static final String HOST_CLIENT_TASK   = "clienttask";
    private static final String HOST_CLIENT_PLUGIN = "clientplugin";

    // Query-string keys reserved by the flare://clientplugin protocol
    // itself (LOCAL_ENGINE_PROTOCOL.md §3). Anything else in the URL is
    // treated as layout-supplied plugin input (Channel A) and forwarded
    // into the plugin's `params`.
    private static final java.util.Set<String> CLIENT_PLUGIN_RESERVED_KEYS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "plugin", "result_var", "on_success", "on_error", "on_cancel", "timeout_ms"
            ));

    // ── Callback interface ──────────────────────────────────────────────────
    public interface FlareActionCallback {
        /**
         * Called when a flare://action is tapped.
         *
         * MULTI-MOUNT UPDATE: now also passes the DivViewFacade that triggered
         * the action. FlareClientActivity uses this to figure out which Mount
         * (content / bottom_bar / top_bar / drawer / end_drawer / overlay) owns
         * the tap, so the event gets pushed on the CORRECT Phoenix channel and
         * the CORRECT mount's pending-action set is used — a tap in flight on
         * the bottom bar must never block a tap on the main content screen.
         *
         * @param actionType  the value of "flare_action" in the payload
         * @param payload     the full resolved payload JSONObject
         * @param view        the DivViewFacade (rendered Div2View) that fired this action
         */
        void onAction(String actionType, JSONObject payload, DivViewFacade view);

        /**
         * ── LOCAL ENGINE ADDITION ──────────────────────────────────────
         * Called for a flare://clienttask URL. Synchronous, on-device,
         * fire-and-forget — no result is ever produced. Default no-op so
         * this interface stays backward compatible with any existing
         * implementer that predates this addition.
         */
        default void onClientTask(String taskId, JSONObject params) {}

        /**
         * ── LOCAL ENGINE ADDITION ──────────────────────────────────────
         * Called for a flare://clientplugin URL. `invocation` bundles
         * plugin/result_var/params/expect_fields/on_success/on_error/
         * on_cancel/timeout_ms exactly per LOCAL_ENGINE_PROTOCOL.md §3.
         * Default no-op for backward compatibility.
         */
        default void onClientPlugin(String pluginId, JSONObject invocation, DivViewFacade view) {}
    }

    private final FlareActionCallback      callback;
    private final DivVariableController    variableController;

    // Cache the resolved Map field/method so we don't scan on every tap.
    // Volatile so the cached value is safely visible across threads.
    private volatile Map<?, ?> cachedVarsMap    = null;
    private volatile boolean   cacheAttempted   = false;

    public FlareDivActionHandler(FlareActionCallback callback,
                                 DivVariableController variableController) {
        this.callback           = callback;
        this.variableController = variableController;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DivActionHandler override
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public boolean handleAction(DivAction action,
                                DivViewFacade view,
                                ExpressionResolver resolver) {

        Uri url = (action.url != null) ? action.url.evaluate(resolver) : null;

        if (url != null && SCHEME_FLARE.equals(url.getScheme())) {

            // ── Route by host FIRST, per LOCAL_ENGINE_PROTOCOL.md §2 ──────
            // Nothing about the query string or payload is inspected before
            // this decision is made.
            String host = url.getHost();

            if (HOST_ACTION.equals(host)) {
                handleServerAction(url, action, view);
                return true;
            }

            if (HOST_CLIENT_TASK.equals(host)) {
                handleClientTask(url);
                return true;
            }

            if (HOST_CLIENT_PLUGIN.equals(host)) {
                handleClientPlugin(url, action, view);
                return true;
            }

            // Unrecognized flare:// host — ignore silently per protocol §2,
            // but leave a debug trace to help catch typos during development.
            Log.w(TAG, "Unrecognized flare:// host: '" + host + "' — ignoring");
            return true;
        }

        // Not a Flare action — let DivKit handle it normally (e.g. div-action://...)
        return super.handleAction(action, view, resolver);
    }

    /** flare://action — EXISTING, UNCHANGED behavior, only extracted into its own method. */
    private void handleServerAction(Uri url, DivAction action, DivViewFacade view) {
        try {
            // PRIMARY: pull params straight out of the resolved url. This
            // Uri is already fully expression-resolved by DivKit (see
            // action.url.evaluate(resolver) at the call site) — Uri.getQueryParameter()
            // also auto percent-decodes, so zero manual resolution needed.
            JSONObject urlParams = parseFlareActionUrl(url);

            // LEGACY: still resolved via reflection for any screen JSON
            // that hasn't been migrated to the url-param form yet.
            JSONObject rawPayload =
                    (action.payload != null) ? action.payload : new JSONObject();
            JSONObject resolvedPayload = resolvePayload(rawPayload);

            // url wins on key collisions — it's the field DivKit actually
            // contracts to resolve, so treat it as the source of truth.
            JSONObject payload = mergeJson(resolvedPayload, urlParams);

            String actionType = payload.optString("flare_action");

            if (actionType.isEmpty()) {
                Log.w(TAG, "Action missing 'flare_action' key — ignoring tap.");
                return;
            }

            // MULTI-MOUNT: pass `view` through so the Activity can identify
            // which Mount this tap belongs to (see FlareActionCallback above).
            callback.onAction(actionType, payload, view);

        } catch (Exception e) {
            Log.e(TAG, "Failed to handle DivKit action payload", e);
        }
    }

    /**
     * ── LOCAL ENGINE ADDITION ──────────────────────────────────────────
     * flare://clienttask?task=<id>&...params
     * Synchronous, no result — just forward the id + raw params straight
     * through to FlareClientActivity, which owns the actual dispatch.
     */
    private void handleClientTask(Uri url) {
        try {
            JSONObject urlParams = parseFlareActionUrl(url);
            String taskId = urlParams.optString("task");
            callback.onClientTask(taskId, urlParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle flare://clienttask payload", e);
        }
    }

    /**
     * ── LOCAL ENGINE ADDITION ──────────────────────────────────────────
     * flare://clientplugin?plugin=<id>&result_var=<name>&...
     * Builds the normalized "invocation" JSONObject described in
     * LOCAL_ENGINE_PROTOCOL.md §3, merging URL query params (minus the
     * protocol-reserved keys) and payload.params into a single `params`
     * object for the plugin, then hands it all to the Activity.
     */
    private void handleClientPlugin(Uri url, DivAction action, DivViewFacade view) {
        try {
            JSONObject urlParams = parseFlareActionUrl(url);
            JSONObject rawPayload = (action.payload != null) ? action.payload : new JSONObject();
            JSONObject resolvedPayload = resolvePayload(rawPayload);

            // Merge payload.params (if any) with non-reserved URL query
            // params into one flat `params` object — this is "Channel A"
            // (layout-supplied input) per protocol §5.1.
            JSONObject pluginParams = new JSONObject();
            JSONObject payloadParams = resolvedPayload.optJSONObject("params");
            if (payloadParams != null) {
                java.util.Iterator<String> payloadKeys = payloadParams.keys();
                while (payloadKeys.hasNext()) {
                    String key = payloadKeys.next();
                    pluginParams.put(key, payloadParams.get(key));
                }
            }
            java.util.Iterator<String> urlKeys = urlParams.keys();
            while (urlKeys.hasNext()) {
                String key = urlKeys.next();
                if (!CLIENT_PLUGIN_RESERVED_KEYS.contains(key)) {
                    pluginParams.put(key, urlParams.get(key));
                }
            }

            JSONObject invocation = new JSONObject();
            invocation.put("plugin", urlParams.optString("plugin"));
            invocation.put("result_var", urlParams.optString("result_var"));
            if (urlParams.has("on_success")) invocation.put("on_success", urlParams.optString("on_success"));
            if (urlParams.has("on_error"))   invocation.put("on_error", urlParams.optString("on_error"));
            if (urlParams.has("on_cancel"))  invocation.put("on_cancel", urlParams.optString("on_cancel"));
            invocation.put("timeout_ms", urlParams.has("timeout_ms") ? urlParams.optLong("timeout_ms") : 0L);
            invocation.put("params", pluginParams);

            // expect_fields is only ever read from payload — it's a static
            // projection instruction, not something that needs @{} resolution.
            Object expectFields = resolvedPayload.opt("expect_fields");
            if (expectFields instanceof JSONArray) {
                invocation.put("expect_fields", expectFields);
            }

            callback.onClientPlugin(urlParams.optString("plugin"), invocation, view);
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle flare://clientplugin payload", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  url query-param parsing (primary path)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Extracts query params from an already-resolved "flare://action?..."
     * Uri. `url` was produced by action.url.evaluate(resolver) in
     * handleAction() above, so every @{...} inside it — including
     * encodeUri()/function calls and item_builder loop scope — is already
     * resolved. Uri.getQueryParameter() also auto percent-decodes, so this
     * needs no reflection and no manual expression handling at all.
     */
    private JSONObject parseFlareActionUrl(Uri url) throws Exception {
        JSONObject params = new JSONObject();
        for (String key : url.getQueryParameterNames()) {
            String value = url.getQueryParameter(key);
            if (value != null) {
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * Merges two JSONObjects, with values from `override` taking priority
     * over `base` on key collisions. Used to let url params (fully resolved,
     * trusted) win over legacy reflected payload values on the same key.
     */
    private JSONObject mergeJson(JSONObject base, JSONObject override) throws Exception {
        JSONObject merged = new JSONObject(base.toString());
        Iterator<String> keys = override.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            merged.put(key, override.get(key));
        }
        return merged;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Payload resolution
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Walk the payload JSON and resolve any @{varName} strings to their
     * current DivKit variable values. Non-expression values are passed through
     * unchanged. Nested objects and arrays are NOT recursed — Flare payloads
     * are always flat key/value maps.
     */
    private JSONObject resolvePayload(JSONObject raw) throws Exception {
        JSONObject resolved = new JSONObject();
        Iterator<String> keys = raw.keys();
        while (keys.hasNext()) {
            String key   = keys.next();
            Object value = raw.get(key);
            if (value instanceof String) {
                resolved.put(key, resolveExpression((String) value));
            } else {
                // Boolean, Integer, Long, Double — pass through as-is
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    /**
     * If the string matches the pattern @{varName}, look up the variable's
     * current value and return it as a String. Otherwise return the string
     * unchanged.
     *
     * Falls back to "" if the variable cannot be found, logging a warning
     * so the developer knows which variable was unresolvable.
     */
    // Tracks whether ALL resolution tiers have ever failed in this process.
    // Once true, we stop silently swallowing failures — see comment below.
    private static volatile boolean allTiersConfirmedBroken = false;

    private String resolveExpression(String raw) {
        if (!raw.startsWith("@{") || !raw.endsWith("}")) {
            return raw; // plain string — not an expression
        }

        String varName = raw.substring(2, raw.length() - 1).trim();

        // ── Tier 1: Try Kotlin-generated getter methods ──────────────────
        // DivKit may expose getVariables() or get(name) depending on version.
        String tier1 = tryKotlinAccessor(varName);
        if (tier1 != null) return tier1;

        // ── Tier 2 + 3: Reflection on field map ─────────────────────────
        String tier2 = tryReflectedMap(varName);
        if (tier2 != null) return tier2;

        // ─────────────────────────────────────────────────────────────────
        // ALL TIERS FAILED FOR THIS VARIABLE.
        //
        // This almost always means the DivKit version in use has changed its
        // internal structure (renamed/relocated the variables map, or removed
        // the getVariables()/get(name) accessors) and our reflection fallback
        // can no longer find it. Silently returning "" here is dangerous:
        // form fields, payment amounts, search queries, etc. would silently
        // submit empty strings to the server with no visible symptom beyond
        // "this feature doesn't work" — very hard to debug in production.
        //
        // We log at ERROR (always visible, unlike DEBUG/WARN which may be
        // stripped in release builds) AND throw in debug builds so this is
        // caught in development rather than shipped silently. In release
        // builds we still return "" so we fail soft for end users, but the
        // ERROR log line is permanent and unconditional.
        // ─────────────────────────────────────────────────────────────────
        Log.e(TAG, "FLARE INTEGRITY ERROR: Could not resolve @{" + varName + "} via any tier. " +
                "This usually means the installed DivKit version changed its internal " +
                "variable storage and Flare's reflection fallback (tryKotlinAccessor / " +
                "tryReflectedMap) is no longer compatible. Check the DivKit version in " +
                "build.gradle against the version Flare was last verified against, and " +
                "file an issue at the Flare repo with both version numbers.");

        allTiersConfirmedBroken = true;

        // NOTE: We deliberately do NOT gate this on BuildConfig.DEBUG. Newer
        // Android Gradle Plugin versions disable BuildConfig generation by
        // default (buildConfig = true must be opted into per-module), so
        // relying on it here would make Flare's compile success dependent
        // on a host app's gradle settings — not acceptable for a library.
        // The Log.e above is unconditional and always visible, which gives
        // the same "fail loudly in development" benefit without a hard
        // dependency on BuildConfig existing at all.
        return "";
    }

    /**
     * Returns true if variable resolution has failed at least once via all tiers
     * during this process lifetime. Host apps can poll this (e.g. in a health
     * check or analytics ping) to detect a broken DivKit upgrade in production
     * without needing the user to report a silent form-submission bug.
     */
    public static boolean hasResolutionEverFailedCompletely() {
        return allTiersConfirmedBroken;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Tier 1 — Kotlin accessor methods
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Attempt to call public Kotlin-generated accessor methods on
     * DivVariableController. These are the cleanest paths and don't
     * depend on internal field layout.
     *
     * Returns the variable value as a String, or null if unavailable.
     */
    @SuppressWarnings("unchecked")
    private String tryKotlinAccessor(String varName) {
        // Attempt: get(String) — a direct single-variable getter
        try {
            Method getMethod = variableController.getClass().getMethod("get", String.class);
            Object result    = getMethod.invoke(variableController, varName);
            if (result instanceof Variable) {
                Object val = ((Variable) result).getValue();
                if (val != null) {
                    Log.d(TAG, "Tier1(get): resolved @{" + varName + "} = " + val);
                    return val.toString();
                }
            }
        } catch (NoSuchMethodException ignored) {
            // Method doesn't exist in this DivKit version — try next
        } catch (Exception e) {
            Log.d(TAG, "Tier1(get) failed for " + varName + ": " + e.getMessage());
        }

        // Attempt: getVariables() — returns the full map
        try {
            Method mapMethod = variableController.getClass().getMethod("getVariables");
            Object result    = mapMethod.invoke(variableController);
            if (result instanceof Map) {
                Map<String, Variable> vars = (Map<String, Variable>) result;
                Variable v = vars.get(varName);
                if (v != null && v.getValue() != null) {
                    Log.d(TAG, "Tier1(getVariables): resolved @{" + varName + "} = " + v.getValue());
                    return v.getValue().toString();
                }
            }
        } catch (NoSuchMethodException ignored) {
            // Method doesn't exist in this DivKit version — fall through to reflection
        } catch (Exception e) {
            Log.d(TAG, "Tier1(getVariables) failed for " + varName + ": " + e.getMessage());
        }

        return null; // Tier 1 could not resolve
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Tier 2 + 3 — Reflected Map field (declared class + superclass chain)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Access the internal variables map via reflection, checking the declared
     * class first (Tier 2) then walking the superclass chain (Tier 3).
     *
     * The result map is cached after the first successful find so subsequent
     * taps pay zero reflection cost.
     *
     * Returns the variable value as a String, or null if not found.
     */
    private String tryReflectedMap(String varName) {
        Map<?, ?> vars = getOrFindVarsMap();
        if (vars == null) return null;

        Object entry = vars.get(varName);
        if (entry instanceof Variable) {
            Object val = ((Variable) entry).getValue();
            if (val != null) {
                Log.d(TAG, "Tier2/3(reflect): resolved @{" + varName + "} = " + val);
                return val.toString();
            }
        }
        return null;
    }

    /**
     * Returns the cached variables map, or searches for it by reflection
     * and caches the result. Returns null if it cannot be found.
     *
     * Thread-safe: uses double-checked locking on the volatile flag.
     */
    private Map<?, ?> getOrFindVarsMap() {
        if (cachedVarsMap != null) return cachedVarsMap;
        if (cacheAttempted)        return null; // already tried and failed

        synchronized (this) {
            if (cacheAttempted) return cachedVarsMap;
            cacheAttempted = true;
            cachedVarsMap  = findVarsMapByReflection();
        }
        return cachedVarsMap;
    }

    /**
     * Walk the class hierarchy of DivVariableController looking for the
     * first field whose type is assignable from Map. That field holds
     * the variable store.
     *
     * Tier 2: getDeclaredFields() on the concrete class (fast path, current DivKit).
     * Tier 3: walk getSuperclass() chain (future-proofing).
     */
    private Map<?, ?> findVarsMapByReflection() {
        Class<?> cls = variableController.getClass();

        // Walk the full inheritance chain
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object candidate = field.get(variableController);
                        if (candidate instanceof Map) {
                            Log.d(TAG, "Reflected variables map found in: "
                                    + cls.getSimpleName() + "." + field.getName());
                            return (Map<?, ?>) candidate;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not access field "
                                + cls.getSimpleName() + "." + field.getName(), e);
                    }
                }
            }
            cls = cls.getSuperclass(); // Tier 3: climb the superclass chain
        }

        Log.e(TAG, "Could not find DivKit variables map via reflection. "
                + "DivKit internal structure may have changed — check for a new version.");
        return null;
    }
}



