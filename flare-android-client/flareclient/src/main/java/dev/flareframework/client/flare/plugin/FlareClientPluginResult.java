package dev.flareframework.client.flare.plugin;

import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientPluginResult
 *
 *  The FROZEN result envelope for a flare://clientplugin invocation.
 *  See LOCAL_ENGINE_PROTOCOL.md §6.
 *
 *  IMPORTANT: this shape (status / data / error) must NEVER change once
 *  adopted — every layout JSON author's "@{my_result.status == 'ok'}"
 *  expression depends on it staying stable forever. New fields may be
 *  ADDED in the future; existing ones must never be renamed or removed.
 *
 *  This class is intentionally named "...PluginResult", never anything
 *  containing the word "Envelope" — that word is reserved for
 *  FlareEnvelope, which parses the WIRE message coming from the Phoenix
 *  server. This class has nothing to do with that; it is a purely local,
 *  on-device result. Keeping the names visually distinct avoids anyone
 *  ever assuming the two are related.
 * ═══════════════════════════════════════════════════════════════
 */
public final class FlareClientPluginResult {

    /** One of "ok" | "error" | "cancelled" | "unavailable" — never any other string. */
    public final String status;

    /** Present only when status == "ok". Null otherwise. */
    public final JSONObject data;

    /** Present only when status == "error" or "unavailable". Null otherwise. */
    public final JSONObject error;

    // Private constructor — always build via the named static factories below,
    // so it is impossible to accidentally construct an invalid/inconsistent
    // combination of status/data/error from plugin code.
    private FlareClientPluginResult(String status, JSONObject data, JSONObject error) {
        this.status = status;
        this.data = data;
        this.error = error;
    }

    /** A successful capture/transaction. `data` may be null — it will be normalized to {}. */
    public static FlareClientPluginResult ok(JSONObject data) {
        return new FlareClientPluginResult("ok", data != null ? data : new JSONObject(), null);
    }

    /** A plugin-reported failure. `code` MUST be one of the frozen error codes (protocol §6.3). */
    public static FlareClientPluginResult error(String code, String message) {
        JSONObject err = new JSONObject();
        try {
            err.put("code", code);
            err.put("message", message != null ? message : "");
        } catch (Exception ignored) {
            // JSONObject.put on a plain string/string never throws in practice —
            // but we never let a JSON construction problem escape as a crash here.
        }
        return new FlareClientPluginResult("error", null, err);
    }

    /** The user backed out of the native UI (e.g. dismissed the file picker). */
    public static FlareClientPluginResult cancelled() {
        JSONObject err = new JSONObject();
        try {
            err.put("code", "USER_CANCELLED");
            err.put("message", "Cancelled by user");
        } catch (Exception ignored) {}
        return new FlareClientPluginResult("cancelled", null, err);
    }

    /** Built by the engine itself when the requested plugin id isn't registered. */
    public static FlareClientPluginResult unavailable(String pluginId) {
        JSONObject err = new JSONObject();
        try {
            err.put("code", "UNAVAILABLE");
            err.put("message", "Plugin not registered: " + pluginId);
        } catch (Exception ignored) {}
        return new FlareClientPluginResult("unavailable", null, err);
    }

    /** Built by the engine itself when the plugin never calls back in time. */
    public static FlareClientPluginResult timeout() {
        JSONObject err = new JSONObject();
        try {
            err.put("code", "TIMEOUT");
            err.put("message", "The plugin did not respond in time.");
        } catch (Exception ignored) {}
        return new FlareClientPluginResult("error", null, err);
    }

    /** Built by the engine itself when a plugin throws an uncaught exception. */
    public static FlareClientPluginResult unknown(Throwable t) {
        JSONObject err = new JSONObject();
        try {
            err.put("code", "UNKNOWN");
            err.put("message", t != null ? String.valueOf(t.getMessage()) : "Unknown error");
        } catch (Exception ignored) {}
        return new FlareClientPluginResult("error", null, err);
    }

    /**
     * Serializes this result into the exact wire shape written to the
     * DivKit Dict variable named by `result_var` (protocol §6.5).
     * Never throws — falls back to an empty object on any JSON failure,
     * since a malformed envelope must never crash the caller.
     */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("status", status);
            obj.put("data", data != null ? data : JSONObject.NULL);
            obj.put("error", error != null ? error : JSONObject.NULL);
        } catch (Exception ignored) {
            // Defensive only — put() on primitive-safe values does not throw in practice.
        }
        return obj;
    }
}