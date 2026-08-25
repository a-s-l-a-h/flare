// Location: wait_management_system/assets/web/flare-client.js

import { Socket } from "phoenix";
import {
  render,
  createVariable,
  createGlobalVariablesController
} from "@divkitframework/divkit/dist/client";
import "@divkitframework/divkit/dist/client.css";
import { AmbientIsland } from "./island/ambient-island.js";
import connectionLostScreenJson from "./connection-lost-screen.json";
import screenErrorScreenJson from "./screen-error-screen.json";
// ── LOCAL ENGINE ADDITIONS ───────────────────────────────────────────────
// New, self-contained plugin/task/export subsystem — see
// flare/LOCAL_ENGINE_PROTOCOL.md for the full cross-platform contract.
import { FlareClientPluginEngine } from "./plugin/flare-client-plugin-engine";
import { createClientPluginContext } from "./plugin/flare-client-plugin-context";
import { dispatchClientTask } from "./task/flare-client-task-engine";
import { executeDirective } from "./flare-directive-handler";
import { FlareExportedVariables } from "./export/flare-exported-variables";
import { createPaneContext } from "./nativepane/flare-native-pane-context.js";
import { initNativePaneRegistry, getCustomComponentsMap } from "./nativepane/flare-native-pane-registry.js";

// Solid background colors shown the instant the old screen is cleared,
// synced with local_dark_mode — mirrors Android's COLOR_BG_LIGHT/DARK.
const COLOR_BG_LIGHT = "#ffffff";
const COLOR_BG_DARK  = "#121212";

// Minimal spinner shown between screen transitions.
const SPINNER_HTML = `
  <div style="
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    min-height: 200px;
  ">
    <div style="
      width: 36px;
      height: 36px;
      border: 3px solid rgba(0,0,0,0.1);
      border-top-color: #3498db;
      border-radius: 50%;
      animation: flare-spin 0.7s linear infinite;
    "></div>
  </div>
  <style>
    @keyframes flare-spin {
      to { transform: rotate(360deg); }
    }
  </style>
`;

// ---------------------------------------------------------------------------
// RESERVED VARIABLE: local_flare_pending_<action>
//
// This variable is owned entirely by the Flare SDK. Developers must not use
// this name for their own variables.
//
// Purpose: When any flare://action fires, this is set to true immediately.
//          It is set back to false when the server ACKs the event OR when
//          the server patch arrives (whichever comes first).
//
// How developers use it in layout JSON:
//   Show a loading state on ANY button:
//     "alpha": "@{local_flare_pending_submit ? 0.5 : 1.0}"
//     "text": "@{local_flare_pending_submit ? 'Please wait...' : 'Submit'}"
//
//   Disable taps while pending (handled automatically by the SDK —
//   the SDK ignores all actions while local_flare_pending is true).
//
// MULTI-MOUNT UPDATE:
//   Pending actions are now tracked per-mount (content vs. each persistent region)
//   instead of globally. This ensures that a tap in flight on the bottom bar 
//   doesn't artificially block a tap on the main content screen, and vice versa.
// ---------------------------------------------------------------------------

class TransitionOverlay {
  constructor() {
    this.el               = document.getElementById("flare-transition-overlay");
    this.connectionLostEl = document.getElementById("flare-transition-connection-lost");
    this.lottieEl         = document.getElementById("flare-transition-lottie");
    this.errorEl          = document.getElementById("flare-transition-error");
    this.errorMsgEl       = document.getElementById("flare-transition-error-msg");
    this.retryBtn         = document.getElementById("flare-transition-retry");
    this.resetBtn         = document.getElementById("flare-transition-reset");

    if (this.resetBtn) {
      this.resetBtn.onclick = () => {
        localStorage.removeItem("flare_token");
        localStorage.removeItem("flare_oauth_screen");
        localStorage.removeItem("local_dark_mode");
        window.location.reload();
      };
    }

    this.TIMEOUT_MS  = 8000;
    this.visible       = false;
    this.showStartMs   = 0;
    this.timeoutHandle = null;
    this.onErrorShown  = null;
    this.onErrorHidden = null;
  }

  setOnErrorVisibilityListener(onShown, onHidden) {
    this.onErrorShown = onShown;
    this.onErrorHidden = onHidden;
  }

  show(onRetry) {
    this.showStartMs = Date.now();
    this.visible = true;

    this.errorEl.style.display  = "none";
    if (this.connectionLostEl && this.connectionLostEl.style.display !== "block") {
      this.connectionLostEl.style.display = "none";
    }
    this.lottieEl.style.display = "block";
    this.el.style.display = "flex";

    AmbientIsland.setLoading(true);
    this._armTimeout(onRetry);
  }

  hide() {
    if (!this.visible) return;
    this._clearTimeout();
    this._doHide();
  }

  showError(message, onRetry) {
    this._clearTimeout();
    this.visible = true;
    this.el.style.display = "flex";
    this.lottieEl.style.display = "none";

    AmbientIsland.setLoading(false);

    // If connection lost layout is active, don't flash native error card on top
    if (this.connectionLostEl && this.connectionLostEl.style.display === "block" && this.connectionLostEl.childElementCount > 0) {
      this.errorEl.style.display = "none";
      return;
    }

    if (this.connectionLostEl) this.connectionLostEl.style.display = "none";

    if (this.onErrorShown) this.onErrorShown();

    this.errorMsgEl.textContent = message;
    this.errorEl.style.display  = "block";

    this.retryBtn.onclick = () => {
      this.errorEl.style.display  = "none";
      this.lottieEl.style.display = "block";
      AmbientIsland.setLoading(true);
      this._armTimeout(onRetry);
      onRetry();
    };
  }

  showConnectionLostFallback() {
    this._clearTimeout();
    this.visible = true;
    this.el.style.display = "flex";
    this.lottieEl.style.display = "none";
    this.errorEl.style.display = "none";

    AmbientIsland.setLoading(false);
    if (this.onErrorShown) this.onErrorShown();

    if (this.connectionLostEl) this.connectionLostEl.style.display = "block";
  }

  resetFallback() {
    if (this.connectionLostEl) {
      this.connectionLostEl.style.display = "none";
      this.connectionLostEl.innerHTML = "";
    }
    if (this.errorEl) this.errorEl.style.display = "none";
  }

  startIslandLoading() {
    AmbientIsland.setLoading(true);
  }

