package com.example.flare_android_client.flare.export;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * ═══════════════════════════════════════════════════════════════
 *  FlareExportedVariables
 *
 *  A safe, READ-ONLY mirror of Flare variables that are explicitly
 *  marked "exported": true in a screen's state/<screen>.json. Lets
 *  native code that lives OUTSIDE any DivKit view (e.g. a custom
 *  MapView showing live driver location) read — and reactively
 *  subscribe to — selected Flare variable values.
 *
 *  See LOCAL_ENGINE_PROTOCOL.md §13. The name is deliberately NOT
 *  "FlareBridge" or "FlareStateManager" — those names imply a two-way
 *  channel or a second, competing owner of state. This class can only
 *  ever be WRITTEN to by FlareClientActivity (whenever an exported
 *  variable changes), and can only ever be READ from by native code.
 *  There is no write path exposed to native consumers here at all —
 *  that is the entire safety guarantee.
 *
 *  If native code needs to CHANGE Flare state, it must go through the
 *  sanctioned path instead: fire a flare://clienttask, a
 *  flare://clientplugin, or a server push — exactly as a DivKit-
 *  originated change would. Never through this class.
 *
 *  Thread-safe: backed by ConcurrentHashMap + CopyOnWriteArraySet, so
 *  set()/get()/subscribe() are all safe to call from any thread
 *  (DivKit callbacks, background plugin threads, native UI threads)
 *  without any external locking.
 * ═══════════════════════════════════════════════════════════════
 */
public final class FlareExportedVariables {

    /** Implement this to be notified whenever an exported variable's value changes. */
    public interface Listener {
        void onChanged(String name, Object value);
    }

    private static final Map<String, Object> values = new ConcurrentHashMap<>();
    private static final Map<String, Set<Listener>> listenersByName = new ConcurrentHashMap<>();

    private FlareExportedVariables() {}

    /**
     * Called ONLY by FlareClientActivity's updateVariable() path, and only
     * for variable names that were explicitly declared "exported": true.
     * Never call this from native/plugin code — this is the mirror's
     * write side, reserved for the Flare client itself.
     */
    public static void set(String name, Object value) {
        if (name == null) return;

        if (value == null) {
            values.remove(name);
        } else {
            values.put(name, value);
        }

        Set<Listener> listeners = listenersByName.get(name);
        if (listeners != null) {
            for (Listener l : listeners) {
                try {
                    l.onChanged(name, value);
                } catch (Exception e) {
                    // A misbehaving listener must never break the mirror
                    // update for other subscribers, or for Flare itself.
                    // Intentionally swallowed — this is a native-consumer
                    // bug, not a Flare bug.
                }
            }
        }
    }

    /** Reads the current mirrored value for an exported variable, or null if not present/exported. */
    public static Object get(String name) {
        if (name == null) return null;
        return values.get(name);
    }

    /** Subscribes to future changes of one exported variable by exact name. */
    public static void subscribe(String name, Listener listener) {
        if (name == null || listener == null) return;
        listenersByName.computeIfAbsent(name, k -> new CopyOnWriteArraySet<>()).add(listener);
    }

    /** Always pair a subscribe() with an unsubscribe() (e.g. in onDestroy) to avoid leaking the native consumer. */
    public static void unsubscribe(String name, Listener listener) {
        if (name == null || listener == null) return;
        Set<Listener> listeners = listenersByName.get(name);
        if (listeners != null) listeners.remove(listener);
    }
}