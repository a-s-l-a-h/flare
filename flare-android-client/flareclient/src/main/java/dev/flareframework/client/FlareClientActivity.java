package dev.flareframework.client;
// ═══════════════════════════════════════════════════════════════
//  FlareClientActivity.java
//  Location: app/src/main/java/com/example/flare_android_client/FlareClientActivity.java
//
//  PURPOSE:
//  This is the MAIN runtime Activity for Flare. It owns:
//    - PhoenixSocket connection lifecycle (connect, reconnect, disconnect)
//    - DivKit Div2View rendering (shows server-driven layouts)
//    - Screen navigation (flare:// navigate commands)
//    - Android back-button stack (mirrors the web client's browser history)
//    - Global DivKit variable controller (shared across screens, like web)
//    - Command dispatch (navigate, show_alert, store_token, haptic, clear_storage)
//    - Native feature bridge (camera, QR scan, etc. via NativeFeatureBridge)
//    - Pending-event lock (one event in flight at a time, same as web client)
//
//  ARCHITECTURE:
//  ┌─────────────────────────────────────────────────────┐
//  │  FlareClientActivity                                │
//  │    ├─ PhoenixSocket (phoenix/PhoenixChannelClient)  │
//  │    ├─ PhoenixChannel (current screen channel)       │
//  │    ├─ Div2View (DivKit rendered layout)             │
//  │    ├─ GlobalVariablesController (DivKit variables)  │
//  │    ├─ NativeFeatureBridge (camera, QR, etc.)        │
//  │    └─ FlareCommandHandler (execute server commands) │
//  └─────────────────────────────────────────────────────┘
//
//  THREADING:
//  Phoenix callbacks arrive on the PhxSocket HandlerThread.
//  DivKit MUST be touched on the Main thread.
//  We always runOnUiThread() before touching DivKit.
//
//  SCREEN NAVIGATION:
//  Back stack is a simple ArrayDeque<String> of screen names.
//  navigateTo("profile") pushes "profile".
//  Back button pops and re-joins the previous screen.
// ═══════════════════════════════════════════════════════════════

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import dev.flareframework.client.divkit.FlareDivActionHandler;
import dev.flareframework.client.divkit.FlareDivViewFactory;
import dev.flareframework.client.flare.channel.FlareServerDirectiveHandler;
import dev.flareframework.client.flare.channel.FlareEnvelope;
import dev.flareframework.client.nativefeatures.NativeFeatureBridge;
import dev.flareframework.client.phoenix.PhoenixChannelClient;
import dev.flareframework.client.flare.channel.FlareMessageDecoder;

// ── LOCAL ENGINE ADDITIONS ───────────────────────────────────────────────
// New, self-contained plugin/task/export subsystem — see
// flare/LOCAL_ENGINE_PROTOCOL.md for the full cross-platform contract.
import dev.flareframework.client.flare.plugin.FlareClientPluginContext;
import dev.flareframework.client.flare.plugin.FlareClientPluginEngine;
import dev.flareframework.client.flare.task.FlareClientTaskEngine;
import dev.flareframework.client.flare.export.FlareExportedVariables;
import dev.flareframework.client.flare.nativepane.FlareNativePaneAdapter;
import dev.flareframework.client.flare.nativepane.FlareNativePaneContext;
import com.yandex.div.core.Div2Context;
import com.yandex.div.core.DivConfiguration;
import com.yandex.div.core.view2.Div2View;
import com.yandex.div.data.Variable;
import com.yandex.div.coil.CoilDivImageLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// ── MULTI-MOUNT ADDITIONS ───────────────────────────────────────────────────
// Map/HashMap/Arrays/List back the new Mount registry (persistentMounts) and
// the SCAFFOLD_REGIONS whitelist. DivViewFacade is the type the action handler
// now hands us so we can identify which Mount a tap came from.
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import com.yandex.div.core.DivViewFacade;

public class FlareClientActivity extends AppCompatActivity {

    private static final String TAG = "FlareClient";

    // ── Intent extras (passed from MainActivity) ────────────────────────────
    public static final String EXTRA_WS_URL      = "flare_ws_url";
    public static final String EXTRA_ENTRY_SCREEN = "flare_entry_screen";
    public static final String EXTRA_TOKEN       = "flare_token";
    public static final String EXTRA_ENTRY_PARAMS = "flare_entry_params";

    // ── SharedPreferences (for store_token / clear_storage commands) ─────────
    private static final String PREF_FILE      = "flare_prefs";
    private static final String PREF_TOKEN     = "flare_auth_token";
    private static final String PREF_GUEST_ID  = "flare_guest_id";
    //  web stores dark mode under localStorage key "local_dark_mode".
    // We reuse that exact string as the SharedPreferences key too, purely so
    // anyone debugging both clients side-by-side isn't confused by two names
    // for the same concept. There's no functional requirement that the keys match.
    private static final String PREF_DARK_MODE = "local_dark_mode";

    // ── Reserved DivKit variable — SDK owns this, mirrors web client ──────────
    // When any flare://action fires, this becomes true immediately.
    // Cleared when the server's patch (or ACK) arrives.
    // Layout JSON can use: "@{local_flare_pending ? 0.5 : 1.0}" to dim buttons.
    public static final String PENDING_VAR = "local_flare_pending";

    // ── Window background colors, kept in sync with local_dark_mode ─────────
    // These are what shows through during the slide transition (see handleInit),
    // so they must match your dark-mode palette, not the system theme.
    private static final int COLOR_BG_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_BG_DARK  = 0xFF121212;

    // ── SLIDE TRANSITION — developer-tunable, global for the whole app ──────
    // Set these from anywhere (e.g. Application.onCreate()) before any screen
    // loads. Changing them mid-session takes effect on the NEXT navigation.
    public static boolean SLIDE_TRANSITION_ENABLED = true;
    public static long    SLIDE_TRANSITION_DURATION_MS = 500L;

    // ── Failure escalation ────────────────────────────────────────────────
    // Prevents a permanently-bad token (e.g. after an SDUI/backend update
    // invalidates old stored tokens) from trapping the user in an endless
    // "Connection lost — Retry" loop with no way out. After this many
    // consecutive failures, we stop auto-retrying quietly and force a
    // blocking dialog that explicitly offers Sign Out.
    private static final int MAX_RECONNECT_FAILURES     = 2;
    private static final int MAX_CONTENT_JOIN_FAILURES  = 2;
    private int reconnectFailureStreak   = 0;
    private int contentJoinFailureStreak = 0;
    // Add this near your other variables (like wsUrl, socket, etc.)
    private AlertDialog giveUpDialog = null;

    // ── Views ──────────────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════
    //  MULTI-MOUNT ARCHITECTURE
    //
    //  Mirrors the web client's `_makeMount(el, screenName)` exactly:
    //  every region (content + 5 persistent scaffold regions) is a Mount
    //  with its own <container view, Phoenix channel, screen name,
    //  rendered Div2View, and per-mount pending-action set>.
    //
    //  Why per-mount pending sets matter
    //  A tap in flight on the bottom bar must never freeze a button on the
    //  main content screen, and vice versa. Before this refactor, Android
    //  had exactly one global Set<String> pendingActions for the whole
    //  Activity — that was fine with one channel, but breaks the moment
    //  6 Div2Views can be rendered and tapped independently.
    // ══════════════════════════════════════════════════════════════════════
    private static class Mount {
        /** Region name — "content" for the primary screen, or the scaffold
         *  region name ("bottom_bar", "top_bar", "drawer", "end_drawer", "overlay"). */
        final String region;

        /** The FrameLayout this mount's Div2View gets attached to. */
        final FrameLayout container;

        /** This mount's own Phoenix channel — content's is replaced on every
         *  navigateTo(); persistent regions' channels are joined ONCE and
         *  never left (matches web's _joinPersistentScreens comment). */
        PhoenixChannelClient.PhoenixChannel channel;

        /** Which Flare screen this mount currently displays, e.g. "home",
         *  "bottom_bar", "drawer". For persistent mounts this never changes
         *  after the first join. */
        String screenName;

        /** The currently rendered Div2View — kept so onDivKitAction() can
         *  match a tap's DivViewFacade back to the owning Mount, and so we
         *  can diff-update (setData) instead of rebuilding from scratch. */
        Div2View div2View;

        /** Per-mount in-flight action names — mirrors web's `mount.pendingActions`. */
        final Set<String> pendingActions = ConcurrentHashMap.newKeySet();

        Mount(String region, FrameLayout container) {
            this.region = region;
            this.container = container;
        }
    }

    // The primary content mount — the ONLY one navigateTo()/back-button ever touches.
    private Mount contentMount;

    // Tracks locally-initialized variables so we don't overwrite them with server defaults
    private final Set<String> initializedLocalVars = new HashSet<>();

    //  gate for the blocking "give up" dialog. The blocking Sign Out / Retry
    // modal (showGiveUpDialog) should only ever be able to fire BEFORE the
    // user has seen any content — i.e. during the very first connect/join,
    // conceptually the same phase LoginActivity's connection is in. Once a
    // screen has rendered successfully at least once, any later connection
    // drop is just background flakiness: PhoenixSocket keeps retrying on
    // its own, and retryCurrentScreen()/joinPersistentScreens() already
    // rejoin everything automatically once it reconnects. Interrupting an
    // already-loaded screen with a non-cancelable modal is unnecessary and
    // can force the user to tap Retry repeatedly just to get back to a
    // screen that's already working. After first load we always use the
    // lightweight, self-dismissing inline overlay instead.
    private boolean hasEverLoadedContent = false;

    // All 5 persistent scaffold regions, keyed by region name. Joined once,
    // right after socket.onOpen(), and never left for the life of the session
    // (mirrors web's "these channels are never left by navigateTo()" comment).
    private final Map<String, Mount> persistentMounts = new HashMap<>();

