// ═══════════════════════════════════════════════════════════════
//  flare-client-plugin-engine.js
//
//  The ROUTER described in LOCAL_ENGINE_PROTOCOL.md §4 — the JS
//  mirror of Android's FlareClientPluginEngine.java. Contains NO
//  plugin-specific logic. Only: lookup -> launch -> timeout ->
//  crash isolation -> mount-liveness check -> expect_fields
//  projection -> result write-back -> follow-up action.
// ═══════════════════════════════════════════════════════════════

import { getClientPlugin } from "./flare-client-plugin-registry";
import * as ClientPluginResult from "./flare-client-plugin-result";

// Open implementation choice per protocol Part C — override per call
// site with &timeout_ms=... in the flare://clientplugin URL.
const DEFAULT_TIMEOUT_MS = 30000;

export class FlareClientPluginEngine {
  /**
   * @param {Object} deps
   * @param {Object}   deps.context           - FlareClientPluginContext instance
   * @param {Function} deps.isMountStillLive  - (screenName) => boolean, protocol §11
   * @param {Function} deps.fireLocalAction   - (actionName, originScreenName) => void
   * @param {Function} deps.setVariable       - (name, type, value) — reuses FlareClient's own DivKit variable writer
   */
  constructor({ context, isMountStillLive, fireLocalAction, setVariable }) {
    this.context = context;
    this.isMountStillLive = isMountStillLive;
    this.fireLocalAction = fireLocalAction;
    this.setVariable = setVariable;
  }

  /**
   * Entry point mirroring FlareClientPluginEngine.dispatch() on Android.
   * `invocation` fields match LOCAL_ENGINE_PROTOCOL.md §3 exactly.
   */
  dispatch({ pluginId, resultVar, params, expectFields, onSuccess, onError, onCancel, timeoutMs, originScreenName }) {
    // ── Guard: malformed invocation ─────────────────────────────────────
    if (!pluginId || !resultVar) {
      console.error("[FlareClientPluginEngine] dispatch() missing plugin id or result_var — ignoring invocation");
      return;
    }

    const plugin = getClientPlugin(pluginId);

    // ── Not-found path (protocol §10) ───────────────────────────────────
    if (!plugin) {
      console.error(`[FlareClientPluginEngine] Client plugin not found: '${pluginId}' — is it registered yet?`);
      this._showNotFoundToast();
      this._resolve(ClientPluginResult.unavailable(pluginId), resultVar, expectFields, onSuccess, onError, onCancel, originScreenName);
      return;
    }

    let alreadyResolved = false;
    const effectiveTimeoutMs = timeoutMs && timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;

    // Timeout backstop — protocol §8. Guarantees a poorly-written or
    // third-party plugin can never leave a pending state stuck forever.
    const timeoutHandle = setTimeout(() => {
      if (alreadyResolved) return;
      alreadyResolved = true;
      console.warn(`[FlareClientPluginEngine] Client plugin '${pluginId}' timed out after ${effectiveTimeoutMs}ms`);
      this._resolve(ClientPluginResult.timeoutResult(), resultVar, expectFields, onSuccess, onError, onCancel, originScreenName);
    }, effectiveTimeoutMs);

    // Exactly-once discipline (protocol §8/§9.3) — a duplicate or late
    // callback (e.g. arriving after the timeout already fired) is a no-op,
    // never a crash, never a double-write.
    const callback = (result) => {
      if (alreadyResolved) {
        console.debug(`[FlareClientPluginEngine] Ignoring duplicate/late callback for client plugin: ${pluginId}`);
        return;
      }
      alreadyResolved = true;
      clearTimeout(timeoutHandle);
      this._resolve(result, resultVar, expectFields, onSuccess, onError, onCancel, originScreenName);
    };

    // Crash isolation (protocol §9.1) — a throw here must never escape
    // into the caller (FlareClient._handleAction).
    try {
      plugin.launch(params || {}, this.context, callback);
    } catch (e) {
      console.error(`[FlareClientPluginEngine] Client plugin '${pluginId}' threw during launch()`, e);
      if (!alreadyResolved) {
        alreadyResolved = true;
        clearTimeout(timeoutHandle);
        this._resolve(ClientPluginResult.unknownError(e), resultVar, expectFields, onSuccess, onError, onCancel, originScreenName);
      }
    }
  }

  /** Runs once, exactly once, per invocation — writes the result and fires any follow-up action. */
  _resolve(result, resultVar, expectFields, onSuccess, onError, onCancel, originScreenName) {
    try {
      // ── Mount-liveness check (protocol §11) ─────────────────────────
      if (this.isMountStillLive && originScreenName && !this.isMountStillLive(originScreenName)) {
        console.debug(`[FlareClientPluginEngine] Dropping client plugin result — origin mount '${originScreenName}' no longer live`);
        return;
      }

      const projected = this._applyExpectFieldsProjection(result, expectFields);
      this.setVariable(resultVar, "dict", projected);

      let actionToFire = null;
      if (result.status === "ok") actionToFire = onSuccess;
      else if (result.status === "cancelled") actionToFire = onCancel;
      else actionToFire = onError; // "error" or "unavailable"

      if (actionToFire && this.fireLocalAction) {
        this.fireLocalAction(actionToFire, originScreenName);
      }
    } catch (e) {
      // Absolute last line of defense — resolving a result must never crash the caller.
      console.error("[FlareClientPluginEngine] Error resolving client plugin result", e);
    }
  }

  /** Shallow, type-blind allowlist filter — protocol §6.2. Applies only to a successful result's `data`. */
  _applyExpectFieldsProjection(result, expectFields) {
    if (!expectFields || !expectFields.length || result.status !== "ok" || !result.data) {
      return result;
    }
    try {
      const allowedKeys = new Set(expectFields);
      const trimmedData = {};
      Object.keys(result.data).forEach(key => {
        if (allowedKeys.has(key)) trimmedData[key] = result.data[key];
      });
      return { ...result, data: trimmedData };
    } catch (e) {
      // Fail SAFE by falling back to the unprojected result rather than crashing.
      console.error("[FlareClientPluginEngine] Error applying expect_fields projection — returning unfiltered result", e);
      return result;
    }
  }

  /** Brief, non-blocking, dismissible notice — no confirmation required (protocol §10). */
  _showNotFoundToast() {
    try {
      const el = document.createElement("div");
      el.textContent = "This feature isn't available";
      el.style.cssText =
        "position:fixed;bottom:24px;left:50%;transform:translateX(-50%);" +
        "background:rgba(0,0,0,0.8);color:#fff;padding:10px 18px;border-radius:20px;" +
        "font-size:13px;z-index:2000;pointer-events:none;";
      document.body.appendChild(el);
      setTimeout(() => el.remove(), 2500);
    } catch (e) {
      // Even a toast failing to render must never break the app.
      console.error("[FlareClientPluginEngine] Failed to show not-found toast", e);
    }
  }
}