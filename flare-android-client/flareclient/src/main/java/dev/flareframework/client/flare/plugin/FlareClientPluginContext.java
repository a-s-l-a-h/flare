package dev.flareframework.client.flare.plugin;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientPluginContext
 *
 *  "Channel B" from LOCAL_ENGINE_PROTOCOL.md §5.2 — engine-supplied
 *  runtime facts a plugin needs but must NEVER receive as a stale
 *  literal typed into layout JSON (auth token, base URL, etc.).
 *
 *  A fresh instance is constructed by FlareClientActivity for EVERY
 *  single plugin invocation. A plugin MUST NOT cache the token or base
 *  URL beyond one invocation — always re-read via these getters if the
 *  plugin needs the value again later (e.g. after a retry).
 *
 *  This interface is intentionally narrow. It is NOT a general-purpose
 *  bag of app state. If a plugin needs some other piece of Flare state,
 *  that belongs in "Channel A" instead — i.e. the layout author passes
 *  it explicitly via URL query params or payload.params, resolved
 *  through the existing @{...} expression path.
 * ═══════════════════════════════════════════════════════════════
 */
public interface FlareClientPluginContext {

    /** The current auth token, read live — never a snapshot taken earlier. */
    String getAuthToken();

    /** The current API base HTTP URL (derived the same way the client derives its ws:// URL). */
    String getBaseHttpUrl();

    /** The Flare screen name that originated this plugin invocation. */
    String getScreenName();

    /**
     * Call this if the plugin's OWN network request (e.g. a file upload)
     * comes back with an authentication failure. This routes into the
     * SAME auth-failure/logout handling the rest of the app already uses
     * — a plugin must never implement its own separate logout path.
     */
    void notifyAuthFailure();
}