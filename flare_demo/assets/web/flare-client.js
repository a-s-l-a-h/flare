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


export class FlareClient {
  constructor(config) {
    this.wsUrl        = config.wsUrl;
    this.token        = config.token;
    this.rootEl       = config.rootEl;
    this.entryScreen  = config.entryScreen || "welcome";

    this.socket         = null;
    this.currentChannel = null;
    this.currentScreen  = null;

    // Tracks action names currently in flight (e.g. "increment", "save_profile").
    // Used to clear the correct local_flare_pending_<action> variable when an
    // action is abandoned (nav away, layout update, or unexpected error) or
    // when its result arrives via "patch" before the channel ack does.
    this.pendingActions = new Set();

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

  // Token is always required — obtained from /auth/guest or /auth/login
  // before this connect() is called. index.html guarantees this.
  if (!this.token) {
    console.error("[Flare] connect() called with no token. User must authenticate first.");
    this._handleAuthFailure();
    return;
  }

  const params = { token: this.token };

  // ---------------------------------------------------------------------------
  // Custom decoder: handles both text frames (patch, ACKs, all normal messages)
  // and binary frames (init, layout_update when server has optimize: true).
  //
  // Phoenix Socket accepts a `decode` option. If we return a Promise,
  // Phoenix awaits it before dispatching to channel event handlers.
  //
  // Text frames arrive as strings — we parse the Phoenix V2 format: "event\njson"
  // Binary frames arrive as ArrayBuffer — we parse our custom Flare binary format.
  //
  // FALLBACK SAFETY: if binary parsing throws for any reason (corrupt frame,
  // version mismatch, etc.), the error is caught in the channel .on("init") handler
  // which shows an error message rather than crashing silently.
  // ---------------------------------------------------------------------------
  this.socket = new Socket(this.wsUrl, {
    params,
    decode: (rawData, callback) => this._decodeFrame(rawData, callback)
  });

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
    // Clear pending state on navigation.
    // When the user navigates to a new screen, any in-flight event from the
    // previous screen is abandoned. Reset pending so the new screen starts clean.
    // ---------------------------------------------------------------------------
    this._clearAllPending();

    // Show spinner immediately — user sees movement, not a frozen screen
    this._showSpinner();

    this.currentScreen  = screenName;
    this.currentChannel = this.socket.channel(`flare:${screenName}`, params);

    // ---------------------------------------------------------------------------
// _handleInit and _handleLayoutUpdate are async because binary frame
// decompression uses the browser's native DecompressionStream API which
// returns Promises. We wrap in async arrow + try/catch so an error in
// binary parsing shows a user-visible error rather than a silent failure.
// _handlePatch remains synchronous — patch messages are always text JSON.
// ---------------------------------------------------------------------------
this.currentChannel.on("init", async (envelope) => {
  try {
    await this._handleInit(envelope);
  } catch (e) {
    console.error("[Flare] ❌ Error handling init", e);
    this._showError("Failed to load screen. Please refresh.");
  }
});

this.currentChannel.on("patch", (envelope) => this._handlePatch(envelope));

this.currentChannel.on("layout_update", async (envelope) => {
  try {
    await this._handleLayoutUpdate(envelope);
  } catch (e) {
    console.error("[Flare] ❌ Error handling layout_update", e);
  }
});

    this.currentChannel
      .join()
      .receive("ok", () => {
        this.log(`✅ Joined flare:${screenName}`);
        this._pushUrl(screenName);  // <-- ADD THIS LINE ONLY
      })
      .receive("error", (resp) => {
  console.error(`❌ Failed to join flare:${screenName}`, resp);
  // Auth errors mean token is missing, expired, or invalid.
  // Clear it and redirect to login so user can re-authenticate.
  if (resp && (resp.reason === "authentication_required" ||
               resp.reason === "session_expired" ||
               resp.reason === "invalid_token")) {
    this._handleAuthFailure();
  } else {
    this._showError(`Could not load screen: ${screenName}`);
  }
});
  }

