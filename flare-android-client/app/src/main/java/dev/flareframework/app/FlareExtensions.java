package dev.flareframework.app;

import android.util.Log;

// Builtin tasks
import dev.flareframework.extensions.builtin.tasks.open_browser.OpenBrowserTask;
import dev.flareframework.extensions.builtin.tasks.force_logout.ForceLogoutTask;
import dev.flareframework.extensions.builtin.tasks.haptic.HapticTask;
import dev.flareframework.extensions.builtin.tasks.show_alert.ShowAlertTask;
import dev.flareframework.extensions.builtin.tasks.show_scaffold.ShowScaffoldTask;
import dev.flareframework.extensions.builtin.tasks.hide_scaffold.HideScaffoldTask;

// Builtin native panes
import dev.flareframework.extensions.builtin.panes.placeholder_pane.PlaceholderPaneProvider;

/**
 * Single place where every task/plugin — builtin, community, or app-level
 * — gets wired up. Lives here, in :app, and NOWHERE inside :flareclient,
 * so a broken third-party registration can never touch core.
 *
 * Order matters: builtin -> community -> app. Later registrations
 * silently override earlier ones with the same id (see
 * FlareClientTaskRegistry/FlareClientPluginRegistry override semantics),
 * so app-level always wins, community can override builtin, etc.
 */
public final class FlareExtensions {
    private static final String TAG = "FlareExtensions";

    private FlareExtensions() {}

        public static void registerAll() {
        Log.d(TAG, "Registering all extensions across tiers...");

        registerBuiltInTasks();
        registerBuiltInPlugins();
        registerBuiltInPanes();

        registerCommunityTasks();
        registerCommunityPlugins();
        registerCommunityPanes();

        registerAppTasks();
        registerAppPlugins();
        registerAppPanes();

        Log.d(TAG, "Extension registration complete.");
    }

    private static void registerBuiltInTasks() {
        new OpenBrowserTask().register();
        new ForceLogoutTask().register();
        new HapticTask().register();
        new ShowAlertTask().register();
        new ShowScaffoldTask().register();
        new HideScaffoldTask().register();
    }

        private static void registerBuiltInPlugins() { /* none yet */ }

    private static void registerBuiltInPanes() {
        new PlaceholderPaneProvider().register();
    }

    private static void registerCommunityTasks() { /* none yet */ }
    private static void registerCommunityPlugins() { /* none yet */ }
    private static void registerCommunityPanes() { /* none yet */ }
    private static void registerAppTasks() { /* none yet */ }
    private static void registerAppPlugins() { /* none yet */ }
    private static void registerAppPanes() { /* none yet */ }
}