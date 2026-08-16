package com.example.flare_android_client.flare.plugin;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.yandex.div.core.expression.variables.DivVariableController;
import com.yandex.div.data.Variable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientPluginEngine
 *
 *  The ROUTER described in LOCAL_ENGINE_PROTOCOL.md §4. This class must
 *  NEVER contain any logic specific to a particular plugin id — no
 *  switch/case/if-chain keyed on "file_pick" vs "gps_location" etc. If
 *  you find yourself wanting to add one, that logic belongs inside a
 *  plugin file instead, not here.
 *
 *  Responsibilities, and NOTHING more:
 *    1. Look up the plugin id in FlareClientPluginRegistry.
 *    2. If missing -> not-found handling (toast + "unavailable" result).
 *    3. If found -> launch it, with:
 *         - an engine-owned timeout clock (protocol §8)
 *         - a try/catch boundary around the launch() call itself
 *           (protocol §9.1)
 *         - exactly-once callback protection, defensively enforced
 *           (protocol §8 / §9.3) — a duplicate/late callback is a no-op
 *         - main-thread marshaling of the eventual result (protocol §9.4)
 *    4. Before writing the result: verify the ORIGINATING mount is still
 *       live (protocol §11) — if the user navigated away, the result is
 *       dropped silently rather than written into a stale screen.
 *    5. Apply expect_fields projection (protocol §6.2) — a shallow,
 *       type-blind allowlist filter. This is the ONLY inspection the
 *       engine is allowed to do on a plugin's `data` — it never reads or
 *       branches on any field's VALUE, only filters by key name.
 *    6. Write the (possibly projected) envelope to the named DivKit Dict
 *       variable.
 *    7. Fire on_success/on_error/on_cancel, if named, as an ORDINARY
 *       flare_action — reusing the exact same pending-lock + channel-push
 *       path that a normal user tap already uses. This engine never talks
 *       to Phoenix directly.
 * ═══════════════════════════════════════════════════════════════
 */
public final class FlareClientPluginEngine {

    private static final String TAG = "FlareClientPluginEngine";

    // Chosen default per LOCAL_ENGINE_PROTOCOL.md Part C — an open
    // implementation choice, not a frozen protocol value. Override per
    // call site with &timeout_ms=... in the flare://clientplugin URL.
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** Lets the engine ask "is the screen/mount that started this still alive?" (protocol §11). */
    public interface MountLivenessCheck {
        boolean isMountStillLive(String screenName);
    }

    /** Lets the engine fire on_success/on_error/on_cancel through the existing action-dispatch path. */
    public interface ClientActionFirer {
        void fireLocalAction(String actionName, String originScreenName);
    }

    private final Activity host;
    private final DivVariableController variableController;
    private final FlareClientPluginContext context;
    private final MountLivenessCheck livenessCheck;
    private final ClientActionFirer actionFirer;

    // All timeout/callback marshaling goes through the main-thread Handler,
    // never a background thread — this is what guarantees DivKit state is
    // only ever touched from the UI thread, regardless of which thread a
    // plugin's own callback happens to arrive on.
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    public FlareClientPluginEngine(Activity host,
                                   DivVariableController variableController,
                                   FlareClientPluginContext context,
                                   MountLivenessCheck livenessCheck,
                                   ClientActionFirer actionFirer) {
        this.host = host;
        this.variableController = variableController;
        this.context = context;
        this.livenessCheck = livenessCheck;
        this.actionFirer = actionFirer;
    }

    /**
     * Entry point called by FlareClientActivity once a flare://clientplugin
     * URL has been parsed. Every parameter here mirrors the URL/payload
     * shape defined in LOCAL_ENGINE_PROTOCOL.md §3.
     */
    public void dispatch(String pluginId, String resultVar, JSONObject params,
                         JSONArray expectFields, String onSuccess, String onError,
                         String onCancel, long timeoutMsOverride, String originScreenName) {

        // ── Guard: malformed invocation ──────────────────────────────────
        // A missing plugin id or result_var means this call can never be
        // resolved meaningfully — log loudly and bail out rather than
        // guessing at defaults.
        if (pluginId == null || pluginId.trim().isEmpty() || resultVar == null || resultVar.trim().isEmpty()) {
            Log.e(TAG, "dispatch() missing plugin id or result_var — ignoring invocation");
            return;
        }

        FlareClientPlugin plugin = FlareClientPluginRegistry.get(pluginId);

        // ── Not-found path (protocol §10) ────────────────────────────────
        if (plugin == null) {
            Log.e(TAG, "Client plugin not found: '" + pluginId + "' — is it registered yet?");
            // Non-blocking, dismissible, no confirmation required.
            showNotFoundToast();
            resolve(FlareClientPluginResult.unavailable(pluginId), resultVar, expectFields,
                    onSuccess, onError, onCancel, originScreenName);
            return;
        }

        long timeoutMs = timeoutMsOverride > 0 ? timeoutMsOverride : DEFAULT_TIMEOUT_MS;

        // AtomicBoolean gives us a cheap, thread-safe "has this already
        // resolved?" flag so the exactly-once discipline (protocol §8/§9.3)
        // holds true regardless of which thread wins the race between the
        // timeout firing and the plugin's own callback arriving.
        AtomicBoolean alreadyResolved = new AtomicBoolean(false);

        Runnable timeoutRunnable = () -> {
            if (alreadyResolved.compareAndSet(false, true)) {
                Log.w(TAG, "Client plugin '" + pluginId + "' timed out after " + timeoutMs + "ms");
                resolve(FlareClientPluginResult.timeout(), resultVar, expectFields,
                        onSuccess, onError, onCancel, originScreenName);
            }
            // else: the plugin's own callback already won the race — nothing to do.
        };
        mainThreadHandler.postDelayed(timeoutRunnable, timeoutMs);

        // The callback handed to the plugin. Always hops back to the main
        // thread FIRST (protocol §9.4), before any exactly-once check or
        // DivKit-facing work happens — this is true regardless of which
        // thread the plugin itself calls back on.
        FlareClientPluginCallback callback = result -> mainThreadHandler.post(() -> {
            if (!alreadyResolved.compareAndSet(false, true)) {
                // Either the timeout already fired, or the plugin buggily
                // called back twice. Either way: never crash, never double-write.
                Log.d(TAG, "Ignoring duplicate/late callback for client plugin: " + pluginId);
                return;
            }
            mainThreadHandler.removeCallbacks(timeoutRunnable);
            resolve(result, resultVar, expectFields, onSuccess, onError, onCancel, originScreenName);
        });

        // ── The one and only place a plugin's own code is actually invoked ──
        // Wrapped per protocol §9.1: an exception thrown here must never
        // propagate into FlareClientActivity/FlareDivActionHandler.
        try {
            JSONObject safeParams = params != null ? params : new JSONObject();
            plugin.launch(host, safeParams, context, callback);
        } catch (Exception e) {
            Log.e(TAG, "Client plugin '" + pluginId + "' threw an exception during launch()", e);
            if (alreadyResolved.compareAndSet(false, true)) {
                mainThreadHandler.removeCallbacks(timeoutRunnable);
                resolve(FlareClientPluginResult.unknown(e), resultVar, expectFields,
                        onSuccess, onError, onCancel, originScreenName);
            }
        }
    }

