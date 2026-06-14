defmodule Flare.Commands do
  @moduledoc """
  Helper functions that attach client-side commands to a socket.

  Commands are instructions from the server to the client SDK. They are
  processed by the Flare client SDK in order, after state changes are applied.

  ## Usage

  All functions accept a socket and return a new socket. They chain naturally
  with the pipe operator:

      socket
      |> assign(:flare_auth_success, true)
      |> store_token(token)
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
  - `store_token/2` — store an auth token securely on the device
  - `clear_storage/1` — remove the stored auth token (logout)
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
    add_command(socket, "navigate", %{"screen" => screen, "params" => params})
  end

  @doc """
  Shows a native platform alert dialog.

  ## Example

      socket |> show_alert("Are you sure?", "This action cannot be undone.", "Delete")
  """
  def show_alert(socket, title, message, button \\ "OK") do
    add_command(socket, "show_alert", %{"title" => title, "message" => message, "button" => button})
  end

  @doc """
  Stores an authentication token securely on the client device.

  - Web: server-side session via HTTP POST (never localStorage)
  - iOS: Keychain via Flare iOS SDK
  - Android: EncryptedSharedPreferences via Flare Android SDK

  ## Example

      token = Phoenix.Token.sign(MyApp.Endpoint, "user_auth", user_id)
      socket |> store_token(token)
  """
  def store_token(socket, token) do
    add_command(socket, "store_token", %{"token" => token})
  end

  @doc """
  Clears the stored authentication token from the client device.

  Use on logout. The client will reconnect as anonymous on next app open.

  ## Example

      socket |> clear_storage()
  """
  def clear_storage(socket) do
    add_command(socket, "clear_storage", %{})
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
    add_command(socket, "haptic", %{"style" => to_string(style)})
  end

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  # All payload values use string keys so they serialize cleanly to JSON
  # without any atom-to-string conversion surprises.
  defp add_command(socket, type, payload) do
    Flare.Logger.debug(__MODULE__, "Adding command: #{type}")
    command = %{"type" => type, "payload" => payload}
    %{socket | commands: socket.commands ++ [command]}
  end
end