  // ---------------------------------------------------------------------------
  // _handleInit — full screen render
  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
// _handleInit — full screen render
//
// envelope arrives already decoded by _decodeFrame:
//   Plain path (optimize: false OR use_cache false screens):
//     { screen, layout: {...}, variables: [...], state: {...}, commands: [...] }
//   Optimized binary path (optimize: true, use_cache true screens):
//     Same shape — _decodeFrame already decompressed layout and variables.
//     The handler code is IDENTICAL for both paths. Zero special casing here.
// ---------------------------------------------------------------------------
async _handleInit(envelope) {
  this.log("📥 INIT received", envelope);

  // Register per-action pending variables by scanning the layout JSON tree.
  // Must happen before variables are registered so the pending vars exist
  // when DivKit starts binding expressions.
  this._registerActionPendingVars(envelope.layout);

  // Register variable type definitions from the state JSON file.
  // These define the type (integer, boolean, string, etc.) for each variable.
  if (envelope.variables) {
    envelope.variables.forEach(v => {
      this._setVariable(v.name, v.type, v.value);
    });
  }

  // Apply current server-side state values on top of the type definitions.
  // These are the user's actual runtime values from their UserState GenServer.
  if (envelope.state) {
    Object.entries(envelope.state).forEach(([key, value]) => {
      this._setVariable(key, null, value);
    });
  }

  // Execute any commands the server sent with the init envelope
  // (e.g. store_token on first guest session connect).
  if (envelope.commands) {
    envelope.commands.forEach(cmd => this._executeCommand(cmd));
  }

  // Handle both { card: {...} } and bare card JSON (DivKit accepts both)
  let divkitJson = envelope.layout;
  if (!divkitJson.card) {
    divkitJson = { card: envelope.layout };
  }

  // Clear spinner and render DivKit layout
  this.rootEl.innerHTML = "";
  render({
    id:                        `flare-${this.currentScreen}`,
    target:                    this.rootEl,
    json:                      divkitJson,
    globalVariablesController: this.globalController,
    onCustomAction:            (action) => this._handleAction(action)
  });

  const divkitRoot = this.rootEl.firstElementChild;
  if (divkitRoot) {
    divkitRoot.style.width  = "100%";
    divkitRoot.style.height = "100%";
  }
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
    // Clear pending when patch arrives.
    // The patch IS the server's response to the event. When it arrives,
    // the operation is complete. Release the pending lock(s) so the next
    // action can be taken.
    //
    // This works even if the ACK and the patch arrive in different order —
    // whichever arrives first clears pending. The second one is a no-op
    // because clearing an already-cleared (removed from the Set) action does
    // nothing the second time .receive("ok"/...) fires.
    // ---------------------------------------------------------------------------
    this._clearAllPending();

    if (envelope.commands) {
      envelope.commands.forEach(cmd => this._executeCommand(cmd));
    }
  }

