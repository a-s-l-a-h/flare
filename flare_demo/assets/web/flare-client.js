// Location: flare_demo/assets/web/flare-client.js

import { Socket } from "phoenix";
import {
  render,
  createVariable,
  createGlobalVariablesController
} from "@divkitframework/divkit/dist/client";

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
// RESERVED VARIABLE: local_flare_pending
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
//     "alpha": "@{local_flare_pending ? 0.5 : 1.0}"
//     "text": "@{local_flare_pending ? 'Please wait...' : 'Submit'}"
//
//   Disable taps while pending (handled automatically by the SDK —
//   the SDK ignores all actions while local_flare_pending is true).
//
// This means:
//   - Developers do NOT need to manage pending state per button
//   - One screen can only have one event in flight at a time
//   - The client is truly dumb — no business logic, pure UI feedback
// ---------------------------------------------------------------------------
const PENDING_VARIABLE = "local_flare_pending";

export class FlareClient {
  constructor(config) {
    this.wsUrl        = config.wsUrl;
    this.token        = config.token;
    this.rootEl       = config.rootEl;
    this.entryScreen  = config.entryScreen || "welcome";

    this.socket         = null;
    this.currentChannel = null;
    this.currentScreen  = null;

    // Single global variable controller shared across all screens.
    // Variables persist through navigation — flare_cart_count stays
    // correct when moving from store to product and back.
    this.globalController = createGlobalVariablesController();

    this.debug = true;
    // Handle browser back/forward button
window.addEventListener("popstate", () => {
  this.navigateTo(this._screenFromUrl());
});

    // ---------------------------------------------------------------------------
    // CHANGED: Removed _optimisticSnapshot.
    // We no longer do optimistic UI — server is truth, client is dumb.
    // Pending state is handled purely via local_flare_pending variable.
    // ---------------------------------------------------------------------------
  }

  log(msg, data = "") {
    if (this.debug) console.log(`[🔥 Flare] ${msg}`, data);
  }

  // ---------------------------------------------------------------------------
  // connect() — open the WebSocket connection once
  // ---------------------------------------------------------------------------
  connect() {
    this.log("Connecting...", this.wsUrl);

    // Send whatever token we have — could be null on absolute first load.
    // If null, server will generate a signed guest token and send it back
    // via store_token in the init envelope. We store it and use it from then on.
    // On all future page loads this.token will be set from localStorage in app.js.
    const params = this.token ? { token: this.token } : {};

    this.socket = new Socket(this.wsUrl, { params });
    this.socket.connect();
    this.socket.onOpen(() => this.log("✅ WebSocket connected"));
    this.socket.onClose(() => this.log("❌ WebSocket closed"));
  }
  // ADD this method — reads current browser URL, returns which screen to show
// Reads current browser URL, dynamically returns which screen to show
  _screenFromUrl() {
    const path = window.location.pathname;
    if (!path || path === "/") {
      return "welcome";
    }
    // Remove the leading slash (e.g., "/profile" -> "profile")
    return path.substring(1);
  }

  // Updates browser URL bar when screen changes
  _pushUrl(screenName) {
    const url = screenName === "welcome" ? "/" : `/${screenName}`;
    if (window.location.pathname !== url) {
      window.history.pushState({}, "", url);
    }
  }

// ADD this method — updates browser URL bar when screen changes


  // ---------------------------------------------------------------------------
  // navigateTo(screenName) — leave current channel, show spinner, join new one
  // ---------------------------------------------------------------------------
  navigateTo(screenName, params = {}) {
    this.log(`Navigating to: ${screenName}`);

    // Leave current channel cleanly
    if (this.currentChannel) {
      this.currentChannel.leave();
      this.currentChannel = null;
    }

    // ---------------------------------------------------------------------------
    // CHANGED: Clear pending state on navigation.
    // When the user navigates to a new screen, any in-flight event from the
    // previous screen is abandoned. Reset pending so the new screen starts clean.
    // ---------------------------------------------------------------------------
    this._setPending(false);

    // Show spinner immediately — user sees movement, not a frozen screen
    this._showSpinner();

    this.currentScreen  = screenName;
    this.currentChannel = this.socket.channel(`flare:${screenName}`, params);

    this.currentChannel.on("init",          (envelope) => this._handleInit(envelope));
    this.currentChannel.on("patch",         (envelope) => this._handlePatch(envelope));
    this.currentChannel.on("layout_update", (envelope) => this._handleLayoutUpdate(envelope));

    this.currentChannel
      .join()
      .receive("ok", () => {
        this.log(`✅ Joined flare:${screenName}`);
        this._pushUrl(screenName);  // <-- ADD THIS LINE ONLY
      })
      .receive("error", (resp) => {
        console.error(`❌ Failed to join flare:${screenName}`, resp);
        this._showError(`Could not load screen: ${screenName}`);
      });
  }

