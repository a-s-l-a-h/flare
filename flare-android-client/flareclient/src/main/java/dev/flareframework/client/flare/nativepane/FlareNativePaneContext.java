package dev.flareframework.client.flare.nativepane;

import org.json.JSONObject;

/**
 * Runtime facts + callbacks handed to every native pane, fresh per bind.
 * Mirrors FlareClientPluginContext's "never cache, always re-read" discipline.
 */
public interface FlareNativePaneContext {
    String getScreenName();
    String getAuthToken();
    String getBaseHttpUrl();
    void notifyAuthFailure();
    void setVariable(String name, Object value);
    void fireAction(String actionName, JSONObject payload);
}