  stopIslandLoading() {
    AmbientIsland.setLoading(false);
  }

  isVisible() { return this.visible; }

  forceHide() {
    this._clearTimeout();
    this.visible = false;
    AmbientIsland.setLoading(false);
    this.resetFallback();
    this.el.style.display = "none";
  }

  _armTimeout(onRetry) {
    this._clearTimeout();
    this.timeoutHandle = setTimeout(() => {
      this.showError("Connection problem. Please check your network.", onRetry);
    }, this.TIMEOUT_MS);
  }

  _clearTimeout() {
    if (this.timeoutHandle) { clearTimeout(this.timeoutHandle); this.timeoutHandle = null; }
  }

  _doHide() {
    this.visible = false;
    AmbientIsland.setLoading(false);
    if (this.onErrorHidden) this.onErrorHidden();
    this.resetFallback();
    this.el.style.display = "none";
  }
}

export class FlareClient {
  constructor(config) {
    this.wsUrl        = config.wsUrl;
    this.token        = config.token;
    this.entryScreen  = config.entryScreen || "home";

    // Defines screens that stay mounted perpetually (like nav bars).
    this.persistentScreens = config.persistentScreens || [];

    // Subset of persistentScreens region names that participate in per-screen
    // scaffold visibility (bottom_bar, top_bar, drawer, end_drawer). Regions
    // NOT in this list (e.g. "overlay") are persistent but exempt from
    // scaffold toggling — they self-govern their own visibility instead.
    this.scaffoldRegions = config.scaffoldRegions || [];

    this.socket = null;
    this.hasEverLoadedContent = false;
    this.reconnectFailureStreak = 0;

    // "content" is the primary mount that navigateTo() swaps out.
    this.content = this._makeMount(config.rootEl, null);
    this.regions = {};

    // Mirrors Android's TransitionOverlayView.
    this.transitionOverlay = new TransitionOverlay();
    this.transitionOverlay.setOnErrorVisibilityListener(
      () => this._setScaffoldVisible("bottom_bar", false),
      () => this._setScaffoldVisible("bottom_bar", true)
    );

    // Set right before we intentionally call socket.disconnect() (logout),
    // so the onClose handler below knows not to show a "reconnecting" error
    // and try to retry a screen on a socket that no longer exists.
    this._intentionalDisconnect = false;

    // 1. MUST BE CREATED FIRST
    this.globalController = createGlobalVariablesController();

    // ── LOCAL ENGINE ADDITIONS ────────────────────────────────────────
    // Names of variables declared with "exported": true — mirrored into
    // FlareExportedVariables on every _setVariable() call below.
    this._exportedVariableNames = new Set();

        this._clientPluginEngine = new FlareClientPluginEngine({
      context: createClientPluginContext({
        getToken: () => this.token,
        getBaseUrl: () => this._deriveBaseHttpUrl(),
        getScreenName: () => this.content.screenName,
        onAuthFailure: () => this._handleAuthFailure()
      }),
      isMountStillLive: (screenName) => this._isMountStillLive(screenName),
      fireLocalAction: (actionName, screenName) => this._fireFollowupAction(actionName, screenName),
      setVariable: (name, type, value) => this._setVariable(name, type, value)
    });

    // ── NATIVE PANE SETUP ────────────────────────────────────────────
    initNativePaneRegistry(createPaneContext({
      getScreenName: () => this.content.screenName,
      getAuthToken: () => this.token,
      getBaseHttpUrl: () => this._deriveBaseHttpUrl(),
      notifyAuthFailure: () => this._handleAuthFailure(),
      setVariable: (name, value) => this._setVariable(name, null, value),
      fireAction: (actionName, payload) => this._handleAction(
        { url: `flare://action?flare_action=${encodeURIComponent(actionName)}`, payload },
        this.content
      )
    }));

    // 2. NOW IT IS SAFE TO SET THE VARIABLE
    const isDark = localStorage.getItem("local_dark_mode") === "true";
    this._setVariable("local_dark_mode", "boolean", isDark);
    this._syncBackgroundColor(isDark);

    this.debug = true;

    // Handle browser back/forward button natively
    window.addEventListener("popstate", () => {
      this.navigateTo(this._screenFromUrl(), this._paramsFromUrl());
    });
  }

  log(msg, data = "") {
    if (this.debug) console.log(`[🔥 Flare] ${msg}`, data);
  }

  // ---------------------------------------------------------------------------
  // _makeMount — Helper to standardize mount points (content vs regions)
  // ---------------------------------------------------------------------------
  _makeMount(el, screenName) {
    return { 
      el, 
      screenName, 
      channel: null, 
      pendingActions: new Set() 
    };
  }

  // Colors the content mount's own background — this is what shows through
  // the transparent transition overlay the instant the old screen is
  // cleared, before the new screen has loaded.
  _syncBackgroundColor(isDark) {
    this.content.el.style.backgroundColor = isDark ? COLOR_BG_DARK : COLOR_BG_LIGHT;
  }

  // ---------------------------------------------------------------------------
  // connect() — open the WebSocket connection once, then join persistent
  // regions. Content is joined separately via navigateTo().
  // ---------------------------------------------------------------------------
  connect() {
    this.log("Connecting...", this.wsUrl);

    // Token is always required — obtained from /auth/guest or /auth/login
    // before this connect() is called. index.html guarantees this.
    if (!this.token) {
      console.error("[Flare] connect() called with no token. User must authenticate first.");
      this._handleAuthFailure();
      return;
    }

    const params = { token: this.token };

    // Custom decoder: handles both text frames (patch, ACKs, normal messages)
    // and binary frames (init, layout_update when server has optimize: true).
    //
    // FALLBACK SAFETY: if binary parsing throws for any reason, the error is 
    // caught in the channel handler to prevent silent crashes.
    this.socket = new Socket(this.wsUrl, {
      params,
      decode: (rawData, callback) => this._decodeFrame(rawData, callback)
    });

    this.socket.connect();
    this.socket.onOpen(() => {
      this.log("✅ WebSocket connected");
      this.reconnectFailureStreak = 0;
      this._joinPersistentScreens();

      if (this.transitionOverlay.isVisible() && this.hasEverLoadedContent) {
        this.log("Socket reconnected — retrying silently, overlay stays as-is");
        this._retryCurrentScreen();
      } else if (this.transitionOverlay.isVisible() && this._pendingScreenName) {
        // Pre-first-load: Keep the spinner active, no error flash
        this.transitionOverlay.show(() => this._retryCurrentScreen());
        this._retryCurrentScreen();
      }
    });

    this.socket.onClose(() => {
      this.log("❌ WebSocket closed");
      if (this._intentionalDisconnect) {
        this._intentionalDisconnect = false;
        return;
      }

      this.reconnectFailureStreak++;

      // 🔥 FIX: During initial boot/first load, NEVER flash the error screen.
      // Keep the loading spinner up unless it fails repeatedly (streak >= 3).
      if (!this.hasEverLoadedContent) {
        if (this.reconnectFailureStreak >= 3) {
          this._showConnectionLostFallback("We're having trouble connecting. Please check your network or try again.");
        }
        return;
      }

      // If content has loaded before, show the fallback screen
      this._showConnectionLostFallback("Connection lost. Reconnecting…");
    });
  }

