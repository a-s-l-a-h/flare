# 🔥 Flare

**Server-Driven UI for Elixir + Phoenix & DivKit.**

Build native Android, iOS, and Web apps from a single Elixir codebase. The server owns all UI and business logic. Clients are intentionally "dumb" — they simply render the JSON layouts the server sends, fire events when the user taps, and update their UI variables when patches arrive from the server. Nothing more.

> ⚠️ **This is a proof of concept — version 0.5, not production ready.**
> It demonstrates the SDUI architecture and core ideas. APIs will change. Use it to understand the pattern, run the demo, and build on these concepts.

---

## How it works

Every screen in your app is a Phoenix Channel topic (e.g., `flare:welcome`). When a client joins, the server sends a full screen layout as DivKit JSON, plus the current state. When the user taps a button, the client pushes an event. The server runs your Elixir handler, computes what changed, and pushes back only the diff (a patch). The client applies it.

No page reloads, no REST APIs, no client-side routing logic.

```text
Client joins "flare:welcome"
  → Server sends INIT  { layout: <divkit json>, state: { flare_count: 3 } }
  → Client renders the screen

User taps "+"
  → Client pushes  { type: "increment", payload: {} }
  → Server runs handle_event, assigns flare_count: 4
  → Server computes DIFF: only flare_count changed
  → Server sends PATCH { state: { flare_count: 4 } }
  → Client updates the variable, DivKit instantly re-renders the number
```

---

## Variable System

Flare uses a three-tier variable system. Understanding this is the key to understanding Flare.

### 1. `flare_` — server-owned, persisted across screens
Variables prefixed with `flare_` are the only variables the server cares about. They are cached automatically in RAM and restored automatically when a user navigates to a new screen.

```elixir
# In your Elixir event handlers:
socket |> assign(:flare_count, 5)
```

### 2. `local_` — client-owned, never sent to server
Variables prefixed with `local_` exist *only* on the client. The server never sees them, stores them, or diffs them. They are lost when the user navigates to a new screen.

Use them for UI-only state: text input values before submission, tab selections, or toggle states.

```json
{ "type": "input", "text_variable": "local_first_name" }
```
```json
{
  "payload": {
    "flare_action": "save_profile",
    "first_name": "@{local_first_name}"
  }
}
```
*Note: The client SDK automatically resolves `@{local_first_name}` to the actual typed text before sending the payload to the server.*

### 3. `local_flare_pending_<action>` — SDK reserved, automatic
These are created automatically by the Flare client SDK for every action found in your layout.

When a user taps a button that fires `flare_action: "increment"`, the SDK immediately sets `local_flare_pending_increment = true`. When the server replies, it sets it back to `false`.

Use them in your layout to give instant visual feedback without writing any JS or Android code.

---

## State Management: Highly Efficient & Cross-Screen

### How `assign` and the Diffing Engine work

```elixir
def handle_event("increment", _payload, socket) do
  current = socket.assigns[:flare_count] || 0

  socket
  |> assign(:flare_count, current + 1)
  |> then(&{:noreply, &1})
end
```

**Behind the scenes:**
1. Flare's diffing engine compares old and new assigns.
2. It drops any `local_` prefixed keys.
3. Only changed values become a patch: `%{flare_count: 1}`.
4. That tiny patch is sent over the WebSocket.

### How State is Shared Across Any Screen

Flare keeps each user's state in a `Flare.UserState` GenServer in RAM.

1. You increment `flare_count` on the Welcome screen. The value is saved to the GenServer.
2. The user navigates to Profile. The client joins the Profile channel.
3. The Profile channel calls `Flare.UserState.get_all(user_id)` — fetches all state from RAM instantly.
4. Your `mount/2` receives a socket already containing `flare_count`.
5. You fetch any additional data you need from your database explicitly in `mount/2` and assign it.

Because the GenServer sits entirely separate from Phoenix Channels, channel crashes or screen navigation never destroys the user's data.

### Multi-Screen Sync (`global_keys`)

If you want a value to update live across all open screens simultaneously, declare it as a global key:

```elixir
config :flare, global_keys: [:flare_cart_count, :flare_cart_total]
```

