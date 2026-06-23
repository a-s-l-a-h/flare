
# Flare

Server-Driven UI library for Phoenix + DivKit. Build native Android and Web apps from one Elixir codebase.

> ⚠️ **This is a proof of concept — version 0.5, not production ready.**
> It demonstrates the SDUI architecture and core ideas. APIs will change. Use it to understand the pattern, run the demo, and build on these concepts.

---

## What is Flare?

Flare is an Elixir library that connects Phoenix Framework on the server to DivKit on the client. You write one Elixir module per screen. Flare handles WebSocket connections, state synchronisation, diffing, caching, and broadcasting. DivKit renders genuinely native UI on Android and Web from the same JSON layout file.


```

				You write Elixir
                         ↓
   Flare manages state, diffs, routing, commands
                         ↓
         Phoenix sends JSON over WebSocket
                         ↓
     DivKit renders native UI on Android / Web
                         ↓
                 User taps a button
                         ↓
Flare routes the event to your handle_event function
                         ↓
                   Cycle repeats
```

No JavaScript framework. No Kotlin knowledge required. One developer can ship a production-grade native app on multiple platforms.

---

## Why Flare Exists

Traditional cross-platform development requires separate teams and codebases for backend, Android, and web. Every UI change touches all three. App store reviews add weeks of delay.

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
│             Your Elixir App             │
│       Screen modules · DB queries       │
├─────────────────────────────────────────┤
│              Flare Library              │
│   Channel · UserState · Diff · Layout   │
├─────────────────────────────────────────┤
│            Phoenix Framework            │
│       WebSocket · PubSub · Token        │
└─────────────────────────────────────────┘
                     ↕
              WebSocket (JSON)
                     ↕
┌─────────────────────────────────────────┐
│            Flare Client SDK             │
│           Web (JS) · Android            │
├─────────────────────────────────────────┤
│                 DivKit                  │
│   Renders native UI from JSON layout    │
└─────────────────────────────────────────┘
```

### Server-Side Processes (per connected user)

```
Flare.Registry — maps user_id → GenServer PID
Flare.UserSupervisor — supervises all UserState processes
Flare.UserState — one GenServer per user, holds state in memory
Flare.PubSub — broadcasts global state between screens
```

When a user connects, Flare starts one `UserState` GenServer for them. This process:
- Caches all `flare_` prefixed state in memory
- Serves cached state instantly on screen navigation — no DB hit
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
  → splits diff into global keys and screen keys
      → global keys → UserState.update → PubSub broadcast → all screens
      → screen keys → UserState.save → pushed as "patch" to this screen
  ↓
Client receives "patch" — only changed values
  ↓
DivKit updates only the bound UI elements
```




### Wire Format

**INIT** — sent once when a screen loads:
```json
{
  "screen": "welcome",
  "layout": { "...DivKit card JSON..." },
  "variables": [
    { "name": "flare_title",  "type": "string",  "value": "" },
    { "name": "flare_clicks", "type": "integer", "value": 0 },
    { "name": "local_tab",    "type": "string",  "value": "home" }
  ],
  "state": {
    "flare_title":  "Welcome to Flare!",
    "flare_clicks": 42
  }
}

```

**PATCH** — sent after any state change:

```json
{
  "screen": "welcome",
  "state": { "flare_clicks": 43 }
}

```

**PATCH with commands:**

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

----------

## Variable Naming Convention

### `flare_` prefix — Flare manages this

-   Set using `assign/2` or `assign/3` in your screen module
-   Flare diffs them and sends only changed values to the client
-   Persist across navigation — stored in the UserState GenServer cache
-   The client reads them via DivKit bindings: `@{flare_title}`
-   The client **never writes** to these directly

```elixir
assign(socket, :flare_title, "Welcome!")
assign(socket, :flare_cart_count, 3)
assign(socket, flare_username: "Ahmad", flare_plan: "pro")

```

### `local_` prefix — client manages this

-   Defined in your `state/screen.json` file
-   Changed by DivKit actions directly — no server roundtrip
-   Flare **never** patches these
-   Never put them in `assign()` — they are client-only
-   Use for: active tab, open modal, search input text, accordion state

```json
{ "name": "local_tab",         "type": "string",  "value": "details" },
{ "name": "local_modal_open",  "type": "boolean", "value": false }

```

----------

## Dependencies

```elixir
{:phoenix,        "~> 1.8"},
{:phoenix_pubsub, "~> 2.1"},
{:jason,          "~> 1.4"}

```

No database dependency — Flare is completely agnostic about your data layer.

----------

## Installation

```elixir
def deps do
  [
    {:flare, path:  "../flare"}
  ]
end

```

