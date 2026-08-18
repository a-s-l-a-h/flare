package dev.flareframework.client.flare.plugin;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Map;
import java.util.UUID;

/**
 * Dynamic runtime-permission helper for FlareClientPlugin authors.
 *
 * WHY THIS EXISTS:
 * The AndroidX registerForActivityResult() CONVENIENCE wrapper must be
 * called before the host Activity reaches STARTED (normally in onCreate).
 * A FlareClientPlugin's launch() runs whenever the user taps a button —
 * long after STARTED — so calling that convenience wrapper from inside
 * launch() throws IllegalStateException and crashes the app.
 *
 * THE FIX: call the RAW ActivityResultRegistry.register() API instead.
 * AndroidX documents this as safe to call at any time, from any code
 * path, including from inside launch(). This requires no change to
 * FlareClientPlugin, no onCreate() wiring, and no protocol change —
 * it is purely an implementation detail available to any plugin author.
 *
 * Each call uses a fresh UUID key and unregisters itself immediately
 * after the result arrives, so concurrent permission requests from
 * different plugins never collide — this satisfies
 * LOCAL_ENGINE_PROTOCOL.md §9.2 (no shared request-code space)
 * automatically, with zero coordination required between plugin authors.
 *
 * USAGE (inside a plugin's launch()):
 *
 *   FlarePluginPermissions.request(
 *       (AppCompatActivity) host,
 *       new String[]{ Manifest.permission.ACCESS_FINE_LOCATION },
 *       (allGranted, perPermission) -> {
 *           if (allGranted) {
 *               // proceed with the native capability
 *           } else {
 *               callback.onResult(FlareClientPluginResult.error(
 *                   "PERMISSION_DENIED", "Location permission was denied."));
 *           }
 *       }
 *   );
 *
 * For non-permission native UI (camera capture, file picker, etc.), the
 * same technique applies: call host.getActivityResultRegistry().register(
 * uniqueKey, contract, callback) directly inside launch() instead of the
 * convenience wrapper, using a fresh UUID-based key per invocation.
 */
public final class FlarePluginPermissions {

    public interface Callback {
        void onResult(boolean allGranted, Map<String, Boolean> perPermission);
    }

    private FlarePluginPermissions() {}

    public static void request(AppCompatActivity host, String[] permissions, Callback callback) {
        String key = "flare_plugin_permission_" + UUID.randomUUID();

        final ActivityResultLauncher<String[]>[] launcherHolder = new ActivityResultLauncher[1];

        ActivityResultCallback<Map<String, Boolean>> resultCallback = result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (granted == null || !granted) { allGranted = false; break; }
            }
            try {
                callback.onResult(allGranted, result);
            } finally {
                if (launcherHolder[0] != null) {
                    launcherHolder[0].unregister();
                }
            }
        };

        launcherHolder[0] = host.getActivityResultRegistry().register(
                key,
                new ActivityResultContracts.RequestMultiplePermissions(),
                resultCallback
        );

        launcherHolder[0].launch(permissions);
    }
}