  // ---------------------------------------------------------------------------
  // _joinPersistentScreens — join every configured region once. These
  // channels are never left by navigateTo(); only _handleAuthFailure() tears
  // them down (on logout / session expiry).
  // ---------------------------------------------------------------------------
  _joinPersistentScreens() {
    this.persistentScreens.forEach(({ screen, region }) => {
      const el = document.getElementById(`flare-${region}`);
      if (!el) {
        console.warn(`[Flare] No container for region "${region}" — expected id="flare-${region}" in index.html`);
        return;
      }
      
      const mount = this._makeMount(el, screen);
      this.regions[region] = mount;
      
      // No spinner for persistent regions — avoids a jarring flash in UI elements like 
      // a 64px bottom bar when the page first loads.
      this._joinChannel(mount, screen, {});
    });
  }

  // Reads current browser URL, dynamically returns which screen to show
  _screenFromUrl() {
    const path = window.location.pathname;
    if (!path || path === "/") {
      return this.entryScreen;
    }
    return path.substring(1);
  }

  // Reads query parameters from the URL (?code=ABC)
  _paramsFromUrl() {
    const params = {};
    const searchParams = new URLSearchParams(window.location.search);
    for (const [key, value] of searchParams) {
      params[key] = value;
    }
    return params;
  }

  // Updates browser URL bar when screen changes
  _pushUrl(screenName, params = {}) {
    const url = new URL(window.location.origin);
    url.pathname = screenName === this.entryScreen ? "/" : `/${screenName}`;

    // Add params to the URL query string
    Object.keys(params).forEach(key => {
      if (key !== "ignore_pin") { // Hide internal navigation flags
        url.searchParams.set(key, params[key]);
      }
    });

    const urlString = url.pathname + url.search;
    if (window.location.pathname + window.location.search !== urlString) {
      window.history.pushState({}, "", urlString);
    }
  }

  // ---------------------------------------------------------------------------
  // navigateTo(screenName) — ONLY affects the primary content mount. Persistent
  // regions (bottom bar, header, etc.) are never touched here.
  // ---------------------------------------------------------------------------
  navigateTo(screenName, params = {}) {
    this.log(`Navigating to: ${screenName}`);

    if (this.content.channel) {
      this.content.channel.leave();
      this.content.channel = null;
    }

    this._clearAllPending(this.content);

    // Remember what we're loading so retry / reconnect can re-attempt it.
    this._pendingScreenName = screenName;
    this._pendingParams     = params;

    // NOTE: the old screen is intentionally left in the DOM here — it
    // stays visible but frozen (fully touch-blocked by transitionOverlay
    // below) until the new screen's init envelope arrives and
    // _handleInit() swaps it in instantly. Zero artificial delay.

    this.transitionOverlay.resetFallback();
    this.transitionOverlay.show(() => this._retryCurrentScreen());

    this._joinChannel(this.content, screenName, params, () => {
      this._pushUrl(screenName, params);
    });
  }

  _retryCurrentScreen() {
    // Guard against retries firing after logout/auth-failure has torn
    // down the socket and cleared the pending screen.
    if (!this._pendingScreenName || !this.socket) return;
    if (this.content.channel) {
      this.content.channel.leave();
      this.content.channel = null;
    }

    // Old screen intentionally left visible — see navigateTo() comment.

    this.transitionOverlay.show(() => this._retryCurrentScreen());
    this._joinChannel(this.content, this._pendingScreenName, this._pendingParams || {}, () => {
      this._pushUrl(this._pendingScreenName, this._pendingParams || {});
    });
  }

  // ---------------------------------------------------------------------------
  // _joinChannel — generic join used by both navigateTo() (content) and
  // _joinPersistentScreens() (regions). Everything below this point operates
  // on the `mount` object instead of global properties.
  // ---------------------------------------------------------------------------
  _joinChannel(mount, screenName, params, onJoined) {
    // Defensive guard — should be unreachable now that _retryCurrentScreen
    // checks this too, but this keeps _joinChannel safe for any future caller.
    if (!this.socket) {
      console.error(`[Flare] _joinChannel("${screenName}") called with no active socket — aborting.`);
      return;
    }

    mount.screenName = screenName;
    
    const channel = this.socket.channel(`flare:${screenName}`, params);
    mount.channel = channel;

    channel.on("init", async (envelope) => {
      try {
        await this._handleInit(envelope, mount);
      } catch (e) {
        console.error("[Flare] ❌ Error handling init", e);
        // Without this, a JS error here left the transition overlay
        // spinning forever on top of the error message underneath.
        if (mount === this.content) this.transitionOverlay.hide();
        this._showError(mount, "Something went wrong loading this screen.");
      }
    });

    channel.on("patch", (envelope) => {
      try {
        this._handlePatch(envelope, mount);
      } catch (e) {
        console.error("[Flare] ❌ Error handling patch", e);
        if (mount === this.content) this.transitionOverlay.hide();
      }
    });

    channel.on("layout_update", async (envelope) => {
      try {
        await this._handleLayoutUpdate(envelope, mount);
      } catch (e) {
        console.error("[Flare] ❌ Error handling layout_update", e);
        if (mount === this.content) this.transitionOverlay.hide();
      }
    });

    channel
      .join()
      .receive("ok", () => {
        this.log(`✅ Joined flare:${screenName}`);
        if (onJoined) onJoined();
      })
      .receive("error", (resp) => {
        console.error(`❌ Failed to join flare:${screenName}`, resp);
        if (resp && (resp.reason === "authentication_required" ||
                     resp.reason === "session_expired" ||
                     resp.reason === "invalid_token")) {
          this._handleAuthFailure();
        } else if (mount === this.content) {
          this.transitionOverlay.hide();
          this._showScreenErrorFallback(mount, "Something went wrong loading this screen. Please try another tab or check back later.");
        } else {
          console.error(`[Flare] Persistent region failed to join: ${screenName}`);
          this._hideBrokenRegion(mount);
        }
      })
      .receive("timeout", () => {
        console.error(`⏱ Timeout joining flare:${screenName}`);
        if (mount === this.content) {
          this.transitionOverlay.hide();
          this._showScreenErrorFallback(mount, "Screen load timed out. Please try another tab or check back later.");
        } else {
          this._hideBrokenRegion(mount);
        }
      });
  }