Flare starts its own supervision tree automatically. You do not need to add anything to your Application supervisor.

----------

## Configuration

```elixir
config :flare,
  # Required — your router module
  router: MyApp.FlareRouter,

  # Optional — keys broadcast live to all of a user's open screens
  # When flare_cart_count changes on any screen,
  # all other open screens update automatically
  global_keys: [:flare_cart_count, :flare_unread_count],

  # Optional — GenServer idle timeout in milliseconds
  # Default: 300_000 (5 minutes)
  user_state_timeout: 300_000,

  # Optional — silence info/debug logs in production
  # Errors are always logged regardless of this setting
  enable_logging: true

```

----------

## Project Structure

```text
lib/my_app/
├── welcome/
│   ├── welcome.ex          ← your screen module (Elixir logic)
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

```

The `state/` folder is optional. If absent, the screen has no server variables declared — valid for static screens.

----------

## Creating a Screen

### 1. The screen module

```elixir
defmodule MyApp.Welcome do
  use Flare.Screen

  screen_dir __DIR__

  @impl true
  def mount(_params, socket) do
    # Previously cached flare_ values are already in socket.assigns.
    # Fetch any additional data you need from your DB here explicitly.
    {:ok, assign(socket,
      flare_title:  Map.get(socket.assigns, :flare_title, "Welcome!"),
      flare_clicks: Map.get(socket.assigns, :flare_clicks, 0)
    )}
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
{
  "variables": [
    { "name": "flare_title",  "type": "string",  "value": "" },
    { "name": "flare_clicks", "type": "integer", "value": 0 }
  ]
}

```

### 4. Register the screen

```elixir
defmodule MyApp.FlareRouter do
  use Flare.Router

  screen "welcome", MyApp.Welcome
  screen "profile", MyApp.Profile
  screen "store",   MyApp.Store
  screen "product", MyApp.Product
  screen "cart",    MyApp.Cart
end

```

### 5. Wire up the socket

```elixir
defmodule MyApp.UserSocket do
  use Phoenix.Socket

  channel "flare:*", Flare.Channel

  @impl true
  def connect(%{"token" => token}, socket, _connect_info) do
    case Phoenix.Token.verify(MyApp.Endpoint, "user_auth", token, max_age: 86_400) do
      {:ok, user_id} -> {:ok, assign(socket, :user_id, user_id)}
      {:error, :expired} -> {:error, %{reason: "session_expired"}}
      {:error, _}        -> {:error, %{reason: "invalid_token"}}
    end
  end

  # Hard reject connections without a token
  def connect(_params, _socket, _connect_info) do
    {:error, %{reason: "authentication_required"}}
  end

  @impl true
  def id(_socket), do: nil
end

```

----------

## Screen Callbacks

### `mount/2` — required

Called once when a client joins the screen. Restore state and fetch any missing data from your database here.

```elixir
def mount(params, socket) do
  # Previously cached flare_ values already in socket.assigns.
  # Fetch anything not yet cached from your DB explicitly:
  user = MyApp.Accounts.get_user!(socket.user_id)

  socket = assign(socket,
    flare_username: Map.get(socket.assigns, :flare_username, user.name),
    flare_plan:     Map.get(socket.assigns, :flare_plan, user.plan)
  )
  {:ok, socket}
end

```

### `handle_event/3` — optional

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

def handle_info(_message, socket) do
  {:noreply, socket}
end

```

----------

## The `assign` Function

`use Flare.Screen` imports `assign/2` and `assign/3` automatically.

```elixir
assign(socket, :flare_count, 5)
assign(socket, flare_count: 5, flare_name: "Ahmad", flare_plan: "pro")

socket.assigns.flare_count
socket.assigns[:flare_count]
Map.get(socket.assigns, :flare_count, 0)

```

----------

## Commands

`use Flare.Screen` imports all command functions automatically.

```elixir
socket |> navigate("store")
socket |> navigate("product", %{product_id: "prod_123"})
socket |> show_alert("Are you sure?", "This cannot be undone.", "Delete")
socket |> store_token(token)
socket |> clear_storage()
socket |> haptic()           # :success (default)
socket |> haptic(:error)
socket |> haptic(:warning)
socket |> haptic(:light)
socket |> haptic(:medium)
socket |> haptic(:heavy)

```

Chaining example:

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

----------

## Global State

Declare keys that should update live across all of a user's open screens:

```elixir
config :flare,
  global_keys: [:flare_cart_count, :flare_unread_count, :flare_user_name]