  // ---------------------------------------------------------------------------
  // _handleInit — full screen render
  // ---------------------------------------------------------------------------
  _handleInit(envelope) {
    this.log("📥 INIT received", envelope);

    // ---------------------------------------------------------------------------
    // CHANGED: Register local_flare_pending first, before any developer variables.
    // This guarantees the variable always exists on every screen regardless of
    // whether the developer listed it in their state JSON.
    // Value starts false — no event is in flight when a screen first loads.
    // ---------------------------------------------------------------------------
    // Reset all per-action pending vars on new screen load
    // Auto scan layout and register pending var for every action found
    this._registerActionPendingVars(envelope.layout);

    // Register variable type definitions from state JSON
    if (envelope.variables) {
      envelope.variables.forEach(v => {
        // Skip if developer accidentally named a variable local_flare_pending.
        // SDK owns this name. Silently protect it.
        if (v.name === PENDING_VARIABLE) {
          console.warn(`[Flare] ⚠️ Reserved variable name used: "${PENDING_VARIABLE}". Ignoring developer definition.`);
          return;
        }
        this._setVariable(v.name, v.type, v.value);
      });
    }

    // Apply current server state values on top
    if (envelope.state) {
      Object.entries(envelope.state).forEach(([key, value]) => {
        this._setVariable(key, null, value);
      });
    }

    // Execute bootstrap commands from the init envelope.
    // The server puts store_token here on first connect (new guest session).
    // This saves the signed guest token to localStorage so the next page
    // load sends it and the server can restore state for this user.
    // Also triggers a socket reconnect (see store_token case below) so
    // the rest of this session uses the signed identity, not anonymous.
    if (envelope.commands) {
      envelope.commands.forEach(cmd => this._executeCommand(cmd));
    }

    // Handle both { card: {...} } and bare card JSON
    let divkitJson = envelope.layout;
    if (!divkitJson.card) {
      divkitJson = { card: envelope.layout };
    }

    // Clear spinner and render DivKit
    this.rootEl.innerHTML = "";
    render({
      id:                        `flare-${this.currentScreen}`,
      target:                    this.rootEl,
      json:                      divkitJson,
      globalVariablesController: this.globalController,
      onCustomAction:            (action) => this._handleAction(action)
    });
  }

  _registerActionPendingVars(layoutJson) {
      const actions = this._extractFlareActions(layoutJson);
      actions.forEach(actionName => {
        const varName = `local_flare_pending_${actionName}`;
        this._setVariable(varName, "boolean", false);
        this.log(`Auto-registered: ${varName}`);
      });
    }

  _extractFlareActions(obj, found = new Set()) {
    if (!obj || typeof obj !== "object") return found;
    if (Array.isArray(obj)) {
      obj.forEach(item => this._extractFlareActions(item, found));
      return found;
    }
    if (obj.flare_action && typeof obj.flare_action === "string") {
      found.add(obj.flare_action);
    }
    Object.values(obj).forEach(v => this._extractFlareActions(v, found));
    return found;
  }

  // ---------------------------------------------------------------------------
  // _handlePatch — incremental state update
  // ---------------------------------------------------------------------------
  _handlePatch(envelope) {
    this.log("📥 PATCH received", envelope);

    if (envelope.state) {
      Object.entries(envelope.state).forEach(([key, value]) => {
        if (value !== null) {
          this._setVariable(key, null, value);
        }
      });
    }

    // ---------------------------------------------------------------------------
    // CHANGED: Clear pending when patch arrives.
    // The patch IS the server's response to the event. When it arrives,
    // the operation is complete. Release the pending lock so the next
    // action can be taken.
    //
    // This works even if the ACK and the patch arrive in different order —
    // whichever arrives first clears pending. The second one is a no-op
    // because _setPending(false) on an already-false variable does nothing.
    // ---------------------------------------------------------------------------
    

    if (envelope.commands) {
      envelope.commands.forEach(cmd => this._executeCommand(cmd));
    }
  }