  // ---------------------------------------------------------------------------
  // _handleInit — full screen render
  //
  // envelope arrives already decoded by _decodeFrame:
  //   Plain path (optimize: false OR use_cache false screens):
  //     { screen, layout: {...}, variables: [...], state: {...}, commands: [...] }
  //   Optimized binary path (optimize: true, use_cache true screens):
  //     Same shape — _decodeFrame already decompressed layout and variables.
  // ---------------------------------------------------------------------------
  async _handleInit(envelope, mount) {
    this.log(`📥 INIT received [${mount.screenName}]`, envelope);

    // Register per-action pending variables by scanning the layout JSON tree.
    this._registerActionPendingVars(envelope.layout);

    // Register variable type definitions from the state JSON file.
    if (envelope.variables) {
      envelope.variables.forEach(v => {
        const existing = this.globalController.getVariable(v.name);

        // ── LOCAL ENGINE ADDITION ───────────────────────────────────
        // Track exported variable names so _setVariable() can mirror
        // future updates into FlareExportedVariables — protocol §13.
        if (v.exported) {
          this._exportedVariableNames.add(v.name);
        }

        // -------------------------------------------------------------
        // FIX: Prevent JSON file defaults from wiping out saved local 
        // state (like our dark mode) when a screen loads!
        // -------------------------------------------------------------
        if (existing && v.name.startsWith("local_")) {
          return;
        }
        
        this._setVariable(v.name, v.type, v.value);
      });
    }

    // Apply current server-side state values on top of the type definitions.
    if (envelope.state) {
      Object.entries(envelope.state).forEach(([key, value]) => {
        this._setVariable(key, null, value);
      });
    }

    // Execute any directives the server sent with the init envelope
    if (envelope.directives) {
      envelope.directives.forEach(d => executeDirective(d, this));
    }
    // Apply this screen's declared scaffold visibility (only meaningful for
    // the primary content mount — persistent regions never carry a "scaffold"
    // field on their own init).
    if (mount === this.content && envelope.scaffold) {
      this._applyScaffold(envelope.scaffold);
    }

    // Handle both { card: {...} } and bare card JSON (DivKit accepts both)
    let divkitJson = envelope.layout;
    if (!divkitJson.card) {
      divkitJson = { card: envelope.layout };
    }

    if (mount === this.content) {
      // ── Content: instant swap over the old screen — no slide, no delay ──
      const oldChildren = Array.from(mount.el.children);

      const newWrapper = document.createElement("div");
      newWrapper.style.cssText = "position:absolute;top:0;left:0;width:100%;height:100%;";
      mount.el.appendChild(newWrapper);

      render({
        id:                        `flare-${mount.screenName}`,
        target:                    newWrapper,
        json:                      divkitJson,
        globalVariablesController: this.globalController,
        customComponents:          getCustomComponentsMap(),
        onCustomAction:            (action) => this._handleAction(action, mount)
      });      

      const divkitRoot = newWrapper.firstElementChild;
      if (divkitRoot) { divkitRoot.style.width = "100%"; divkitRoot.style.height = "100%"; }

      this.hasEverLoadedContent = true;
      this.reconnectFailureStreak = 0;
      oldChildren.forEach(child => child.remove());
      this.transitionOverlay.hide();
    } else {
      // Persistent regions: unchanged, immediate render, no overlay/slide.
      mount.el.innerHTML = "";
      render({
        id:                        `flare-${mount.screenName}`,
        target:                    mount.el,
        json:                      divkitJson,
        globalVariablesController: this.globalController,
        customComponents:          getCustomComponentsMap(),
        onCustomAction:            (action) => this._handleAction(action, mount)
      });
      const divkitRoot = mount.el.firstElementChild;
      if (divkitRoot) { divkitRoot.style.width = "100%"; divkitRoot.style.height = "100%"; }
    }
  }

  _registerActionPendingVars(layoutJson) {
    const actions = this._extractFlareActions(layoutJson);
    actions.forEach(actionName => {
      const varName = `local_flare_pending_${actionName}`;
      this._setVariable(varName, "boolean", false);
    });
  }

  _extractFlareActions(obj, found = new Set()) {
    if (!obj || typeof obj !== "object") return found;
    if (Array.isArray(obj)) {
      obj.forEach(item => this._extractFlareActions(item, found));
      return found;
    }

    // Legacy form: flare_action as an explicit payload key.
    if (obj.flare_action && typeof obj.flare_action === "string") {
      found.add(obj.flare_action);
    }

    // Current form: flare_action encoded in the url query string, e.g.
    // "flare://action?flare_action=go_back&code=@{...}". Action *names*
    // must stay static literals (only params are expression-driven), so a
    // plain regex against the raw JSON string is safe here — this runs
    // before DivKit ever evaluates anything.
    if (obj.url && typeof obj.url === "string") {
      const match = obj.url.match(/[?&]flare_action=([^&]+)/);
      if (match) {
        found.add(decodeURIComponent(match[1]));
      }
    }

    Object.values(obj).forEach(v => this._extractFlareActions(v, found));
    return found;
  }

  // ---------------------------------------------------------------------------
  // _handlePatch — incremental state update
  // ---------------------------------------------------------------------------
  _handlePatch(envelope, mount) {
    this.log(`📥 PATCH received [${mount.screenName}]`, envelope);

    if (envelope.state) {
      Object.entries(envelope.state).forEach(([key, value]) => {
        if (value !== null) {
          this._setVariable(key, null, value);
        }
      });
    }

    // ---------------------------------------------------------------------------
    // Clear pending when patch arrives.
    // The patch IS the server's response to the event. When it arrives,
    // the operation is complete. Release the pending lock(s) for this mount.
    // ---------------------------------------------------------------------------
    this._clearAllPending(mount);

    if (envelope.directives) {
      envelope.directives.forEach(d => executeDirective(d, this));
    }
  }

