# Flare Local Engine Protocol — Cross-Platform Contract

This document defines how `flare://clienttask` and `flare://clientplugin`
actions are routed, executed, and resolved across every Flare client
(Web, Android, and any future platform). It is the single source of
truth for this subsystem. When in doubt, update this file first, then
bring every client into compliance.

This protocol governs behavior that runs entirely on-device. Nothing
here ever requires a Phoenix server round-trip. A developer may still
choose to forward a result to the server afterward (Section 7), but
that is always an explicit choice made in layout JSON, never something
the engine does on its own.

This protocol does NOT cover `flare://action`, which is unchanged.

---

## 1. Scope

In scope: routing `flare://clienttask` / `flare://clientplugin`, the
result envelope contract, the registration model, timeout/not-found/
error guarantees, the two plugin input channels, `expect_fields`
output projection, and `FlareExportedVariables`.

Not in scope: which plugins/tasks exist (additive, no protocol change
needed), the existing hardcoded local intercepts (`toggle_dark_mode`,
`open_drawer`, `close_drawer`, `open_end_drawer`, `close_end_drawer` —
untouched), and the existing scaffold model (untouched).

---

## 2. The three action families

| Host | Family | Server involved | Returns a result |
|---|---|---|---|
| `flare://action`       | Server action (existing) | Yes | Via server patch |
| `flare://clienttask`   | Client Task   | No | No — fire-and-forget |
| `flare://clientplugin` | Client Plugin | No, unless opted in | Yes — result envelope |

A client MUST determine the family by reading the URL host alone,
before parsing any query parameter or payload field. An unrecognized
`flare://` host is ignored silently.

---

## 3. URL and payload shape

### Client Task
```
flare://clienttask?task=<task_id>
```
Additional query params are passed through to the task as key/value strings.

### Client Plugin
```
flare://clientplugin?plugin=<plugin_id>&result_var=<var_name>[&on_success=<action>][&on_error=<action>][&on_cancel=<action>][&timeout_ms=<n>]
```

| Param | Required | Meaning |
|---|---|---|
| `plugin` | Yes | The registered plugin id to invoke |
| `result_var` | Yes | DivKit variable name the result envelope is written to |
| `on_success` | No | A `flare_action` fired when `status == "ok"` |
| `on_error` | No | A `flare_action` fired when `status == "error"` or `"unavailable"` |
| `on_cancel` | No | A `flare_action` fired when `status == "cancelled"` |
| `timeout_ms` | No | Overrides the engine default timeout for this call only |

Plugin `payload`:
```json
{ "params": {}, "expect_fields": [] }
```
`params` is entirely plugin-defined. `expect_fields` is protocol-defined
(Section 6).

---

## 4. Division of responsibility

**The Engine** (`FlareClientPluginEngine` / `FlareClientTaskEngine`) is a
router and nothing else — lookup, launch, timeout, projection,
mount-liveness check, result write-back, follow-up action. It must
NEVER contain logic keyed on a specific plugin/task id.

**The Registry** (`FlareClientPluginRegistry` / `FlareClientTaskRegistry`)
is an in-memory id → code map, empty by default.

**Plugins and Tasks** are never part of Flare core's own source tree —
always registered explicitly by the host app or an optional module.

---

## 5. Plugin input — two channels

**Channel A (layout-supplied):** ordinary Flare variable values passed
via URL query params or `payload.params`, resolved through the same
`@{...}` expression path `flare://action` already uses.

**Channel B (engine-supplied, `FlareClientPluginContext`):** live auth
token, live base HTTP URL, current screen name, and a
`notifyAuthFailure()` callback that routes into the platform's existing
logout handling. Constructed fresh per invocation — never cached by a
plugin beyond one call.

---

## 6. The result envelope (Plugins only)

### 6.1 Frozen shape
```json
{ "status": "ok", "data": {}, "error": null }
```
`status` ∈ `"ok" | "error" | "cancelled" | "unavailable"`. `data`
present only when `status == "ok"`. `error` present only when
`status` is `"error"` or `"unavailable"`, shaped as
`{ "code": "...", "message": "..." }`.

### 6.2 `expect_fields` projection
A shallow, type-blind allowlist filter applied to a successful result's
`data`. Keys not listed are removed; keys listed but absent from the
plugin's own `data` are simply absent from the output — never an error.

### 6.3 Frozen error code set
`PERMISSION_DENIED`, `USER_CANCELLED`, `UNAVAILABLE`, `TIMEOUT`,
`INVALID_INPUT`, `SESSION_EXPIRED`, `UNKNOWN`. Additive only — never
rename/remove/repurpose an existing code.

