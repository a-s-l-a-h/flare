package dev.flareframework.extensions.builtin.tasks.hide_scaffold;

import android.app.Activity;
import dev.flareframework.client.FlareClientActivity;
import dev.flareframework.client.flare.task.FlareClientTask;
import org.json.JSONObject;

public class HideScaffoldTask implements FlareClientTask {
    public static final String ID = "hide_scaffold";

    @Override
    public String id() { return ID; }

    @Override
    public void execute(Activity host, JSONObject params) {
        String region = params.optString("region", null);
        if (region != null && host instanceof FlareClientActivity) {
            ((FlareClientActivity) host).hideScaffold(region);
        }
    }
}