// ═══════════════════════════════════════════════════════════════
//  flare-client-plugin-result.js
//
//  Builds the FROZEN result envelope for a flare://clientplugin
//  invocation. See LOCAL_ENGINE_PROTOCOL.md §6. This shape
//  (status/data/error) must never change — only new fields may be
//  added in the future.
//
//  Named "...ClientPluginResult", never "...Envelope" — that word is
//  reserved for the wire message coming from the Phoenix server
//  (see the Android FlareEnvelope class). This result type has no
//  relationship to that and never touches the server on its own.
// ═══════════════════════════════════════════════════════════════

/** A successful capture/transaction result. */
export function ok(data) {
  return { status: "ok", data: data || {}, error: null };
}

/** A plugin-reported failure. `code` must be one of the frozen error codes (protocol §6.3). */
export function error(code, message) {
  return { status: "error", data: null, error: { code, message: message || "" } };
}

/** The user backed out of the native UI. */
export function cancelled() {
  return {
    status: "cancelled",
    data: null,
    error: { code: "USER_CANCELLED", message: "Cancelled by user" }
  };
}

/** Built by the engine when the requested plugin id isn't registered. */
export function unavailable(pluginId) {
  return {
    status: "unavailable",
    data: null,
    error: { code: "UNAVAILABLE", message: `Plugin not registered: ${pluginId}` }
  };
}

/** Built by the engine when the plugin never calls back in time. */
export function timeoutResult() {
  return {
    status: "error",
    data: null,
    error: { code: "TIMEOUT", message: "The plugin did not respond in time." }
  };
}

/** Built by the engine when a plugin throws an uncaught exception. */
export function unknownError(e) {
  return {
    status: "error",
    data: null,
    error: { code: "UNKNOWN", message: e ? String(e.message || e) : "Unknown error" }
  };
}