  // ---------------------------------------------------------------------------
  // _handleLayoutUpdate — hot deployment layout refresh
  //
  // Preserves current variable values across the layout swap so the user
  // doesn't lose their dark mode toggle, pagination state, etc.
  // ---------------------------------------------------------------------------
  async _handleLayoutUpdate(envelope, mount) {
    this.log(`📥 LAYOUT_UPDATE received [${mount.screenName}]`, envelope);

    // Save current variable values before destroying the renderer.
    const savedValues = {};
    if (envelope.variables) {
      envelope.variables.forEach(v => {
        const existing = this.globalController.getVariable(v.name);
        if (existing) savedValues[v.name] = existing.getValue();
      });
    }

    // Clear any in-flight pending state for this specific mount
    this._clearAllPending(mount);

    // Re-scan the new layout for action names and register pending vars
    this._registerActionPendingVars(envelope.layout);

    // Re-register variables, restoring saved values where they exist.
    if (envelope.variables) {
      envelope.variables.forEach(v => {
        const value = savedValues[v.name] !== undefined ? savedValues[v.name] : v.value;
        this._setVariable(v.name, v.type, value);
      });
    }

    let divkitJson = envelope.layout;
    if (!divkitJson.card) {
      divkitJson = { card: envelope.layout };
    }

    mount.el.innerHTML = "";
    render({
      id:                        `flare-${mount.screenName}-updated`,
      target:                    mount.el,
      json:                      divkitJson,
      globalVariablesController: this.globalController,
      customComponents:          getCustomComponentsMap(),
      onCustomAction:            (action) => this._handleAction(action, mount)
    });

    const divkitRoot = mount.el.firstElementChild;
    if (divkitRoot) {
      divkitRoot.style.width  = "100%";
      divkitRoot.style.height = "100%";
    }
  }

  // ---------------------------------------------------------------------------
  // _handleAction — DivKit fires this for flare://action URLs
  //
  // Every action that reaches this handler:
  //   1. Checks if another action is already in flight for this specific mount
  //   2. Sets local_flare_pending = true immediately (before network call)
  //   3. Sends the event to the server THROUGH THE MOUNT'S CHANNEL
  //   4. On ACK (ok/error/timeout): clears local_flare_pending = false
  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
  // _handleAction — DivKit fires this for flare://action URLs
  //
  // Every action that reaches this handler:
  //   1. Checks if another action is already in flight for this specific mount
  //   2. Sets local_flare_pending = true immediately (before network call)
  //   3. Sends the event to the server THROUGH THE MOUNT'S CHANNEL
  //   4. On ACK (ok/error/timeout): clears local_flare_pending = false
  // ---------------------------------------------------------------------------
  _handleAction(action, mount) {
    try {
      const actionUrl = action.url || "";
      if (actionUrl === "flare://action" || actionUrl.startsWith("flare://action")) {

        // NEW: pull params out of the url's query string. These are already
        // expression-resolved by DivKit's own engine before onCustomAction
        // fires — including encodeUri()/function calls and item_builder loop
        // scope — so we do zero manual resolution on these values.
        const urlParams = this._parseFlareActionUrl(actionUrl);

        // LEGACY: payload is schema-typed as a static object, so DivKit never
        // resolves @{...} inside it — we still resolve it ourselves here for
        // any screen JSON that hasn't been migrated to the url-based form yet.
        const rawPayload      = action.payload || {};
        const resolvedPayload = this._resolvePayload(rawPayload);

        // url wins on key collisions — it's the field DivKit actually
        // contracts to resolve, so treat it as the source of truth.
        const payload   = { ...resolvedPayload, ...urlParams };
        const eventType = payload.flare_action;

        if (!eventType) {
          console.warn("[Flare] Action missing flare_action key", payload);
          return;
        }

        // -----------------------------------------------------------------
        // Intercept local actions so they stay instantly on the device (no server trip)
        // -----------------------------------------------------------------
        if (eventType === "toggle_dark_mode") {
          const current = this.globalController.getVariable("local_dark_mode")?.getValue() || false;
          const next = !current;
          this._setVariable("local_dark_mode", "boolean", next);
          localStorage.setItem("local_dark_mode", next ? "true" : "false");
          this._syncBackgroundColor(next);
          return; 
        }

        // Chrome open/close state (drawer, end_drawer) is purely local UI state —
        // same treatment as toggle_dark_mode. No server round trip, no pending
        // guard, no channel push. Intentionally not persisted to localStorage
        // (unlike dark mode) — drawers should start closed on reload.
        if (eventType === "open_drawer")       { this._setVariable("local_drawer_open", "boolean", true);  return; }
        if (eventType === "close_drawer")      { this._setVariable("local_drawer_open", "boolean", false); return; }
        if (eventType === "open_end_drawer")   { this._setVariable("local_end_drawer_open", "boolean", true);  return; }
        if (eventType === "close_end_drawer")  { this._setVariable("local_end_drawer_open", "boolean", false); return; }

        // Check if this is a local native action (camera, QR, GPS etc)
        const nativeAction = payload.local_flare_native_action;
        if (nativeAction) {
          console.warn(`[Flare] local_flare_native_action "${nativeAction}" is not supported on web`);
          return;
        }

        // GUARD: If an event is already in flight on this mount, ignore tap.
        const actionPendingVar = `local_flare_pending_${eventType}`;
        const actionPending = this.globalController.getVariable(actionPendingVar);
        
        if (actionPending && actionPending.getValue() === true) {
          this.log(`⏸ Tap ignored — already in flight: ${eventType}`);
          return;
        }

        this.log(`📤 Event [${mount.screenName}]: ${eventType}`, payload);
              
        // Lock only this action on this mount before push
        this._setVariable(actionPendingVar, "boolean", true);
        mount.pendingActions.add(eventType);

        mount.channel
          .push("event", {
            screen:  mount.screenName,
            type:    eventType,
            payload: payload
          })
          .receive("ok", () => {
            this.log(`✅ ACK received: ${eventType}`);
            this._setVariable(actionPendingVar, "boolean", false);
            mount.pendingActions.delete(eventType);
          })
          .receive("error", (resp) => {
            console.error(`[Flare] ❌ Event rejected: ${eventType}`, resp);
            this._setVariable(actionPendingVar, "boolean", false);
            mount.pendingActions.delete(eventType);
          })
          .receive("timeout", () => {
            console.error(`[Flare] ⏱ Event timeout: ${eventType}`);
            this._setVariable(actionPendingVar, "boolean", false);
            mount.pendingActions.delete(eventType);
          });
      } else if (actionUrl.startsWith("flare://clienttask")) {
        // ── LOCAL ENGINE ADDITION ──────────────────────────────────────
        // Synchronous, fire-and-forget, on-device only. Never touches the
        // server. See LOCAL_ENGINE_PROTOCOL.md §2-3.
        const params = this._parseFlareActionUrl(actionUrl);
        dispatchClientTask(params.task, params);
      } else if (actionUrl.startsWith("flare://clientplugin")) {
        // ── LOCAL ENGINE ADDITION ──────────────────────────────────────
        // Async, opens a native capability, always resolves to a
        // structured result envelope. See LOCAL_ENGINE_PROTOCOL.md §2-3.
        this._handleClientPluginAction(actionUrl, action, mount);
      }
    } catch (e) {
      // Unexpected error in action handling. Clear pending so UI is not stuck.
      this._clearAllPending(mount);
      console.error("[Flare] Action handler error", e);
    }
  }