```

When a global key is assigned on any screen, Flare automatically broadcasts the new value to all other open screens via PubSub. No extra code required in your screen modules.

```elixir
# On the product screen — cart count updates on store screen automatically
socket |> assign(:flare_cart_count, new_count)

```

----------

## Pushing Updates From Outside a Screen

```elixir
Flare.push_to_user("user_123", "store", %{flare_sale_active: true})

Flare.push_command_to_user("user_123", "store", "show_alert", %{
  title: "Order shipped!",
  message: "Your order is on the way."
})

Flare.update_layout("user_123", "store")

Flare.broadcast_to_screen("store", %{flare_sale_active: true})

```

----------

## Authentication Contract

Flare expects the host application to handle identity **before** the WebSocket connects. 
Clients (both real users and guests) must obtain a signed `Phoenix.Token` from your HTTP API (e.g., `POST /auth/login` or `POST /auth/guest`), and pass it when connecting.

Flare hard-rejects any connection where `socket.assigns.user_id` is nil. Because of this, you **never** need to check `is_nil(socket.user_id)` in your `mount/2` functions. If `mount/2` is called, the user is guaranteed to be authenticated.

```elixir
# 1. Host App HTTP Endpoint (Before WebSocket opens)
def login(conn, %{"email" => email, "password" => password}) do
  {:ok, user_id} = MyApp.Accounts.authenticate(email, password)
  token = Phoenix.Token.sign(MyApp.Endpoint, "user_auth", user_id)
  json(conn, %{token: token})
end

# 2. Phoenix UserSocket (Validates the token)
def connect(%{"token" => token}, socket, _connect_info) do
  case Phoenix.Token.verify(MyApp.Endpoint, "user_auth", token, max_age: 86_400) do
    {:ok, user_id} -> {:ok, assign(socket, :user_id, user_id)}
    {:error, _}    -> {:error, %{reason: "invalid_token"}}
  end
end

def connect(_params, _socket, _connect_info) do
  {:error, %{reason: "authentication_required"}}
end

# 3. Flare Screen (Always authenticated!)
def mount(_params, socket) do
  # Safe to fetch user directly
  user = MyApp.Accounts.get_user!(socket.user_id)
  {:ok, assign(socket, flare_username: user.name)}
end

# 4. Logout (Clears storage, client disconnects and shows login UI)
def handle_event("logout", _payload, socket) do
  Flare.UserState.stop(socket.user_id)
  socket
  |> clear_storage()
  |> then(&{:noreply, &1})
end

```

----------

## The `local_flare_pending_<action>` Reserved Variables

The Flare client SDK automatically creates a pending variable for every action found in your layout. You do not declare them.

When a button fires `flare_action: "submit_form"`, the SDK sets `local_flare_pending_submit_form = true` immediately and back to `false` when the server responds.

```json
{
  "type": "text",
  "text": "@{local_flare_pending_submit_form ? 'Please wait...' : 'Submit'}",
  "alpha": "@{local_flare_pending_submit_form ? 0.5 : 1.0}",
  "action": {
    "url": "flare://action",
    "payload": { "flare_action": "submit_form" }
  }
}

```

----------

## Module Reference

Module

Purpose

`Flare`

Public API — push, broadcast, update layout

`Flare.Screen`

`use` macro injected into every screen module

`Flare.Socket`

State container — holds assigns and commands

`Flare.Channel`

Phoenix Channel — handles all WebSocket messages

`Flare.UserState`

GenServer per user — caches state in RAM, broadcasts global changes

`Flare.Diff`

Pure flat-map diffing — computes what changed

`Flare.Router`

Maps screen name strings to screen modules

`Flare.Layout`

Builds JSON envelopes sent to clients

`Flare.Commands`

Helper functions that attach commands to a socket

`Flare.Lifecycle`

Safely executes screen callbacks with error formatting

`Flare.Application`

Starts Registry, UserSupervisor, and PubSub

`Flare.Logger`

Internal logger — controlled by `enable_logging` config

----------

## Complete Example

```elixir
defmodule MyApp.Login do
  use Flare.Screen

  screen_dir __DIR__

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

----------

## What Flare Does Not Do

-   **No database access** — Flare never touches your database. You control all data access in your screen modules.
-   **No HTML rendering** — Flare sends JSON to DivKit. No templates, no HTML, no CSS.
-   **No HTTP routing** — Flare handles WebSocket channels only. Your Phoenix router handles HTTP separately.
-   **No list diffing** — If a `flare_` list changes, Flare sends the entire list. Paginate server-side for large lists.

----------

## Logging

```elixir
config :flare, enable_logging: true   # default — show all internal logs
config :flare, enable_logging: false  # silence info/debug in production

```

Errors are always logged regardless of this setting.

