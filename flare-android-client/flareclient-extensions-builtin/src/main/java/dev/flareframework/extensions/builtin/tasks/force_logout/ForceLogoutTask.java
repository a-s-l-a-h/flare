package dev.flareframework.extensions.builtin.tasks.force_logout;

import android.app.Activity;
import dev.flareframework.client.FlareClientActivity;
import dev.flareframework.client.flare.task.FlareClientTask;
import org.json.JSONObject;

/**
 * Delegates to FlareClientActivity.clearStorage() — the ONE place logout
 * logic lives. Never reimplement token-clearing/finish() here.
 */
public class ForceLogoutTask implements FlareClientTask {
    public static final String ID = "force_logout";

    @Override
    public String id() { return ID; }

    @Override
    public void execute(Activity host, JSONObject params) {
        if (host instanceof FlareClientActivity) {
            ((FlareClientActivity) host).clearStorage();
        }
    }
}