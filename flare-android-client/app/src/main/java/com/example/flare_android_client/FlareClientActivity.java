package com.example.flare_android_client;

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

import com.example.flare_android_client.divkit.FlareDivActionHandler;
import com.example.flare_android_client.divkit.FlareDivViewFactory;
import com.example.flare_android_client.flare.FlareCommandHandler;
import com.example.flare_android_client.flare.FlareEnvelope;
import com.example.flare_android_client.native_features.NativeFeatureBridge;
import com.example.flare_android_client.phoenix.PhoenixChannelClient;
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

public class FlareClientActivity extends AppCompatActivity {

    private static final String TAG = "FlareClient";

    // ── Intent extras (passed from MainActivity) ────────────────────────────
    public static final String EXTRA_WS_URL      = "flare_ws_url";
    public static final String EXTRA_ENTRY_SCREEN = "flare_entry_screen";
    public static final String EXTRA_TOKEN       = "flare_token";

    // ── SharedPreferences (for store_token / clear_storage commands) ─────────
    private static final String PREF_FILE     = "flare_prefs";
    private static final String PREF_TOKEN    = "flare_auth_token";
    private static final String PREF_GUEST_ID = "flare_guest_id";

    // ── Reserved DivKit variable — SDK owns this, mirrors web client ──────────
    // When any flare://action fires, this becomes true immediately.
    // Cleared when the server's patch (or ACK) arrives.
    // Layout JSON can use: "@{local_flare_pending ? 0.5 : 1.0}" to dim buttons.
    public static final String PENDING_VAR = "local_flare_pending";

    // ── Views ──────────────────────────────────────────────────────────────────
    private FrameLayout    flContainer;   // root container — DivKit views are added here
    private ProgressBar    pbSpinner;     // shown during screen transitions
    private TextView       tvError;       // shown on connection errors

    // ── Phoenix ────────────────────────────────────────────────────────────────
    private PhoenixChannelClient.PhoenixSocket    socket;
    private PhoenixChannelClient.PhoenixChannel   currentChannel;
    private String                                 currentScreen;
    private String                                 wsUrl;

    // ── Navigation back stack ──────────────────────────────────────────────────
    // Mirrors the web client's browser history (pushState / popstate).
    // Each navigateTo() pushes a screen name; back button pops.
    private final ArrayDeque<String> backStack = new ArrayDeque<>();

    // ── DivKit ─────────────────────────────────────────────────────────────────
    private Div2Context                            div2Context;
    private com.yandex.div.core.state.DivStatePath currentStatePath; // for patch updates
    // Global variable controller — variables survive screen transitions (like web)
    // e.g. flare_cart_count stays set when moving from store → product → cart
    private com.yandex.div.core.expression.variables.DivVariableController globalVarsController;

    // ── Native features ────────────────────────────────────────────────────────
    // Bridge that lets DivKit layouts trigger camera, QR scan, etc.
    // Developers extend NativeFeatureBridge to add their own features.
    private NativeFeatureBridge nativeBridge;

    // ── Current Div2View (kept so we can update variables on patch) ────────────
    private Div2View currentDiv2View;

