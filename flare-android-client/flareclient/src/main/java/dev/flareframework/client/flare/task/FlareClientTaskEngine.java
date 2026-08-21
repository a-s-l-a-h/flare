package dev.flareframework.client.flare.task;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.Set;

public final class FlareClientTaskEngine {
    private static final String TAG = "FlareClientTaskEngine";

    private FlareClientTaskEngine() {}

    public static boolean dispatch(Activity host, Uri uri) {
        if (uri == null || !"flare".equalsIgnoreCase(uri.getScheme()) || !"clienttask".equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        String taskId = uri.getQueryParameter("task");
        if (taskId == null || taskId.trim().isEmpty()) {
            Log.e(TAG, "URI missing required 'task' parameter: " + uri);
            return false;
        }

        JSONObject params = new JSONObject();
        Set<String> queryNames = uri.getQueryParameterNames();
        for (String key : queryNames) {
            if (!"task".equals(key)) {
                try {
                    params.put(key, uri.getQueryParameter(key));
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse URI param: " + key, e);
                }
            }
        }
        return dispatch(host, taskId, params);
    }

    public static boolean dispatch(Activity host, String taskId, JSONObject params) {
        if (taskId == null || taskId.trim().isEmpty()) {
            Log.e(TAG, "dispatch() called with empty taskId");
            return false;
        }

        FlareClientTask task = FlareClientTaskRegistry.get(taskId);
        if (task == null) {
            Log.e(TAG, "Client task not registered: '" + taskId + "'");
            if (host != null) {
                host.runOnUiThread(() -> Toast.makeText(host, "Action unavailable", Toast.LENGTH_SHORT).show());
            }
            return false;
        }

        try {
            task.execute(host, params != null ? params : new JSONObject());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Crash intercepted during execute() for task: " + taskId, t);
            return false;
        }
    }
}