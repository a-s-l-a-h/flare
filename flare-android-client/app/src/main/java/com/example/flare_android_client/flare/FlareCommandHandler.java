package com.example.flare_android_client.flare;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 * Executes imperative commands sent from the Server.
 *
 * The server maps `Flare.Commands.show_alert(...)` into a JSON
 * block. This class interprets that block and executes it.
 * ═══════════════════════════════════════════════════════════════
 */
public class FlareCommandHandler {

    private static final String TAG = "FlareCommands";

    // Callbacks to trigger actions inside FlareClientActivity
    public interface NavigateCallback { void onNavigate(String screen, JSONObject params); }
    public interface TokenCallback { void onStoreToken(String token); }
    public interface SimpleCallback { void onAction(); }
    public interface HapticCallback { void onHaptic(String style); }

    public static void execute(JSONObject patchEnvelope,
                               Context context,
                               NavigateCallback navCb,
                               TokenCallback storeCb,
                               SimpleCallback clearCb,
                               HapticCallback hapticCb) {

        JSONArray commands = patchEnvelope.optJSONArray("commands");
        if (commands == null) return;

        for (int i = 0; i < commands.length(); i++) {
            JSONObject cmd = commands.optJSONObject(i);
            if (cmd == null) continue;

            String type = cmd.optString("type");
            JSONObject payload = cmd.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

            Log.d(TAG, "Executing command: " + type);

            switch (type) {
                case "navigate":
                    String screen = payload.optString("screen");
                    JSONObject params = payload.optJSONObject("params");
                    navCb.onNavigate(screen, params);
                    break;

                case "show_alert":
                    String title = payload.optString("title", "Alert");
                    String msg = payload.optString("message", "");
                    String btn = payload.optString("button", "OK");

                    new AlertDialog.Builder(context)
                            .setTitle(title)
                            .setMessage(msg)
                            .setPositiveButton(btn, null)
                            .show();
                    break;

                case "store_token":
                    storeCb.onStoreToken(payload.optString("token"));
                    break;

                case "clear_storage":
                    clearCb.onAction();
                    break;

                case "haptic":
                    hapticCb.onHaptic(payload.optString("style", "success"));
                    break;

                default:
                    Log.w(TAG, "Unknown server command received: " + type);
                    break;
            }
        }
    }
}