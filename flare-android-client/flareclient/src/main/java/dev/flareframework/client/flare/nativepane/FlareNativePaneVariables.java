package dev.flareframework.client.flare.nativepane;

import dev.flareframework.client.flare.export.FlareExportedVariables;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Thin convenience wrapper over FlareExportedVariables (§13 of the local
 * engine protocol), scoped by a pane's div id so a pane's own subscriptions
 * can all be torn down together in release() — never leaked.
 */
public final class FlareNativePaneVariables {
    private static final class Record {
        final String name;
        final FlareExportedVariables.Listener listener;
        Record(String name, FlareExportedVariables.Listener listener) {
            this.name = name;
            this.listener = listener;
        }
    }

    private static final Map<String, Set<Record>> subscriptionsByPane = new ConcurrentHashMap<>();

    private FlareNativePaneVariables() {}

    public static Object get(String name) {
        return FlareExportedVariables.get(name);
    }

    public static void subscribe(String paneKey, String name, FlareExportedVariables.Listener listener) {
        if (paneKey == null || name == null || listener == null) return;
        subscriptionsByPane.computeIfAbsent(paneKey, k -> new CopyOnWriteArraySet<>())
                           .add(new Record(name, listener));
        FlareExportedVariables.subscribe(name, listener);
    }

    public static void unsubscribeAll(String paneKey) {
        if (paneKey == null) return;
        Set<Record> records = subscriptionsByPane.remove(paneKey);
        if (records != null) {
            for (Record record : records) {
                FlareExportedVariables.unsubscribe(record.name, record.listener);
            }
        }
    }
}