  // ── LOCAL ENGINE ADDITIONS ────────────────────────────────────────────

  /**
   * Parses a flare://clientplugin URL + payload into the normalized
   * invocation shape (protocol §3) and hands it to FlareClientPluginEngine.
   */
  _handleClientPluginAction(actionUrl, action, mount) {
    try {
      const urlParams = this._parseFlareActionUrl(actionUrl);
      const rawPayload = action.payload || {};
      const resolvedPayload = this._resolvePayload(rawPayload);

      // Merge payload.params with non-reserved URL query params — this is
      // "Channel A" (layout-supplied input) per protocol §5.1.
      const reservedKeys = new Set(["plugin", "result_var", "on_success", "on_error", "on_cancel", "timeout_ms"]);
      const params = { ...(rawPayload.params || {}) };
      Object.entries(urlParams).forEach(([key, value]) => {
        if (!reservedKeys.has(key)) params[key] = value;
      });

      const pluginId = urlParams.plugin;
      const resultVar = urlParams.result_var;
      const expectFields = Array.isArray(rawPayload.expect_fields) ? rawPayload.expect_fields : null;
      const timeoutMs = urlParams.timeout_ms ? parseInt(urlParams.timeout_ms, 10) : 0;

      if (!pluginId || !resultVar) {
        console.warn("[Flare] flare://clientplugin missing plugin or result_var — ignoring", urlParams);
        return;
      }

      this._clientPluginEngine.dispatch({
        pluginId,
        resultVar,
        params,
        expectFields,
        onSuccess: urlParams.on_success || null,
        onError: urlParams.on_error || null,
        onCancel: urlParams.on_cancel || null,
        timeoutMs,
        originScreenName: mount.screenName
      });
    } catch (e) {
      console.error("[Flare] Client plugin action error", e);
    }
  }

  /**
   * Fires on_success/on_error/on_cancel through the EXACT SAME
   * _handleAction() path a real DivKit tap uses — never a new/parallel
   * dispatch mechanism. This is what LOCAL_ENGINE_PROTOCOL.md §7 means by
   * "an ordinary flare_action".
   */
  _fireFollowupAction(actionName, screenName) {
    let targetMount = this.content;
    if (screenName && screenName !== this.content.screenName) {
      const region = Object.values(this.regions).find(m => m.screenName === screenName);
      if (region) targetMount = region;
    }
    this._handleAction(
      { url: `flare://action?flare_action=${encodeURIComponent(actionName)}` },
      targetMount
    );
  }

  /** Mount-liveness check per protocol §11 — mirrors Android's MountLivenessCheck. */
  _isMountStillLive(screenName) {
    if (!screenName) return true; // no origin recorded — treat as still-live rather than dropping.
    if (this.content.screenName === screenName) return true;
    return Object.values(this.regions).some(m => m.screenName === screenName);
  }

  /** Derives the current API base HTTP URL the same way the client derives its own WebSocket URL. */
  _deriveBaseHttpUrl() {
    try {
      const url = new URL(this.wsUrl, window.location.origin);
      const proto = url.protocol === "wss:" ? "https:" : "http:";
      return `${proto}//${url.host}`;
    } catch (e) {
      console.error("[Flare] Failed to derive base HTTP URL — falling back to window.location.origin", e);
      return window.location.origin;
    }
  }

  // ---------------------------------------------------------------------------
  // _executeCommand — process server-sent instructions in order
  //
  // IMPORTANT: "navigate" ALWAYS targets the primary content mount, regardless 
  // of which mount's channel the command arrived on. This lets a bottom-bar 
  // tap route navigation to the main content area.
  // ---------------------------------------------------------------------------
  // _executeCommand(cmd) {
  //   this.log(`⚡ Command: ${cmd.type}`, cmd.payload);

  //   switch (cmd.type) {
  //     case "navigate":
  //       this.navigateTo(cmd.payload.screen, cmd.payload.params || {});
  //       break;

  //     case "show_alert":
  //       alert(`${cmd.payload.title}\n\n${cmd.payload.message}`);
  //       break;
      
  //     case "store_token": {
  //       // Server can still refresh/rotate a token mid-session.
  //       localStorage.setItem("flare_token", cmd.payload.token);
  //       this.token = cmd.payload.token;
  //       this.log("Token refreshed by server");
  //       break;
  //     }
      
  //     case "clear_storage":
  //       // Logout — clear token then redirect to login page.
  //       localStorage.removeItem("flare_token");
  //       this.token = null;
  //       this._handleAuthFailure();
  //       break;

  //     case "haptic":
  //       if (navigator.vibrate) navigator.vibrate(50);
  //       break;

  //     case "hide_scaffold":
  //       this._setScaffoldVisible(cmd.payload.region, false);
  //       break;

  //           case "show_scaffold":
  //       this._setScaffoldVisible(cmd.payload.region, true);
  //       break;

