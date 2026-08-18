package dev.flareframework.client.flare.task;

import android.util.Log;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareClientTaskRegistry
 *
 *  Same shape and rules as FlareClientPluginRegistry, just for tasks.
 *  Empty by default. Registering an existing id overwrites it and logs
 *  a warning (never a silent surprise) — see LOCAL_ENGINE_PROTOCOL.md §12.
 * ═══════════════════════════════════════════════════════════════
 */
public final class FlareClientTaskRegistry {

    private static final String TAG = "FlareClientTaskRegistry";
    private static final Map<String, FlareClientTask> tasks = new ConcurrentHashMap<>();

    private FlareClientTaskRegistry() {}

    public static void register(FlareClientTask task) {
        if (task == null || task.id() == null || task.id().trim().isEmpty()) {
            Log.e(TAG, "register() called with an invalid task (null or empty id) — ignoring");
            return;
        }
        if (tasks.containsKey(task.id())) {
            Log.w(TAG, "task '" + task.id() + "' overridden by app-level registration");
        }
        tasks.put(task.id(), task);
    }

    public static FlareClientTask get(String id) {
        if (id == null) return null;
        return tasks.get(id);
    }

    public static Set<String> registeredIds() {
        return tasks.keySet();
    }
}