Now when cart count changes on one screen, all other open screens update instantly via PubSub.

---

## Writing a Screen

```elixir
defmodule MyApp.Welcome do
  use Flare.Screen

  screen_dir __DIR__

  @impl true
  def mount(_params, socket) do
    # Fetch what you need from DB explicitly here
    # Previously cached flare_ values are already in socket.assigns
    {:ok, assign(socket,
      flare_count: Map.get(socket.assigns, :flare_count, 0)
    )}
  end

  @impl true
  def handle_event("increment", _payload, socket) do
    current = Map.get(socket.assigns, :flare_count, 0)

    socket
    |> assign(:flare_count, current + 1)
    |> haptic(:success)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("go_to_profile", _payload, socket) do
    socket
    |> navigate("profile")
    |> then(&{:noreply, &1})
  end
end
```

**File layout expected alongside your screen module:**
```text
welcome/
├── welcome.ex
├── layout/
│   └── welcome.json    ← DivKit card JSON (required)
└── state/
    └── welcome.json    ← variable type definitions (optional)
```

## Commands

Commands are server instructions the client executes *after* applying state changes:

```elixir
socket |> navigate("cart")
socket |> navigate("product", %{id: "prod_123"})
socket |> show_alert("Done", "Your order is placed.")
socket |> haptic(:success)   # :success :error :warning :light :medium :heavy
socket |> store_token(signed_token)
socket |> clear_storage()
```

## Server-initiated pushes

Push updates to clients from anywhere — background jobs, webhooks, LiveDashboard:

```elixir
Flare.push_to_user("user_123", "store", %{flare_cart_count: 4})

Flare.push_command_to_user("user_123", "store", "show_alert", %{
  title: "Order shipped!",
  message: "Your order is on its way."
})

Flare.update_layout("user_123", "store")

Flare.broadcast_to_screen("store", %{flare_sale_active: true})
```

---

## Running the demo

The demo app shows a counter that persists across navigation and a profile form that saves a name. It runs on both web and Android against the same Elixir server.

### Prerequisites
- Elixir 1.15+
- Node.js 18+
- Android Studio (for the Android client)

### Start the server
```bash
cd flare_demo
mix setup          # installs deps and builds JS assets
mix phx.server     # starts on http://localhost:4000
```

Open `http://localhost:4000` in your browser. You should see the counter screen.

### Android client
1. Open `flare-android-client/` in Android Studio.
2. Run on an emulator or physical device.
3. Enter `http://10.0.2.2:4000/` in the URL field (emulator) or your machine's local IP (physical device).
4. Tap **Connect**.

*Pro Tip: Open the Web app and Android app side by side. Increment the counter on Web, navigate to Profile on Android. The state persists across both.*

---

## What is not here yet (Roadmap)
- iOS Client (Swift)
- Presence support
- File upload handling
- Binary WebSocket frames
- Production hardening and load testing
- HexDocs publishing

---

## Project structure

```text
flare/                     ← The library (published to Hex)
  lib/flare/
    application.ex         Starts Registry, UserSupervisor, PubSub
    channel.ex             Phoenix Channel — the runtime core
    commands.ex            navigate, haptic, show_alert, store_token, etc.
    diff.ex                Pure flat-map diffing logic
    layout.ex              Builds INIT / PATCH / LAYOUT_UPDATE JSON envelopes
    lifecycle.ex           Wraps mount/handle_event/handle_info safely
    router.ex              Maps screen name strings to screen modules
    screen.ex              Behaviour + macro for developer screen modules
    socket.ex              Flare.Socket struct + assign/2,3
    user_state.ex          GenServer per user — in-RAM state cache

flare_demo/                ← Example Phoenix App
  lib/flare_demo/
    welcome/               Counter screen (Elixir + JSON)
    profile/               Name form screen (Elixir + JSON)
  assets/web/
    flare-client.js        Web SDK

flare-android-client/      ← Example Android App
  FlareClientActivity.java Runtime — socket, DivKit, commands, navigation
  PhoenixChannel.java      Zero-dependency Phoenix Channels v2 implementation
  NativeFeatureBridge.java
```