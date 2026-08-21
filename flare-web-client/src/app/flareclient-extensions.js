import { registerClientTask } from "../flareclient/task/flare-client-task-registry.js";

// Builtin tasks
import { OpenBrowserTask } from "../flareclient-extensions-builtin/tasks/open-browser/open-browser-task.js";
import { ForceLogoutTask } from "../flareclient-extensions-builtin/tasks/force-logout/force-logout-task.js";
import { HapticTask } from "../flareclient-extensions-builtin/tasks/haptic/haptic-task.js";
import { ShowAlertTask } from "../flareclient-extensions-builtin/tasks/show-alert/show-alert-task.js";
import { ShowScaffoldTask } from "../flareclient-extensions-builtin/tasks/show-scaffold/show-scaffold-task.js";
import { HideScaffoldTask } from "../flareclient-extensions-builtin/tasks/hide-scaffold/hide-scaffold-task.js";

function registerBuiltInTasks() {
  registerClientTask(OpenBrowserTask);
  registerClientTask(ForceLogoutTask);
  registerClientTask(HapticTask);
  registerClientTask(ShowAlertTask);
  registerClientTask(ShowScaffoldTask);
  registerClientTask(HideScaffoldTask);
}

function registerBuiltInPlugins() { /* none yet */ }
function registerCommunityTasks() { /* none yet */ }
function registerCommunityPlugins() { /* none yet */ }
function registerAppTasks() { /* none yet */ }
function registerAppPlugins() { /* none yet */ }

export function registerAll() {
  registerBuiltInTasks();
  registerBuiltInPlugins();
  registerCommunityTasks();
  registerCommunityPlugins();
  registerAppTasks();
  registerAppPlugins();
}