  //     case "run_task": {
  //       const taskId = cmd.payload.task;
  //       const taskParams = cmd.payload.params || {};
  //       if (taskId) {
  //         dispatchClientTask(taskId, taskParams);
  //       } else {
  //         console.warn("[Flare] run_task command missing 'task' identifier.");
  //       }
  //       break;
  //     }

  //     default:
  //       console.warn(`[Flare] Unknown command: ${cmd.type}`);
  //   }
  // }

  // ---------------------------------------------------------------------------
  // _clearAllPending — clear every currently in-flight per-action pending var
  // scoped to a specific mount point.
  // ---------------------------------------------------------------------------
  _clearAllPending(mount) {
    mount.pendingActions.forEach(actionName => {
      const varName = `local_flare_pending_${actionName}`;
      this._setVariable(varName, "boolean", false);
      this.log(`🔓 Cleared pending [${mount.screenName}]: ${actionName}`);
    });
    mount.pendingActions.clear();
  }

  // ---------------------------------------------------------------------------
  // _applyScaffold — show/hide scaffold region containers based on the current
  // content screen's declared "scaffold" list from the router. Regions listed
  // in scaffoldRegions but NOT in scaffoldList are hidden; everything else
  // (e.g. "overlay") is left untouched.
  // ---------------------------------------------------------------------------
  _applyScaffold(scaffoldList) {
    const visible = new Set(scaffoldList);
    this.scaffoldRegions.forEach(region => {
      const mount = this.regions[region];
      if (mount) {
        mount.el.style.display = visible.has(region) ? "" : "none";
      }
    });
  }

  // One-off runtime override for a single region, used by the
  // show_scaffold / hide_scaffold commands.
  _setScaffoldVisible(region, isVisible) {
    const mount = this.regions[region];
    if (mount) {
      mount.el.style.display = isVisible ? "" : "none";
    } else {
      console.warn(`[Flare] show_scaffold/hide_scaffold: unknown region "${region}"`);
    }
  }

  // ---------------------------------------------------------------------------
  // _setVariable — create or update a DivKit global variable
  // ---------------------------------------------------------------------------
  _setVariable(name, type, value) {
  const existing = this.globalController.getVariable(name);
  if (existing) {
    existing.setValue(value);
  } else {
    let finalType = type;
    if (!finalType) {
      if (typeof value === "boolean")     finalType = "boolean";
      else if (typeof value === "number") finalType = Number.isInteger(value) ? "integer" : "number";
      else if (value === null)            return; // skip — no declared type, nothing to infer
      else if (Array.isArray(value) || typeof value === "object") return; // skip — not a primitive
      else                                finalType = "string";
    }
    const variable = createVariable(name, finalType, value);
    this.globalController.setVariable(variable);
  }

  // ── LOCAL ENGINE ADDITION ──────────────────────────────────────────
  // One-directional mirror into FlareExportedVariables — protocol §13.
  // WRITE-ONLY from Flare's perspective; never read back into DivKit.
  if (this._exportedVariableNames && this._exportedVariableNames.has(name)) {
    FlareExportedVariables.set(name, value);
  }
}

  // ---------------------------------------------------------------------------
  // Fallback screens (Connection Lost on Overlay, Screen Error on Mount)
  // ---------------------------------------------------------------------------

  _renderFallback(targetEl, json, variableName, message) {
    this._setVariable(variableName, "string", message);
    targetEl.innerHTML = "";
    render({
      id: `flare-${variableName}`,
      target: targetEl,
      json: json,
      globalVariablesController: this.globalController,
      customComponents: getCustomComponentsMap(),
      onCustomAction: (action) => {
        const url = action.url || "";
        if (url.startsWith("flare://clienttask")) {
          const params = this._parseFlareActionUrl(url);
          dispatchClientTask(params.task, params);
        }
      }
    });
    const root = targetEl.firstElementChild;
    if (root) { root.style.width = "100%"; root.style.height = "100%"; }
  }

  _showConnectionLostFallback(message) {
    try {
      if (!this.transitionOverlay.connectionLostEl) throw new Error("no #flare-transition-connection-lost element");
      this._renderFallback(this.transitionOverlay.connectionLostEl, connectionLostScreenJson, "local_connection_lost_message", message);
      this.transitionOverlay.showConnectionLostFallback();
    } catch (e) {
      console.error("[Flare] Failed to render connection-lost screen — falling back to native error card", e);
      this.transitionOverlay.showError(message, () => this._retryCurrentScreen());
    }
  }

  _showScreenErrorFallback(mount, message) {
    try {
      this._renderFallback(mount.el, screenErrorScreenJson, "local_screen_error_message", message);
    } catch (e) {
      console.error("[Flare] Failed to render screen-error screen — falling back to plain text", e);
      this._showError(mount, message);
    }
  }

