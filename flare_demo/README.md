
# 🔥 Flare Demo App

This is an advanced demonstration of the **Flare SDUI** framework. It showcases server-driven UI, authentication, cross-screen state persistence, and native/web client parity using Phoenix and DivKit.

## Quick Start

1. **Install dependencies and build assets:**
   ```bash
   mix setup
   ```

2. **Start the Phoenix server:**
   You can start the server with custom admin credentials by passing them as environment variables in the terminal:
   
   ```bash
   ADMIN_EMAIL=admin@myapp.com ADMIN_PASSWORD=supersecret mix phx.server
   ```
   *(Or run inside IEx to access the interactive shell: `ADMIN_EMAIL=admin@myapp.com ADMIN_PASSWORD=supersecret iex -S mix phx.server`)*

3. **Visit the app:**
   Open [`localhost:4000`](http://localhost:4000) in your browser.

## Demo Login Details

When you launch the app, you will be presented with a login screen. You can choose to **Continue as Guest**, or log in using the credentials you provided in the terminal.

If you ran standard `mix phx.server` without specifying credentials, it falls back to the default demo account:
* **Email:** `demo@example.com`
* **Password:** `demo`

*(Note: These credentials bypass a real database check in `AuthController` for demonstration purposes. A local SQLite database is provisioned in the project to easily extend this into real DB-backed authentication later).*

## Exploring the Demo

* **Web Routing:** Notice how navigating to the "Profile" screen dynamically updates the browser URL (via `flare-client.js` history pushState) without reloading the page.
* **State Persistence:** Try incrementing the counter on the Welcome screen, navigating to the Profile screen to set a name, and navigating back. The counter state remains intact in RAM (`Flare.UserState`).
* **Optimistic UI / Pending States:** Watch the buttons dim immediately when clicked. This is handled entirely by the client via the `local_flare_pending_<action>` variable system before the server even responds.

---

## Learn more about Phoenix

* Official website: https://www.phoenixframework.org/
* Guides: https://hexdocs.pm/phoenix/overview.html
* Docs: https://hexdocs.pm/phoenix
