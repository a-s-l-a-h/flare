# Flare

Server-Driven UI library for Phoenix + DivKit. Build native iOS, Android, and Web apps from one Elixir codebase.

---

## What is Flare?

Flare is an Elixir library that connects Phoenix Framework on the server to DivKit on the client. You write one Elixir module per screen. Flare handles WebSocket connections, state synchronisation, diffing, caching, and broadcasting. DivKit renders genuinely native UI on iOS, Android, and Web from the same JSON layout file.

```
You write Elixir
      ↓
Flare manages state, diffs, routing, commands
      ↓
Phoenix sends JSON over WebSocket
      ↓
DivKit renders native UI on iOS / Android / Web
      ↓
User taps a button
      ↓
Flare routes the event to your handle_event function
      ↓
Cycle repeats
```

No JavaScript framework. No Swift knowledge required. No Kotlin knowledge required. One developer can ship a production-grade native app on all three platforms.

---

## Why Flare Exists

Traditional cross-platform development requires separate teams and codebases for backend, iOS, Android, and web. Every UI change touches all four. App store reviews add weeks of delay.

Flare collapses this into a single Elixir codebase:

- UI changes deploy instantly without app store review
- One developer can own the entire product
- Real-time updates are built in — no extra infrastructure
- State management is invisible — Flare handles it
- Authentication is handled once at the connection level

---

## Architecture

### The Three Layers

```
┌─────────────────────────────────────────┐
│           Your Elixir App               │
│   View modules  ·  StateLoader  ·  DB   │
├─────────────────────────────────────────┤
│              Flare Library              │
│  Channel · UserState · Diff · Layout    │
├─────────────────────────────────────────┤
│           Phoenix Framework             │
│     WebSocket · PubSub · Token          │
└─────────────────────────────────────────┘
         ↕ WebSocket (JSON)
┌─────────────────────────────────────────┐
│           Flare Client SDK              │
│   Web (JS)  ·  iOS (Swift)  ·  Android  │
├─────────────────────────────────────────┤
│                DivKit                   │
│   Renders native UI from JSON layout    │
└─────────────────────────────────────────┘
```

### Server-Side Processes (per connected user)

```
Flare.Registry          — maps user_id → GenServer PID
Flare.UserSupervisor    — supervises all UserState processes
Flare.UserState         — one GenServer per user, holds state in memory
Flare.PubSub            — broadcasts global state between screens
```

When a user connects, Flare starts one `UserState` GenServer for them. This process:
- Caches all `flare_` prefixed state in memory
- Lazy-loads missing keys from your database via `StateLoader`
- Broadcasts global state changes to all of the user's open screens
- Shuts down after 5 minutes of inactivity (configurable)
- Restarts automatically on the user's next connection

### Message Flow

```
Client joins "flare:welcome"
        ↓
Flare.Channel.join/3
  → ensures UserState is running
  → subscribes to PubSub topics
  → restores saved state from UserState cache
  → calls your mount/2
  → builds INIT envelope
  → pushes "init" to client
        ↓
Client renders screen

User taps button
        ↓
Client pushes "event" message
        ↓
Flare.Channel.handle_in/3
  → calls your handle_event/3
  → computes diff (old assigns vs new assigns)
  → splits diff into global keys and page keys
  → global keys → UserState.update → PubSub broadcast → all screens
  → page keys → UserState.save → pushed as "patch" to this screen
        ↓
Client receives "patch" — only changed values
        ↓
DivKit updates only the bound UI elements
```

### Wire Format

Flare uses four message types over WebSocket.

**INIT** — sent once when a screen loads:
```json
{
  "screen": "welcome",
  "layout": { "...DivKit card JSON..." },
  "variables": [
    { "name": "flare_title", "type": "string", "value": "" },
    { "name": "flare_clicks", "type": "integer", "value": 0 },
    { "name": "local_tab", "type": "string", "value": "home" }
  ],
  "state": {
    "flare_title": "Welcome to Flare!",
    "flare_clicks": 42
  }
}
```

