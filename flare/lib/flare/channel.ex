# Location: flare/lib/flare/channel.ex

defmodule Flare.Channel do
  @moduledoc """
  The Phoenix Channel that handles every Flare WebSocket connection.

  Every screen maps to one channel topic: `"flare:<screen_name>"`.

  Wire it in your UserSocket:

      channel "flare:*", Flare.Channel

  ## Authentication contract

  Flare.Channel expects the host application's UserSocket to have already
  verified the client's token and assigned a non-nil user_id:

      assign(socket, :user_id, user_id)

  Flare never creates identities or issues tokens. That is the host
  application's responsibility. Both real users and guests must obtain
  a signed token from the host app (e.g. POST /auth/guest or POST /auth/login)
  BEFORE opening the WebSocket. If user_id is nil, the join is rejected.
  """

  use Phoenix.Channel

  def join("flare:" <> screen_name, params, channel_socket) do
    user_id = channel_socket.assigns[:user_id]

    Flare.Logger.info(__MODULE__, "Joining screen: #{screen_name} | user: #{inspect(user_id)}")

    # ── Hard gate ─────────────────────────────────────────────────────────────
    # user_id MUST be set by the host app's UserSocket.connect/3 before we
    # get here. Flare is a library — it never issues tokens or creates
    # identities. Both guests and real users must go through the host app's
    # auth endpoints first.
    #
    # If user_id is nil here, the host app's UserSocket allowed an
    # unauthenticated connection through — that is a misconfiguration.
    # Reject immediately with a clear error so the developer knows what to fix.
    # ─────────────────────────────────────────────────────────────────────────
    if is_nil(user_id) do
      Flare.Logger.error(
        __MODULE__,
        "Rejected join for screen '#{screen_name}' — user_id is nil. " <>
        "Your UserSocket.connect/3 must verify the token and assign :user_id. " <>
        "See Flare.Channel moduledoc for the authentication contract."
      )
      {:error, %{"reason" => "authentication_required"}}
    else
      do_join(screen_name, params, channel_socket, user_id)
    end
  end

  defp do_join(screen_name, params, channel_socket, user_id) do
    # ── Start UserState for this user ─────────────────────────────────────────
    # ensure_started is idempotent — safe to call even if already running.
    # Must happen before get_all/1 below.
    Flare.UserState.ensure_started(user_id)

    # Subscribe to PubSub topics for this user and screen
    Phoenix.PubSub.subscribe(Flare.PubSub, "user:#{user_id}")
    Phoenix.PubSub.subscribe(Flare.PubSub, "screen:#{user_id}:#{screen_name}")

    # Broadcasts to all users on this screen (no user_id needed for these)
    Phoenix.PubSub.subscribe(Flare.PubSub, "broadcast:#{screen_name}")

    router = Application.get_env(:flare, :router) ||
      raise "Missing config: config :flare, router: MyApp.FlareRouter"
    screen_module = router.screen_for!(screen_name)

    # Restore saved state for this user.
    # Guests and real users are treated identically here —
    # user_id is user_id regardless of how it was obtained.
    saved_assigns = Flare.UserState.get_all(user_id)
    Flare.Logger.info(__MODULE__, "Restored assigns for #{user_id}: #{inspect(Map.keys(saved_assigns))}")

    flare_socket = %Flare.Socket{
      user_id:       user_id,
      screen_module: screen_module,
      screen_name:   screen_name,
      assigns:       saved_assigns,
      commands:      []
    }

    case Flare.Lifecycle.mount(screen_module, params, flare_socket) do
      {:ok, mounted_socket} ->
        envelope = Flare.Layout.build_init_envelope(screen_module, screen_name, mounted_socket.assigns)
        new_channel_socket = assign(channel_socket, :flare_socket, mounted_socket)
        send(self(), {:push_init, envelope})
        {:ok, new_channel_socket}

      {:error, reason} ->
        Flare.Logger.error(__MODULE__, "mount/2 rejected join for screen #{screen_name}", reason)
        {:error, %{"reason" => "mount_rejected", "detail" => inspect(reason)}}
    end
  end

  def handle_in("event", %{"screen" => _screen, "type" => type, "payload" => payload}, channel_socket) do
    flare_socket = channel_socket.assigns.flare_socket
    old_assigns  = flare_socket.assigns

    flare_socket = %{flare_socket | commands: []}

    case Flare.Lifecycle.handle_event(flare_socket.screen_module, type, payload, flare_socket) do
      {:noreply, new_flare_socket} ->
        diff     = Flare.Diff.compute(old_assigns, new_flare_socket.assigns)
        commands = new_flare_socket.commands

        channel_socket = push_diff_and_commands(channel_socket, diff, commands)

        # :ok reply tells the client push().receive("ok") to fire.
        # This is the WebSocket equivalent of HTTP 200.
        {:reply, :ok, assign(channel_socket, :flare_socket, new_flare_socket)}

      {:error, reason} ->
        # Developer's handle_event returned an error.
        # Client push().receive("error") fires with the reason.
        Flare.Logger.error(__MODULE__, "handle_event returned error for #{type}", reason)
        {:reply, {:error, %{"reason" => inspect(reason)}}, channel_socket}
    end
  end

  # Malformed event — log and ignore rather than crash
  def handle_in("event", bad_payload, channel_socket) do
    Flare.Logger.error(__MODULE__, "Malformed event payload", bad_payload)
    {:noreply, channel_socket}
  end

  def handle_in(unknown, _payload, channel_socket) do
    Flare.Logger.error(__MODULE__, "Unknown message type: #{unknown}")
    {:noreply, channel_socket}
  end

  # Push INIT after join returns (client is now subscribed).
  # No extra commands here — token was issued by the host app over HTTP
  # before the WebSocket opened. No store_token round trip needed.
  def handle_info({:push_init, envelope}, socket) do
    push(socket, "init", envelope)
    {:noreply, socket}
  end

  # Global state from UserState PubSub (another screen changed a shared key)
  def handle_info({:state_update, global_diff}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Global state update received")
    flare_socket = channel_socket.assigns.flare_socket

    new_assigns      = Map.merge(flare_socket.assigns, global_diff)
    new_flare_socket = %{flare_socket | assigns: new_assigns}

    envelope = Flare.Layout.build_patch_envelope(flare_socket.screen_name, global_diff)
    push(channel_socket, "patch", envelope)

    {:noreply, assign(channel_socket, :flare_socket, new_flare_socket)}
  end

  # Direct state push from Flare.push_to_user/3
  def handle_info({:flare_push, state_changes}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Direct push received")
    flare_socket = channel_socket.assigns.flare_socket
    old_assigns  = flare_socket.assigns

    new_assigns      = Map.merge(old_assigns, state_changes)
    new_flare_socket = %{flare_socket | assigns: new_assigns}
    diff             = Flare.Diff.compute(old_assigns, new_assigns)

    unless Flare.Diff.empty?(diff) do
      envelope = Flare.Layout.build_patch_envelope(flare_socket.screen_name, diff)
      push(channel_socket, "patch", envelope)
    end

    {:noreply, assign(channel_socket, :flare_socket, new_flare_socket)}
  end

  # Direct command push from Flare.push_command_to_user/4
  def handle_info({:flare_command, command}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Direct command received: #{command["type"]}")
    flare_socket = channel_socket.assigns.flare_socket

    envelope = Flare.Layout.build_patch_with_commands_envelope(flare_socket.screen_name, %{}, [command])
    push(channel_socket, "patch", envelope)

    {:noreply, channel_socket}
  end

  # Layout update from Flare.update_layout/2
  def handle_info({:flare_layout_update}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Layout update received")
    flare_socket = channel_socket.assigns.flare_socket

    envelope = Flare.Layout.build_layout_update_envelope(flare_socket.screen_module, flare_socket.screen_name)
    push(channel_socket, "layout_update", envelope)

    {:noreply, channel_socket}
  end

  # Broadcast to all users on this screen from Flare.broadcast_to_screen/2
  def handle_info({:flare_broadcast, state_changes}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Screen broadcast received")
    flare_socket = channel_socket.assigns.flare_socket
    old_assigns  = flare_socket.assigns

    new_assigns      = Map.merge(old_assigns, state_changes)
    new_flare_socket = %{flare_socket | assigns: new_assigns}
    diff             = Flare.Diff.compute(old_assigns, new_assigns)

    unless Flare.Diff.empty?(diff) do
      envelope = Flare.Layout.build_patch_envelope(flare_socket.screen_name, diff)
      push(channel_socket, "patch", envelope)
    end

    {:noreply, assign(channel_socket, :flare_socket, new_flare_socket)}
  end

  # Process messages and timers — routes to developer's handle_info/2
  def handle_info(message, channel_socket) do
    flare_socket = channel_socket.assigns.flare_socket
    old_assigns  = flare_socket.assigns

    flare_socket = %{flare_socket | commands: []}

    {:noreply, new_flare_socket} =
      Flare.Lifecycle.handle_info(flare_socket.screen_module, message, flare_socket)

    diff     = Flare.Diff.compute(old_assigns, new_flare_socket.assigns)
    commands = new_flare_socket.commands

    channel_socket = push_diff_and_commands(channel_socket, diff, commands)

    {:noreply, assign(channel_socket, :flare_socket, new_flare_socket)}
  end

  def terminate(reason, channel_socket) do
    Flare.Logger.info(__MODULE__, "Channel terminating: #{inspect(reason)}")

    # Save full state on EVERY disconnect, including peer_closed (OS killed socket).
    # push_diff_and_commands only saves diffs when events fire — if the connection
    # drops between events, the last in-memory state is lost without this save.
    # This is the fix for flare_clicks resetting to 0 after Android backgrounds the app.
    case channel_socket.assigns do
      %{flare_socket: %{user_id: user_id, assigns: assigns}} when not is_nil(user_id) ->
        Flare.Logger.info(__MODULE__, "Saving state on terminate for #{user_id}: #{inspect(Map.keys(assigns))}")
        Flare.UserState.save(user_id, assigns)
      _ ->
        :ok
    end

    :ok
  end

  defp push_diff_and_commands(channel_socket, diff, commands) do
    flare_socket = channel_socket.assigns.flare_socket
    screen_name  = flare_socket.screen_name
    global_keys  = Application.get_env(:flare, :global_keys, [])

    {global_diff, screen_diff} = Flare.Diff.split(diff, global_keys)

    # Global keys are broadcast via UserState → PubSub → all open screens.
    # But we must also update this channel's own flare_socket.assigns so the
    # next diff cycle doesn't see these global values as "changed" again.
    updated_channel_socket =
      unless Flare.Diff.empty?(global_diff) do
        Flare.UserState.update(flare_socket.user_id, global_diff)
        # Merge global changes back into this socket's assigns to keep in sync
        updated_assigns      = Map.merge(flare_socket.assigns, global_diff)
        updated_flare_socket = %{flare_socket | assigns: updated_assigns}
        assign(channel_socket, :flare_socket, updated_flare_socket)
      else
        channel_socket
      end

    unless Flare.Diff.empty?(screen_diff) do
      Flare.UserState.save(flare_socket.user_id, screen_diff)
    end

    has_diff = not Flare.Diff.empty?(screen_diff)
    has_cmds = length(commands) > 0

    if has_diff or has_cmds do
      envelope = Flare.Layout.build_patch_with_commands_envelope(screen_name, screen_diff, commands)
      push(updated_channel_socket, "patch", envelope)
    end

    updated_channel_socket
  end
end
