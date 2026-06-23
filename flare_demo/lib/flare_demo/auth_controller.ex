# flare_demo/lib/flare_demo/auth_controller.ex

defmodule FlareDemo.AuthController do
  use Phoenix.Controller, formats: [:json]

  # ─────────────────────────────────────────────────────────────────────────
  # POST /auth/login
  # Body: { "email": "...", "password": "..." }
  # Returns: { "token": "<signed Phoenix.Token>" }
  #
  # DEVELOPER: Replace authenticate_user/2 with your real user lookup.
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
  # Guest = real identity, just anonymously issued.
  # Same token flow as a real user — Flare treats both identically.
  guest_id = "guest_" <> Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)
  token    = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_guest", guest_id)
  json(conn, %{token: token})
end

  # ─────────────────────────────────────────────────────────────────────────
  # GET /auth/google
  #
  # DEVELOPER: Replace with your real Google OAuth setup.
  # Recommended libraries:
  #   - assent      (hex.pm/packages/assent)
  #   - ueberauth   (hex.pm/packages/ueberauth)
  #
  # The redirect_uri must match what you registered in Google Cloud Console.
  # Pattern: https://yourdomain.com/auth/google/callback
  # ─────────────────────────────────────────────────────────────────────────
  def google_redirect(conn, _params) do
    client_id    = System.get_env("GOOGLE_CLIENT_ID") ||
                     raise "Missing env: GOOGLE_CLIENT_ID"
    redirect_uri = System.get_env("GOOGLE_REDIRECT_URI") ||
                     raise "Missing env: GOOGLE_REDIRECT_URI"
    # e.g. "https://yourdomain.com/auth/google/callback"
    # In dev:      "http://localhost:4000/auth/google/callback"

    google_url =
      "https://accounts.google.com/o/oauth2/v2/auth?" <>
      URI.encode_query(%{
        client_id:     client_id,
        redirect_uri:  redirect_uri,
        response_type: "code",
        scope:         "openid email profile",
        access_type:   "online"
      })

    redirect(conn, external: google_url)
  end

  # ─────────────────────────────────────────────────────────────────────────
  # GET /auth/google/callback
  # Google redirects here with ?code=XXX after user approves.
  #
  # DEVELOPER: Implement exchange_google_code/1 below with your real logic.
  # ─────────────────────────────────────────────────────────────────────────
  def google_callback(conn, %{"code" => code}) do
    case exchange_google_code(code) do
      {:ok, user_id} ->
        token = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_session", user_id)
        # index.html reads ?token= on load and starts Flare
        redirect(conn, to: "/?token=#{token}")

      {:error, reason} ->
        redirect(conn, to: "/?auth_error=#{URI.encode(reason)}")
    end
  end

  # ─────────────────────────────────────────────────────────────────────────
  # GET /auth/keycloak
  #
  # DEVELOPER: Set these env vars:
  #   KEYCLOAK_URL         = https://auth.yourdomain.com
  #   KEYCLOAK_REALM       = your-realm
  #   KEYCLOAK_CLIENT_ID   = your-client-id
  #   KEYCLOAK_REDIRECT_URI = https://yourdomain.com/auth/keycloak/callback
  # ─────────────────────────────────────────────────────────────────────────
  def keycloak_redirect(conn, _params) do
    base_url     = System.get_env("KEYCLOAK_URL")          ||
                     raise "Missing env: KEYCLOAK_URL"
    realm        = System.get_env("KEYCLOAK_REALM")        ||
                     raise "Missing env: KEYCLOAK_REALM"
    client_id    = System.get_env("KEYCLOAK_CLIENT_ID")    ||
                     raise "Missing env: KEYCLOAK_CLIENT_ID"
    redirect_uri = System.get_env("KEYCLOAK_REDIRECT_URI") ||
                     raise "Missing env: KEYCLOAK_REDIRECT_URI"

    keycloak_url =
      "#{base_url}/realms/#{realm}/protocol/openid-connect/auth?" <>
      URI.encode_query(%{
        client_id:     client_id,
        redirect_uri:  redirect_uri,
        response_type: "code",
        scope:         "openid email profile"
      })

    redirect(conn, external: keycloak_url)
  end

  def keycloak_callback(conn, %{"code" => code}) do
    case exchange_keycloak_code(code) do
      {:ok, user_id} ->
        token = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_session", user_id)
        redirect(conn, to: "/?token=#{token}")

      {:error, reason} ->
        redirect(conn, to: "/?auth_error=#{URI.encode(reason)}")
    end
  end

  # ─────────────────────────────────────────────────────────────────────────
  # Private stubs — DEVELOPER: replace these with real implementations
  # ─────────────────────────────────────────────────────────────────────────

  # Stub: hardcoded demo credentials.
  # Replace with: MyApp.Accounts.authenticate(email, password)
  # which does:   Bcrypt.verify_pass(password, user.password_hash)
  defp authenticate_user("demo@example.com", "demo"), do: {:ok, "user_demo_001"}
  defp authenticate_user(_email, _password),          do: {:error, "Invalid email or password"}

  # Stub: replace with real Google token exchange.
  # Steps:
  #   1. POST https://oauth2.googleapis.com/token with code + client_secret
  #   2. GET  https://www.googleapis.com/oauth2/v3/userinfo with access_token
  #   3. Use userinfo["sub"] or userinfo["email"] as your user_id
  defp exchange_google_code(_code) do
    {:error, "Google OAuth not configured — implement exchange_google_code/1"}
  end

  # Stub: replace with real Keycloak token exchange.
  # Steps:
  #   1. POST #{base_url}/realms/#{realm}/protocol/openid-connect/token
  #   2. Decode the JWT or fetch userinfo endpoint
  #   3. Use the subject claim as your user_id
  defp exchange_keycloak_code(_code) do
    {:error, "Keycloak OAuth not configured — implement exchange_keycloak_code/1"}
  end
end
