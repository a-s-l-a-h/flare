package com.example.flare_android_client.flare.task;

import android.app.Activity;
import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientTask
 *
 *  Interface for a synchronous, instant, on-device, no-result local
 *  action — triggered by flare://clienttask from layout JSON.
 *
 *  NAMING: deliberately called "task", never "command" — Flare already
 *  has a SERVER-sent "commands" concept (see FlareCommandHandler, which
 *  executes navigate/store_token/haptic/show_scaffold/etc. delivered
 *  inside init/patch envelopes from Phoenix). Calling this a "task"
 *  instead avoids that name collision entirely and tells a backend
 *  developer at a glance: "this runs silently on the phone right now,
 *  it never touches my server."
 *
 *  Examples of what belongs here: opening a URL in the browser,
 *  copying text to the clipboard, forcing a local logout. Anything that
 *  needs to open native UI or return a structured result belongs in
 *  FlareClientPlugin instead, not here.
 * ═══════════════════════════════════════════════════════════════
 */
public interface FlareClientTask {

    /** Unique, stable id for this task (e.g. "open_browser"). Treat renames as breaking. */
    String id();

    /**
     * Executes the task. MUST be synchronous and fast — never block on
     * network I/O or any long-running work. If you need an async result,
     * this is the wrong interface; use FlareClientPlugin instead.
     */
    void execute(Activity host, JSONObject params);
}