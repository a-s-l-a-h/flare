package dev.flareframework.client.flare.plugin;

import android.app.Activity;
import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientPlugin
 *
 *  The interface every native-capability plugin (file pick, GPS,
 *  QR scanner, and anything invented later by Flare maintainers OR by
 *  a third-party app developer) implements.
 *
 *  IMPORTANT — this file lives in Flare's plugin/ package only to
 *  define the CONTRACT. Actual plugin implementations (the classes that
 *  implement this interface) must NEVER live inside Flare core's own
 *  source tree — see LOCAL_ENGINE_PROTOCOL.md §A.4. They are written by
 *  the host app, or shipped as a separate optional module, and
 *  registered at startup via FlareClientPluginRegistry.register(...).
 * ═══════════════════════════════════════════════════════════════
 */
public interface FlareClientPlugin {

    /**
     * Unique, stable id for this plugin (e.g. "file_pick", "gps_location").
     * Renaming this later is a BREAKING change for every layout JSON that
     * references it — treat it as permanent once shipped.
     */
    String id();

    /**
     * Purely informational metadata for future debug/inspector tooling.
     * The engine NEVER reads this to change routing or result handling —
     * it exists only to help a human understand, at a glance, whether a
     * plugin is a "capture" (the native action IS the answer, e.g. QR
     * scan) or a "transaction" (the native action performs an operation
     * with its own side effects, e.g. file upload). See protocol §A.6.
     */
    default String category() { return "capture"; }

    /**
     * Launch the native capability. MUST call `callback.onResult(...)`
     * exactly once, on any thread, no matter which path this method
     * takes (success, denial, cancellation, or an internal failure this
     * plugin catches itself). If this method throws BEFORE ever calling
     * the callback, FlareClientPluginEngine will catch it and synthesize
     * an UNKNOWN-error result on the plugin's behalf — but a well-behaved
     * plugin should still prefer to catch its own exceptions and resolve
     * with a specific, meaningful error code instead of relying on that
     * fallback.
     *
     * @param host     the hosting Activity — needed for permission
     *                 requests, launching Intents, etc.
     * @param params   Channel A input (protocol §5.1) — layout-supplied,
     *                 already expression-resolved. Never null (engine
     *                 always passes at least an empty object).
     * @param context  Channel B input (protocol §5.2) — engine-supplied
     *                 runtime facts. Fresh per invocation.
     * @param callback must be invoked exactly once.
     */
    void launch(Activity host, JSONObject params, FlareClientPluginContext context,
                FlareClientPluginCallback callback);
}