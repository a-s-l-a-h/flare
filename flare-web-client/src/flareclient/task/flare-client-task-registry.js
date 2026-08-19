// ═══════════════════════════════════════════════════════════════
//  flare-client-task-registry.js
//  Same shape and override rules as flare-client-plugin-registry.js,
//  just for synchronous, no-result client tasks.
// ═══════════════════════════════════════════════════════════════

const registeredTasks = new Map();

export function registerClientTask(task) {
  if (!task || !task.id) {
    console.error("[FlareClientTaskRegistry] registerClientTask() called with an invalid task (missing id) — ignoring");
    return;
  }
  if (registeredTasks.has(task.id)) {
    console.warn(`[FlareClientTaskRegistry] task '${task.id}' overridden by app-level registration`);
  }
  registeredTasks.set(task.id, task);
}

export function getClientTask(id) {
  if (!id) return undefined;
  return registeredTasks.get(id);
}

export function registeredClientTaskIds() {
  return Array.from(registeredTasks.keys());
}