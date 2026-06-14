
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
Variables prefixed with `flare_` are the only variables that the server cares about. They are saved automatically and restored automatically when a user navigates to a new screen.

```elixir
# In your Elixir event handlers, use the assign function:
socket |> assign(:flare_count, 5)
```

### 2. `local_` — client-owned, never sent to server
Variables prefixed with `local_` exist *only* on the client. The server never sees them, stores them, or diffs them. They are lost when the user navigates to a new screen. 

Use them for UI-only state: text input values before submission, tab selections, or toggle states.

```json
{ "type": "input", "text_variable": "local_first_name" }
```
```json
// Read local_ in the layout, and pass it to the server on tap:
{
  "payload": {
    "flare_action": "save_profile",
    "first_name": "@{local_first_name}" 
  }
}
```
*Note: The client SDK automatically resolves `@{local_first_name}` to the actual typed text (e.g. "Ahmad") before sending the payload to the server.*

### 3. `local_flare_pending_<action>` — SDK reserved, automatic
These are created automatically by the Flare client SDK for every action found in your layout. 

When a user taps a button that fires `flare_action: "increment"`, the SDK immediately sets `local_flare_pending_increment = true`. When the server replies (ACKs), it sets it back to `false`.

Use them in your layout to give instant visual feedback (dimming buttons, showing loaders) without writing any JS or Android code.

---

## State Management: Highly Efficient & Cross-Screen

Flare makes accessing your state from *any* page incredibly fast and efficient without requiring you to pass parameters around in URLs. 

### How `assign` and the Diffing Engine work
In your Elixir views, you modify state using the `assign/3` function:

```elixir
def handle_event("increment", _payload, socket) do
  current = socket.assigns[:flare_count] || 0
  
  socket
  |> assign(:flare_count, current + 1)
  |> then(&{:noreply, &1})
end
```
**Behind the scenes:**
1. When `handle_event` finishes, Flare's diffing engine (`Flare.Diff`) looks at the `old_assigns` (before the event) and the `new_assigns` (after the event).
2. It drops any variables prefixed with `local_` (ignoring client state).
3. It performs a flat map comparison. If `:flare_count` changed from `0` to `1`, it creates a tiny patch: `%{flare_count: 1}`. Unchanged variables are ignored.
4. This tiny patch is sent over the WebSocket to update the UI instantly.

### How State is Shared Across Any Page
Flare keeps a user's state inside a highly efficient Elixir process in RAM (`Flare.UserState` GenServer) for as long as they are active.

1. You increment `flare_count` on the "Welcome" screen. The diffing engine saves the new value to the GenServer.
2. The user taps "Go to Profile". The client leaves the "Welcome" channel and joins the "Profile" channel.
3. When the user joins the "Profile" screen, the channel process calls `Flare.UserState.get_all(user_id)`.
4. This fetches the *entire* accumulated state for that user from RAM instantly. 
5. The `mount/2` function of the Profile screen receives a `socket` that *already contains* `flare_count`.

Because the GenServer sits entirely separate from the Phoenix Channels, channel crashes or screen navigations never destroy the user's data.

### Multi-Screen Sync (`global_keys`)
If you want a variable to update *live* across all open screens simultaneously (e.g., a user has your app open on their iPad and their Android phone at the same time, or has two web tabs open), configure it as a **global key**:

```elixir
# config/config.exs
config :flare, global_keys: [:flare_cart_count, :flare_cart_total]
```
Now, if the user adds an item to their cart on one screen, Flare saves it to the GenServer AND uses Phoenix PubSub to instantly push a UI patch to all other open screens in the background.

---

## Writing a view

Think of Views like standard Phoenix LiveViews or Controllers.

```elixir
defmodule MyApp.Welcome do
  use Flare.View

  # Tells Flare where your layout/ and state/ JSON files are
  view_files __DIR__

  @impl true
  def mount(_params, socket) do
    # State is already pre-loaded into socket.assigns by the GenServer!
    {:ok, assign(socket,
      flare_count: Map.get(socket.assigns, :flare_count, 0)
    )}
  end

  @impl true
  def handle_event("increment", _payload, socket) do
    current = Map.get(socket.assigns, :flare_count, 0)
    
    socket
    |> assign(:flare_count, current + 1)
    |> haptic(:success) # Triggers phone vibration!
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

**File layout expected alongside your view module:**
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
socket |> navigate("cart")                          # go to a screen
socket |> navigate("product", %{id: "prod_123"})    # with params
socket |> show_alert("Done", "Your order is placed.")
socket |> haptic(:success)                          # :success :error :warning :light :medium :heavy
socket |> store_token(signed_token)                 # save auth token on device securely
socket |> clear_storage()                           # logout (removes token)
```

## Server-initiated pushes

You can push state changes to a user from *anywhere* — background jobs, webhooks, or LiveDashboard:

```elixir
# Push state to a specific user's specific screen
Flare.push_to_user("user_123", "store", %{flare_cart_count: 4})

# Push a command to a user's screen
Flare.push_command_to_user("user_123", "store", "show_alert", %{
  title: "Order shipped!",
  message: "Your order is on its way."
})

# Hot-reload the layout for a user (after a server deployment!)
Flare.update_layout("user_123", "store")

# Broadcast to ALL users currently on a screen (e.g., flash sales)
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
3. Enter `http://10.0.2.2:4000/` in the URL field (for emulator) or your machine's local IP (physical device).
4. Tap **Connect**.

*Pro Tip: Open the Web app and the Android app side-by-side. Increment the counter on Web, navigate to the Profile screen on Android. Notice how the state persists on the server across both!*

---

## What is not here yet (Roadmap)
- Deprecating Magic StateLoader for Explicit Hydration: Currently, Flare includes a StateLoader behaviour that attempts to magically "lazy-load" missing variables from the database in the background. However, we are moving towards an "Explicit Hydration" model. In future versions, StateLoader will be removed. Developers will simply fetch missing data explicitly inside the mount/2 function and assign it to the socket. This gives developers complete predictability and prevents accidental N+1 database queries, while Flare's GenServer continues to cache those assigned values efficiently in RAM for lightning-fast cross-screen navigation.
- iOS Client (Swift)
- Presence support
- File upload handling
- Binary WebSocket frames
- Production hardening and load testing
- HexDocs publishing

---

## Project structure

```text
flare/                     ← The library (What gets published to Hex)
  lib/flare/
    application.ex         Starts Registry, UserSupervisor, PubSub
    channel.ex             Phoenix Channel — the runtime core
    commands.ex            navigate, haptic, show_alert, store_token, etc.
    diff.ex                Pure flat-map diffing logic
    layout.ex              Builds INIT / PATCH / LAYOUT_UPDATE JSON envelopes
    lifecycle.ex           Wraps mount/handle_event/handle_info safely
    router.ex              Maps screen name strings to view modules
    socket.ex              Flare.Socket struct + assign/2,3
    user_state.ex          GenServer per user — the caching state store
    view.ex                Behaviour + macro for developer view modules

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