**PATCH** — sent after any state change (only what changed):
```json
{
  "screen": "welcome",
  "state": {
    "flare_clicks": 43
  }
}
```

**PATCH with commands** — state change plus client instructions:
```json
{
  "screen": "login",
  "state": { "flare_auth_success": true },
  "commands": [
    { "type": "store_token", "payload": { "token": "..." } },
    { "type": "navigate",    "payload": { "screen": "store" } },
    { "type": "haptic",      "payload": { "style": "success" } }
  ]
}
```

**LAYOUT_UPDATE** — sent after deployment to refresh UI without disconnecting:
```json
{
  "screen": "welcome",
  "layout": { "...new DivKit card JSON..." },
  "variables": [ "...updated variable definitions..." ]
}
```

---

## Variable Naming Convention

Flare uses a two-prefix system to separate ownership clearly.

### `flare_` prefix — Flare manages this

Variables prefixed `flare_` are owned by the server and synced to the client by Flare.

- Set them using `assign/2` or `assign/3` in your view module
- Flare diffs them and sends only changed values to the client
- They persist across navigation — stored in the UserState GenServer cache
- The client reads them via DivKit variable bindings: `@{flare_title}`
- The client **never writes** to these directly

```elixir
assign(socket, :flare_title, "Welcome!")
assign(socket, :flare_cart_count, 3)
assign(socket, flare_username: "Ahmad", flare_plan: "pro")
```

### `local_` prefix — client manages this

Variables prefixed `local_` are owned entirely by DivKit on the client.

- Defined in your `state/screen.json` file
- Changed by DivKit actions directly — no server roundtrip
- Flare **never** patches these — the server cannot overwrite them
- Never put them in `assign()` — they are client-only
- Use them for: active tab, open/closed modal, search input text, accordion state

```json
{ "name": "local_tab",         "type": "string",  "value": "details" },
{ "name": "local_modal_open",  "type": "boolean", "value": false },
{ "name": "local_search_query","type": "string",  "value": "" }
```

### Why this matters

Without this separation, a server patch could silently overwrite text the user is currently typing. The prefix system makes this impossible — `local_` keys are filtered out of every diff before anything is sent over the wire.

---

## Dependencies

```elixir
# mix.exs
{:phoenix,        "~> 1.8"},
{:phoenix_pubsub, "~> 2.1"},
{:jason,          "~> 1.4"}
```

Flare depends on Phoenix for WebSocket channel infrastructure and PubSub. Jason handles JSON encoding and decoding of layout files and envelopes. No database dependency — Flare is completely agnostic about your data layer.

---

## Installation

Add Flare to your `mix.exs`:

```elixir
def deps do
  [
    {:flare, "~> 0.1"}
  ]
end
```

Flare starts its own supervision tree automatically when your app starts. You do not need to add anything to your Application supervisor.

---

## Configuration

In `config/config.exs`:

```elixir
config :flare,
  # Required — your router module
  router: MyApp.FlareRouter,

  # Optional — keys broadcast to all of a user's open screens
  # When flare_cart_count changes on the product screen,
  # the store screen updates automatically
  global_keys: [:flare_cart_count, :flare_unread_count],

  # Optional — how long UserState GenServer stays alive with no activity
  # Default: 300_000 (5 minutes)
  user_state_timeout: 300_000,

  # Optional — module that loads state from your database
  state_loader: MyApp.FlareStateLoader,

  # Optional — control Flare's internal logging
  # Default: true. Set false in production to silence info/debug logs.
  # Errors are always logged regardless of this setting.
  enable_logging: true
```

---

## Project Structure

Each screen is a self-contained folder with three files:

```
lib/my_app/
├── welcome/
│   ├── welcome.ex          ← your view module (Elixir logic)
│   ├── layout/
│   │   └── welcome.json    ← DivKit card JSON (UI structure)
│   └── state/
│       └── welcome.json    ← variable definitions (optional)
├── product/
│   ├── product.ex
│   ├── layout/
│   │   └── product.json
│   └── state/
│       └── product.json
└── ...
```