  // ---------------------------------------------------------------------------
  // _handleLayoutUpdate — hot deployment layout refresh
  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
// _handleLayoutUpdate — hot deployment layout refresh
//
// Same envelope shape as _handleInit. Already decoded by _decodeFrame.
// Preserves current variable values across the layout swap so the user
// doesn't lose their dark mode toggle, pagination state, etc.
// ---------------------------------------------------------------------------
async _handleLayoutUpdate(envelope) {
  this.log("📥 LAYOUT_UPDATE received", envelope);

  // Save current variable values before destroying the renderer.
  // We restore these after re-registering variables below.
  const savedValues = {};
  if (envelope.variables) {
    envelope.variables.forEach(v => {
      const existing = this.globalController.getVariable(v.name);
      if (existing) savedValues[v.name] = existing.getValue();
    });
  }

  // Clear any in-flight pending state — a layout update during an in-flight
  // event is unusual but we handle it cleanly.
  this._clearAllPending();

  // Re-scan the new layout for action names and register pending vars
  this._registerActionPendingVars(envelope.layout);

  // Re-register variables, restoring saved values where they exist.
  // New variables introduced by the layout update get their default values.
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

  this.rootEl.innerHTML = "";
  render({
    id:                        `flare-${this.currentScreen}-updated`,
    target:                    this.rootEl,
    json:                      divkitJson,
    globalVariablesController: this.globalController,
    onCustomAction:            (action) => this._handleAction(action)
  });

  const divkitRoot = this.rootEl.firstElementChild;
  if (divkitRoot) {
    divkitRoot.style.width  = "100%";
    divkitRoot.style.height = "100%";
  }
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
              // Manually resolve @{variables} in the payload to match Android's
              // behavior — see SPEC_EXPRESSION_RESOLUTION.md. DivKit's native
              // resolver only handles layout-bound properties, not arbitrary
              // strings sitting inside an opaque action.payload object.
              const rawPayload = action.payload || {};
              const payload    = this._resolvePayload(rawPayload);
              const eventType  = payload.flare_action;

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
            
      // Lock only this action before push, and track it so navigation,
      // layout updates, errors, or an incoming patch can clear it correctly.
      this._setVariable(actionPendingVar, "boolean", true);
      this.pendingActions.add(eventType);

      this.currentChannel
        .push("event", {
          screen:  this.currentScreen,
          type:    eventType,
          payload: payload
        })
        .receive("ok", () => {
          this.log(`✅ ACK received: ${eventType}`);
          this._setVariable(actionPendingVar, "boolean", false);
          this.pendingActions.delete(eventType);
        })
        .receive("error", (resp) => {
          console.error(`[Flare] ❌ Event rejected: ${eventType}`, resp);
          this._setVariable(actionPendingVar, "boolean", false);
          this.pendingActions.delete(eventType);
        })
        .receive("timeout", () => {
          console.error(`[Flare] ⏱ Event timeout: ${eventType}`);
          this._setVariable(actionPendingVar, "boolean", false);
          this.pendingActions.delete(eventType);
        });
      }
    } catch (e) {
      // ---------------------------------------------------------------------------
      // Unexpected error in action handling. Clear pending so UI is not stuck.
      // ---------------------------------------------------------------------------
      this._clearAllPending();
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
  // Server can still refresh/rotate a token mid-session.
  // Just save it — no reconnect needed since we already have identity.
  localStorage.setItem("flare_token", cmd.payload.token);
  this.token = cmd.payload.token;
  this.log("Token refreshed by server");
  break;
}
      case "clear_storage":
  // Logout — clear token then redirect to login page.
  // Next connection requires fresh authentication (guest or real).
  localStorage.removeItem("flare_token");
  this.token = null;
  this._handleAuthFailure();
  break;

      case "haptic":
        if (navigator.vibrate) navigator.vibrate(50);
        break;

      default:
        console.warn(`[Flare] Unknown command: ${cmd.type}`);
    }
  }

  // ---------------------------------------------------------------------------
  // _clearAllPending — clear every currently in-flight per-action pending var
  //
  // Called from 4 places:
  //   _handleAction catch block (unexpected error — don't leave button stuck)
  //   _handlePatch  (server result arrived — whatever was pending is done)
  //   navigateTo    (leaving the screen abandons any in-flight action)
  //   _handleLayoutUpdate (hot layout reload abandons any in-flight action)
  //
  // Each per-action variable is created lazily the first time that action
  // fires (see _handleAction), so this only touches variables that exist.
  // ---------------------------------------------------------------------------
  _clearAllPending() {
    this.pendingActions.forEach(actionName => {
      const varName = `local_flare_pending_${actionName}`;
      this._setVariable(varName, "boolean", false);
      this.log(`🔓 Cleared pending: ${actionName}`);
    });
    this.pendingActions.clear();
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

              // ---------------------------------------------------------------------------
// _decodeFrame — custom Phoenix socket decoder
//
// Called by Phoenix Socket for EVERY incoming WebSocket frame before the
// message is dispatched to channel .on() handlers.
//
// Text frame (string): standard Phoenix V2 format "event\npayload_json"
//   → parse synchronously, call callback immediately
//
// Binary frame (ArrayBuffer): Flare binary format (see Flare.Serializer)
//   → parse asynchronously (DecompressionStream), call callback when done
//
// Phoenix accepts a Promise returned from the decode callback and awaits it,
// so async binary parsing integrates cleanly with the channel dispatch loop.
// ---------------------------------------------------------------------------
_decodeFrame(rawData, callback) {
    if (rawData instanceof ArrayBuffer) {
      // Binary frame — Flare optimized path (optimize: true on server)
      return this._parseBinaryFrame(rawData).then(callback).catch(e => {
        console.error("[Flare] ❌ Binary frame parse error:", e);
      });
    }

    // Text frame — standard Phoenix V2 format is a JSON Array: [join_ref, ref, topic, event, payload]
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
//
// Android equivalent: java.util.zip.GZIPInputStream (zero dependencies)
// iOS equivalent:     compression_decode_buffer with COMPRESSION_ZLIB
//                     (Foundation framework, zero dependencies)
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
              //   - Only top-level string values are candidates (Section 1)
              //   - Non-string values (boolean/number) pass through unchanged (Section 5)
              //   - Missing or null variable values resolve to "" + warning (Section 4)
              //   - Nested objects/arrays are NOT recursed into (Section 1) — flat only
              // ---------------------------------------------------------------------------
              _resolvePayload(rawPayload) {
                const resolved = {};
                for (const [key, value] of Object.entries(rawPayload)) {
                  if (typeof value === "string" && value.startsWith("@{") && value.endsWith("}")) {
                    const varName = value.substring(2, value.length - 1).trim();
                    const variable = this.globalController.getVariable(varName);

                    // Treat "variable doesn't exist" AND "variable exists but value is
                    // null/undefined" identically — both degrade to "". This matches
                    // Android's tryKotlinAccessor/tryReflectedMap, which only return a
                    // non-null String when v.getValue() is itself non-null.
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
// Cleans up the connection and returns user to the login screen.
// ---------------------------------------------------------------------------
_handleAuthFailure() {
  this.log("Auth failure — clearing session and returning to login");

  localStorage.removeItem("flare_token");
  this.token = null;

  if (this.currentChannel) {
    this.currentChannel.leave();
    this.currentChannel = null;
  }
  if (this.socket) {
    this.socket.disconnect();
    this.socket = null;
  }

  // Works for both same-page login (index.html with two divs)
  // and separate login page setups.
  const loginDiv = document.getElementById("flare-login");
  const rootDiv  = document.getElementById("flare-root");
  if (loginDiv && rootDiv) {
    // Same-page login — just toggle visibility
    loginDiv.style.display = "flex";
    rootDiv.style.display  = "none";
  } else {
    // Separate login page
    window.location.href = "/login";
  }
}
}