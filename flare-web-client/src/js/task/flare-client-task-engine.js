// ═══════════════════════════════════════════════════════════════
//  flare-client-task-engine.js
//  Router for flare://clienttask. Deliberately tiny — tasks are
//  synchronous and never return a result, so there's no timeout, no
//  callback, no envelope. Just: look up, run, isolate crashes.
// ═══════════════════════════════════════════════════════════════

import { getClientTask } from "./flare-client-task-registry";

export function dispatchClientTask(taskId, params) {
  if (!taskId) {
    console.error("[FlareClientTaskEngine] dispatchClientTask() called with an empty task id — ignoring");
    return;
  }

  const task = getClientTask(taskId);
  if (!task) {
    console.error(`[FlareClientTaskEngine] Client task not found: '${taskId}' — is it registered yet?`);
    showNotFoundToast();
    return;
  }

  // Crash isolation (protocol §9.1) — a bug in one task must never break
  // the surrounding action-dispatch flow.
  try {
    task.execute(params || {});
  } catch (e) {
    console.error(`[FlareClientTaskEngine] Client task '${taskId}' threw an exception during execute()`, e);
  }
}

function showNotFoundToast() {
  try {
    const el = document.createElement("div");
    el.textContent = "Action unavailable";
    el.style.cssText =
      "position:fixed;bottom:24px;left:50%;transform:translateX(-50%);" +
      "background:rgba(0,0,0,0.8);color:#fff;padding:10px 18px;border-radius:20px;" +
      "font-size:13px;z-index:2000;pointer-events:none;";
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 2500);
  } catch (e) {
    console.error("[FlareClientTaskEngine] Failed to show not-found toast", e);
  }
}