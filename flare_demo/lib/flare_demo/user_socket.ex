defmodule FlareDemo.UserSocket do
  use Phoenix.Socket

  channel "flare:*", Flare.Channel

  # ─────────────────────────────────────────────────────────────────────────
  # connect/3 — called once per WebSocket connection before any channel joins.
  #
  # THREE CASES handled in priority order:
  #
  #   1. Token sent + valid real-user token  → restore real user session
  #   2. Token sent + valid guest token      → restore guest session
  #   3. Token sent but invalid/expired      → treat as anonymous (nil)
  #   4. No token sent at all               → treat as anonymous (nil)
  #
  # For cases 3 and 4: channel.ex will generate a new signed guest token
  # and send it back to the client via the store_token command in the init
  # envelope. The client stores it and sends it on every future connection.
  #
  # WHY WE NEVER REJECT (always return {:ok, ...}):
  #   Rejecting a connection on bad token would lock out legitimate users
  #   after a server secret key rotation. Treating bad tokens as anonymous
  #   is safer — worst case they lose their session, not their connection.
  #
  # WHY WE DON'T GENERATE GUEST IDs HERE:
  #   user_socket.ex cannot push messages to the client — only channels can.
  #   The guest ID must be created in channel.ex where we can send store_token.
  # ─────────────────────────────────────────────────────────────────────────

  @impl true
  def connect(%{"token" => token}, socket, _connect_info) do
    # ─────────────────────────────────────────────────────────────────────────
    # SECURITY v1.0: TWO SEPARATE SALTS for two token types.
    #
    # "flare_session" salt → real authenticated user tokens
    #                         max_age: 86_400 seconds (24 hours)
    #                         A stolen real-user token expires in max 24 hours.
    #
    # "flare_guest" salt   → anonymous guest tokens
    #                         max_age: 2_592_000 seconds (30 days)
    #                         Guests get a longer window so their cart/state
    #                         survives casual multi-day browsing sessions.
    #
    # WHY TWO SALTS?
    #   If both token types used the same salt, a guest token would verify
    #   successfully as a real-user token (same signature algorithm, same salt).
    #   Different salts make the two token types cryptographically incompatible —
    #   the server will reject a guest token even if the signature is intact,
    #   because the salt won't match "flare_session".
    #
    # VERIFICATION ORDER:
    #   We try "flare_session" first. If that fails for ANY reason (wrong salt,
    #   expired, corrupted), we fall through to try "flare_guest". This means:
    #     - Real users: verified on first try, fast path
    #     - Guest users: one extra verification call, still fast
    #     - Attackers with forged tokens: both calls fail, treated as anonymous
    # ─────────────────────────────────────────────────────────────────────────

    # STEP 1: Try to verify as a real authenticated user (24-hour expiry)
    case Phoenix.Token.verify(FlareDemo.Endpoint, "flare_session", token, max_age: 86_400) do
      {:ok, user_id} ->
        # Valid real-user token, not expired — restore their authenticated session
        {:ok, assign(socket, :user_id, user_id)}

      {:error, :expired} ->
        # Real-user token has expired (>24 hours old).
        # Treat as anonymous — channel.ex will issue a fresh guest token.
        # In a production app with login, you would instead return {:error, :expired}
        # and redirect the client to the login screen. For now this is safe because
        # it prevents permanent access with a stolen token while not crashing the UX.
        require Logger
        Logger.warning("[FlareSocket] Real-user session token expired — treating as anonymous")
        {:ok, assign(socket, :user_id, nil)}

      {:error, _reason} ->
        # Not a real-user token (wrong salt, corrupted, etc.)
        # STEP 2: Try verifying as a guest token (30-day expiry, different salt)
        case Phoenix.Token.verify(FlareDemo.Endpoint, "flare_guest", token, max_age: 2_592_000) do
          {:ok, guest_id} ->
            # Valid, non-expired guest token — restore their guest session
            # (their saved state like cart, click count etc. will be loaded by channel.ex)
            {:ok, assign(socket, :user_id, guest_id)}

          {:error, :expired} ->
            # Guest token older than 30 days — start a completely fresh guest session.
            # Their old guest state is effectively abandoned, which is intentional:
            # 30 days of inactivity means we don't need to preserve their session.
            require Logger
            Logger.info("[FlareSocket] Guest token expired (>30 days) — starting fresh guest session")
            {:ok, assign(socket, :user_id, nil)}

          {:error, reason} ->
            # Token is corrupted, forged, or signed by a different server secret.
            # Treat as anonymous — channel.ex will issue a new valid guest token.
            # This also gracefully handles server secret key rotation.
            require Logger
            Logger.warning("[FlareSocket] Invalid token rejected (#{inspect(reason)}) — treating as anonymous")
            {:ok, assign(socket, :user_id, nil)}
        end
    end
  end

  # ─────────────────────────────────────────────────────────────────────────
  # No token sent — absolute first connection ever from this browser/device.
  # Assign nil so channel.ex knows to generate and sign a fresh guest token,
  # which it will send back via the store_token command in the init envelope.
  # Every future connection from this client will hit the clause above instead.
  # ─────────────────────────────────────────────────────────────────────────
  def connect(_params, socket, _connect_info) do
    {:ok, assign(socket, :user_id, nil)}
  end

  @impl true
  def id(_socket), do: nil
end