    //  of the 5 persistent regions, only these 4 participate in
    // per-screen scaffold visibility toggling (envelope.scaffold list +
    // show_scaffold/hide_scaffold commands). "overlay" is intentionally
    // excluded — it self-governs its own visibility via its own DivKit
    // variable (flare_overlay_visible), exactly like the web client's
    // scaffoldRegions config which excludes "overlay" for the same reason.
    // all scafold region
//    private final List<String> SCAFFOLD_REGIONS =
//            Arrays.asList("bottom_bar", "top_bar", "drawer", "end_drawer");
    //only bottom bar and content area
    private final List<String> SCAFFOLD_REGIONS =
            Arrays.asList("bottom_bar");

    // ── Views (Android-only, no mount equivalent needed) ────────────────────────
    private TransitionOverlayView transitionOverlay; // navigation loading animation + error card

    // ── Phoenix ────────────────────────────────────────────────────────────────
    private PhoenixChannelClient.PhoenixSocket    socket;
    private String                                 wsUrl;
    private JSONObject                             entryParams; // params to join entry screen with (e.g. {"code": "..."})
    // Renamed from `currentScreen` → `currentContentScreen` to make it explicit
    // this only ever tracks the CONTENT mount's screen (back-stack navigation
    // never applies to persistent regions).
    private String                                 currentContentScreen;

    // ── Navigation back stack ──────────────────────────────────────────────────
    // Mirrors the web client's browser history (pushState / popstate).
    // Each navigateTo() pushes a (screenName, params) pair; back button pops.
    //
    //  BUGFIX: previously this only stored the screen NAME. Any join params
    // (e.g. "?code=4ALTWP" used to join a specific session) were discarded on
    // push, so navigateBack() always rejoined with params=null. For screens
    // that require a param to locate the right server-side session, that
    // rejoin fails and the server reports "not found" — even though the
    // screen is still perfectly valid. Now we carry the params through.
    private static class BackStackEntry {
        final String screenName;
        final JSONObject params;
        BackStackEntry(String screenName, JSONObject params) {
            this.screenName = screenName;
            this.params = params;
        }
    }
    private final ArrayDeque<BackStackEntry> backStack = new ArrayDeque<>();

    // Params the CONTENT mount is currently joined with — needed so we can
    // push them into backStack, and so retryCurrentScreen() can rejoin with
    // the same params instead of dropping them.
    private JSONObject currentContentParams;

    // ── DivKit ─────────────────────────────────────────────────────────────────
    private Div2Context                            div2Context;
    // Global variable controller — SHARED across every mount, same as web's
    // single globalController. This is exactly how "@{local_dark_mode}" set
    // by a tap on the top_bar can simultaneously affect the content screen,
    // the bottom bar, and the drawer without any extra plumbing.
    private com.yandex.div.core.expression.variables.DivVariableController globalVarsController;

    // ── Native features ────────────────────────────────────────────────────────
    // Bridge that lets DivKit layouts trigger camera, QR scan, etc.
    private NativeFeatureBridge nativeBridge;

    // ── LOCAL ENGINE ADDITIONS ────────────────────────────────────────────────
    // The router for flare://clientplugin invocations — see
    // flare/plugin/FlareClientPluginEngine.java.
    private FlareClientPluginEngine clientPluginEngine;

    // Names of variables declared with "exported": true in state JSON.
    // Mirrored into FlareExportedVariables on every update() call — see
    // updateVariable() below. A plain Set is safe here because it is only
    // ever read/written on the main thread (same discipline already used
    // by initializedLocalVars just above).
    private final Set<String> exportedVariableNames = new HashSet<>();


    // ═══════════════════════════════════════
    //  STATIC LAUNCH HELPER
    // ═══════════════════════════════════════

    /**
     * Launch FlareClientActivity from any context.
     *
     * @param context     caller context
     * @param wsUrl       Phoenix WebSocket URL, e.g. "wss://host/socket"
     * @param entryScreen First Flare screen to join, e.g. "welcome"
     */
    public static void launch(Context context, String wsUrl, String entryScreen) {
        launch(context, wsUrl, entryScreen, null, null);
    }

    public static void launch(Context context, String wsUrl, String entryScreen, String token) {
        launch(context, wsUrl, entryScreen, token, null);
    }

    /**
     * @param entryParamsJson raw JSON string of params to join the entry screen with,
     *                        e.g. {"code":"4ALTWP"} — parsed back to JSONObject in onCreate().
     *                        Pass null if there are none.
     */
    public static void launch(Context context, String wsUrl, String entryScreen, String token, String entryParamsJson) {
        Intent intent = new Intent(context, FlareClientActivity.class);
        intent.putExtra(EXTRA_WS_URL, wsUrl);
        intent.putExtra(EXTRA_ENTRY_SCREEN, entryScreen);
        if (token != null) {
            intent.putExtra(EXTRA_TOKEN, token);
        }
        if (entryParamsJson != null) {
            intent.putExtra(EXTRA_ENTRY_PARAMS, entryParamsJson);
        }
        context.startActivity(intent);
    }

    // ═══════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        setContentView(R.layout.activity_flare_client);
        transitionOverlay = findViewById(R.id.transition_overlay);
        transitionOverlay.setOnSignOutListener(this::clearStorage);
        transitionOverlay.setOnErrorVisibilityListener(
                () -> hideScaffold("bottom_bar"),
                () -> showScaffold("bottom_bar")
        );

        //  create every Mount up front ─────────────────────────
        // The content mount is the one navigateTo() swaps. The 4 findViewById
        // ids below correspond 1:1 to the FrameLayouts added in
        // activity_flare_client.xml. Region names here MUST exactly match the
        // Flare screen names your router registers them under
        // (see FlareRouter: "bottom_bar", "overlay", "top_bar", "drawer", "end_drawer").
        // all scaffold region
//        contentMount = new Mount("content", findViewById(R.id.fl_content));
//        persistentMounts.put("bottom_bar", new Mount("bottom_bar", findViewById(R.id.fl_bottom_bar)));
//        persistentMounts.put("top_bar",    new Mount("top_bar",    findViewById(R.id.fl_top_bar)));
//        persistentMounts.put("drawer",     new Mount("drawer",     findViewById(R.id.fl_drawer)));
//        persistentMounts.put("end_drawer", new Mount("end_drawer", findViewById(R.id.fl_end_drawer)));
//        persistentMounts.put("overlay",     new Mount("overlay",     findViewById(R.id.fl_overlay)));
        //two scaffold region
        contentMount = new Mount("content", findViewById(R.id.fl_content));
        persistentMounts.put("bottom_bar", new Mount("bottom_bar", findViewById(R.id.fl_bottom_bar)));

        // Read intent extras
        wsUrl               = getIntent().getStringExtra(EXTRA_WS_URL);
        String entryScreen  = getIntent().getStringExtra(EXTRA_ENTRY_SCREEN);
        if (entryScreen == null) entryScreen = "home";

        String entryParamsJson = getIntent().getStringExtra(EXTRA_ENTRY_PARAMS);
        if (entryParamsJson != null) {
            try {
                entryParams = new JSONObject(entryParamsJson);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse entry params: " + entryParamsJson, e);
            }
        }

        Log.d(TAG, "wsUrl=" + wsUrl + " entryScreen=" + entryScreen);

        // ── Set up DivKit context (one per Activity lifetime) ─────────────────
        setupDivKit();

        // ── Set up native feature bridge ──────────────────────────────────────
        nativeBridge = new NativeFeatureBridge(this, this::onNativeResult);

        // ── Set up Android back button ─────────────────────────────────────────
        // Uses the modern OnBackPressedCallback API (works on all API levels)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        // ── Connect Phoenix socket ─────────────────────────────────────────────
        buildAndConnectSocket(entryScreen);
    }

