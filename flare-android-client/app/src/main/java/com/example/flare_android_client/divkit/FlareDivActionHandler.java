package com.example.flare_android_client.divkit;

import android.net.Uri;
import android.util.Log;

import com.yandex.div.core.DivActionHandler;
import com.yandex.div.core.DivViewFacade;
import com.yandex.div.core.expression.variables.DivVariableController;
import com.yandex.div.data.Variable;
import com.yandex.div.json.expressions.ExpressionResolver;
import com.yandex.div2.DivAction;

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
 *  WHY WE NEED REFLECTION FOR PAYLOAD RESOLUTION:
 *  ─────────────────────────────────────────────
 *  DivKit's ExpressionResolver (passed to handleAction) resolves
 *  layout-bound expressions in view properties — NOT arbitrary
 *  @{varName} strings sitting inside action.payload (a raw JSONObject).
 *
 *  The payload is opaque JSON. DivKit does not auto-resolve expressions
 *  inside it. This means form values like:
 *      "first_name": "@{local_first_name}"
 *  arrive as the literal string "@{local_first_name}", not the value.
 *
 *  To resolve them we must read the variable value ourselves from
 *  DivVariableController. DivKit Kotlin hides its internal variables
 *  map from Java (no public get(name) API in the version we use), so
 *  we use a 3-tier fallback strategy:
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
 * ═══════════════════════════════════════════════════════════════════
 */
public class FlareDivActionHandler extends DivActionHandler {

    private static final String TAG = "FlareActionHandler";
    private static final String SCHEME_FLARE = "flare";
    private static final String HOST_ACTION  = "action";

    // ── Callback interface ──────────────────────────────────────────────────
    public interface FlareActionCallback {
        /**
         * Called when a flare://action is tapped.
         *
         * @param actionType  the value of "flare_action" in the payload
         * @param payload     the full resolved payload JSONObject
         */
        void onAction(String actionType, JSONObject payload);
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

        if (url != null
                && SCHEME_FLARE.equals(url.getScheme())
                && HOST_ACTION.equals(url.getHost())) {

            try {
                JSONObject rawPayload =
                        (action.payload != null) ? action.payload : new JSONObject();

                // Resolve @{varName} expressions inside the payload JSON
                JSONObject resolvedPayload = resolvePayload(rawPayload);

                String actionType = resolvedPayload.optString("flare_action");

                if (actionType.isEmpty()) {
                    Log.w(TAG, "Action payload missing 'flare_action' key — ignoring tap.");
                    return true;
                }

                callback.onAction(actionType, resolvedPayload);

            } catch (Exception e) {
                Log.e(TAG, "Failed to handle DivKit action payload", e);
            }

            return true; // always consume flare:// actions
        }

        // Not a Flare action — let DivKit handle it normally (e.g. div-action://...)
        return super.handleAction(action, view, resolver);
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



