# Flare Action Payload Expression Resolution — Cross-Platform Contract

This document defines how `@{variable_name}` expressions inside a DivKit
`action.payload` JSON object MUST be resolved before the event is pushed
to the Phoenix server. Both the Web client (flare-client.js) and the
Android client (FlareDivActionHandler.java) MUST implement this contract
identically. If you are adding a new client (iOS, etc.), implement this
spec exactly — do not infer behavior by reading one existing client only.

## 1. Scope

This applies ONLY to string values found directly inside the top-level
keys of `action.payload`. It does NOT apply to:
- Values resolved by the DivKit layout engine itself for view properties
  (font_size, text, alpha, etc.) — those follow DivKit's own expression
  language and are out of scope here.
- Nested objects or arrays inside `action.payload`. Flare payloads MUST
  be flat key/value maps. Nested values are passed through unresolved
  on both clients today — do not rely on resolution inside nested
  structures until this spec is updated.

## 2. Expression syntax

A string value is treated as an expression if and only if:
- It starts with the two characters `@{`
- It ends with the character `}`

Anything else is passed through as a literal string, unmodified.

The variable name is everything between `@{` and `}`, trimmed of
leading/trailing whitespace.

## 3. Resolution source

The variable is looked up in the **global DivKit variable controller**
for the current client session (`globalVarsController` on Android,
`this.globalController` on web). This is the SAME controller that
backs layout-bound `@{}` expressions elsewhere on screen — there is
only one variable namespace per client session, shared across screens.

## 4. Resolution failure behavior

If the variable cannot be found or its value is null:
- The resolved value MUST be the empty string `""`.
- The client MUST log a warning (or, per Update 1, an error) so the
  failure is debuggable.
- The client MUST NOT throw an unhandled exception in release builds —
  a missing variable must degrade gracefully to `""`, never crash the
  app or block the event from being sent.

## 5. Type passthrough

Non-string JSON values in `action.payload` (boolean, integer, double)
MUST be passed through unchanged — they are never treated as
expressions, even if they happen to look like one after implicit
string coercion. Only values that are JSON strings at parse time are
candidates for `@{}` resolution.

## 6. Why this exists

DivKit's native ExpressionResolver (passed into action handlers)
resolves expressions bound to view properties in the layout tree. It
does NOT resolve arbitrary strings sitting inside an opaque
`action.payload` JSON blob — that payload is just data to DivKit, not
a layout property. This means:

- On Web, the DivKit JS client happens to resolve some of this for you
  depending on version — verify this with each web client release.
- On Android, DivKit's Java/Kotlin API does not expose a way to read
  arbitrary variable values by name (no stable public `get(name)`
  API as of the version pinned in `app/build.gradle`), forcing a
  reflection-based fallback (see FlareDivActionHandler.java).

Because the underlying mechanism differs per platform, a layout author
writing `"first_name": "@{local_first_name}"` in JSON has NO guarantee
their layout behaves identically on Web vs Android unless both clients
correctly implement Sections 1–5 above. This file is the single source
of truth for that behavior — when in doubt, update this file first,
then bring both clients into compliance, then bump Flare's minor version.

## 7. Conformance checklist for new clients

- [ ] Detects `@{name}` syntax per Section 2
- [ ] Resolves only top-level flat string keys (Section 1)
- [ ] Reads from the single shared global variable controller (Section 3)
- [ ] Falls back to `""` + warning log on failure, never crashes in release (Section 4)
- [ ] Passes through non-string values unchanged (Section 5)