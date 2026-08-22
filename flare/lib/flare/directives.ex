defmodule Flare.Directives do
  @moduledoc """
  Helper functions that attach client-side directives to a socket.

  directives are instructions from the server to the client SDK. They are
  processed by the Flare client SDK in order, after state changes are applied.

  ## Usage

  All functions accept a socket and return a new socket. They chain naturally
  with the pipe operator:

      socket
      |> assign(:flare_auth_success, true)
      |> store_login_token(token)
      |> navigate("store")
      |> haptic(:success)

  ## Why commands instead of state-watching

  Commands cleanly separate instructions from state. The alternative —
  setting a `redirect_to` variable and watching for it on the client —
  mixes control flow with data, creates race conditions, and is hard to debug.

  With commands: state is data, commands are instructions. Never mixed.

  ## Available commands

  - `navigate/3` — leave current channel, join a new screen
  - `show_alert/4` — show a native platform alert dialog
  - `store_login_token/2` — store an auth token securely on the device
  - `clear_login_token/1` — remove the stored auth token (logout)
  - `haptic/2` — trigger haptic feedback on supported devices
  """

  @doc """
  Navigates the client to a different screen.

  Causes the client SDK to leave the current channel and join the new one.

  ## Example

      socket |> navigate("store")
      socket |> navigate("product", %{product_id: "prod_123"})
  """
  def navigate(socket, screen, params \\ %{}) do
    add_directive(socket, "navigate", %{"screen" => screen, "params" => params})
  end

  @doc """
  Shows a native platform alert dialog.

  ## Example

      socket |> show_alert("Are you sure?", "This action cannot be undone.", "Delete")
  """
  def show_alert(socket, title, message, button \\ "OK") do
    add_directive(socket, "show_alert", %{"title" => title, "message" => message, "button" => button})
  end

  @doc """
  Stores an authentication token securely on the client device.

  - Web: server-side session via HTTP POST (never localStorage)
  - iOS: Keychain via Flare iOS SDK
  - Android: EncryptedSharedPreferences via Flare Android SDK

  ## Example

      token = Phoenix.Token.sign(MyApp.Endpoint, "user_auth", user_id)
      socket |> store_login_token(token)
  """
  def store_login_token(socket, token) do
    add_directive(socket, "store_login_token", %{"token" => token})
  end

  @doc """
  Clears the stored authentication token from the client device.

  Use on logout. The client will reconnect as anonymous on next app open.

  ## Example

      socket |> clear_login_token()
  """
  def clear_login_token(socket) do
    add_directive(socket, "clear_login_token", %{})
  end

  @doc """
  Triggers haptic feedback on supported devices.

  `style` must be one of:
  - `:success` — positive confirmation (default)
  - `:warning` — caution feedback
  - `:error` — failure feedback
  - `:light` — subtle tap
  - `:medium` — standard tap
  - `:heavy` — strong tap

  ## Example

      socket |> haptic()             # :success
      socket |> haptic(:error)
  """
  def haptic(socket, style \\ :success) do
    add_directive(socket, "haptic", %{"style" => to_string(style)})
  end

  @doc """
      Runtime override — hides one scaffold region (`:bottom_bar`, `:top_bar`,
      `:drawer`, `:end_drawer`) without changing the screen's declared default
      in the router. Useful for transient states, e.g. hiding the bottom bar
      while a full-screen modal-like flow is active.

      ## Example

          socket |> hide_scaffold(:bottom_bar)
      """
      def hide_scaffold(socket, region) do
        add_directive(socket, "hide_scaffold", %{"region" => to_string(region)})
      end

      @doc """
      Runtime override — re-shows a scaffold region previously hidden with
      `hide_scaffold/2`.

      ## Example

          socket |> show_scaffold(:bottom_bar)
      """
      def show_scaffold(socket, region) do
        add_directive(socket, "show_scaffold", %{"region" => to_string(region)})
      end

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  # All payload values use string keys so they serialize cleanly to JSON
  # without any atom-to-string conversion surprises.
  defp add_directive(socket, type, payload) do
    Flare.Logger.debug(__MODULE__, "Adding directive: #{type}")
    directive = %{"type" => type, "payload" => payload}
    %{socket | directives: socket.directives ++ [directive]}
  end
end