  // ---------------------------------------------------------------------------
  // _handleLayoutUpdate — hot deployment layout refresh
  // ---------------------------------------------------------------------------
  _handleLayoutUpdate(envelope) {
    this.log("📥 LAYOUT_UPDATE received", envelope);

    // Capture all current variable values before destroying renderer
    const savedValues = {};
    if (envelope.variables) {
      envelope.variables.forEach(v => {
        const existing = this.globalController.getVariable(v.name);
        if (existing) savedValues[v.name] = existing.getValue();
      });
    }

    // ---------------------------------------------------------------------------
    // CHANGED: Always restore local_flare_pending to false on layout update.
    // A layout update during an in-flight event would be unusual, but if it
    // happens, start the new layout with a clean pending state.
    // ---------------------------------------------------------------------------
    // Reset all per-action pending vars on new screen load
    // Auto scan layout and register pending var for every action found
    this._registerActionPendingVars(envelope.layout);

    // Re-register variable definitions, restoring saved values
    if (envelope.variables) {
      envelope.variables.forEach(v => {
        if (v.name === PENDING_VARIABLE) return; // SDK owns this
        const value = savedValues[v.name] !== undefined ? savedValues[v.name] : v.value;
        this._setVariable(v.name, v.type, value);
      });
    }

    let divkitJson = envelope.layout;
    if (!divkitJson.card) {
      divkitJson = { card: envelope.layout };
    }

    this.rootEl.innerHTML = "";
    render({
      id:                        `flare-${this.currentScreen}-updated`,
      target:                    this.rootEl,
      json:                      divkitJson,
      globalVariablesController: this.globalController,
      onCustomAction:            (action) => this._handleAction(action)
    });
  }

  // ---------------------------------------------------------------------------
  // _handleAction — DivKit fires this for flare://action URLs
  //
  // CHANGED: This is the core of the automatic pending system.
  // Every action that reaches this handler:
  //   1. Checks if another action is already in flight (local_flare_pending = true)
  //      If yes: tap is silently ignored. User sees the button is already dimmed.
  //   2. Sets local_flare_pending = true immediately (before network call)
  //   3. Sends the event to the server
  //   4. On ACK (ok/error/timeout): clears local_flare_pending = false
  //
  // The patch handler also clears pending (whichever arrives first wins).
  // This means pending is always cleared even if ACK and patch race each other.
  // ---------------------------------------------------------------------------
  _handleAction(action) {
    try {
      const url = new URL(action.url);

      const actionUrl = action.url || "";
if (actionUrl === "flare://action" || actionUrl.startsWith("flare://action")) {
  const payload   = action.payload || {};
  const eventType = payload.flare_action;

        // Check if this is a local native action (camera, QR, GPS etc)
        // These are handled by the native bridge on Android/iOS
        // On web they are not supported — log and ignore
        const nativeAction = payload.local_flare_native_action;
        if (nativeAction) {
          console.warn(`[Flare] local_flare_native_action "${nativeAction}" is not supported on web`);
          return;
        }

        if (!eventType) {
          console.warn("[Flare] Action missing flare_action key", payload);
          return;
        }

        // ---------------------------------------------------------------------------
        // GUARD: If an event is already in flight, ignore this tap entirely.
        // This is the correct SDUI behavior — one event at a time, server is truth.
        // The user sees the button dimmed (via local_flare_pending in their layout)
        // so they know the system received their first tap.
        // ---------------------------------------------------------------------------
        // Per-action pending variable — auto derived from event name
      // "add_to_cart" → "local_flare_pending_add_to_cart"
      const actionPendingVar = `local_flare_pending_${eventType}`;

      // Check only this specific action — other actions stay unblocked
      const actionPending = this.globalController.getVariable(actionPendingVar);
      if (actionPending && actionPending.getValue() === true) {
        this.log(`⏸ Tap ignored — already in flight: ${eventType}`);
        return;
      }

      this.log(`📤 Event: ${eventType}`, payload);

      // Lock only this action before push
      this._setVariable(actionPendingVar, "boolean", true);

      this.currentChannel
        .push("event", {
          screen:  this.currentScreen,
          type:    eventType,
          payload: payload
        })
        .receive("ok", () => {
          this.log(`✅ ACK received: ${eventType}`);
          this._setVariable(actionPendingVar, "boolean", false);
        })
        .receive("error", (resp) => {
          console.error(`[Flare] ❌ Event rejected: ${eventType}`, resp);
          this._setVariable(actionPendingVar, "boolean", false);
        })
        .receive("timeout", () => {
          console.error(`[Flare] ⏱ Event timeout: ${eventType}`);
          this._setVariable(actionPendingVar, "boolean", false);
        });
      }
    } catch (e) {
      // ---------------------------------------------------------------------------
      // Unexpected error in action handling. Clear pending so UI is not stuck.
      // ---------------------------------------------------------------------------
      this._setPending(false);
      console.error("[Flare] Action handler error", e);
    }
  }