The `state/` folder is optional. If absent, the screen simply has no server variables declared — valid for static screens.

---

## Creating a Screen

### 1. The view module

```elixir
# lib/my_app/welcome/welcome.ex

defmodule MyApp.Welcome do
  use Flare.View

  # Tells Flare where to find this screen's layout/ and state/ folders
  view_files __DIR__

  @impl true
  def mount(_params, socket) do
    socket = assign(socket,
      flare_title:  "Welcome!",
      flare_clicks: 0
    )
    {:ok, socket}
  end

  @impl true
  def handle_event("button_clicked", _payload, socket) do
    current = socket.assigns.flare_clicks

    socket
    |> assign(:flare_clicks, current + 1)
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

### 2. The layout file

```json
// lib/my_app/welcome/layout/welcome.json
{
  "card": {
    "log_id": "welcome_screen",
    "states": [{
      "state_id": 0,
      "div": {
        "type": "container",
        "orientation": "vertical",
        "paddings": { "top": 60, "left": 24, "right": 24, "bottom": 40 },
        "items": [
          {
            "type": "text",
            "text": "@{flare_title}",
            "font_size": 28,
            "font_weight": "bold"
          },
          {
            "type": "text",
            "text": "Clicked @{flare_clicks} times",
            "font_size": 18,
            "margins": { "top": 16, "bottom": 32 }
          },
          {
            "type": "text",
            "text": "Click Me",
            "font_size": 18,
            "font_weight": "bold",
            "text_color": "#ffffff",
            "background": [{ "type": "solid", "color": "#3498db" }],
            "border": { "corner_radius": 12 },
            "paddings": { "top": 16, "bottom": 16 },
            "action": {
              "log_id": "btn_click",
              "url": "flare://action",
              "payload": { "flare_action": "button_clicked" }
            }
          }
        ]
      }
    }]
  }
}
```

### 3. The state file

```json
// lib/my_app/welcome/state/welcome.json
{
  "variables": [
    { "name": "flare_title",  "type": "string",  "value": "" },
    { "name": "flare_clicks", "type": "integer", "value": 0 }
  ]
}
```

Variable names here must exactly match what you use in `assign()` and in the layout `@{}` bindings. The `value` field is the default shown before the server sends real data.

### 4. Register the screen

```elixir
# lib/my_app/flare_router.ex

defmodule MyApp.FlareRouter do
  use Flare.Router

  view "welcome", MyApp.Welcome
  view "profile", MyApp.Profile
  view "store",   MyApp.Store
  view "product", MyApp.Product
  view "cart",    MyApp.Cart
end
```

### 5. Wire up the socket

```elixir
# lib/my_app/user_socket.ex

defmodule MyApp.UserSocket do
  use Phoenix.Socket

  channel "flare:*", Flare.Channel

  @impl true
  def connect(%{"token" => token}, socket, _connect_info) do
    case Phoenix.Token.verify(MyApp.Endpoint, "user_auth", token, max_age: 86_400) do
      {:ok, user_id} -> {:ok, assign(socket, :user_id, user_id)}
      {:error, _}    -> {:ok, assign(socket, :user_id, nil)}
    end
  end

  def connect(_params, socket, _connect_info) do
    {:ok, assign(socket, :user_id, nil)}
  end

  @impl true
  def id(_socket), do: nil
end
```

---

## View Callbacks

### `mount/2` — required

Called once when a client joins the screen. Set initial state here.

```elixir
def mount(params, socket) do
  # params — map of values passed when navigating to this screen
  # socket — the Flare.Socket struct

  socket = assign(socket, flare_title: "Hello")
  {:ok, socket}

  # Or reject the join:
  # {:error, :unauthorized}
