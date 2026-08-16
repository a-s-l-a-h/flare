// ═══════════════════════════════════════════════════════════════
//  flare-exported-variables.js
//
//  Web equivalent of Android's FlareExportedVariables — a safe,
//  READ-ONLY mirror of Flare variables explicitly marked
//  "exported": true. Lets code OUTSIDE the DivKit render tree (e.g.
//  a native map widget) read and subscribe to selected Flare values.
//
//  See LOCAL_ENGINE_PROTOCOL.md §13. There is NO write path exposed
//  here for external consumers — set() is called only by
//  FlareClient's own _setVariable(), never by app code directly.
// ═══════════════════════════════════════════════════════════════

const mirroredValues = new Map();
const listenersByName = new Map();

export const FlareExportedVariables = {
  /**
   * Called ONLY by FlareClient's internal _setVariable() path, and only
   * for variable names explicitly declared "exported": true. Never call
   * this from app/native code — this is the mirror's write side.
   */
  set(name, value) {
    if (!name) return;

    if (value === undefined || value === null) {
      mirroredValues.delete(name);
    } else {
      mirroredValues.set(name, value);
    }

    const listeners = listenersByName.get(name);
    if (listeners) {
      listeners.forEach(fn => {
        try {
          fn(name, value);
        } catch (e) {
          // A misbehaving listener must never break other subscribers or Flare itself.
          console.error(`[FlareExportedVariables] listener for '${name}' threw`, e);
        }
      });
    }
  },

  /** Reads the current mirrored value, or undefined if not present/exported. */
  get(name) {
    return mirroredValues.get(name);
  },

  /** Subscribes to future changes of one exported variable by exact name. */
  subscribe(name, fn) {
    if (!name || typeof fn !== "function") return;
    if (!listenersByName.has(name)) listenersByName.set(name, new Set());
    listenersByName.get(name).add(fn);
  },

  /** Always pair a subscribe() with an unsubscribe() to avoid leaking the consumer. */
  unsubscribe(name, fn) {
    const listeners = listenersByName.get(name);
    if (listeners) listeners.delete(fn);
  }
};

// Convenience for non-module native/embedded code that wants access
// without importing the ES module directly.
window.FlareExportedVariables = FlareExportedVariables;