  // ---------------------------------------------------------------------------
  // _executeCommand — process server-sent instructions in order
  // ---------------------------------------------------------------------------
  _executeCommand(cmd) {
    this.log(`⚡ Command: ${cmd.type}`, cmd.payload);

    switch (cmd.type) {
      case "navigate":
        // navigateTo calls _setPending(false) internally — handled there
        this.navigateTo(cmd.payload.screen, cmd.payload.params || {});
        break;

      case "show_alert":
        alert(`${cmd.payload.title}\n\n${cmd.payload.message}`);
        break;

      case "store_token": {
        const isFirstToken = !localStorage.getItem("flare_token");
        localStorage.setItem("flare_token", cmd.payload.token);
        this.token = cmd.payload.token;

        // If this is the first token we've received this session,
        // the socket connected anonymously. We must reconnect it now
        // so all future channel joins use the signed identity.
        // Without this, state cannot be restored within the same session.
        if (isFirstToken) {
          this.log("First token received — reconnecting socket with identity");
          const screenToRejoin = this.currentScreen;

          if (this.currentChannel) {
            this.currentChannel.leave();
            this.currentChannel = null;
          }
          if (this.socket) {
            this.socket.disconnect();
            this.socket = null;
          }

          // Short delay to let the old connection close cleanly
          setTimeout(() => {
            this.connect();
            if (screenToRejoin) this.navigateTo(screenToRejoin);
          }, 300);
        }
        break;
      }

      case "clear_storage":
        // Full logout — remove token so next connect is anonymous again.
        // Server will issue a fresh guest token on next connection.
        localStorage.removeItem("flare_token");
        this.token = null;
        break;

      case "haptic":
        if (navigator.vibrate) navigator.vibrate(50);
        break;

      default:
        console.warn(`[Flare] Unknown command: ${cmd.type}`);
    }
  }

  // ---------------------------------------------------------------------------
  // _setPending — set local_flare_pending variable value
  //
  // CHANGED: Extracted into its own method because it is called from 5 places:
  //   _handleAction (set true before push, set false on ack/error/timeout)
  //   _handlePatch  (set false when patch arrives)
  //   navigateTo    (set false on screen change)
  //   _handleLayoutUpdate (set false on layout refresh)
  //
  // If the variable does not exist yet (screen not initialized), this is a no-op.
  // The variable is always created in _handleInit before any actions are possible.
  // ---------------------------------------------------------------------------
  _setPending(value) {
    const variable = this.globalController.getVariable(PENDING_VARIABLE);
    if (variable) {
      variable.setValue(value);
      this.log(`🔒 Pending: ${value}`);
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
        else                                finalType = "string";
      }
      const variable = createVariable(name, finalType, value);
      this.globalController.setVariable(variable);
    }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  _showSpinner() {
    this.rootEl.innerHTML = SPINNER_HTML;
  }

  _showError(message) {
    this.rootEl.innerHTML = `
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
}