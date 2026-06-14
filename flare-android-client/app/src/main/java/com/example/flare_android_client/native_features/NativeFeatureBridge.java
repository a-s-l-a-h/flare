package com.example.flare_android_client.native_features;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 * NATIVE FEATURE EXPANSION BRIDGE
 *
 * Allows users of your library to trigger Native Android Intents
 * (Camera, QR Scanner, File Picker, GPS, etc.) from Server JSON.
 *
 * HOW IT WORKS:
 * 1. In Elixir JSON layout:
 *    "action": {
 *      "url": "flare://action",
 *      "payload": { "flare_action": "on_qr_scan", "flare_native": "qr_scan" }
 *    }
 * 2. FlareClientActivity intercepts "flare_native" and routes here.
 * 3. This class launches the camera/scanner.
 * 4. When done, it calls `onNativeResult`, which pushes the data
 *    back to the Elixir server automatically!
 * ═══════════════════════════════════════════════════════════════
 */
public class NativeFeatureBridge {

    private static final String TAG = "NativeBridge";

    // Example Request Codes
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_QR_SCAN = 1002;

    public interface NativeResultCallback {
        /**
         * @param eventType    The original "flare_action" name (so server knows what happened)
         * @param resultKey    DivKit variable name to update (e.g. "flare_qr_result")
         * @param resultValue  The actual data (e.g. "https://example.com")
         * @param sendToServer True if this data should be pushed to Phoenix immediately
         */
        void onResult(String eventType, String resultKey, Object resultValue, boolean sendToServer);
    }

    private final Activity activity;
    private final NativeResultCallback callback;

    // Temporary storage to remember which event triggered the intent
    private String pendingEventType = null;

    public NativeFeatureBridge(Activity activity, NativeResultCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /**
     * Handle requests coming from DivKit taps.
     */
    public void handleFeature(String featureName, String eventType, JSONObject payload) {
        Log.d(TAG, "Triggering native feature: " + featureName);
        this.pendingEventType = eventType;

        switch (featureName) {
            case "qr_scan":
                // ── Example: Open a QR Scanner intent ──
                // If you use ZXing or another library, launch it here:
                // Intent i = new Intent(activity, MyQRScannerActivity.class);
                // activity.startActivityForResult(i, REQ_QR_SCAN);
                Toast.makeText(activity, "Simulating QR Scan...", Toast.LENGTH_SHORT).show();

                // For demonstration, simulating an immediate result:
                activity.getWindow().getDecorView().postDelayed(() -> {
                    callback.onResult(eventType, "flare_scanned_code", "12345-QR-CODE", true);
                }, 1500);
                break;

            case "open_browser":
                // ─────────────────────────────────────────────────────────────────
                // SECURITY FIX v1.0: Validate the URL before passing it to an Intent.
                //
                // VULNERABILITY BEING FIXED:
                // The old code took any URL string from the server JSON and passed it
                // directly to Intent.ACTION_VIEW. This is dangerous because:
                //
                //   1. file:// URLs — could read arbitrary files on the device
                //      e.g. "file:///data/data/com.example/shared_prefs/flare_prefs.xml"
                //
                //   2. Custom deep-link URLs — could launch other apps' activities
                //      with arbitrary payloads, potentially triggering unauthorized actions
                //      e.g. "fb://profile/123" or "intent://..."
                //
                //   3. javascript: URLs — some browsers execute these as code
                //
                // THE FIX:
                //   - Only allow https:// URLs (plain http:// is also allowed for
                //     local dev/testing, but consider removing in production)
                //   - Reject all other schemes silently
                //   - Log rejected URLs for debugging (without the full URL to avoid
                //     leaking sensitive data to logs)
                //
                // WHY NOT JUST VALIDATE ON THE SERVER?
                //   Defense in depth. The server should also sanitize URLs, but the
                //   Android client is the last line of defense. Even if the server
                //   is compromised or has a bug, the client must not blindly execute
                //   arbitrary intents.
                // ─────────────────────────────────────────────────────────────────
                String rawUrl = payload.optString("url", "");

                if (rawUrl.isEmpty()) {
                    Log.w(TAG, "open_browser: received empty URL — ignoring");
                    break;
                }

                // Parse the URI to inspect its scheme safely
                Uri parsedUri = Uri.parse(rawUrl);
                String scheme = parsedUri.getScheme();

                // Whitelist: only https:// and http:// are allowed.
                // Everything else (file://, javascript:, intent://, fb://, etc.)
                // is rejected to prevent intent hijacking and local file access.
                if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                    Log.d(TAG, "open_browser: opening URL with scheme=" + scheme);
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, parsedUri);

                    // Extra safety: explicitly target a browser category so Android
                    // won't silently route this to a non-browser app that handles http://
                    browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);

                    // Verify at least one app can handle this intent before firing it.
                    // startActivity() throws ActivityNotFoundException if nothing handles it,
                    // which would crash the app without this check.
                    if (browserIntent.resolveActivity(activity.getPackageManager()) != null) {
                        activity.startActivity(browserIntent);
                    } else {
                        Log.w(TAG, "open_browser: no browser app found to handle the URL");
                        Toast.makeText(activity, "No browser app found", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    // Rejected — log the scheme only (not the full URL) to avoid
                    // leaking potentially sensitive URL content into logs
                    Log.w(TAG, "open_browser: rejected unsafe URL scheme='"
                            + (scheme != null ? scheme : "null") + "' — only https/http allowed");
                }
                // We don't send anything to the server for browser opens
                break;

            default:
                Log.w(TAG, "Unknown native feature requested: " + featureName);
                break;
        }
    }

    /**
     * Intercept results from Activity.onActivityResult.
     * (Call this from FlareClientActivity.onActivityResult)
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_QR_SCAN && resultCode == Activity.RESULT_OK && data != null) {
            String scannedData = data.getStringExtra("SCAN_RESULT");

            if (pendingEventType != null) {
                // Send result back to DivKit UI AND Phoenix Server!
                callback.onResult(pendingEventType, "flare_scanned_code", scannedData, true);
                pendingEventType = null;
            }
        }
    }
}