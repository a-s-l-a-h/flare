# Location: flare/lib/flare/channel.ex

defmodule Flare.Channel do
  @moduledoc """
  The Phoenix Channel that handles every Flare WebSocket connection.

  Every screen maps to one channel topic: `"flare:<page_name>"`.

  Wire it in your UserSocket:

      channel "flare:*", Flare.Channel
  """

  use Phoenix.Channel

  def join("flare:" <> page_name, params, channel_socket) do
    # user_id from UserSocket — nil means first ever connection, no token sent.
    # For returning guests: already a "guest_XXX" string (verified by signature).
    # For real users: whatever ID was signed into their auth token.
    user_id = channel_socket.assigns[:user_id]
    Flare.Logger.info(__MODULE__, "Joining screen: #{page_name} | user: #{inspect(user_id)}")

    # ── Determine the final user identity ──────────────────────────────────
    # If user_id is nil (first ever connection), we generate and sign a guest
    # ID RIGHT NOW — before loading state — so get_all/1 uses the correct ID.
    # If user_id is already set, use it as-is (no new token needed).
    {final_user_id, guest_command} =
      if is_nil(user_id) do
        # Generate a cryptographically random guest ID
        gid = "guest_" <> Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)

        # Sign it with Phoenix.Token so the client cannot forge or tamper with it.
        # The client will store this signed string and send it on every future connect.
        # UserSocket.connect/3 will verify the signature and extract the guest ID.
        endpoint = Application.get_env(:flare, :endpoint) ||
          raise """
          Flare: missing endpoint config.
          Add this to config/config.exs:
              config :flare, endpoint: MyApp.Endpoint
          """
        # ─────────────────────────────────────────────────────────────────────────
        # SECURITY FIX v1.0: Guest tokens are now signed with the "flare_guest"
        # salt, NOT "flare_session".
        #
        # This salt separation is critical:
        #   - "flare_guest"   is verified with max_age: 2_592_000 (30 days)
        #   - "flare_session" is verified with max_age: 86_400    (24 hours)
        #
        # If both used "flare_session", a guest token would expire in 24 hours,
        # locking anonymous users out of their cart/session data every day.
        # Guest tokens intentionally live longer — 30 days without activity means
        # the user has likely abandoned the session anyway.
        #
        # A malicious user CANNOT use a guest token as a real-user token because
        # user_socket.ex verifies them with different salts in sequence.
        # ─────────────────────────────────────────────────────────────────────────
        signed = Phoenix.Token.sign(endpoint, "flare_guest", gid)

        # store_token command — client SDK will call localStorage.setItem / SharedPreferences
        command = %{"type" => "store_token", "payload" => %{"token" => signed}}
        {gid, [command]}
      else
        # Returning user — we already have a verified identity, no new token needed.
        {user_id, []}
      end

    # ── Start UserState AFTER we know the final user_id ────────────────────
    # This is the critical ordering fix.
    # The old broken version called ensure_started(nil) then loaded state for nil
    # (empty map), then created a new guest ID — so state was always empty.
    # Now we have final_user_id before touching UserState.
    Flare.UserState.ensure_started(final_user_id)

    # Subscribe to PubSub topics for this user and screen
    Phoenix.PubSub.subscribe(Flare.PubSub, "user:#{final_user_id}")
    Phoenix.PubSub.subscribe(Flare.PubSub, "screen:#{final_user_id}:#{page_name}")

    # Broadcasts to all users on this screen (no user_id needed for these)
    Phoenix.PubSub.subscribe(Flare.PubSub, "broadcast:#{page_name}")

    router      = Application.get_env(:flare, :router) ||
      raise "Missing config: config :flare, router: MyApp.FlareRouter"
    view_module = router.view_for!(page_name)

    # Restore saved state for this user.
    # Now uses final_user_id — so returning guests get their saved count back.
    # New guests get %{} (empty) which is correct — no previous state exists.
    saved_assigns = Flare.UserState.get_all(final_user_id)
    Flare.Logger.info(__MODULE__, "Restored assigns for #{final_user_id}: #{inspect(Map.keys(saved_assigns))}")

    flare_socket = %Flare.Socket{
      user_id:  final_user_id,
      view:     view_module,
      page:     page_name,
      assigns:  saved_assigns,
      commands: []
    }

    case Flare.Lifecycle.mount(view_module, params, flare_socket) do
      {:ok, mounted_socket} ->
        envelope = Flare.Layout.build_init_envelope(view_module, page_name, mounted_socket.assigns)

        # guest_command is either [] (returning user) or [store_token command] (new guest).
        # The store_token command is merged into the init envelope by handle_info below.
        # Client processes it immediately when init arrives — no separate round trip needed.
        new_channel_socket = assign(channel_socket, :flare_socket, mounted_socket)
        send(self(), {:push_init, envelope, guest_command})
        {:ok, new_channel_socket}

      {:error, reason} ->
        Flare.Logger.error(__MODULE__, "mount/2 rejected join for page #{page_name}", reason)
        {:error, %{"reason" => "mount_rejected", "detail" => inspect(reason)}}
    end
  end

  def handle_in("event", %{"screen" => _screen, "type" => type, "payload" => payload}, channel_socket) do
  flare_socket = channel_socket.assigns.flare_socket
  old_assigns  = flare_socket.assigns

  flare_socket = %{flare_socket | commands: []}

  case Flare.Lifecycle.handle_event(flare_socket.view, type, payload, flare_socket) do
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

  # Push INIT after join returns (client is now subscribed)
  # Push INIT after join returns (client is now subscribed)
  def handle_info({:push_init, envelope, extra_commands}, socket) do
    # Merge any bootstrap commands (e.g. store_token for new guests)
    # into the init envelope so client processes them immediately
    envelope_with_commands =
      if extra_commands == [] do
        envelope
      else
        Map.put(envelope, "commands", extra_commands)
      end
    push(socket, "init", envelope_with_commands)
    {:noreply, socket}
  end

  # Global state from UserState PubSub (another screen changed a shared key)
  def handle_info({:state_update, global_diff}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Global state update received")
    flare_socket = channel_socket.assigns.flare_socket

    new_assigns      = Map.merge(flare_socket.assigns, global_diff)
    new_flare_socket = %{flare_socket | assigns: new_assigns}

    envelope = Flare.Layout.build_patch_envelope(flare_socket.page, global_diff)
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
      envelope = Flare.Layout.build_patch_envelope(flare_socket.page, diff)
      push(channel_socket, "patch", envelope)
    end

    {:noreply, assign(channel_socket, :flare_socket, new_flare_socket)}
  end

  # Direct command push from Flare.push_command_to_user/4
  def handle_info({:flare_command, command}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Direct command received: #{command["type"]}")
    flare_socket = channel_socket.assigns.flare_socket

    envelope = Flare.Layout.build_patch_with_commands_envelope(flare_socket.page, %{}, [command])
    push(channel_socket, "patch", envelope)

    {:noreply, channel_socket}
  end

  # Layout update from Flare.update_layout/2
  def handle_info({:flare_layout_update}, channel_socket) do
    Flare.Logger.info(__MODULE__, "Layout update received")
    flare_socket = channel_socket.assigns.flare_socket

    envelope = Flare.Layout.build_layout_update_envelope(flare_socket.view, flare_socket.page)
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
      envelope = Flare.Layout.build_patch_envelope(flare_socket.page, diff)
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
      Flare.Lifecycle.handle_info(flare_socket.view, message, flare_socket)

    diff     = Flare.Diff.compute(old_assigns, new_flare_socket.assigns)
    commands = new_flare_socket.commands

    channel_socket = push_diff_and_commands(channel_socket, diff, commands)

    {:noreply, assign(channel_socket, :flare_socket, new_flare_socket)}
  end

  # def terminate(reason, _channel_socket) do
  #   Flare.Logger.info(__MODULE__, "Channel terminating: #{inspect(reason)}")
  #   :ok
  # end

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
    page_name    = flare_socket.page
    global_keys  = Application.get_env(:flare, :global_keys, [])

    {global_diff, page_diff} = Flare.Diff.split(diff, global_keys)

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

    unless Flare.Diff.empty?(page_diff) do
      Flare.UserState.save(flare_socket.user_id, page_diff)
    end

    has_diff = not Flare.Diff.empty?(page_diff)
    has_cmds = length(commands) > 0

    if has_diff or has_cmds do
      envelope = Flare.Layout.build_patch_with_commands_envelope(page_name, page_diff, commands)
      push(updated_channel_socket, "patch", envelope)
    end

    updated_channel_socket
  end
end
