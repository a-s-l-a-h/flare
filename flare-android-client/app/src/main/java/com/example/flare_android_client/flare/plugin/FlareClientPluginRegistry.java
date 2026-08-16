package com.example.flare_android_client.flare.plugin;

import android.util.Log;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientPluginRegistry
 *
 *  A plain in-memory id -> FlareClientPlugin map. Empty by default —
 *  Flare core registers NOTHING here on its own. Every entry comes from
 *  an explicit register() call made by the host app (or an optional
 *  bundled plugin module) at startup.
 *
 *  NOT related to Flare.Registry on the Elixir/server side (which is a
 *  BEAM process registry for UserState lookup) — same word, completely
 *  different runtime, different language, different purpose. Worth
 *  knowing explicitly if you work across both codebases.
 *
 *  Thread-safety: backed by ConcurrentHashMap so register()/get() are
 *  safe to call from any thread without external locking.
 * ═══════════════════════════════════════════════════════════════
 */
public final class FlareClientPluginRegistry {

    private static final String TAG = "FlareClientPluginRegistry";

    // id -> plugin. Package-private visibility is intentionally NOT used —
    // this stays fully private; all access must go through the static
    // methods below so registration logging/override-detection can never
    // be bypassed by a caller reaching into the map directly.
    private static final Map<String, FlareClientPlugin> plugins = new ConcurrentHashMap<>();

    // Static-only utility class — never instantiate.
    private FlareClientPluginRegistry() {}

    /**
     * Registers a plugin under its own id(). Registering an id that
     * ALREADY exists intentionally OVERWRITES the previous entry — this
     * is the supported mechanism for an app to replace a built-in plugin
     * (e.g. a custom-branded file picker) without forking Flare. See
     * LOCAL_ENGINE_PROTOCOL.md §12. A clearly visible warning log line is
     * always emitted on override so it is never a silent surprise.
     */
    public static void register(FlareClientPlugin plugin) {
        // Defensive guard: a null plugin or a plugin with a missing/blank
        // id must never be allowed to corrupt the registry or later cause
        // a null-key lookup crash.
        if (plugin == null || plugin.id() == null || plugin.id().trim().isEmpty()) {
            Log.e(TAG, "register() called with an invalid plugin (null or empty id) — ignoring");
            return;
        }
        if (plugins.containsKey(plugin.id())) {
            Log.w(TAG, "plugin '" + plugin.id() + "' overridden by app-level registration");
        }
        plugins.put(plugin.id(), plugin);
    }

    /** Returns the registered plugin for this id, or null if none is registered. Never throws. */
    public static FlareClientPlugin get(String id) {
        if (id == null) return null;
        return plugins.get(id);
    }

    /** Debug-only enumeration of currently registered plugin ids — see protocol §14. */
    public static Set<String> registeredIds() {
        return plugins.keySet();
    }
}