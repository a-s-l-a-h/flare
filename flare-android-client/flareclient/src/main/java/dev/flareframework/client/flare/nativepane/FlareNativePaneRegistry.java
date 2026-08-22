package dev.flareframework.client.flare.nativepane;

import android.util.Log;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Same shape and override rules as FlareClientTaskRegistry/
 * FlareClientPluginRegistry. Empty by default — flareclient registers
 * nothing here on its own.
 */
public final class FlareNativePaneRegistry {
    private static final String TAG = "FlareNativePaneRegistry";
    private static final Map<String, FlareNativePaneProvider> providers = new ConcurrentHashMap<>();

    private FlareNativePaneRegistry() {}

    public static void register(FlareNativePaneProvider provider) {
        if (provider == null || provider.id() == null || provider.id().trim().isEmpty()) {
            Log.e(TAG, "register() called with an invalid pane provider (null or empty id) — ignoring");
            return;
        }
        if (providers.containsKey(provider.id())) {
            Log.w(TAG, "pane '" + provider.id() + "' overridden by app-level registration");
        }
        providers.put(provider.id(), provider);
    }

    public static FlareNativePaneProvider get(String id) {
        if (id == null) return null;
        return providers.get(id);
    }

    public static Set<String> registeredIds() {
        return providers.keySet();
    }
}