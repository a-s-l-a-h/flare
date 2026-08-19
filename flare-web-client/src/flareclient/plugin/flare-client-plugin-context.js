// ═══════════════════════════════════════════════════════════════
//  flare-client-plugin-context.js
//
//  "Channel B" from LOCAL_ENGINE_PROTOCOL.md §5.2 — engine-supplied
//  runtime facts a plugin needs but must never receive as a stale
//  literal typed into layout JSON.
//
//  Values are read via LIVE getters, never snapshotted — a plugin
//  must call `context.getAuthToken()` again if it needs the token a
//  second time later (e.g. on a retry), rather than caching the
//  first value it received.
// ═══════════════════════════════════════════════════════════════

/**
 * @param {Object} deps
 * @param {() => string} deps.getToken        - live current auth token
 * @param {() => string} deps.getBaseUrl      - live current API base URL
 * @param {() => string} deps.getScreenName   - live current content screen name
 * @param {() => void}   deps.onAuthFailure   - routes into the SAME auth-failure
 *                                               handling the rest of the app uses
 */
export function createClientPluginContext({ getToken, getBaseUrl, getScreenName, onAuthFailure }) {
  return {
    getAuthToken: () => getToken(),
    getBaseHttpUrl: () => getBaseUrl(),
    getScreenName: () => getScreenName(),
    notifyAuthFailure: () => onAuthFailure()
  };
}