end
```

Flare restores previously cached state before calling `mount`. This means if the user navigated away and comes back, their `flare_` variables are already populated. You can override specific keys in `mount` if you want reset behaviour for a particular variable.

### `handle_event/3` — optional

Called when the client fires a `flare://action`. The default implementation does nothing and returns `{:noreply, socket}`.

```elixir
def handle_event("add_to_cart", %{"product_id" => id}, socket) do
  case MyApp.Cart.add(socket.user_id, id) do
    {:ok, cart} ->
      socket
      |> assign(:flare_cart_count, cart.item_count)
      |> haptic(:success)
      |> then(&{:noreply, &1})

    {:error, :out_of_stock} ->
      socket
      |> assign(:flare_error, "Sorry, this item is out of stock.")
      |> haptic(:error)
      |> then(&{:noreply, &1})
  end
end
```

### `handle_info/2` — optional

Called for process messages and timers. The default implementation does nothing.

```elixir
def mount(_params, socket) do
  Process.send_after(self(), :refresh, 5_000)
  {:ok, socket}
end

def handle_info(:refresh, socket) do
  Process.send_after(self(), :refresh, 5_000)
  data = MyApp.Dashboard.fetch_live_stats()
  {:noreply, assign(socket, flare_visitors: data.visitors)}
end

# Always add a catch-all when you define custom handle_info clauses.
# Without this, unexpected system messages crash the channel.
def handle_info(_message, socket) do
  {:noreply, socket}
end
```

---

## The `assign` Function

`use Flare.View` imports `assign/2` and `assign/3` automatically.

```elixir
# Single key
assign(socket, :flare_count, 5)

# Multiple keys at once
assign(socket, flare_count: 5, flare_name: "Ahmad", flare_plan: "pro")

# Reading values
socket.assigns.flare_count
socket.assigns[:flare_count]          # returns nil if missing
Map.get(socket.assigns, :flare_count, 0)  # with default
```

Assign always returns a new socket. The original is never modified. Chain naturally with the pipe operator.

---

## Commands

Commands are instructions from the server to the client SDK. They are processed after state changes are applied. `use Flare.View` imports all command functions automatically.

### `navigate/3`

Navigate the client to a different screen.

```elixir
socket |> navigate("store")
socket |> navigate("product", %{product_id: "prod_123"})
```

### `show_alert/4`

Show a native platform alert dialog.

```elixir
socket |> show_alert("Are you sure?", "This cannot be undone.", "Delete")
socket |> show_alert("Error", "Please try again.")
```

### `store_token/2`

Store an authentication token securely on the device. Web uses localStorage, iOS uses Keychain, Android uses EncryptedSharedPreferences.

```elixir
token = Phoenix.Token.sign(MyApp.Endpoint, "user_auth", user_id)
socket |> store_token(token)
```

### `clear_storage/1`

Remove the stored authentication token. Use on logout.

```elixir
socket |> clear_storage()
```

### `haptic/2`

Trigger haptic feedback. Ignored silently on devices that do not support it.

```elixir
socket |> haptic()           # :success (default)
socket |> haptic(:success)
socket |> haptic(:warning)
socket |> haptic(:error)
socket |> haptic(:light)
socket |> haptic(:medium)
socket |> haptic(:heavy)
```

### Chaining commands

Commands chain naturally and are sent to the client in order:

```elixir
def handle_event("login", %{"email" => email, "password" => pass}, socket) do
  case MyApp.Accounts.authenticate(email, pass) do
    {:ok, user_id} ->
      token = Phoenix.Token.sign(MyApp.Endpoint, "user_auth", user_id)
      socket
      |> assign(:flare_auth_success, true)
      |> store_token(token)
      |> navigate("store")
      |> haptic(:success)
      |> then(&{:noreply, &1})

    {:error, :invalid_credentials} ->
      socket
      |> assign(:flare_error_message, "Invalid email or password")
      |> haptic(:error)
      |> then(&{:noreply, &1})
  end
end
```

---

## Global State

