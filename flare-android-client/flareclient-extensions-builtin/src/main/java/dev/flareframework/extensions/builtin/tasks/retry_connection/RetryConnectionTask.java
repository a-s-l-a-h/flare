package dev.flareframework.extensions.builtin.tasks.retry_connection;

import android.app.Activity;
import dev.flareframework.client.FlareClientActivity;
import dev.flareframework.client.flare.task.FlareClientTask;
import org.json.JSONObject;

public class RetryConnectionTask implements FlareClientTask {
    public static final String ID = "retry_connection";

    @Override
    public String id() { return ID; }

    @Override
    public void execute(Activity host, JSONObject params) {
        if (host instanceof FlareClientActivity) {
            ((FlareClientActivity) host).retryConnection();
        }
    }
}