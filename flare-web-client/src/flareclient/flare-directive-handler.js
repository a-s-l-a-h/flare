// ═══════════════════════════════════════════════════════════════
//  flare-directive-handler.js
//
//  Executes the "directives" array sent by the server inside
//  init/patch envelopes. Mirrors Android's FlareServerDirectiveHandler —
//  kept in its own file, separate from flare-client.js, so this list
//  of server-initiated instructions has one obvious home on both
//  platforms.
//
//  Unrelated to flare-client-task-engine.js / flare-client-plugin-engine.js
//  — those execute CLIENT-initiated (tap-triggered) actions. This file
//  executes SERVER-initiated ones.
// ═══════════════════════════════════════════════════════════════

import { dispatchClientTask } from "./task/flare-client-task-engine";

/**
 * @param {Object} directive - a single { type, payload } entry from envelope.directives
 * @param {FlareClient} client - the owning FlareClient instance, for callbacks
 *   that need to touch client state (navigate, token, scaffold, etc.)
 */
export function executeDirective(directive, client) {
  client.log(`⚡ Directive: ${directive.type}`, directive.payload);

  switch (directive.type) {
    case "navigate":
      client.navigateTo(directive.payload.screen, directive.payload.params || {});
      break;

    case "show_alert":
      alert(`${directive.payload.title}\n\n${directive.payload.message}`);
      break;

    case "store_login_token": {
      localStorage.setItem("flare_token", directive.payload.token);
      client.token = directive.payload.token;
      client.log("Token refreshed by server");
      break;
    }

    case "clear_login_token":
      localStorage.removeItem("flare_token");
      client.token = null;
      client._handleAuthFailure();
      break;

    case "haptic":
      if (navigator.vibrate) navigator.vibrate(50);
      break;

    case "hide_scaffold":
      client._setScaffoldVisible(directive.payload.region, false);
      break;

    case "show_scaffold":
      client._setScaffoldVisible(directive.payload.region, true);
      break;

    case "run_task": {
      const taskId = directive.payload.task;
      const taskParams = directive.payload.params || {};
      if (taskId) {
        dispatchClientTask(taskId, taskParams);
      } else {
        console.warn("[Flare] run_task directive missing 'task' identifier.");
      }
      break;
    }

    default:
      console.warn(`[Flare] Unknown directive: ${directive.type}`);
  }
}