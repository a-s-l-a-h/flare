package com.example.flare_android_client.flare.task;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientTaskEngine
 *
 *  The router for flare://clienttask. Deliberately tiny compared to
 *  FlareClientPluginEngine — tasks are synchronous and never return a
 *  result, so there's no timeout, no callback, no mount-liveness check,
 *  no envelope to build. Just: look up, run, isolate crashes.
 * ═══════════════════════════════════════════════════════════════
 */
public final class FlareClientTaskEngine {

    private static final String TAG = "FlareClientTaskEngine";

    private FlareClientTaskEngine() {}

    public static void dispatch(Activity host, String taskId, JSONObject params) {
        if (taskId == null || taskId.trim().isEmpty()) {
            Log.e(TAG, "dispatch() called with an empty task id — ignoring");
            return;
        }

        FlareClientTask task = FlareClientTaskRegistry.get(taskId);
        if (task == null) {
            Log.e(TAG, "Client task not found: '" + taskId + "' — is it registered yet?");
            try {
                host.runOnUiThread(() -> Toast.makeText(host, "Action unavailable", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Failed to show not-found toast for task", e);
            }
            return;
        }

        // Crash isolation (protocol §9.1) applies to tasks too — a bug in
        // one task's execute() must never take down the whole app.
        try {
            task.execute(host, params != null ? params : new JSONObject());
        } catch (Exception e) {
            Log.e(TAG, "Client task '" + taskId + "' threw an exception during execute()", e);
        }
    }
}