Some state needs to be visible on all of a user's open screens simultaneously. For example, a cart count badge should update on every screen when the user adds a product.

Declare global keys in config:

```elixir
config :flare,
  global_keys: [:flare_cart_count, :flare_unread_count, :flare_user_name]
```

When `handle_event` assigns a global key, Flare automatically:
1. Broadcasts the new value to all of the user's open screens via PubSub
2. Each open screen receives the update and pushes a patch to the client
3. The client updates the variable on all screens simultaneously

No extra code required in your view modules. Just assign the value normally.

```elixir
# On the product screen — cart count updates on store screen automatically
socket |> assign(:flare_cart_count, new_count)
```

---

## Pushing Updates From Outside a Screen

These functions work from anywhere in your application — background jobs, webhooks, scheduled tasks, admin panels.

```elixir
# Push state to a specific user's specific screen
Flare.push_to_user("user_123", "store", %{flare_sale_active: true})

# Push a command to a specific user's screen
Flare.push_command_to_user("user_123", "store", "show_alert", %{
  title: "Order shipped!",
  message: "Your order is on the way."
})

# Refresh layout for a specific user after deployment
Flare.update_layout("user_123", "store")

# Broadcast to ALL users currently on a screen
Flare.broadcast_to_screen("store", %{flare_sale_active: true})
```

---

## Lazy State Loading

Flare does not require you to load all state on every mount. You can declare a `StateLoader` module that loads values on demand when they are first needed.

```elixir
# config/config.exs
config :flare, state_loader: MyApp.FlareStateLoader
```

```elixir
# lib/my_app/flare_state_loader.ex

defmodule MyApp.FlareStateLoader do
  @behaviour Flare.StateLoader

  @impl true
  def load(user_id, missing_keys) do
    result = %{}

    # Group related keys into single queries
    cart_keys = [:flare_cart_count, :flare_cart_total]

    result =
      if Enum.any?(missing_keys, &(&1 in cart_keys)) do
        cart = MyApp.Cart.get_for_user(user_id)
        Map.merge(result, %{
          flare_cart_count: cart.item_count,
          flare_cart_total: cart.total_string
        })
      else
        result
      end

    result
  end
end
```

When any screen first accesses `flare_cart_count` and it is not yet in the cache, Flare calls `load/2` with the missing keys. The result is cached for the duration of the session. Subsequent accesses return the cached value instantly with no database call.

Call `Flare.UserState.invalidate(user_id, [:flare_cart_count])` after a database write to force a fresh load on next access.

---

## Authentication

Authenticate once at the WebSocket connection level. All screens automatically receive the authenticated `user_id`. No per-screen or per-event authentication code needed.

### Generating a token (on login)

```elixir
token = Phoenix.Token.sign(MyApp.Endpoint, "user_auth", user.id)
socket |> store_token(token)
```

### Validating the token (in UserSocket)

```elixir
def connect(%{"token" => token}, socket, _connect_info) do
  case Phoenix.Token.verify(MyApp.Endpoint, "user_auth", token, max_age: 86_400) do
    {:ok, user_id} -> {:ok, assign(socket, :user_id, user_id)}
    {:error, _}    -> {:ok, assign(socket, :user_id, nil)}
  end
end

def connect(_params, socket, _connect_info) do
  {:ok, assign(socket, :user_id, nil)}
end
```

### Using user_id in view modules

```elixir
def mount(_params, socket) do
  user_id = socket.user_id   # nil for anonymous

  if is_nil(user_id) do
    socket |> navigate("login") |> then(&{:ok, &1})
  else
    user = MyApp.Accounts.get_user!(user_id)
    {:ok, assign(socket, flare_username: user.name)}
  end
end
```

### Logout

```elixir
def handle_event("logout", _payload, socket) do
  Flare.UserState.stop(socket.user_id)

  socket
  |> clear_storage()
  |> navigate("welcome")
  |> then(&{:noreply, &1})
end
```

---

