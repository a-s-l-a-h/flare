package dev.flareframework.extensions.builtin.tasks.show_alert;

import android.app.Activity;
import android.app.AlertDialog;
import dev.flareframework.client.flare.task.FlareClientTask;
import org.json.JSONObject;

public class ShowAlertTask implements FlareClientTask {
    public static final String ID = "show_alert";

    @Override
    public String id() { return ID; }

    @Override
    public void execute(Activity host, JSONObject params) {
        if (host == null) return;
        String title = params.optString("title", "");
        String message = params.optString("message", "");
        String button = params.optString("button", "OK");

        host.runOnUiThread(() -> new AlertDialog.Builder(host)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(button, (dialog, which) -> dialog.dismiss())
                .show());
    }
}