//    @Override
//    protected void onStop() {
//        super.onStop();
//        // Disconnect when app goes to background (saves battery, mirrors web onhide)
//        Log.d(TAG, "onStop: pausing socket");
//        if (socket != null) socket.onActivityPause();
//    }
//
//    @Override
//    protected void onStart() {
//        super.onStart();
//        // Reconnect when app returns to foreground
//        Log.d(TAG, "onStart: resuming socket");
//        if (socket != null) socket.onActivityResume();
//    }

    @Override
    protected void onStop() {
        super.onStop();
        // ❌ REMOVE the disconnect command!
        // We want to keep the socket alive so typed text isn't lost on quick app switches.
        Log.d(TAG, "onStop: app backgrounded, but keeping socket alive");

        // Tell the socket the app is hidden so it doesn't try to aggressively
        // reconnect in the background IF the network drops, but DO NOT kill the active connection.
        if (socket != null) {
            // (Optional) If you want, you can add a method to PhoenixSocket just to set pageHidden=true,
            // but simply commenting out onActivityPause() is the standard Android way.
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart: app foregrounded");

        // If the OS *did* kill the connection because the app was in the background
        // for 10+ minutes, this will force it to wake up and reconnect.
        if (socket != null && !socket.isConnected()) {
            socket.connect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: shutting down socket");
        if (socket != null) socket.shutdown();
    }

    // ═══════════════════════════════════════
    //  DIVKIT SETUP
    // ═══════════════════════════════════════

    /**
     * Create the DivKit Div2Context and the global variables controller.
     * Called once in onCreate — these objects live for the whole Activity lifetime.
     *
     * The global variables controller is the Android equivalent of the web client's
     * createGlobalVariablesController() — variables set here persist across screens.
     */
    private void setupDivKit() {
        Log.d(TAG, "Setting up DivKit");

        // 1. Create the variable controller FIRST
        globalVarsController = new com.yandex.div.core.expression.variables.DivVariableController();
        globalVarsController.putOrUpdate(new Variable.BooleanVariable(PENDING_VAR, false));

        //  restore dark mode from SharedPreferences ─────────────
        // Mirrors the web client's constructor:
        //   const isDark = localStorage.getItem("local_dark_mode") === "true";
        //   this._setVariable("local_dark_mode", "boolean", isDark);
        //
        // This MUST happen before any screen's init envelope arrives, so that
        // handleInit()'s "don't clobber existing local_ variables" guard
        // (added below in section 3.8) has something to protect. If we skip
        // this step, the very first screen's state/*.json file would define
        // local_dark_mode = false and there'd be nothing to protect it from.
        boolean isDarkMode = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                .getBoolean(PREF_DARK_MODE, false);
        globalVarsController.putOrUpdate(new Variable.BooleanVariable("local_dark_mode", isDarkMode));
        initializedLocalVars.add("local_dark_mode");

        // Sync the window background NOW, before any screen renders — this is
        // what's visible during the slide transition, so it must match immediately.
        getWindow().getDecorView().setBackgroundColor(isDarkMode ? COLOR_BG_DARK : COLOR_BG_LIGHT);

        // Also color the content mount's own container — this is what shows
        // through the transparent transition overlay the instant the old
        // screen is cleared, before the new screen has loaded.
        contentMount.container.setBackgroundColor(isDarkMode ? COLOR_BG_DARK : COLOR_BG_LIGHT);

        // ═══════════════════════════════════════════════════════════════
        //  LOCAL ENGINE SETUP
        //  Builds the engine-supplied runtime context (Channel B, protocol
        //  §5.2), the plugin engine itself, and registers built-in client
        //  tasks. All of this is additive — nothing above this block changes.
        // ═══════════════════════════════════════════════════════════════
        FlareClientPluginContext clientPluginContext = new FlareClientPluginContext() {
            @Override
            public String getAuthToken() {
                // Read live from SharedPreferences every time — never a
                // cached/stale value, per protocol §5.2.
                return getSharedPreferences(PREF_FILE, MODE_PRIVATE).getString(PREF_TOKEN, null);
            }

            @Override
            public String getBaseHttpUrl() {
                // Derive from the current wsUrl the exact same way the rest
                // of the client already thinks about connectivity — never a
                // second, independently-maintained URL.
                if (wsUrl == null) return null;
                String base = wsUrl.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
                int socketIndex = base.indexOf("/socket");
                return socketIndex >= 0 ? base.substring(0, socketIndex) : base;
            }

            @Override
            public String getScreenName() {
                return currentContentScreen;
            }

            @Override
            public void notifyAuthFailure() {
                // Routes into the SAME auth-failure/logout handling used
                // everywhere else in this Activity — never a separate path.
                clearStorage();
            }
        };

        clientPluginEngine = new FlareClientPluginEngine(
                this,
                globalVarsController,
                clientPluginContext,
                // Mount-liveness check (protocol §11): a screen name is
                // "still live" if it's either the current content screen or
                // one of the always-mounted persistent scaffold regions.
                screenName -> screenName != null &&
                        (screenName.equals(contentMount.screenName) || persistentMounts.containsKey(screenName)),
                // Fires on_success/on_error/on_cancel through the EXISTING
                // pending-lock + channel-push path — never a new/parallel
                // action-dispatch mechanism.
                (actionName, originScreenName) -> {
                    Mount targetMount = contentMount;
                    if (originScreenName != null && !originScreenName.equals(contentMount.screenName)) {
                        Mount region = persistentMounts.get(originScreenName);
                        if (region != null) targetMount = region;
                    }
                    JSONObject followUpPayload = new JSONObject();
                    try {
                        followUpPayload.put("flare_action", actionName);
                    } catch (Exception ignored) {
                        // put() on a plain string key/value never throws in practice.
                    }
                    handleResolvedAction(actionName, followUpPayload, targetMount);
                }
        );

                // All built-in / community / app tasks, plugins, and native panes
        // are registered once, globally, by MainApplication.onCreate() in
        // the :app module — before any Activity exists. FlareClientActivity
        // (and flareclient core generally) never imports or knows about any
        // specific task/plugin/pane implementation. A bug in a third-party
        // extension can never touch this file.

        FlareNativePaneContext paneContext = new FlareNativePaneContext() {
            @Override
            public String getAuthToken() {
                return getSharedPreferences(PREF_FILE, MODE_PRIVATE).getString(PREF_TOKEN, null);
            }

            @Override
            public String getBaseHttpUrl() {
                if (wsUrl == null) return null;
                String base = wsUrl.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
                int socketIndex = base.indexOf("/socket");
                return socketIndex >= 0 ? base.substring(0, socketIndex) : base;
            }

            @Override
            public String getScreenName() {
                return currentContentScreen;
            }

            @Override
            public void notifyAuthFailure() {
                clearStorage();
            }

            @Override
            public void setVariable(String name, Object value) {
                runOnUiThread(() -> updateVariable(name, value));
            }

            @Override
            public void fireAction(String actionName, JSONObject payload) {
                runOnUiThread(() -> {
                    JSONObject data = payload != null ? payload : new JSONObject();
                    try {
                        data.put("flare_action", actionName);
                    } catch (Exception ignored) {}
                    handleResolvedAction(actionName, data, contentMount);
                });
            }
        };

        FlareDivActionHandler actionHandler = new FlareDivActionHandler(new FlareDivActionHandler.FlareActionCallback() {
            @Override
            public void onAction(String actionType, JSONObject payload, DivViewFacade view) {
                // Existing behavior — completely unchanged.
                onDivKitAction(actionType, payload, view);
            }

            @Override
            public void onClientTask(String taskId, JSONObject params) {
                FlareClientTaskEngine.dispatch(FlareClientActivity.this, taskId, params);
            }

            @Override
            public void onClientPlugin(String pluginId, JSONObject invocation, DivViewFacade view) {
                dispatchClientPlugin(pluginId, invocation, view);
            }
        }, globalVarsController);

                // 2. Attach it to the DivKit Configuration!
        //
        // NOTE: .divCustomContainerViewAdapter(...) is the builder method
        // for the modern DivCustomContainerViewAdapter interface (confirmed
        // from DivKit source). Verify this exact method name compiles
        // against your pinned divkit:32.52.0 artifact — if it doesn't
        // resolve, check DivConfiguration.Builder's actual API in your
        // IDE and rename this one call accordingly.
        DivConfiguration config = new DivConfiguration.Builder(new CoilDivImageLoader(this))
                .actionHandler(actionHandler)
                .divVariableController(globalVarsController) // 🔥 FIX: Attach variables here
                .divCustomContainerViewAdapter(new FlareNativePaneAdapter(paneContext))
                .visualErrorsEnabled(true)
                .build();

        div2Context = new Div2Context(this, config, com.yandex.div.R.style.Div_Theme);
        Log.d(TAG, "DivKit setup complete");
    }

    // ═══════════════════════════════════════
    //  PHOENIX SOCKET
    // ═══════════════════════════════════════

    /**
     * Build and connect the PhoenixSocket, then join the entry screen.
     * Socket-level callbacks (onOpen, onClose, onError) are registered here.
     */
    private void buildAndConnectSocket(String entryScreen) {
        Log.d(TAG, "Building PhoenixSocket → " + wsUrl);

        SharedPreferences prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);

        // Prefer a real auth token (from store_token command) over guest ID.
        // Fall back to a stable guest ID that is generated once and kept forever.
        // Use stored token only (either a guest token issued by the server,
        // or a real auth token from login). Both are Phoenix.Token signed strings.
        // If nothing is stored yet, connect with no token — the server will
        // generate and sign a guest token, sending it back via store_token.
        // storeToken() below saves it to PREF_TOKEN for all future connects.
        // Accept token passed directly via Intent (e.g. from LoginActivity after auth)
        String intentToken = getIntent().getStringExtra(EXTRA_TOKEN);
        if (intentToken != null) {
            prefs.edit().putString(PREF_TOKEN, intentToken).apply();
        }

        String storedToken = prefs.getString(PREF_TOKEN, null);

        if (storedToken != null) {
            Log.d(TAG, "Attaching stored token (guest or auth)");
        } else {
            Log.d(TAG, "No token stored — server will issue a signed guest token on first connect");
        }

        PhoenixChannelClient.PhoenixSocket.Builder builder =
                new PhoenixChannelClient.PhoenixSocket.Builder(wsUrl)
                        .timeout(10_000)
                        .heartbeatIntervalMs(30_000)
                        .decoder(new FlareMessageDecoder())
                        .logger((tag, msg) -> Log.d("PhxSocket[" + tag + "]", msg));

        // Only attach token param if we have one.
        // Sending no token param is valid — server handles anonymous connects.
        if (storedToken != null) {
            builder.param("token", storedToken);
        }

        socket = builder.build();

        // Socket opened (or re-opened after reconnect)
        // Socket opened (or re-opened after reconnect)
        socket.onOpen(() -> {
            Log.d(TAG, "Socket opened");
            reconnectFailureStreak = 0; // connection succeeded — clear the streak
            runOnUiThread(() -> {
                joinPersistentScreens();

                // FIX: If the popup is showing and the background connection succeeds,
                // kill the popup, show the loading spinner, and let the page load!
                if (giveUpDialog != null && giveUpDialog.isShowing()) {
                    Log.d(TAG, "Socket reconnected! Auto-dismissing give-up dialog.");
                    giveUpDialog.dismiss();
                    giveUpDialog = null;
                    transitionOverlay.show(this::retryCurrentScreen, null);
                    retryCurrentScreen();
                }
                // FIX: If the overlay is stuck showing an error card, force it back to the
                // loading spinner so the user knows it's actively reconnecting!
                else if (transitionOverlay.isVisible()) {
                    Log.d(TAG, "Socket reconnected while overlay showing — switching to spinner");

                    // Hide the error card and show the spinner again
                    transitionOverlay.show(
                            this::retryCurrentScreen,
                            hasEverLoadedContent ? null : () -> { /* suppressed pre-first-load */ }
                    );

                    retryCurrentScreen();
                }
            });
        });

        // Socket closed — show error in UI
        // Socket closed — clear pending locks and show error
        socket.onClose((code, reason) -> {
            Log.w(TAG, "Socket closed: code=" + code + " reason=" + reason);
            clearAllPendingActions(); // resets DivKit variables too, not just the Set
            runOnUiThread(() -> {
                if (code != 1000) {
                    handleConnectionFailure("Connection lost. Reconnecting…");
                }
            });
        });

        // Socket error
        socket.onError(reason -> {
            Log.e(TAG, "Socket error: " + reason);
            clearAllPendingActions();
            runOnUiThread(() -> handleConnectionFailure("Connection error. Please check your network."));
        });

        socket.connect();

        // Join the first screen — with entryParams if the launch URL carried a
        // query string (e.g. "?code=4ALTWP") for joining a specific session.
        navigateTo(entryScreen, entryParams);
    }

    // ═══════════════════════════════════════
    //  SCREEN NAVIGATION
    // ═══════════════════════════════════════

    /**
     * Navigate to a Flare screen by name.
     *
     * This is the Android equivalent of the web client's navigateTo(screenName).
     * Steps:
     *  1. Leave current channel cleanly
     *  2. Clear the pending lock (in case an event was in-flight)
     *  3. Show spinner
     *  4. Push screen name to back stack
     *  5. Join the new Phoenix channel ("flare:<screenName>")
     *  6. Register init/patch/layout_update listeners
     *
     * Always called on the main thread (or safely posted to it).
     *
     * @param screenName  Flare screen identifier, e.g. "welcome", "product"
     */
    // ═══════════════════════════════════════
    //  SCREEN NAVIGATION & MULTI-MOUNT JOINING
    // ═══════════════════════════════════════

    /**
     * Joins every persistent scaffold region ("bottom_bar", "top_bar", "drawer",
     * "end_drawer", "overlay") exactly once. Called from socket.onOpen() — see
     * buildAndConnectSocket() — so it also naturally re-runs on reconnect.
     *
     * The `m.channel == null` guard is what makes it safe to call repeatedly:
     * a mount that's already joined is skipped.
     * (nulling out persistent mounts' channels during the reconnect teardown in
     * storeToken()) matters — if we forgot that, this guard would see a stale
     * non-null channel object and never rejoin after a token upgrade.
     */
    private void joinPersistentScreens() {
        for (Mount m : persistentMounts.values()) {
            // FIX: If the channel is null OR dead (isClosed), we must recreate it.
            if (m.channel == null || m.channel.isClosed()) {
                // By convention, region name == screen name for every scaffold region.
                joinChannel(m, m.region, null, null);
            }
        }
    }

    public void navigateTo(String screenName) {
        navigateTo(screenName, null);
    }

    /**
     * Navigate the CONTENT mount to a different screen.
     *
     *  this now ONLY ever touches contentMount — persistent regions
     * (bottom bar, header, drawers, overlay) are never left or rejoined here,
     * exactly matching the web client's navigateTo() comment: "ONLY affects the
     * primary content mount."
     *
     * pending-state is cleared for contentMount ONLY
     * (clearPendingForMount), not globally — an in-flight tap on the bottom bar
     * must survive a content navigation.
     */
    public void navigateTo(String screenName, JSONObject params) {
        Log.d(TAG, "navigateTo: " + screenName);

        // ── Leave the CONTENT channel only ───────────────────────────────────
        if (contentMount.channel != null) {
            Log.d(TAG, "Leaving current content channel: flare:" + currentContentScreen);
            contentMount.channel.leave();
            contentMount.channel = null;
        }

        //   clear pending state for content only — NOT persistentMounts.
        clearPendingForMount(contentMount);

        // NOTE: the old screen is intentionally left visible in
        // contentMount.container here. It stays on-screen (frozen, fully
        // touch-blocked by transitionOverlay below) until the new screen's
        // init envelope arrives and handleInit() swaps it in instantly —
        // no artificial delay, no slide. See Step 4 in handleInit().

        // ── Show transition overlay ─────────────────────────────────────────
        //  before the first ever successful load, suppress the overlay's
        // own auto-timeout error card — showGiveUpDialog() (via
        // handleConnectionFailure/handleJoinFailure) owns escalation for
        // that phase, so we don't want a transient "Connection problem" card
        // flashing on screen in between the spinner and the blocking dialog.
        runOnUiThread(() -> transitionOverlay.show(
                this::retryCurrentScreen,
                hasEverLoadedContent ? null : () -> { /* suppressed pre-first-load */ }
        ));

        // ── Update back stack (content-only, same as before) ───────────────────
        // BUGFIX: push the params the OLD screen was joined with, not just its
        // name, so navigateBack() can rejoin it correctly later.
        if (!screenName.equals(currentContentScreen)) {
            if (currentContentScreen != null) {
                backStack.push(new BackStackEntry(currentContentScreen, currentContentParams));
            }
            contentMount.div2View = null; // Clean slate for new page
        }
        currentContentScreen = screenName;
        currentContentParams = params;

        joinChannel(contentMount, screenName, params, null);
    }

    /**
     * Navigate back to a previous CONTENT screen without pushing to the back stack.
     * Used internally by handleBack(). Persistent regions are untouched, same as navigateTo().
     */
    private void navigateBack(String screenName, JSONObject params) {
        Log.d(TAG, "navigateBack: " + screenName);

        if (contentMount.channel != null) {
            contentMount.channel.leave();
            contentMount.channel = null;
        }
        contentMount.div2View = null; // Clean slate for previous page
        clearPendingForMount(contentMount);

        // Old screen intentionally left visible — see navigateTo() comment.

        // Same suppression as navigateTo() — see comment there.
        runOnUiThread(() -> transitionOverlay.show(
                this::retryCurrentScreen,
                hasEverLoadedContent ? null : () -> { /* suppressed pre-first-load */ }
        ));

        currentContentScreen = screenName;
        currentContentParams = params;
        // BUGFIX: rejoin with the ORIGINAL params this screen was joined with
        // (e.g. "code"), instead of always passing null.
        joinChannel(contentMount, screenName, params, null);
    }

    /**
     * Re-join whatever screen the CONTENT mount currently thinks it's on.
     * Called by the retry button in the transition overlay, and by the
     * socket reconnect handler.
     */
    private void retryCurrentScreen() {
        if (currentContentScreen == null) return;

        // Old screen intentionally left visible — see navigateTo() comment.

        if (contentMount.channel != null) {
            // FIX: If the channel was forcibly closed by the server (e.g. processing a stale leave
            // from a previous timeout), the object is completely dead. We MUST discard it and create a new one.
            if (contentMount.channel.isClosed()) {
                Log.d(TAG, "retryCurrentScreen: channel is CLOSED (dead). Discarding old object.");
                contentMount.channel = null;
            } else {
                // The channel is ERRORED or JOINING and will auto-rejoin itself via socket.onOpen().
                Log.d(TAG, "retryCurrentScreen: content channel already exists for "
                        + currentContentScreen + " — letting it self-rejoin, not creating a duplicate");
                return;
            }
        }

        Log.d(TAG, "retryCurrentScreen: " + currentContentScreen);
        clearPendingForMount(contentMount);
        // BUGFIX: use currentContentParams instead of null, same reasoning
        // as navigateBack() above.
        joinChannel(contentMount, currentContentScreen, currentContentParams, null);
    }

    /**
     * Called on every abnormal socket close/error. First few attempts get
     * the normal "reconnecting" overlay (same as before). After
     * MAX_RECONNECT_FAILURES in a row, force a blocking dialog so a
     * permanently-bad token can't loop the user forever.
     */
    private void handleConnectionFailure(String message) {
        reconnectFailureStreak++;
        Log.w(TAG, "handleConnectionFailure: streak=" + reconnectFailureStreak);

        if (!hasEverLoadedContent) {
            // Pre-first-load phase (e.g. app reopened while already logged in,
            // but the server is unreachable). Only two UI states are allowed
            // here: the loading spinner (already up from show()), and — once
            // the streak crosses the threshold — the blocking give-up dialog.
            // No transient error cards in between.
            if (reconnectFailureStreak >= MAX_RECONNECT_FAILURES) {
                showGiveUpDialog("We're having trouble connecting. This can happen if your " +
                        "session is no longer valid. You can try again or sign out.");
            }
            // else: do nothing — keep the spinner up, let it keep retrying quietly.
        } else {
            // Screen has loaded before — this is just background flakiness.
            // The lightweight, self-dismissing inline overlay is correct here.
            transitionOverlay.showError(message, this::retryCurrentScreen);
        }
    }

    /**
     * Non-cancelable dialog shown once retries have failed repeatedly.
     * Gives the user an explicit, impossible-to-miss way out.
     */
    /**
     * Non-cancelable dialog shown once retries have failed repeatedly.
     * Gives the user an explicit, impossible-to-miss way out.
     */
    private void showGiveUpDialog(String message) {
        transitionOverlay.hide();

        // 1. FIX MULTIPLE POPUPS: If the dialog is already showing, ignore new requests
        if (giveUpDialog != null && giveUpDialog.isShowing()) {
            return;
        }

        // This is a fully blocking, modal failure state — hide persistent
        // scaffold regions too. (transitionOverlay.hide() just above already
        // restored them if it was showing its own error card — this
        // re-hides for the dialog, which is even more severe.)
        hideScaffold("bottom_bar");

        giveUpDialog = new AlertDialog.Builder(this)
                .setTitle("Something went wrong")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Retry", (dialog, which) -> {
                    // Clear the reference since it's being dismissed
                    giveUpDialog = null;

                    reconnectFailureStreak = 0;
                    contentJoinFailureStreak = 0;

                    // 2. FIX LOTTIE ANIMATION: Show the transition overlay again while retrying
                    transitionOverlay.show(
                            this::retryCurrentScreen,
                            hasEverLoadedContent ? null : () -> { /* suppressed pre-first-load */ }
                    );

                    if (socket != null && !socket.isConnected()) socket.connect();
                    retryCurrentScreen();
                })
                .setNegativeButton("Sign Out", (dialog, which) -> {
                    giveUpDialog = null;
                    clearStorage();
                })
                .show();
    }

    /**
     * Inline "something went wrong" message inside the CONTENT mount only —
     * mirrors the web client's _showError(). Unlike transitionOverlay, this
     * does NOT consume touches on other regions, so bottom bar / drawer taps
     * still work and the user can navigate away from the broken screen.
     */
    private void showContentInlineError(String message, Runnable onRetry) {
        transitionOverlay.hide();
        contentMount.container.removeAllViews();

        TextView tv = new TextView(this);
        tv.setText(message + "\n\n(Tap to retry)");
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(64, 64, 64, 64);
        tv.setTextColor(0xFFE74C3C);
        tv.setOnClickListener(v -> { if (onRetry != null) onRetry.run(); });

        contentMount.container.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * GENERIC channel-join used by EVERY mount — content via navigateTo()/
     * navigateBack()/retryCurrentScreen(), and every persistent region via
     * joinPersistentScreens(). This single method replaces the THREE
     * near-duplicate join blocks that used to exist (one per navigation method)
     * and is the direct Android equivalent of the web client's `_joinChannel()`.
     *
     * @param mount      the Mount to join (sets mount.channel / mount.screenName)
     * @param screenName Flare screen name, e.g. "home", "bottom_bar", "drawer"
     * @param params     join params (only ever non-null for content, e.g. {"code": "AH7K2P"})
     * @param onJoined   optional callback fired after a successful "ok" join reply
     */
    private void joinChannel(Mount mount, String screenName, JSONObject params, Runnable onJoined) {
        mount.screenName = screenName;
        String topic = "flare:" + screenName;
        mount.channel = socket.channel(topic, params);

        // Route each Phoenix event to the handler, tagging it with `mount` so the
        // handler knows which FrameLayout/pending-set/screenName it's working with.
        mount.channel.on("init",          (p, r, jr) -> runOnUiThread(() -> handleInit(p, mount)));
        mount.channel.on("patch",         (p, r, jr) -> runOnUiThread(() -> handlePatch(p, mount)));
        mount.channel.on("layout_update", (p, r, jr) -> runOnUiThread(() -> handleLayoutUpdate(p, mount)));

        mount.channel.join()
                .receive("ok", (p, r, jr) -> {
                    Log.d(TAG, "Joined channel: " + topic);
                    if (mount == contentMount) contentJoinFailureStreak = 0;
                    if (onJoined != null) onJoined.run();
                })
                .receive("error",   (p, r, jr) -> handleJoinFailure(screenName, mount, p))
                .receive("timeout", (p, r, jr) -> handleJoinFailure(screenName, mount, null));
    }

    /**
     * Shared error/timeout handling for joinChannel(). Auth failures redirect to
     * login regardless of which mount failed. Any other failure only shows the
     * user-facing transition-overlay error if it was the CONTENT mount that
     * failed — a persistent region failing to join (e.g. bottom_bar) shouldn't
     * block the whole screen with an error card.
     */
    private void handleJoinFailure(String screenName, Mount mount, JSONObject p) {
        String reason = p != null ? p.optString("reason", "") : "";

        if ("authentication_required".equals(reason) ||
                "session_expired".equals(reason) ||
                "invalid_token".equals(reason)) {
            Log.e(TAG, "Auth failed via socket — redirecting to login");
            runOnUiThread(this::clearStorage);
        } else if (mount == contentMount) {
            contentJoinFailureStreak++;
            Log.e(TAG, "Failed to join content channel: flare:" + screenName
                    + " (streak=" + contentJoinFailureStreak + ")");

            if (!hasEverLoadedContent) {
                // Same reasoning as handleConnectionFailure(): before the
                // first successful load, don't show the inline "tap to
                // retry" text — it would just flash briefly before being
                // replaced by the blocking dialog anyway. Keep the spinner
                // up until the streak threshold is hit, then go straight to
                // showGiveUpDialog().
                if (contentJoinFailureStreak >= MAX_CONTENT_JOIN_FAILURES) {
                    runOnUiThread(() -> showGiveUpDialog(
                            "This screen isn't loading after several attempts. " +
                                    "You can try again or sign out."));
                }
                // else: do nothing — keep the spinner up.
            } else {
                // Screen loaded before — inline, dismissible error is correct.
                runOnUiThread(() -> showContentInlineError(
                        "Could not load screen: " + screenName, this::retryCurrentScreen));
            }
        } else {
            // Isolate the failure to this one region instead of leaving it in
            // limbo — mirrors the web client's _hideBrokenRegion().
            Log.e(TAG, "Failed to join persistent region channel: flare:" + screenName
                    + " (region=" + mount.region + ")");
            runOnUiThread(() -> mount.container.setVisibility(View.GONE));
        }
    }

    // ═══════════════════════════════════════
    //  ANDROID BACK BUTTON  (unchanged — content-only, as before)
    // ═══════════════════════════════════════

    /**
     * Handle Android back button press.
     *
     * Pops the back stack to go to the previous Flare CONTENT screen.
     * If the stack is empty, finish the Activity (goes back to MainActivity).
     *
     * This mirrors the web client's window popstate handler. Persistent
     * scaffold regions are never affected by the back button.
     */
    private void handleBack() {
        // If overlay is showing an error, dismiss and go back rather than retry
        if (transitionOverlay.isVisible()) {
            transitionOverlay.hide();
            if (!backStack.isEmpty()) {
                BackStackEntry previous = backStack.pop();
                navigateBack(previous.screenName, previous.params);
            } else {
                finish();
            }
            return;
        }

        Log.d(TAG, "handleBack: backStack size=" + backStack.size());
        if (!backStack.isEmpty()) {
            BackStackEntry previous = backStack.pop();
            Log.d(TAG, "Back → " + previous.screenName);
            navigateBack(previous.screenName, previous.params);
        } else {
            Log.d(TAG, "Back stack empty — finishing Activity");
            finish();
        }
    }

    // ═══════════════════════════════════════
    //  FLARE MESSAGE HANDLERS
    // ═══════════════════════════════════════

    /**
     * Handle the "init" message — full screen layout + state.
     *
     * Equivalent of _handleInit() in the web client.
     * Steps:
     *  1. Register variable type definitions from state JSON
     *  2. Apply current server state values
     *  3. Parse layout JSON and render with DivKit
     *  4. Hide spinner
     */
    private void handleInit(JSONObject envelope, Mount mount) {
        Log.d(TAG, "handleInit: building DivKit view for mount=" + mount.region);

        try {
            FlareEnvelope parsed = FlareEnvelope.fromInit(envelope);

            // ── Step 1: Register variable types ──────────────────────────────
            registerActionPendingVars(parsed.layout);

            // Register each variable definition from the state JSON.
            //
            //  skip re-registering a "local_" prefixed variable if it
            // ALREADY EXISTS in the controller. Without this, every screen's
            // state/*.json (which defines local_dark_mode/local_drawer_open with
            // a hardcoded default) would silently reset the user's restored
            // dark-mode preference back to false the moment ANY screen loads —
            // exactly the bug the web client's comment calls out:
            //   "Prevent JSON file defaults from wiping out saved local
            //    state (like our dark mode) when a screen loads!"
            if (parsed.variables != null) {
                for (FlareEnvelope.VariableDef def : parsed.variables) {
                    if (PENDING_VAR.equals(def.name)) {
                        Log.w(TAG, "Developer tried to define reserved variable: " + PENDING_VAR + " — ignoring");
                        continue; // SDK owns this name
                    }
                    if (def.name.startsWith("local_") && initializedLocalVars.contains(def.name)) {
                        Log.d(TAG, "Skipping re-register of existing local_ variable: " + def.name);
                        continue;
                    }
                    if (def.name.startsWith("local_")) {
                        initializedLocalVars.add(def.name);
                    }
                    registerVariable(def.name, def.type, def.value);

                    // ── LOCAL ENGINE ADDITION ────────────────────────────
                    // Track exported variable names so updateVariable() can
                    // mirror future changes into FlareExportedVariables —
                    // see LOCAL_ENGINE_PROTOCOL.md §13.
                    if (def.exported) {
                        exportedVariableNames.add(def.name);
                    }
                }
            }

            // ── Step 2: Apply server state ────────────────────────────────────
            if (parsed.state != null) {
                parsed.state.keys().forEachRemaining(key -> {
                    try {
                        Object value = parsed.state.get(key);
                        updateVariable(key, value);
                    } catch (Exception e) {
                        Log.e(TAG, "Error applying state key: " + key, e);
                    }
                });
            }

            //  apply scaffold visibility ─────────────────────────
            // Only the CONTENT mount's init envelope ever carries a "scaffold"
            // field (it's the router's per-screen `scaffold: [...]` option —
            // see Flare.Channel.get_router_scaffold/2). Persistent regions never
            // carry this field on their own init, so we gate on `mount == contentMount`
            // exactly like the web client does: `if (mount === this.content && envelope.scaffold)`.
            if (mount == contentMount && envelope.has("scaffold")) {
                applyScaffold(envelope.optJSONArray("scaffold"));
            }

            // ── Step 3: Render layout ─────────────────────────────────────────
            if (parsed.layout == null) {
                if (mount == contentMount) {
                    transitionOverlay.showError(
                            "Server sent empty layout for screen: " + currentContentScreen,
                            this::retryCurrentScreen
                    );
                }
                return;
            }

            // Build Div2View — variables have been updated in steps 1 & 2 above
            FlareDivViewFactory factory = new FlareDivViewFactory(div2Context, globalVarsController);
            Div2View div2View = factory.createView(parsed.layout);
            mount.div2View = div2View;

            // Mark that content has successfully loaded at least once. From
            // here on, connection drops must never trigger the blocking
            // give-up dialog — see hasEverLoadedContent's declaration.
            if (mount == contentMount) {
                hasEverLoadedContent = true;
            }

            // ── Step 4: Show it — into THIS mount's container, not a shared one ──
            // Instant swap: add the new screen, then remove the old one(s)
            // immediately. No slide, no artificial delay — transitionOverlay
            // already covered/blocked the old screen for the entire load.
            int oldViewsCount = mount.container.getChildCount();
            mount.container.addView(div2View, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            for (int i = 0; i < oldViewsCount; i++) {
                mount.container.removeViewAt(0);
            }
            if (mount == contentMount) {
                transitionOverlay.hide();
            }

            // SAFETY CATCH: The page has successfully rendered.
            // If the popup is still stuck on the screen somehow, destroy it instantly!
            if (giveUpDialog != null && giveUpDialog.isShowing()) {
                giveUpDialog.dismiss();
                giveUpDialog = null;
            }

            // ── Step 5: Execute bootstrap commands from init envelope ─────────
            // The server puts store_token here on first connect (new guest).
            FlareServerDirectiveHandler.execute(
                    envelope,
                    this,
                    this::navigateTo,
                    this::storeToken,
                    this::clearStorage,
                    this::triggerHaptic
            );

            Log.d(TAG, "handleInit complete for mount=" + mount.region + " screen=" + mount.screenName);

        } catch (Exception e) {
            Log.e(TAG, "handleInit error for mount=" + mount.region, e);
            if (mount == contentMount) {
                transitionOverlay.showError(
                        "Error rendering screen: " + e.getMessage(),
                        this::retryCurrentScreen
                );
            }
        }
    }

    /**
     * Handle the "patch" message — incremental state update.
     *
     * Equivalent of _handlePatch() in the web client.
     * Steps:
     *  1. Apply new state values to DivKit global variables
     *  2. Clear the pending lock
     *  3. Execute any commands from the server
     */
    private void handlePatch(JSONObject envelope, Mount mount) {
        Log.d(TAG, "handlePatch for mount=" + mount.region);

        try {
            // ── Step 1: Update variables ──────────────────────────────────────
            JSONObject state = envelope.optJSONObject("state");
            if (state != null) {
                state.keys().forEachRemaining(key -> {
                    try {
                        Object val = state.get(key);
                        if (val != JSONObject.NULL) {
                            updateVariable(key, val);
                        }
                        // null means variable was removed — no-op, same as web.
                    } catch (Exception e) {
                        Log.e(TAG, "Patch: error updating key=" + key, e);
                    }
                });
            }

            // ── Step 2: the patch IS the server's ACK — release THIS mount's
            // pending lock(s), not everyone's. Mirrors web's:
            //   "The patch IS the server's response to the event. When it
            //    arrives, the operation is complete."
            clearPendingForMount(mount);

            // ── Step 3: Execute commands ──────────────────────────────────────
            FlareServerDirectiveHandler.execute(
                    envelope,
                    this,
                    this::navigateTo,
                    this::storeToken,
                    this::clearStorage,
                    this::triggerHaptic
            );

        } catch (Exception e) {
            Log.e(TAG, "handlePatch error", e);
        }
    }

    private void registerActionPendingVars(JSONObject layoutJson) {
        Set<String> actions = new HashSet<>();
        extractFlareActions(layoutJson, actions);
        for (String action : actions) {
            String varName = "local_flare_pending_" + action;
            globalVarsController.putOrUpdate(
                    new Variable.BooleanVariable(varName, false)
            );
            Log.d(TAG, "Auto-registered: " + varName);
        }
    }

    private void extractFlareActions(Object obj, Set<String> found) {
        if (obj instanceof JSONObject) {
            JSONObject json = (JSONObject) obj;
            if (json.has("flare_action")) {
                found.add(json.optString("flare_action"));
            }
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                try {
                    extractFlareActions(json.get(keys.next()), found);
                } catch (Exception ignored) {}
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.length(); i++) {
                try {
                    extractFlareActions(arr.get(i), found);
                } catch (Exception ignored) {}
            }
        }
    }
    /**
     * Handle "layout_update" — hot reload: new layout JSON, preserve variable values.
     *
     * Equivalent of _handleLayoutUpdate() in the web client.
     * Used after server deployments to refresh the UI without disconnecting.
     */
    private void handleLayoutUpdate(JSONObject envelope, Mount mount) {
        Log.d(TAG, "handleLayoutUpdate for mount=" + mount.region);

        try {
            FlareEnvelope parsed = FlareEnvelope.fromLayoutUpdate(envelope);

            // Reset the global pending flag on layout refresh (unchanged behavior)
            globalVarsController.putOrUpdate(new Variable.BooleanVariable(PENDING_VAR, false));
            // Also clear THIS mount's per-action pending set — a hot-deploy while
            // a tap was in flight on this exact mount shouldn't leave it frozen.
            clearPendingForMount(mount);

            // Re-render with new layout, keeping current variable values
            // (variables are in globalVarsController — they survive the view swap)
            if (parsed.layout != null) {
                FlareDivViewFactory factory = new FlareDivViewFactory(div2Context, globalVarsController);
                Div2View div2View = factory.createView(parsed.layout);
                mount.div2View = div2View;

                // Don't remove old views yet! Add new one on top.
                int oldViewsCount = mount.container.getChildCount();
                mount.container.addView(div2View, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                // Instant swap — no slide animation, no artificial delay.
                for (int i = 0; i < oldViewsCount; i++) {
                    mount.container.removeViewAt(0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "handleLayoutUpdate error for mount=" + mount.region, e);
        }
    }

    // ═══════════════════════════════════════
    //  DIVKIT ACTION HANDLER
    // ═══════════════════════════════════════

    /**
     * Called by FlareDivActionHandler when a DivKit element is tapped.
     *
     * This is the Android equivalent of _handleAction() in the web client.
     *
     * @param eventType  the "flare_action" value from the JSON payload
     * @param payload    the full payload JSONObject from the DivKit action
     */
    /**
     * MULTI-MOUNT UPDATE: now takes the DivViewFacade that fired the tap, so we
     * can identify which Mount owns it (see findMountForView below) — a tap on
     * the bottom bar must push through the bottom_bar channel and use the
     * bottom_bar mount's pending set, NOT content's.
     */
    public void onDivKitAction(String eventType, JSONObject payload, DivViewFacade view) {
        Log.d(TAG, "onDivKitAction: " + eventType);
        Mount sourceMount = findMountForView(view);
        handleResolvedAction(eventType, payload, sourceMount);
    }

    /**
     * ── LOCAL ENGINE ADDITION ────────────────────────────────────────────
     * Extracted, verbatim, from the original onDivKitAction() body so that
     * a flare://clientplugin follow-up action (on_success/on_error/
     * on_cancel) can be fired through the EXACT SAME pending-lock and
     * channel-push machinery a real DivKit tap uses — without needing a
     * DivViewFacade, which a synthetic follow-up action doesn't have.
     *
     * Nothing about the logic below is new or modified — it is the
     * pre-existing onDivKitAction() body, just given a name so it can be
     * called from two places instead of one.
     */
    private void handleResolvedAction(String eventType, JSONObject payload, Mount sourceMount) {
        String actionPendingVar = "local_flare_pending_" + eventType;

        // ═══════════════════════════════════════════════════════════════════
        // local dark mode intercept.
        // NEVER hits the server. Persisted to SharedPreferences so it survives
        // app restarts, exactly like web's localStorage.setItem("local_dark_mode", ...).
        // This must run BEFORE the pending-lock guard below — it's not a
        // server round trip, so there's no "in flight" state to guard against.
        // ═══════════════════════════════════════════════════════════════════
        if ("toggle_dark_mode".equals(eventType)) {
            boolean current = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                    .getBoolean(PREF_DARK_MODE, false);
            boolean next = !current;
            getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                    .edit().putBoolean(PREF_DARK_MODE, next).apply();
            runOnUiThread(() -> {
                globalVarsController.putOrUpdate(
                        new Variable.BooleanVariable("local_dark_mode", next)
                );
                // Keep the window background in sync so the NEXT transition
                // doesn't flash the old theme's color.
                getWindow().getDecorView().setBackgroundColor(next ? COLOR_BG_DARK : COLOR_BG_LIGHT);
                contentMount.container.setBackgroundColor(next ? COLOR_BG_DARK : COLOR_BG_LIGHT);
            });
            return; // local-only — no server push, no pending lock needed.
        }

        // ═══════════════════════════════════════════════════════════════════
        // local drawer / end_drawer open-close intercepts.
        // Purely local UI state, same treatment as toggle_dark_mode.
        // Intentionally NOT persisted (drawers start closed on every app
        // launch — matches the web client's explicit comment on this point).
        // ═══════════════════════════════════════════════════════════════════
        if ("open_drawer".equals(eventType) || "close_drawer".equals(eventType)
                || "open_end_drawer".equals(eventType) || "close_end_drawer".equals(eventType)) {
            boolean isOpen = eventType.startsWith("open_");
            String varName = eventType.endsWith("end_drawer") ? "local_end_drawer_open" : "local_drawer_open";

            initializedLocalVars.add(varName); // Track that we set this locally

            runOnUiThread(() -> globalVarsController.putOrUpdate(
                    new Variable.BooleanVariable(varName, isOpen)
            ));
            return; // local-only — no server push, no pending lock needed.
        }

        // ── Guard: one event in flight at a time, PER-MOUNT ────────────────────
        //  guard against sourceMount.pendingActions, not the old
        // Activity-wide set — a tap in flight on the bottom bar must not block
        // content, and vice versa.
        if (sourceMount.pendingActions.contains(eventType)) {
            Log.d(TAG, "Tap ignored — already in flight on mount=" + sourceMount.region + ": " + eventType);
            return;
        }

        // Lock only this action, only on the mount it belongs to.
        sourceMount.pendingActions.add(eventType);
        runOnUiThread(() -> globalVarsController.putOrUpdate(
                new Variable.BooleanVariable(actionPendingVar, true)
        ));

        // ── Check if this is a native feature request ──────────────────────────
        // Native features (camera, QR, etc.) only ever make sense from content,
        // but we don't hard-assume that — we just release via the SAME mount
        // that originated the tap so releasePendingAction stays symmetric with
        // the lock above.
        if (payload != null && payload.has("local_flare_native_action")) {
            String nativeFeature = payload.optString("local_flare_native_action");
            Log.d(TAG, "Local native action: " + nativeFeature);
            nativeBridge.handleFeature(nativeFeature, eventType, payload);
            return;
        }

        // ── Push event to Phoenix server — through THIS mount's channel ────────
        if (sourceMount.channel == null) {
            Log.e(TAG, "Cannot push event — no active channel on mount=" + sourceMount.region);
            releasePendingAction(sourceMount, actionPendingVar, eventType);
            return;
        }

        try {
            JSONObject eventPayload = new JSONObject();
            eventPayload.put("screen",  sourceMount.screenName);
            eventPayload.put("type",    eventType);
            eventPayload.put("payload", payload != null ? payload : new JSONObject());

            sourceMount.channel.push("event", eventPayload)
                    .receive("ok", (p, r, jr) -> {
                        Log.d(TAG, "ACK received for: " + eventType);
                        releasePendingAction(sourceMount, actionPendingVar, eventType);
                    })
                    .receive("error", (p, r, jr) -> {
                        Log.e(TAG, "Server rejected event: " + eventType);
                        releasePendingAction(sourceMount, actionPendingVar, eventType);
                    })
                    .receive("timeout", (p, r, jr) -> {
                        Log.e(TAG, "Event timeout: " + eventType);
                        releasePendingAction(sourceMount, actionPendingVar, eventType);
                    });

        } catch (Exception e) {
            Log.e(TAG, "onDivKitAction: failed to push event", e);
            releasePendingAction(sourceMount, actionPendingVar, eventType);
        }
    }

    /**
     * ── LOCAL ENGINE ADDITION ────────────────────────────────────────────
     * Routes a resolved flare://clientplugin invocation (already parsed by
     * FlareDivActionHandler) into FlareClientPluginEngine.dispatch(). This
     * method itself contains NO plugin-specific logic — only unpacking the
     * normalized invocation object and delegating to the engine, per
     * LOCAL_ENGINE_PROTOCOL.md §4.
     */
    private void dispatchClientPlugin(String pluginId, JSONObject invocation, DivViewFacade view) {
        if (clientPluginEngine == null) {
            // Should be unreachable — setupDivKit() always runs before any
            // tap can occur — but guarded defensively so a future refactor
            // can never turn this into a NullPointerException crash.
            Log.e(TAG, "dispatchClientPlugin: engine not initialized yet");
            return;
        }

        Mount sourceMount = findMountForView(view);
        String originScreenName = sourceMount != null ? sourceMount.screenName : currentContentScreen;

        String resultVar = invocation.optString("result_var", null);
        JSONObject params = invocation.optJSONObject("params");
        JSONArray expectFields = invocation.optJSONArray("expect_fields");
        String onSuccess = invocation.has("on_success") ? invocation.optString("on_success") : null;
        String onError   = invocation.has("on_error")   ? invocation.optString("on_error")   : null;
        String onCancel  = invocation.has("on_cancel")  ? invocation.optString("on_cancel")  : null;
        long timeoutMs   = invocation.optLong("timeout_ms", 0L);

        clientPluginEngine.dispatch(
                pluginId, resultVar, params, expectFields,
                onSuccess, onError, onCancel, timeoutMs, originScreenName
        );
    }

    /**
     *  identify which Mount a DivViewFacade belongs to, by identity
     * comparison against each mount's currently-rendered Div2View. Falls back
     * to contentMount if nothing matches (shouldn't normally happen, but keeps
     * onDivKitAction from NPE'ing on a weird timing edge case instead of
     * silently dropping the tap).
     */
    private Mount findMountForView(DivViewFacade view) {
        if (contentMount.div2View == view) return contentMount;
        for (Mount m : persistentMounts.values()) {
            if (m.div2View == view) return m;
        }
        Log.w(TAG, "findMountForView: no mount matched this view — defaulting to content");
        return contentMount;
    }

    // ═══════════════════════════════════════
    //  NATIVE FEATURE BRIDGE CALLBACK
    // ═══════════════════════════════════════

    /**
     * Called by NativeFeatureBridge when a native feature completes.
     *
     * The result is injected as a DivKit variable so the layout can react to it,
     * AND sent to the server as a Flare event so business logic can run.
     *
     * @param eventType    the original "flare_action" string
     * @param resultKey    DivKit variable name to set (e.g. "flare_qr_result")
     * @param resultValue  the result value (String, Boolean, Integer, etc.)
     * @param sendToServer whether to also push this as a server event
     */
    public void onNativeResult(String eventType, String resultKey, Object resultValue, boolean sendToServer) {
        Log.d(TAG, "onNativeResult: eventType=" + eventType + " key=" + resultKey + " value=" + resultValue);

        // ── Update the DivKit variable immediately (fast UI feedback) ──────────
        runOnUiThread(() -> updateVariable(resultKey, resultValue));

        // ── Optionally push to server ──────────────────────────────────────────
        //  native features (camera, QR scan) only ever originate
        // from the content screen in practice, so we target contentMount
        // explicitly here rather than trying to thread a Mount reference through
        // NativeFeatureBridge's callback signature (which would be a much bigger
        // change for a feature that isn't actually multi-mount-aware).
        if (sendToServer && contentMount.channel != null) {
            String actionPendingVar = "local_flare_pending_" + eventType;
            contentMount.pendingActions.add(eventType);
            runOnUiThread(() -> globalVarsController.putOrUpdate(
                    new Variable.BooleanVariable(actionPendingVar, true)
            ));

            try {
                JSONObject payload = new JSONObject();
                payload.put(resultKey, resultValue);

                JSONObject eventPayload = new JSONObject();
                eventPayload.put("screen",  contentMount.screenName);
                eventPayload.put("type",    eventType);
                eventPayload.put("payload", payload);

                contentMount.channel.push("event", eventPayload)
                        .receive("ok",      (p, r, jr) -> releasePendingAction(contentMount, actionPendingVar, eventType))
                        .receive("error",   (p, r, jr) -> releasePendingAction(contentMount, actionPendingVar, eventType))
                        .receive("timeout", (p, r, jr) -> releasePendingAction(contentMount, actionPendingVar, eventType));

            } catch (Exception e) {
                Log.e(TAG, "onNativeResult: failed to push to server", e);
                releasePendingAction(contentMount, actionPendingVar, eventType);
            }
        }
    }

    // ═══════════════════════════════════════
    //  DIVKIT VARIABLE MANAGEMENT
    // ═══════════════════════════════════════

    /**
     * Register a new variable in the global DivKit controller with type information.
     * If the variable already exists, updates its value instead.
     *
     * Type string matches the Flare state JSON format:
     *   "string", "integer", "number", "boolean"
     */
    private void registerVariable(String name, String type, Object initialValue) {
        Log.d(TAG, "registerVariable: " + name + " type=" + type + " value=" + initialValue);

        try {
            Variable var;
            switch (type == null ? "string" : type.toLowerCase()) {
                case "integer":
                    long longVal = initialValue instanceof Number
                            ? ((Number) initialValue).longValue() : 0L;
                    var = new Variable.IntegerVariable(name, longVal);
                    break;
                case "number":
                    double doubleVal = initialValue instanceof Number
                            ? ((Number) initialValue).doubleValue() : 0.0;
                    var = new Variable.DoubleVariable(name, doubleVal);
                    break;
                case "boolean":
                    boolean boolVal = initialValue instanceof Boolean
                            ? (Boolean) initialValue : false;
                    var = new Variable.BooleanVariable(name, boolVal);
                    break;
                case "array": {
                    org.json.JSONArray arrVal = (initialValue instanceof org.json.JSONArray)
                            ? (org.json.JSONArray) initialValue : new org.json.JSONArray();
                    var = new Variable.ArrayVariable(name, arrVal);
                    break;
                }
                case "dict": {
                    JSONObject dictVal = (initialValue instanceof JSONObject)
                            ? (JSONObject) initialValue : new JSONObject();
                    var = new Variable.DictVariable(name, dictVal);
                    break;
                }
                case "string":
                default:
                    String strVal = initialValue != null ? initialValue.toString() : "";
                    var = new Variable.StringVariable(name, strVal);
                    break;
            }
            globalVarsController.putOrUpdate(var);
        } catch (Exception e) {
            Log.e(TAG, "registerVariable error for " + name, e);
        }
    }

    /**
     * Update an existing variable's value in the global DivKit controller.
     * DivKit will automatically re-render any layout elements that bind to this variable.
     *
     * Type is inferred from the Java type of the value (same as web client).
     */
    private void updateVariable(String name, Object value) {
        try {
            if (value instanceof Long || value instanceof Integer) {
                long v = ((Number) value).longValue();
                globalVarsController.putOrUpdate(new Variable.IntegerVariable(name, v));
            } else if (value instanceof Double || value instanceof Float) {
                double v = ((Number) value).doubleValue();
                globalVarsController.putOrUpdate(new Variable.DoubleVariable(name, v));
            } else if (value instanceof Boolean) {
                boolean v = (Boolean) value;
                globalVarsController.putOrUpdate(new Variable.BooleanVariable(name, v));
            } else if (value instanceof org.json.JSONArray) {
                globalVarsController.putOrUpdate(new Variable.ArrayVariable(name, (org.json.JSONArray) value));
            } else if (value instanceof JSONObject) {
                globalVarsController.putOrUpdate(new Variable.DictVariable(name, (JSONObject) value));
            } else {
                String v = value != null ? value.toString() : "";
                globalVarsController.putOrUpdate(new Variable.StringVariable(name, v));
            }

            // ── LOCAL ENGINE ADDITION ────────────────────────────────────
            // One-directional mirror into FlareExportedVariables for any
            // variable declared "exported": true — protocol §13. This is a
            // WRITE-ONLY path from Flare's perspective: FlareExportedVariables
            // is never read back into DivKit here or anywhere else.
            if (exportedVariableNames.contains(name)) {
                FlareExportedVariables.set(name, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateVariable error for " + name, e);
        }
    }

    // ═══════════════════════════════════════
    //  PENDING LOCK
    // ═══════════════════════════════════════

    /**
     * Set the local_flare_pending DivKit variable.
     * true  = event in flight, UI should dim buttons
     * false = idle, buttons are interactive
     *
     * Must be safe to call from any thread.
     */
    public void setPending(boolean value) {
        runOnUiThread(() -> {
            try {
                globalVarsController.putOrUpdate(new Variable.BooleanVariable(PENDING_VAR, value));
                Log.d(TAG, "setPending: " + value);
            } catch (Exception e) {
                Log.e(TAG, "setPending error", e);
            }
        });
    }

    // ═══════════════════════════════════════
    //  COMMANDS (server → client)
    // ═══════════════════════════════════════

    /**
     * Store an auth token locally (from Flare store_token command).
     * Saved to SharedPreferences. Sent in socket params on next connect.
     */
    public void storeToken(String token) {
        Log.d(TAG, "storeToken: saving token");

        SharedPreferences prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
        boolean isFirstToken = prefs.getString(PREF_TOKEN, null) == null;

        // Save the token to SharedPreferences so next app launch uses it
        prefs.edit().putString(PREF_TOKEN, token).apply();

        // If this is the very first token (we were anonymous this session),
        // we must reconnect the socket with the token NOW.
        // The socket sends token as a connect param — it cannot be changed
        // after connection. A reconnect is the only way to get an identity
        // for the rest of this session.
        if (isFirstToken) {
            Log.d(TAG, "First token received — reconnecting socket with identity");
            String screenToRejoin = currentContentScreen; // remember where we are

            // Disconnect cleanly — PhoenixSocket will reconnect automatically
            // because we call connect() again below
            if (contentMount.channel != null) {
                contentMount.channel.leave();
                contentMount.channel = null;
            }

            // ── ──────────────────────────────────────────────────
            // We're about to shut down the ENTIRE socket and build a new one.
            // joinPersistentScreens() (called from the new socket's onOpen)
            // only rejoins a mount whose `channel` is null. If we left the
            // persistent mounts' stale channel references pointing at the
            // dead socket, EVERY persistent region (bottom_bar, top_bar,
            // drawer, end_drawer, overlay) would silently never rejoin after
            // this reconnect — a guest→logged-in upgrade would leave the
            // whole scaffold blank. Null them all out here so the guard
            // in joinPersistentScreens() sees them as needing a fresh join.
            for (Mount m : persistentMounts.values()) {
                if (m.channel != null) {
                    m.channel.leave();
                    m.channel = null;
                }
            }

            if (socket != null) {
                socket.shutdown();
                socket = null;
            }

            // Small delay to let the OS close the old connection cleanly
            // before opening a new one with the token param attached
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "Rebuilding socket with token, rejoining: " + screenToRejoin);
                buildAndConnectSocket(screenToRejoin);
            }, 300);
        }
    }


    // ═══════════════════════════════════════
    //  SCAFFOLD VISIBILITY
    // ═══════════════════════════════════════

    /**
     *  apply the content screen's declared scaffold list from its
     * init envelope's "scaffold" field (router option, e.g.
     * `scaffold: [:bottom_bar, :top_bar, :drawer, :end_drawer]`).
     *
     * Regions listed in SCAFFOLD_REGIONS but NOT present in `scaffoldList` are
     * hidden; "overlay" is never touched here (it self-governs via its own
     * flare_overlay_visible variable, same as web).
     *
     * @param scaffoldList JSON array of region-name strings from the envelope,
     *                      or null if the screen didn't declare a scaffold
     *                      option at all (meaning: "leave current visibility as-is").
     */
    public void applyScaffold(JSONArray scaffoldList) {
        if (scaffoldList == null) return; // nil in Elixir → client leaves regions untouched.

        Set<String> visible = new HashSet<>();
        for (int i = 0; i < scaffoldList.length(); i++) {
            visible.add(scaffoldList.optString(i));
        }

        runOnUiThread(() -> {
            for (String region : SCAFFOLD_REGIONS) {
                Mount m = persistentMounts.get(region);
                if (m != null) {
                    m.container.setVisibility(visible.contains(region) ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    /**
     *  runtime override for a single region — driven by the
     * server-sent "show_scaffold" command (Flare.Commands.show_scaffold/2).
     * Does NOT change the screen's declared default in the router; it's a
     * transient override, e.g. re-showing the bottom bar after a modal-like
     * flow finishes.
     */
    public void showScaffold(String region) {
        runOnUiThread(() -> {
            Mount m = persistentMounts.get(region);
            if (m != null) {
                m.container.setVisibility(View.VISIBLE);
            } else {
                Log.w(TAG, "showScaffold: unknown region '" + region + "'");
            }
        });
    }

    /**
     *  runtime override — hides one scaffold region without
     * changing the screen's declared default. Driven by the server-sent
     * "hide_scaffold" command (Flare.Commands.hide_scaffold/2).
     */
    public void hideScaffold(String region) {
        runOnUiThread(() -> {
            Mount m = persistentMounts.get(region);
            if (m != null) {
                m.container.setVisibility(View.GONE);
            } else {
                Log.w(TAG, "hideScaffold: unknown region '" + region + "'");
            }
        });
    }

    /**
     * Clear stored auth token (from Flare clear_storage command — i.e. logout).
     */
    public void clearStorage() {
        Log.d(TAG, "clearStorage: removing auth token and returning to login");
        getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                .edit()
                .remove(PREF_TOKEN)
                .apply();
        finish(); // Closes FlareClientActivity, throwing user back to previous screen
    }

    /**
     * Trigger haptic feedback (from Flare haptic command).
     *
     * Maps Flare haptic styles to Android vibration patterns:
     *   success → medium single pulse
     *   warning → double pulse
     *   error   → strong triple pulse
     *   light   → very short
     *   medium  → short
     *   heavy   → long
     */
    public void triggerHaptic(String style) {
        Log.d(TAG, "triggerHaptic: " + style);
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;

            long durationMs;
            switch (style) {
                case "light":   durationMs = 30;  break;
                case "medium":  durationMs = 60;  break;
                case "heavy":   durationMs = 100; break;
                case "warning":
                    vibrator.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 40, 80, 40}, -1));
                    return;
                case "error":
                    vibrator.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 40, 60, 40, 60, 80}, -1));
                    return;
                case "success":
                default:
                    durationMs = 50;
                    break;
            }
            vibrator.vibrate(VibrationEffect.createOneShot(
                    durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception e) {
            Log.e(TAG, "triggerHaptic error", e);
        }
    }


    // ═══════════════════════════════════════
//  PENDING ACTION HELPERS
// ═══════════════════════════════════════

    /**
     * Release a single pending action lock and reset its DivKit variable.
     * Replaces the repeated pendingActions.remove + putOrUpdate pattern.
     * Safe to call from any thread.
     */
    /**
     * Release a single pending action lock for ONE mount and reset its DivKit
     * variable. `eventType` (the raw action name, e.g. "increment") is needed
     * separately from `actionPendingVar` (the DivKit variable name,
     * "local_flare_pending_increment") because the Set stores the former while
     * the DivKit controller is keyed by the latter.
     */
    private void releasePendingAction(Mount mount, String actionPendingVar, String eventType) {
        mount.pendingActions.remove(eventType);
        runOnUiThread(() -> globalVarsController.putOrUpdate(
                new Variable.BooleanVariable(actionPendingVar, false)
        ));
    }

    /**
     *  clear pending locks for ONE mount only. This is what ordinary
     * content navigation should call — clearing everyone else's pending state
     * just because the content screen changed would incorrectly unfreeze a
     * button on, say, the bottom bar mid-request.
     */
    private void clearPendingForMount(Mount mount) {
        Set<String> snapshot = new HashSet<>(mount.pendingActions);
        mount.pendingActions.clear();
        runOnUiThread(() -> {
            for (String eventType : snapshot) {
                globalVarsController.putOrUpdate(
                        new Variable.BooleanVariable("local_flare_pending_" + eventType, false));
            }
        });
    }

    /**
     * TRUE global clear — every mount's pending state, plus the coarse
     * PENDING_VAR flag. Reserved for socket-level failure (onClose/onError)
     * and auth-failure teardown, where NOTHING is safely "in flight" anymore
     * because the whole connection is gone. Ordinary navigation must use
     * clearPendingForMount() instead
     */
    private void clearAllPendingActions() {
        clearPendingForMount(contentMount);
        for (Mount m : persistentMounts.values()) {
            clearPendingForMount(m);
        }
        runOnUiThread(() -> globalVarsController.putOrUpdate(new Variable.BooleanVariable(PENDING_VAR, false)));
    }

    /**
     * Re-join the current screen. Called by retry button in the transition overlay.
     */
//    private void retryCurrentScreen() {
//        if (currentScreen == null) return;
//        Log.d(TAG, "retryCurrentScreen: " + currentScreen);
//
//        if (currentChannel != null) {
//            currentChannel.leave();
//            currentChannel = null;
//        }
//        clearAllPendingActions();
//
//        String topic = "flare:" + currentScreen;
//        currentChannel = socket.channel(topic, null);
//
//        currentChannel.on("init",          (p, r, jr) -> runOnUiThread(() -> handleInit(p)));
//        currentChannel.on("patch",         (p, r, jr) -> runOnUiThread(() -> handlePatch(p)));
//        currentChannel.on("layout_update", (p, r, jr) -> runOnUiThread(() -> handleLayoutUpdate(p)));
//
//        currentChannel.join()
//                .receive("ok",      (p, r, jr) -> Log.d(TAG, "Retry joined: " + topic))
//                .receive("error",   (p, r, jr) -> runOnUiThread(() -> transitionOverlay.showError(
//                        "Could not reload screen: " + currentScreen, this::retryCurrentScreen)))
//                .receive("timeout", (p, r, jr) -> runOnUiThread(() -> transitionOverlay.showError(
//                        "Reload timed out.", this::retryCurrentScreen)));
//    }

    // ═══════════════════════════════════════
    //  ACTIVITY RESULT PASSTHROUGH
    // ═══════════════════════════════════════

    /**
     * Pass activity results through to the NativeFeatureBridge.
     * Required for camera, QR scan, and file picker features.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (nativeBridge != null) {
            nativeBridge.onActivityResult(requestCode, resultCode, data);
        }
    }
}