  /**
   * Entry point for the built-in "retry_connection" client task.
   */
  retryConnection() {
    if (this.socket && !this.socket.isConnected()) {
      this.socket.connect();
    }

    if (this.transitionOverlay.isVisible()) {
      // Full screen connection lost: keep overlay active
      this.transitionOverlay.show(() => this._retryCurrentScreen());
    } else {
      // Single content area screen error: animate ONLY Island so bottom bar stays clickable
      this.transitionOverlay.startIslandLoading();
    }

    this._retryCurrentScreen();
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  _showSpinner(mount) {
    mount.el.innerHTML = SPINNER_HTML;
  }

  _showError(mount, message) {
    mount.el.innerHTML = `
      <div style="
        display: flex;
        align-items: center;
        justify-content: center;
        height: 200px;
        color: #e74c3c;
        font-family: sans-serif;
        font-size: 14px;
      ">${message}</div>
    `;
  }

  // For small persistent regions (top bar, bottom bar, drawers), showing a
  // full error message doesn't fit and looks broken. Instead we just hide
  // that one region — the rest of the app (content + other regions) keeps
  // working normally. Devs will still see the console.error from the caller.
  _hideBrokenRegion(mount) {
    mount.el.innerHTML = "";
    mount.el.style.display = "none";
  }

  // ---------------------------------------------------------------------------
  // _decodeFrame — custom Phoenix socket decoder
  //
  // Called by Phoenix Socket for EVERY incoming WebSocket frame before the
  // message is dispatched to channel .on() handlers.
  //
  // Text frame (string): standard Phoenix V2 format "event\npayload_json"
  // Binary frame (ArrayBuffer): Flare binary format (see Flare.Serializer)
  // ---------------------------------------------------------------------------
  _decodeFrame(rawData, callback) {
    if (rawData instanceof ArrayBuffer) {
      // Binary frame — Flare optimized path (optimize: true on server)
      return this._parseBinaryFrame(rawData).then(callback).catch(e => {
        console.error("[Flare] ❌ Binary frame parse error:", e);
      });
    }

    // Text frame — standard Phoenix V2 format is a JSON Array
    try {
      const parsed = JSON.parse(rawData);
      callback({
        join_ref: parsed[0],
        ref:      parsed[1],
        topic:    parsed[2],
        event:    parsed[3],
        payload:  parsed[4]
      });
    } catch (e) {
      console.error("[Flare] Malformed text frame:", rawData);
    }
  }

  async _parseBinaryFrame(buffer) {
    const view = new DataView(buffer);
    let offset = 0;

    const version = view.getUint8(offset); offset += 1;
    if (version !== 1) {
      throw new Error(`[Flare] Unknown binary frame version: ${version}. Update your Flare client.`);
    }

    const headerLen   = view.getUint32(offset, false); offset += 4;  // false = big-endian
    const headerBytes = new Uint8Array(buffer, offset, headerLen);   offset += headerLen;
    const header      = JSON.parse(new TextDecoder().decode(headerBytes));

    const layoutLen = view.getUint32(offset, false); offset += 4;
    const layoutGz  = new Uint8Array(buffer, offset, layoutLen);    offset += layoutLen;

    const varsLen = view.getUint32(offset, false); offset += 4;
    const varsGz  = new Uint8Array(buffer, offset, varsLen);

    const [layout, variables] = await Promise.all([
      this._gunzip(layoutGz),
      this._gunzip(varsGz)
    ]);

    const { encoding: _encoding, ...payloadWithoutEncoding } = header.payload;
    
    // Pass the restored routing properties (topic, join_ref, etc) back to Phoenix JS
    return {
      join_ref: header.join_ref,
      ref:      header.ref,
      topic:    header.topic,
      event:    header.event,
      payload: {
        ...payloadWithoutEncoding,
        layout,
        variables
      }
    };
  }

  // ---------------------------------------------------------------------------
  // _gunzip — decompress gzip bytes to a parsed JSON value
  //
  // Uses the browser's native DecompressionStream API — no library needed,
  // available in all modern browsers (Chrome 80+, Firefox 113+, Safari 16.4+).
  // ---------------------------------------------------------------------------
  async _gunzip(gzippedBytes) {
    // Feed the compressed bytes into a native gzip decompression stream
    const ds     = new DecompressionStream("gzip");
    const writer = ds.writable.getWriter();
    writer.write(gzippedBytes);
    writer.close();

    // Collect all output chunks
    const reader = ds.readable.getReader();
    const chunks = [];
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
    }

    // Concatenate chunks into a single Uint8Array
    const totalLen = chunks.reduce((n, c) => n + c.length, 0);
    const allBytes = new Uint8Array(totalLen);
    let pos = 0;
    for (const chunk of chunks) {
      allBytes.set(chunk, pos);
      pos += chunk.length;
    }

    // Decode UTF-8 bytes and parse JSON
    return JSON.parse(new TextDecoder().decode(allBytes));
  }

  // ---------------------------------------------------------------------------
  // _resolvePayload — Cross-platform contract: see SPEC_EXPRESSION_RESOLUTION.md
  //
  // Resolves @{variable_name} strings found in the TOP-LEVEL keys of an
  // action.payload object against the shared global variable controller.
  // Matches the Android reflection-based resolver exactly:
  //   - Only top-level string values are candidates
  //   - Missing or null variable values resolve to "" + warning
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // _parseFlareActionUrl — extracts query params from a "flare://action?..."
  // url. These arrive already expression-resolved by DivKit, so no manual
  // @{...} handling is needed here — just standard query-string decoding.
  // ---------------------------------------------------------------------------
  _parseFlareActionUrl(actionUrl) {
    const params = {};
    try {
      const parsed = new URL(actionUrl);
      parsed.searchParams.forEach((value, key) => {
        params[key] = value;
      });
    } catch (e) {
      // No query string, or not a valid URL shape (e.g. bare "flare://action").
      // Not an error — plenty of actions still use payload-only or no params.
    }
    return params;
  }
  _resolvePayload(rawPayload) {
    const resolved = {};
    for (const [key, value] of Object.entries(rawPayload)) {
      if (typeof value === "string" && value.startsWith("@{") && value.endsWith("}")) {
        const varName = value.substring(2, value.length - 1).trim();
        const variable = this.globalController.getVariable(varName);

        // Treat "variable doesn't exist" AND "variable exists but value is
        // null/undefined" identically — both degrade to "".
        const resolvedValue = variable ? variable.getValue() : null;

        if (resolvedValue !== null && resolvedValue !== undefined) {
          resolved[key] = resolvedValue;
        } else {
          console.warn(`[Flare] Could not resolve @{${varName}} — defaulting to ""`);
          resolved[key] = "";
        }
      } else {
        // Pass through booleans, numbers, and plain (non-expression) strings
        resolved[key] = value;
      }
    }
    return resolved;
  }

  // ---------------------------------------------------------------------------
  // _handleAuthFailure — token missing, expired, or rejected by server.
  // Cleans up all connections and returns user to the login screen.
  // ---------------------------------------------------------------------------
  _handleAuthFailure() {
    this.log("Auth failure — clearing session and returning to login");

    localStorage.removeItem("flare_token");
    this.token = null;

    // Cancel any in-flight navigation/retry state and force-hide the
    // transition overlay immediately — this is what was causing the
    // "Connection lost / Retry" popup + crash on logout.
    this._pendingScreenName = null;
    this._pendingParams     = null;
    this.transitionOverlay.forceHide();

    if (this.content.channel) {
      this.content.channel.leave();
      this.content.channel = null;
    }

    // Tear down persistent regions
    Object.values(this.regions).forEach(mount => {
      if (mount.channel) {
        mount.channel.leave();
        mount.channel = null;
      }
    });

    if (this.socket) {
      this._intentionalDisconnect = true;
      this.socket.disconnect();
      this.socket = null;
    }

    // Force a hard reload to the root URL. 
    // This completely clears old DOM elements, duplicate Lottie instances, 
    // and stuck button states, guaranteeing a 100% clean login next time.
    window.location.href = "/";
  }
}