    /**
     * Runs once, exactly once, per invocation — writes the (possibly
     * projected) result and fires the matching follow-up action if any.
     * Always called on the main thread by this point.
     */
    private void resolve(FlareClientPluginResult result, String resultVar, JSONArray expectFields,
                         String onSuccess, String onError, String onCancel, String originScreenName) {
        try {
            // ── Mount-liveness check (protocol §11) ──────────────────────
            // If the screen that started this plugin is no longer the
            // currently active mount (user navigated away, tab switched,
            // etc.), silently drop the result rather than writing into a
            // stale/nonexistent screen.
            if (livenessCheck != null && originScreenName != null
                    && !livenessCheck.isMountStillLive(originScreenName)) {
                Log.d(TAG, "Dropping client plugin result — origin mount '" + originScreenName + "' no longer live");
                return;
            }

            JSONObject projectedEnvelope = applyExpectFieldsProjection(result, expectFields);

            // Written as a Dict variable so every field is directly
            // addressable in layout JSON, e.g. "@{my_result.data.file_url}".
            variableController.putOrUpdate(new Variable.DictVariable(resultVar, projectedEnvelope));

            // Decide which (if any) follow-up flare_action to fire based on status.
            String actionToFire;
            switch (result.status) {
                case "ok":        actionToFire = onSuccess; break;
                case "cancelled": actionToFire = onCancel;  break;
                default:          actionToFire = onError;   break; // "error" or "unavailable"
            }

            if (actionToFire != null && !actionToFire.trim().isEmpty() && actionFirer != null) {
                actionFirer.fireLocalAction(actionToFire, originScreenName);
            }
        } catch (Exception e) {
            // Absolute last line of defense — resolve() itself must never
            // crash the caller, no matter what.
            Log.e(TAG, "Error resolving client plugin result", e);
        }
    }

    /**
     * Shallow, type-blind allowlist filter per protocol §6.2. Only applies
     * to a successful result's `data`; status/error always pass through
     * untouched. A key in expect_fields that the plugin didn't return is
     * simply absent from the output — never treated as an error.
     */
    private JSONObject applyExpectFieldsProjection(FlareClientPluginResult result, JSONArray expectFields) {
        JSONObject envelope = result.toJson();

        if (expectFields == null || expectFields.length() == 0) return envelope;
        if (!"ok".equals(result.status) || result.data == null) return envelope;

        try {
            Set<String> allowedKeys = new HashSet<>();
            for (int i = 0; i < expectFields.length(); i++) {
                allowedKeys.add(expectFields.optString(i));
            }

            JSONObject trimmedData = new JSONObject();
            Iterator<String> keys = result.data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (allowedKeys.contains(key)) {
                    trimmedData.put(key, result.data.get(key));
                }
            }
            envelope.put("data", trimmedData);
        } catch (Exception e) {
            // If projection itself fails for any reason, fail SAFE by
            // falling back to the unprojected envelope rather than
            // crashing or dropping the whole result.
            Log.e(TAG, "Error applying expect_fields projection — returning unfiltered result", e);
            return result.toJson();
        }
        return envelope;
    }

    /** Brief, non-blocking, dismissible notice — no confirmation required (protocol §10). */
    private void showNotFoundToast() {
        try {
            host.runOnUiThread(() ->
                    Toast.makeText(host, "This feature isn't available", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            // Even showing a Toast must never be allowed to crash the app.
            Log.e(TAG, "Failed to show not-found toast", e);
        }
    }
}