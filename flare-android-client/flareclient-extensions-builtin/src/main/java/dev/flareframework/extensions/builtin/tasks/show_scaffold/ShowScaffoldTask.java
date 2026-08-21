package dev.flareframework.extensions.builtin.tasks.show_scaffold;

import android.app.Activity;
import dev.flareframework.client.FlareClientActivity;
import dev.flareframework.client.flare.task.FlareClientTask;
import org.json.JSONObject;

/**
 * Delegates to FlareClientActivity's existing Mount-based showScaffold(),
 * using the same "region" param key the server's show_scaffold command
 * already uses. Do NOT resolve region -> view-id here — that mapping
 * only exists correctly inside the Activity's Mount registry.
 */
public class ShowScaffoldTask implements FlareClientTask {
    public static final String ID = "show_scaffold";

    @Override
    public String id() { return ID; }

    @Override
    public void execute(Activity host, JSONObject params) {
        String region = params.optString("region", null);
        if (region != null && host instanceof FlareClientActivity) {
            ((FlareClientActivity) host).showScaffold(region);
        }
    }
}