## The `local_flare_pending` Reserved Variable

The Flare client SDK automatically creates a reserved variable called `local_flare_pending` on every screen. You do not declare it — it is always there.

When any button fires a `flare://action`:
- `local_flare_pending` is set to `true` immediately
- Further taps are ignored while an event is in flight
- `local_flare_pending` is set to `false` when the server responds

Use it in your layout to show loading feedback:

```json
{
  "type": "text",
  "text": "@{local_flare_pending ? 'Please wait...' : 'Submit'}",
  "alpha": "@{local_flare_pending ? 0.5 : 1.0}",
  "action": {
    "url": "flare://action",
    "payload": { "flare_action": "submit_form" }
  }
}
```

Do not use `local_flare_pending` as a variable name in your own `state/` files — it is reserved by the SDK.

---

## Cache Invalidation

After a database write that changes user data, invalidate the relevant cache keys so they reload fresh on next access:

```elixir
# After updating the user's cart
Flare.UserState.invalidate(user_id, [:flare_cart_count, :flare_cart_total])

# After the user updates their profile
Flare.UserState.invalidate(user_id, [:flare_username, :flare_avatar_url])
```

---

## Module Reference

| Module | Purpose |
|---|---|
| `Flare` | Public API — push, broadcast, update layout |
| `Flare.View` | `use` macro injected into every screen module |
| `Flare.Socket` | State container — holds assigns and commands |
| `Flare.Channel` | Phoenix Channel — handles all WebSocket messages |
| `Flare.UserState` | GenServer per user — caches state, broadcasts global changes |
| `Flare.Diff` | Pure flat-map diffing — computes what changed |
| `Flare.Router` | Maps page name strings to view modules |
| `Flare.Layout` | Builds JSON envelopes sent to clients |
| `Flare.Commands` | Helper functions that attach commands to a socket |
| `Flare.Lifecycle` | Safely executes view callbacks with error formatting |
| `Flare.StateLoader` | Behaviour for loading state from your database |
| `Flare.Application` | Starts Registry, UserSupervisor, and PubSub |
| `Flare.Logger` | Internal logger — controlled by `enable_logging` config |

---

## Complete Example

A login screen that authenticates and navigates to the store:

```elixir
defmodule MyApp.Login do
  use Flare.View

  view_files __DIR__

  @impl true
  def mount(_params, socket) do
    {:ok, assign(socket,
      flare_error_message: "",
      flare_show_error:    false
    )}
  end

  @impl true
  def handle_event("submit_login", %{"email" => email, "password" => pass}, socket) do
    case MyApp.Accounts.authenticate(email, pass) do
      {:ok, user_id} ->
        token = Phoenix.Token.sign(MyApp.Endpoint, "user_auth", user_id)
        socket
        |> assign(:flare_show_error, false)
        |> store_token(token)
        |> navigate("store")
        |> haptic(:success)
        |> then(&{:noreply, &1})

      {:error, :invalid_credentials} ->
        socket
        |> assign(:flare_show_error,    true)
        |> assign(:flare_error_message, "Invalid email or password")
        |> haptic(:error)
        |> then(&{:noreply, &1})
    end
  end
end
```

---

## What Flare Does Not Do

- **No database access** — Flare never touches your database directly. You control all data access through your own modules and the optional `StateLoader` behaviour.
- **No HTML rendering** — Flare sends JSON to DivKit. There are no templates, no HTML generation, no CSS.
- **No routing for HTTP requests** — Flare handles WebSocket channels only. Your Phoenix router handles HTTP separately.
- **No list diffing** — If a `flare_` assigned list changes, Flare sends the entire list. Paginate server-side for large lists and send one page at a time.

---

## Logging

Flare logs all lifecycle events internally. Control verbosity in config:

```elixir
# Show all Flare internal logs (default: true)
config :flare, enable_logging: true

# Silence info and debug logs
config :flare, enable_logging: false
```

Errors are always logged regardless of this setting.

---

## License

MIT