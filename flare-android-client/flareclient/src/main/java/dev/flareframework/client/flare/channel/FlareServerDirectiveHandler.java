package dev.flareframework.client.flare.channel;

import android.app.AlertDialog;
import android.util.Log;

import dev.flareframework.client.FlareClientActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Executes the "directives" array sent by the server inside init/patch envelopes.
 *
 * Unrelated to FlareClientTask/FlareClientPlugin — those are client-initiated
 * (a DivKit tap), this is server-initiated (the Elixir screen decided to do
 * something). The "Server" prefix on this class name exists specifically to
 * keep that distinction visually obvious next to the "Client" prefix used
 * throughout the local task/plugin engine.
 */
public final class FlareServerDirectiveHandler {

    private static final String TAG = "FlareServerDirectiveHandler";

    private FlareServerDirectiveHandler() {} // static-only class

    /**
     * @param envelope             the full "init" or "patch" envelope JSON
     * @param activity             the Activity, used for scaffold directives and haptics
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
        JSONArray directives = envelope.optJSONArray("directives");
        if (directives == null) return;

        for (int i = 0; i < directives.length(); i++) {
            JSONObject directive = directives.optJSONObject(i);
            if (directive == null) continue;

            String type = directive.optString("type");
            JSONObject directivePayload = directive.optJSONObject("payload");
            if (directivePayload == null) directivePayload = new JSONObject();

            Log.d(TAG, "Executing directive: " + type + " payload=" + directivePayload);

            switch (type) {

                case "navigate": {
                    String screen = directivePayload.optString("screen", null);
                    JSONObject params = directivePayload.optJSONObject("params");
                    if (screen != null) {
                        navigateCallback.accept(screen, params);
                    } else {
                        Log.w(TAG, "navigate directive missing 'screen' — ignoring");
                    }
                    break;
                }

                case "show_alert": {
                    String title   = directivePayload.optString("title", "");
                    String message = directivePayload.optString("message", "");
                    new AlertDialog.Builder(activity)
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton(directivePayload.optString("button", "OK"), null)
                            .show();
                    break;
                }

                case "store_login_token": {
                    String token = directivePayload.optString("token", null);
                    if (token != null) storeTokenCallback.accept(token);
                    break;
                }

                case "clear_login_token": {
                    clearStorageCallback.run();
                    break;
                }

                case "haptic": {
                    hapticCallback.accept(directivePayload.optString("style", "success"));
                    break;
                }

                case "hide_scaffold": {
                    String region = directivePayload.optString("region", null);
                    if (region != null) {
                        activity.hideScaffold(region);
                    }
                    break;
                }

                case "show_scaffold": {
                    String region = directivePayload.optString("region", null);
                    if (region != null) {
                        activity.showScaffold(region);
                    }
                    break;
                }

                default:
                    Log.w(TAG, "Unknown directive type: " + type);
            }
        }
    }
}