package com.example.flare_android_client.flare.plugin;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientPluginCallback
 *
 *  A plugin author receives ONE of these per launch() call and MUST
 *  invoke onResult() exactly once — no more, no less — across every
 *  possible code path the plugin has (success, permission denied,
 *  cancel, internal error). May be called from any thread; the engine
 *  (FlareClientPluginEngine) is solely responsible for marshaling back
 *  onto the main/UI thread before touching any DivKit-facing state.
 *
 *  Naming note: this is deliberately "Client**Plugin**Callback", not
 *  just "PluginCallback" or "FeatureCallback" — the "Client" prefix is
 *  applied consistently across every type in this package so that,
 *  years from now, nobody confuses this local/on-device concept with
 *  anything server-side (e.g. Flare.Registry on the Elixir side, or
 *  FlareCommandHandler which executes SERVER-sent commands).
 * ═══════════════════════════════════════════════════════════════
 */
public interface FlareClientPluginCallback {
    void onResult(FlareClientPluginResult result);
}