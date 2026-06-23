defmodule FlareDemo.AuthController do
  use Phoenix.Controller, formats: [:json]

  # ─────────────────────────────────────────────────────────────────────────
  # POST /auth/login
  # Body: { "email": "...", "password": "..." }
  # Returns: { "token": "<signed Phoenix.Token>" }
  # ─────────────────────────────────────────────────────────────────────────
  def login(conn, %{"email" => email, "password" => password}) do
    case authenticate_user(email, password) do
      {:ok, user_id} ->
        token = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_session", user_id)
        json(conn, %{token: token})

      {:error, reason} ->
        conn
        |> put_status(:unauthorized)
        |> json(%{error: reason})
    end
  end

  # ─────────────────────────────────────────────────────────────────────────
  # POST /auth/guest
  # Issues a signed guest token over HTTP.
  # Same token the channel would auto-generate — just issued earlier
  # so the client has an identity before the WebSocket opens.
  # ─────────────────────────────────────────────────────────────────────────
  def guest(conn, _params) do
    guest_id = "guest_" <> Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)
    token    = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_guest", guest_id)
    json(conn, %{token: token})
  end

  # ─────────────────────────────────────────────────────────────────────────
  # DEVELOPER NOTE: OAuth (Google, Keycloak, Apple, etc.)
  # If you need third-party logins, you can implement them here.
  # We recommend using libraries like `ueberauth` or `assent`.
  # ─────────────────────────────────────────────────────────────────────────

  # ─────────────────────────────────────────────────────────────────────────
  # Private stubs — DEVELOPER: replace these with real implementations
  # ─────────────────────────────────────────────────────────────────────────

  # Stub: hardcoded demo credentials.
  # Replace with: MyApp.Accounts.authenticate(email, password)
  # which does:   Bcrypt.verify_pass(password, user.password_hash)
  defp authenticate_user("demo@example.com", "demo"), do: {:ok, "user_demo_001"}
  defp authenticate_user(_email, _password),          do: {:error, "Invalid email or password"}
end
