package dev.flareframework.client.flare.task.builtin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import dev.flareframework.client.flare.task.FlareClientTask;

import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 *  OpenBrowserTask
 *
 *  Migrated verbatim (including its https/http scheme allowlist) from
 *  the old, now-retired NativeFeatureBridge.java "open_browser" handler.
 *  This is the ONE piece of real logic worth preserving from that file —
 *  everything else there was dead/stub code.
 *
 *  SECURITY: only https:// and http:// schemes are ever launched. This
 *  deliberately rejects file://, javascript:, custom deep-link schemes,
 *  etc. — see the inline comments below for the full rationale, kept
 *  from the original implementation.
 *
 *  This is registered as a BUILT-IN task by FlareClientActivity itself
 *  (not by an optional external module), since it has no meaningful
 *  async result and is broadly useful to almost every app.
 * ═══════════════════════════════════════════════════════════════
 */
public class OpenBrowserTask implements FlareClientTask {

    private static final String TAG = "OpenBrowserTask";

    @Override
    public String id() { return "open_browser"; }

    @Override
    public void execute(Activity host, JSONObject params) {
        String rawUrl = params.optString("url", "");

        if (rawUrl.isEmpty()) {
            Log.w(TAG, "received empty URL — ignoring");
            return;
        }

        Uri parsedUri = Uri.parse(rawUrl);
        String scheme = parsedUri.getScheme();

        // Whitelist: only https:// and http:// are allowed. Everything else
        // (file://, javascript:, intent://, fb://, etc.) is rejected to
        // prevent intent hijacking and local file access — same rationale
        // as the original NativeFeatureBridge implementation.
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, parsedUri);

            // Explicitly target a browser category so Android won't
            // silently route this to a non-browser app that also
            // happens to handle http://.
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);

            // Verify a handler exists before firing — startActivity()
            // would otherwise throw ActivityNotFoundException and crash
            // the app if no browser is installed.
            if (browserIntent.resolveActivity(host.getPackageManager()) != null) {
                host.startActivity(browserIntent);
            } else {
                Log.w(TAG, "no browser app found to handle the URL");
                Toast.makeText(host, "No browser app found", Toast.LENGTH_SHORT).show();
            }
        } else {
            // Log only the scheme, never the full URL, to avoid leaking
            // potentially sensitive URL content into logs.
            Log.w(TAG, "rejected unsafe URL scheme='" + (scheme != null ? scheme : "null")
                    + "' — only https/http allowed");
        }
    }
}