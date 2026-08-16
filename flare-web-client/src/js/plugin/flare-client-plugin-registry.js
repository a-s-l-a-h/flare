// ═══════════════════════════════════════════════════════════════
//  flare-client-plugin-registry.js
//
//  Plain in-memory id -> plugin map. Empty by default. Every entry
//  comes from an explicit registerClientPlugin() call made by the
//  host app at startup — see LOCAL_ENGINE_PROTOCOL.md §4/§12.
// ═══════════════════════════════════════════════════════════════

const registeredPlugins = new Map();

/**
 * Registers a plugin under `plugin.id`. Registering an id that already
 * exists OVERWRITES the previous entry intentionally — this is the
 * supported extension mechanism for replacing a built-in plugin. A
 * clearly visible warning is logged on override so it's never a silent
 * surprise during debugging.
 */
export function registerClientPlugin(plugin) {
  if (!plugin || !plugin.id) {
    console.error("[FlareClientPluginRegistry] registerClientPlugin() called with an invalid plugin (missing id) — ignoring");
    return;
  }
  if (registeredPlugins.has(plugin.id)) {
    console.warn(`[FlareClientPluginRegistry] plugin '${plugin.id}' overridden by app-level registration`);
  }
  registeredPlugins.set(plugin.id, plugin);
}

/** Returns the registered plugin for this id, or undefined if none is registered. */
export function getClientPlugin(id) {
  if (!id) return undefined;
  return registeredPlugins.get(id);
}

/** Debug-only enumeration of currently registered plugin ids — see protocol §14. */
export function registeredClientPluginIds() {
  return Array.from(registeredPlugins.keys());
}