    private boolean isEventPending = false;
    private final Set<String> pendingActions = new HashSet<>();


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
        launch(context, wsUrl, entryScreen, null);
    }

    public static void launch(Context context, String wsUrl, String entryScreen, String token) {
        Intent intent = new Intent(context, FlareClientActivity.class);
        intent.putExtra(EXTRA_WS_URL, wsUrl);
        intent.putExtra(EXTRA_ENTRY_SCREEN, entryScreen);
        if (token != null) {
            intent.putExtra(EXTRA_TOKEN, token);
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

        flContainer = findViewById(R.id.fl_container);
        pbSpinner   = findViewById(R.id.pb_spinner);
        tvError     = findViewById(R.id.tv_error);

        // Read intent extras
        wsUrl               = getIntent().getStringExtra(EXTRA_WS_URL);
        String entryScreen  = getIntent().getStringExtra(EXTRA_ENTRY_SCREEN);
        if (entryScreen == null) entryScreen = "welcome";

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
        // 1. Create the variable controller FIRST
        globalVarsController = new com.yandex.div.core.expression.variables.DivVariableController();
        globalVarsController.putOrUpdate(new Variable.BooleanVariable(PENDING_VAR, false));

        FlareDivActionHandler actionHandler = new FlareDivActionHandler(this::onDivKitAction, globalVarsController);

        // 2. Attach it to the DivKit Configuration!

        // 2. Attach it to the DivKit Configuration!
        DivConfiguration config = new DivConfiguration.Builder(new CoilDivImageLoader(this))
                .actionHandler(actionHandler)
                .divVariableController(globalVarsController) // 🔥 FIX: Attach variables here
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
                        .logger((tag, msg) -> Log.d("PhxSocket[" + tag + "]", msg));

        // Only attach token param if we have one.
        // Sending no token param is valid — server handles anonymous connects.
        if (storedToken != null) {
            builder.param("token", storedToken);
        }

        socket = builder.build();

        // Socket opened (or re-opened after reconnect)
        socket.onOpen(() -> {
            Log.d(TAG, "Socket opened");
            runOnUiThread(() -> tvError.setVisibility(View.GONE));
        });

        // Socket closed — show error in UI
        socket.onClose((code, reason) -> {
            Log.w(TAG, "Socket closed: code=" + code + " reason=" + reason);
            runOnUiThread(() -> {
                if (code != 1000) { // 1000 = normal close (we disconnected on purpose)
                    showError("Disconnected. Reconnecting…");
                }
            });
        });

        // Socket error
        socket.onError(reason -> {
            Log.e(TAG, "Socket error: " + reason);
            runOnUiThread(() -> showError("Connection error: " + reason));
        });

        socket.connect();

        // Join the first screen
        navigateTo(entryScreen);
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
    public void navigateTo(String screenName) {
        navigateTo(screenName, null);
    }

    public void navigateTo(String screenName, JSONObject params) {
        Log.d(TAG, "navigateTo: " + screenName);

        // ── Leave current channel ─────────────────────────────────────────────
        if (currentChannel != null) {
            Log.d(TAG, "Leaving current channel: flare:" + currentScreen);
            currentChannel.leave();
            currentChannel = null;
        }

        // ── Clear all pending state — new screen starts fresh ─────────────────
        isEventPending = false;
        pendingActions.clear();

        // ── Show spinner ───────────────────────────────────────────────────────
        runOnUiThread(this::showSpinner);

        // ── Update back stack ──────────────────────────────────────────────────
        if (!screenName.equals(currentScreen)) {
            if (currentScreen != null) backStack.push(currentScreen);
            currentDiv2View = null; // Clean slate for new page
        }
        currentScreen = screenName;

        // ── Join Phoenix channel ───────────────────────────────────────────────
        String topic = "flare:" + screenName;
        currentChannel = socket.channel(topic, params);

        currentChannel.on("init",          (payload, ref, joinRef) -> {
            Log.d(TAG, "INIT received for screen: " + screenName);
            runOnUiThread(() -> handleInit(payload));
        });
        currentChannel.on("patch",         (payload, ref, joinRef) -> {
            Log.d(TAG, "PATCH received for screen: " + screenName);
            runOnUiThread(() -> handlePatch(payload));
        });
        currentChannel.on("layout_update", (payload, ref, joinRef) -> {
            Log.d(TAG, "LAYOUT_UPDATE received for screen: " + screenName);
            runOnUiThread(() -> handleLayoutUpdate(payload));
        });

        // Join!
        currentChannel.join()
                .receive("ok", (p, r, jr) -> {
                    Log.d(TAG, "Joined channel: " + topic);
                })
                .receive("error", (p, r, jr) -> {
                    Log.e(TAG, "Failed to join channel: " + topic + " payload=" + p);
                    String reason = p != null ? p.optString("reason", "") : "";

                    if ("authentication_required".equals(reason) ||
                            "session_expired".equals(reason) ||
                            "invalid_token".equals(reason)) {
                        Log.e(TAG, "Auth failed via socket — redirecting to login");
                        runOnUiThread(this::clearStorage);
                    } else {
                        runOnUiThread(() -> showError("Could not load screen: " + screenName));
                    }
                })
                .receive("timeout", (p, r, jr) -> {
                    Log.e(TAG, "Timeout joining channel: " + topic);
                    runOnUiThread(() -> showError("Timeout loading screen: " + screenName));
                });
    }

    // ═══════════════════════════════════════
    //  ANDROID BACK BUTTON
    // ═══════════════════════════════════════

    /**
     * Handle Android back button press.
     *
     * Pops the back stack to go to the previous Flare screen.
     * If the stack is empty, finish the Activity (goes back to MainActivity).
     *
     * This mirrors the web client's window popstate handler.
     */
    private void handleBack() {
        Log.d(TAG, "handleBack: backStack size=" + backStack.size());
        if (!backStack.isEmpty()) {
            String previous = backStack.pop();
            Log.d(TAG, "Back → " + previous);
            // Use a private version that doesn't push to the back stack again
            navigateBack(previous);
        } else {
            Log.d(TAG, "Back stack empty — finishing Activity");
            finish();
        }
    }

    /**
     * Navigate back to a previous screen without pushing to the back stack.
     * Used internally by handleBack().
     */
    private void navigateBack(String screenName) {
        Log.d(TAG, "navigateBack: " + screenName);

        if (currentChannel != null) {
            currentChannel.leave();
            currentChannel = null;
        }
        currentDiv2View = null; // 🔥 FIX: Clean slate for previous page
        isEventPending = false;
        pendingActions.clear();
        runOnUiThread(this::showSpinner);

        currentScreen  = screenName;
        String topic   = "flare:" + screenName;
        currentChannel = socket.channel(topic, null);

        currentChannel.on("init",          (p, r, jr) -> runOnUiThread(() -> handleInit(p)));
        currentChannel.on("patch",         (p, r, jr) -> runOnUiThread(() -> handlePatch(p)));
        currentChannel.on("layout_update", (p, r, jr) -> runOnUiThread(() -> handleLayoutUpdate(p)));

        currentChannel.join()
                .receive("ok",      (p, r, jr) -> Log.d(TAG, "Rejoined: " + topic))
                .receive("error",   (p, r, jr) -> runOnUiThread(() -> showError("Error: " + screenName)))
                .receive("timeout", (p, r, jr) -> runOnUiThread(() -> showError("Timeout: " + screenName)));
    }


    private void renderDivKitLayout(JSONObject layoutJson) {
        if (layoutJson == null) return;
        try {
            com.yandex.div.data.DivParsingEnvironment env = new com.yandex.div.data.DivParsingEnvironment(com.yandex.div.json.ParsingErrorLogger.ASSERT);
            JSONObject cardJson = layoutJson.has("card") ? layoutJson.optJSONObject("card") : layoutJson;
            com.yandex.div2.DivData divData = com.yandex.div2.DivData.Companion.fromJson(env, cardJson);

            if (currentDiv2View != null) {
                // 🔥 DIFFING: Update existing screen gracefully
                currentDiv2View.setData(divData, new com.yandex.div.DivDataTag("flare_screen"));
            } else {
                // FIRST TIME: Build screen from scratch
                currentDiv2View = new com.yandex.div.core.view2.Div2View(div2Context);
                currentDiv2View.setData(divData, new com.yandex.div.DivDataTag("flare_screen"));
                flContainer.removeAllViews();
                flContainer.addView(currentDiv2View, new FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to render DivKit layout", e);
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
    private void handleInit(JSONObject envelope) {
        Log.d(TAG, "handleInit: building DivKit view");

        try {
            FlareEnvelope parsed = FlareEnvelope.fromInit(envelope);

            // ── Step 1: Register variable types ──────────────────────────────
            // Reset the pending var first (always false when a screen loads)
            // Auto register per-action pending vars by scanning layout
            registerActionPendingVars(parsed.layout);

            // Register each variable definition from the state JSON
            if (parsed.variables != null) {
                for (FlareEnvelope.VariableDef def : parsed.variables) {
                    if (PENDING_VAR.equals(def.name)) {
                        Log.w(TAG, "Developer tried to define reserved variable: " + PENDING_VAR + " — ignoring");
                        continue; // SDK owns this name
                    }
                    registerVariable(def.name, def.type, def.value);
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

            // ── Step 3: Render layout ─────────────────────────────────────────
            if (parsed.layout == null) {
                showError("Server sent empty layout for screen: " + currentScreen);
                return;
            }

            // Build Div2View — variables have been updated in steps 1 & 2 above
            FlareDivViewFactory factory = new FlareDivViewFactory(div2Context, globalVarsController);
            Div2View div2View = factory.createView(parsed.layout);
            currentDiv2View = div2View;

            // ── Step 4: Show it ───────────────────────────────────────────────
            flContainer.removeAllViews();
            flContainer.addView(div2View, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            hideSpinner();

            // ── Step 5: Execute bootstrap commands from init envelope ─────────
            // The server puts store_token here on first connect (new guest).
            // Without this the token is never saved and every reconnect
            // generates a brand new guest ID — which is why count resets to 0.
            FlareCommandHandler.execute(
                    envelope,
                    this,
                    this::navigateTo,
                    this::storeToken,
                    this::clearStorage,
                    this::triggerHaptic
            );

            Log.d(TAG, "handleInit complete for screen: " + currentScreen);

        } catch (Exception e) {
            Log.e(TAG, "handleInit error", e);
            showError("Error rendering screen: " + e.getMessage());
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
    private void handlePatch(JSONObject envelope) {
        Log.d(TAG, "handlePatch");

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
                        // null means variable was removed — we don't delete DivKit vars,
                        // but we could set them to a default. Currently a no-op like web.
                    } catch (Exception e) {
                        Log.e(TAG, "Patch: error updating key=" + key, e);
                    }
                });
            }



            // ── Step 3: Execute commands ──────────────────────────────────────
            FlareCommandHandler.execute(
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
    private void handleLayoutUpdate(JSONObject envelope) {
        Log.d(TAG, "handleLayoutUpdate");

        try {
            FlareEnvelope parsed = FlareEnvelope.fromLayoutUpdate(envelope);

            // Reset pending to false on layout refresh
            globalVarsController.putOrUpdate(new Variable.BooleanVariable(PENDING_VAR, false));

            // Re-render with new layout, keeping current variable values
            // (variables are in globalVarsController — they survive the view swap)
            if (parsed.layout != null) {
                FlareDivViewFactory factory = new FlareDivViewFactory(div2Context, globalVarsController);
                Div2View div2View = factory.createView(parsed.layout);
                currentDiv2View = div2View;

                flContainer.removeAllViews();
                flContainer.addView(div2View, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "handleLayoutUpdate error", e);
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
    public void onDivKitAction(String eventType, JSONObject payload) {
        Log.d(TAG, "onDivKitAction: " + eventType);

        // ── Guard: one event in flight at a time ──────────────────────────────
        // Per-action pending variable
        String actionPendingVar = "local_flare_pending_" + eventType;

// Check only this action — others stay unblocked
        // Check per-action pending state using a local tracking set
// DivKit variable controller does not expose getVariable — we track state separately
        if (pendingActions.contains(actionPendingVar)) {
            Log.d(TAG, "Tap ignored — already in flight: " + eventType);
            return;
        }

// Lock only this action
        // Lock only this action — track in our set AND update DivKit variable
        pendingActions.add(actionPendingVar);
        runOnUiThread(() -> globalVarsController.putOrUpdate(
                new Variable.BooleanVariable(actionPendingVar, true)
        ));

        // ── Check if this is a native feature request ──────────────────────────
        if (payload != null && payload.has("local_flare_native_action")) {
            String nativeFeature = payload.optString("local_flare_native_action");
            Log.d(TAG, "Local native action: " + nativeFeature);
            nativeBridge.handleFeature(nativeFeature, eventType, payload);
            return;
        }

        // ── Push event to Phoenix server ───────────────────────────────────────
        if (currentChannel == null) {
            Log.e(TAG, "Cannot push event — no active channel");
            // Clear per-action lock since we are not pushing
            runOnUiThread(() -> globalVarsController.putOrUpdate(
                    new Variable.BooleanVariable(actionPendingVar, false)
            ));
            return;
        }

        try {
            JSONObject eventPayload = new JSONObject();
            eventPayload.put("screen",  currentScreen);
            eventPayload.put("type",    eventType);
            eventPayload.put("payload", payload != null ? payload : new JSONObject());

            currentChannel.push("event", eventPayload)
                    .receive("ok", (p, r, jr) -> {
                        Log.d(TAG, "ACK received for: " + eventType);
                        pendingActions.remove(actionPendingVar);
                        runOnUiThread(() -> globalVarsController.putOrUpdate(
                                new Variable.BooleanVariable(actionPendingVar, false)
                        ));
                    })
                    .receive("error", (p, r, jr) -> {
                        Log.e(TAG, "Server rejected event: " + eventType);
                        pendingActions.remove(actionPendingVar);
                        runOnUiThread(() -> globalVarsController.putOrUpdate(
                                new Variable.BooleanVariable(actionPendingVar, false)
                        ));
                    })
                    .receive("timeout", (p, r, jr) -> {
                        Log.e(TAG, "Event timeout: " + eventType);
                        pendingActions.remove(actionPendingVar);
                        runOnUiThread(() -> globalVarsController.putOrUpdate(
                                new Variable.BooleanVariable(actionPendingVar, false)
                        ));
                    });

        } catch (Exception e) {
            Log.e(TAG, "onDivKitAction: failed to push event", e);
            // Clear per-action lock on unexpected error
            final String pendingVarCapture = actionPendingVar;
            pendingActions.remove(pendingVarCapture);
            runOnUiThread(() -> globalVarsController.putOrUpdate(
                    new Variable.BooleanVariable(pendingVarCapture, false)
            ));
        }
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
        if (sendToServer && currentChannel != null) {
            // Use per-action pending var for native results too
            String actionPendingVar = "local_flare_pending_" + eventType;
            pendingActions.add(actionPendingVar);
            runOnUiThread(() -> globalVarsController.putOrUpdate(
                    new Variable.BooleanVariable(actionPendingVar, true)
            ));

            try {
                JSONObject payload = new JSONObject();
                payload.put(resultKey, resultValue);

                JSONObject eventPayload = new JSONObject();
                eventPayload.put("screen",  currentScreen);
                eventPayload.put("type",    eventType);
                eventPayload.put("payload", payload);

                currentChannel.push("event", eventPayload)
                        .receive("ok", (p, r, jr) -> {
                            pendingActions.remove(actionPendingVar);
                            runOnUiThread(() -> globalVarsController.putOrUpdate(
                                    new Variable.BooleanVariable(actionPendingVar, false)
                            ));
                        })
                        .receive("error", (p, r, jr) -> {
                            pendingActions.remove(actionPendingVar);
                            runOnUiThread(() -> globalVarsController.putOrUpdate(
                                    new Variable.BooleanVariable(actionPendingVar, false)
                            ));
                        })
                        .receive("timeout", (p, r, jr) -> {
                            pendingActions.remove(actionPendingVar);
                            runOnUiThread(() -> globalVarsController.putOrUpdate(
                                    new Variable.BooleanVariable(actionPendingVar, false)
                            ));
                        });

            } catch (Exception e) {
                Log.e(TAG, "onNativeResult: failed to push to server", e);
                pendingActions.remove(actionPendingVar);
                runOnUiThread(() -> globalVarsController.putOrUpdate(
                        new Variable.BooleanVariable(actionPendingVar, false)
                ));
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
            } else {
                String v = value != null ? value.toString() : "";
                globalVarsController.putOrUpdate(new Variable.StringVariable(name, v));
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
        this.isEventPending = value;
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
            String screenToRejoin = currentScreen; // remember where we are

            // Disconnect cleanly — PhoenixSocket will reconnect automatically
            // because we call connect() again below
            if (currentChannel != null) {
                currentChannel.leave();
                currentChannel = null;
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
    //  UI HELPERS
    // ═══════════════════════════════════════

    private void showSpinner() {
        Log.d(TAG, "showSpinner");
        pbSpinner.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        flContainer.setVisibility(View.INVISIBLE); // keep layout space, hide content
    }

    private void hideSpinner() {
        Log.d(TAG, "hideSpinner");
        pbSpinner.setVisibility(View.GONE);
        flContainer.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        Log.e(TAG, "showError: " + message);
        pbSpinner.setVisibility(View.GONE);
        tvError.setVisibility(View.VISIBLE);
        tvError.setText(message);
    }

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