### 6.4 Wire format
Written as a DivKit Dict variable at `result_var`, e.g.
`"@{my_result.status == 'ok' ? 'visible' : 'gone'}"`.

---

## 7. Forwarding to the server (opt-in only)

The engine never contacts the server on its own. Naming an existing
`flare_action` in `on_success`/`on_error`/`on_cancel` fires it through
the same pending-lock + channel-push path a normal tap uses.

---

## 8. Timeout

Engine-owned, not plugin-owned. On timeout, the engine synthesizes
`{ "status": "error", "error": { "code": "TIMEOUT" } }` and proceeds as
if the plugin had returned it. A plugin must call its callback exactly
once; a late/duplicate callback is silently ignored.

---

## 9. Crash isolation

1. Wrapped boundary crossings (launch + callback receipt).
2. No shared native request-code space — each plugin owns its own.
3. Exactly-once callback discipline, enforced defensively.
4. Engine-owned main-thread marshaling before any DivKit-facing write.

---

## 10. Not-found behavior

Never crash. Show a brief, non-blocking, dismissible notice. For
plugins, still write `status: "unavailable"` / `error.code:
"UNAVAILABLE"` and still fire `on_error` if named. Log the missing id.

---

## 11. Result delivery and screen lifecycle

Before writing a result, the engine MUST confirm the originating mount
is still live. If not, drop the result silently (debug-log only).

---

## 12. Registry override semantics

Registering an existing id OVERWRITES it and logs a visible warning.
Never blocked. This is the supported extension mechanism.

---

## 13. FlareExportedVariables

A safe, one-directional, read-only mirror of variables declared
`"exported": true` in `state/<screen>.json`. Native/external code can
`get()`/`subscribe()` but never `set()` — the only writer is the Flare
client itself, on every variable update. To change Flare state from
outside DivKit, use a `flare://clienttask`, `flare://clientplugin`, or
a server push — never this mirror.

---

## 14. Debug discoverability

Clients SHOULD expose a debug-only way to enumerate currently
registered plugin/task ids at runtime.

---

## 15. Naming disambiguation

- `FlareCommandHandler` (existing) executes SERVER-sent commands
  delivered inside init/patch envelopes. Unrelated to
  `FlareClientTask`, which never touches the server.
- `FlareEnvelope` (existing) parses the server wire message. Unrelated
  to `FlareClientPluginResult`, a purely local result type.
- `FlareClientPluginRegistry` (client-side) is unrelated to
  `Flare.Registry` (Elixir/BEAM process registry, server-side).
- The "Client" prefix is applied consistently across every type in
  this subsystem (`FlareClientPlugin`, `FlareClientPluginCallback`,
  `FlareClientPluginContext`, `FlareClientPluginResult`,
  `FlareClientPluginRegistry`, `FlareClientPluginEngine`,
  `FlareClientTask`, `FlareClientTaskRegistry`,
  `FlareClientTaskEngine`) specifically so nothing in this local,
  on-device subsystem can ever be mistaken for a server-side concept.

---

## 16. File and module layout

### Android
```
flare/plugin/FlareClientPlugin.java
flare/plugin/FlareClientPluginCallback.java
flare/plugin/FlareClientPluginContext.java
flare/plugin/FlareClientPluginResult.java
flare/plugin/FlareClientPluginRegistry.java
flare/plugin/FlareClientPluginEngine.java
flare/task/FlareClientTask.java
flare/task/FlareClientTaskRegistry.java
flare/task/FlareClientTaskEngine.java
flare/task/builtin/OpenBrowserTask.java
flare/export/FlareExportedVariables.java
```

### Web
```
src/js/plugin/flare-client-plugin-result.js
src/js/plugin/flare-client-plugin-registry.js
src/js/plugin/flare-client-plugin-context.js
src/js/plugin/flare-client-plugin-engine.js
src/js/task/flare-client-task-registry.js
src/js/task/flare-client-task-engine.js
src/js/task/builtin/open-browser-task.js
src/js/export/flare-exported-variables.js
```

---

## 17. Conformance checklist for a new plugin/task

- [ ] Unique, stable `id()` — never rename once shipped
- [ ] Task: synchronous, never blocks on network/long I/O
- [ ] Plugin: calls its callback exactly once, on every code path
- [ ] Plugin: never invents a `status` or `error.code` outside the frozen sets
- [ ] Plugin: validates its own `params`, reports `INVALID_INPUT` rather than guessing
- [ ] Plugin: reads token/base URL only from `FlareClientPluginContext`, never caches
- [ ] Plugin: calls `context.notifyAuthFailure()` on its own HTTP auth failures
- [ ] Plugin: uses its own isolated native result channel (no shared request codes)
- [ ] Registered explicitly by the host app or an optional module — never inside Flare core
```

