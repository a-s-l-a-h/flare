package dev.flareframework.client.flare.channel;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════
 * Parses the raw JSON message envelopes sent by the Elixir server.
 *
 * Safely extracts:
 * - layout (DivKit UI)
 * - state (key/value map of variables)
 * - variables (type definitions)
 * ═══════════════════════════════════════════════════════════════
 */
public class FlareEnvelope {

    public String screen;
    public JSONObject layout;
    public JSONObject state;
    public List<VariableDef> variables;

    public static class VariableDef {
        public String name;
        public String type;
        public Object value;

        // ── LOCAL ENGINE ADDITION ────────────────────────────────────────
        // Opt-in flag read from "exported": true in state JSON — see
        // LOCAL_ENGINE_PROTOCOL.md §13 (FlareExportedVariables). Defaults
        // to false, which is behaviorally IDENTICAL to before this field
        // existed — no existing state file needs to change.
        public boolean exported;
    }

    public static FlareEnvelope fromInit(JSONObject json) {
        FlareEnvelope env = new FlareEnvelope();
        env.screen = json.optString("screen");

        // ── Fix: Give DivKit exactly the JSON structure it expects ──
        JSONObject rawLayout = json.optJSONObject("layout");
        if (rawLayout != null) {
            if (rawLayout.has("card")) {
                // If the server already sent {"card": {...}}, use it directly
                env.layout = rawLayout;
            } else {
                // If the server sent the raw card contents, wrap it in {"card": {...}, "log_id": "flare_screen"}
                JSONObject wrapper = new JSONObject();
                try {
                    // DivKit strict parsing REQUIRES a log_id at the root of the layout object
                    wrapper.put("log_id", "flare_" + env.screen);
                    wrapper.put("card", rawLayout);
                } catch (Exception ignored) {}
                env.layout = wrapper;
            }
        }

        env.state = json.optJSONObject("state");
        env.variables = parseVariables(json.optJSONArray("variables"));
        return env;
    }

    public static FlareEnvelope fromLayoutUpdate(JSONObject json) {
        // Same structure as Init, but without the "state" block
        return fromInit(json);
    }

    private static List<VariableDef> parseVariables(JSONArray array) {
        List<VariableDef> list = new ArrayList<>();
        if (array == null) return list;

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj != null) {
                VariableDef def = new VariableDef();
                def.name = obj.optString("name");
                def.type = obj.optString("type", "string");
                def.value = obj.opt("value"); // Can be String, Int, Bool, etc.
                // Missing/absent "exported" key defaults to false — safe,
                // zero-behavior-change default for every existing state file.
                def.exported = obj.optBoolean("exported", false);
                list.add(def);
            }
        }
        return list;
    }
}