// Location: app/src/main/java/com/example/flare_android_client/flare/FlareCommandHandler.java
package dev.flareframework.client.flare.channel;

import android.app.AlertDialog;
import android.util.Log;

import dev.flareframework.client.FlareClientActivity;
import dev.flareframework.client.flare.task.FlareClientTaskEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Executes the "commands" array sent by the server inside init/patch envelopes.
 */
public final class FlareCommandHandler {

    private static final String TAG = "FlareCommandHandler";

    private FlareCommandHandler() {} // static-only class

    /**
     * @param envelope             the full "init" or "patch" envelope JSON
     * @param activity             the Activity, used for scaffold commands and haptics
     * @param navigateCallback     (screen, params) callback
     * @param storeTokenCallback   Consumer<String> — receives the token to persist
     * @param clearStorageCallback Runnable — logout / clear-storage
     * @param hapticCallback       Consumer<String> — haptic style string
     */
    public static void execute(
            JSONObject envelope,
            FlareClientActivity activity,
            BiConsumer<String, JSONObject> navigateCallback,
            Consumer<String> storeTokenCallback,
            Runnable clearStorageCallback,
            Consumer<String> hapticCallback
    ) {
        JSONArray commands = envelope.optJSONArray("commands");
        if (commands == null) return;

        for (int i = 0; i < commands.length(); i++) {
            JSONObject cmd = commands.optJSONObject(i);
            if (cmd == null) continue;

            String type = cmd.optString("type");
            JSONObject cmdPayload = cmd.optJSONObject("payload");
            if (cmdPayload == null) cmdPayload = new JSONObject();

            Log.d(TAG, "Executing command: " + type + " payload=" + cmdPayload);

            switch (type) {

                case "navigate": {
                    String screen = cmdPayload.optString("screen", null);
                    JSONObject params = cmdPayload.optJSONObject("params");
                    if (screen != null) {
                        navigateCallback.accept(screen, params);
                    } else {
                        Log.w(TAG, "navigate command missing 'screen' — ignoring");
                    }
                    break;
                }

                case "show_alert": {
                    String title   = cmdPayload.optString("title", "");
                    String message = cmdPayload.optString("message", "");
                    new AlertDialog.Builder(activity)
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton(cmdPayload.optString("button", "OK"), null)
                            .show();
                    break;
                }

                case "store_token": {
                    String token = cmdPayload.optString("token", null);
                    if (token != null) storeTokenCallback.accept(token);
                    break;
                }

                case "clear_storage": {
                    clearStorageCallback.run();
                    break;
                }

                case "haptic": {
                    hapticCallback.accept(cmdPayload.optString("style", "success"));
                    break;
                }

                case "hide_scaffold": {
                    String region = cmdPayload.optString("region", null);
                    if (region != null) {
                        activity.hideScaffold(region);
                    }
                    break;
                }

                case "show_scaffold": {
                    String region = cmdPayload.optString("region", null);
                    if (region != null) {
                        activity.showScaffold(region);
                    }
                    break;
                }

                case "run_task": {
                    String taskId = cmdPayload.optString("task", null);
                    JSONObject taskParams = cmdPayload.optJSONObject("params");
                    if (taskId != null) {
                        FlareClientTaskEngine.dispatch(activity, taskId, taskParams != null ? taskParams : new JSONObject());
                    } else {
                        Log.w(TAG, "Command 'run_task' missing required 'task' identifier.");
                    }
                    break;
                }

                default:
                    Log.w(TAG, "Unknown command type: " + type);
            }
        }
    }
}