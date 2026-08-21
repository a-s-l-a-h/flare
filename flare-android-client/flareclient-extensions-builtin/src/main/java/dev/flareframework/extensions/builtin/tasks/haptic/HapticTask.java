package dev.flareframework.extensions.builtin.tasks.haptic;

import android.app.Activity;
import dev.flareframework.client.FlareClientActivity;
import dev.flareframework.client.flare.task.FlareClientTask;
import org.json.JSONObject;

/**
 * Delegates to FlareClientActivity.triggerHaptic(style), which already
 * implements the full success/warning/error/light/medium/heavy pattern
 * set. Do not reimplement with HapticFeedbackConstants here.
 */
public class HapticTask implements FlareClientTask {
    public static final String ID = "haptic";

    @Override
    public String id() { return ID; }

    @Override
    public void execute(Activity host, JSONObject params) {
        if (host instanceof FlareClientActivity) {
            ((FlareClientActivity) host).triggerHaptic(params.optString("style", "success"));
        }
    }
}