package dev.flareframework.extensions.builtin.tasks.open_browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import dev.flareframework.client.flare.task.FlareClientTask;
import org.json.JSONObject;

public class OpenBrowserTask implements FlareClientTask {
    public static final String ID = "open_browser";
    private static final String TAG = "OpenBrowserTask";

    @Override
    public String id() { return ID; }

    @Override
    public void execute(Activity host, JSONObject params) {
        if (host == null) return;
        String rawUrl = params.optString("url", "");
        if (rawUrl.isEmpty()) {
            Log.w(TAG, "Received empty URL — ignoring");
            return;
        }

        Uri parsedUri = Uri.parse(rawUrl);
        String scheme = parsedUri.getScheme();

        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, parsedUri);
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            if (browserIntent.resolveActivity(host.getPackageManager()) != null) {
                host.startActivity(browserIntent);
            } else {
                Toast.makeText(host, "No browser app found", Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.w(TAG, "Rejected unsafe URL scheme='" + scheme + "'");
        }
    }
}