defmodule Flare do
  @moduledoc """
  Flare — Server-Driven UI library for Phoenix + DivKit.

  ## Public API

  These functions let you push updates to connected clients from anywhere in
  your application — background jobs, LiveDashboard, external webhooks, etc.

      # Push a state change to one user's specific screen
      Flare.push_to_user("user_123", "store", %{flare_cart_count: 4})

      # Sync a value to that SAME user's own other screens/devices — call
      # this explicitly wherever you decide it's needed
      Flare.sync_user_state("user_123", %{flare_theme: "dark"})

      # Sync several values at once
      Flare.sync_user_state("user_123", %{flare_theme: "dark", flare_active_tab: "counter"})

      # Atomic form — safe when two of that user's own devices might race
      # on the same key (counters, accumulators)
      Flare.sync_user_state("user_123", fn data ->
        %{flare_cart_count: Map.get(data, :flare_cart_count, 0) + 1}
      end)

      # Push a directive to one client connection
      Flare.push_directive_to_client("user_123", "store", "show_alert", %{
        title: "Order shipped!",
        message: "Your order is on the way."
      })

      # Reload the layout for one user (after a deployment)
      Flare.update_layout("user_123", "store")

      # Broadcast a state change to all users currently on a screen
      Flare.broadcast_to_screen("store", %{flare_sale_active: true})
  """

  # ---------------------------------------------------------------------------
  # Sync state to ONE user's own open screens/devices — never other users.
  # Call this explicitly whenever YOU decide a value should be visible
  # everywhere that user currently is. Not automatic, not called on every
  # assign — a plain assign() stays local to the current screen/device
  # unless you call this.
  # ---------------------------------------------------------------------------

  @doc """
  Updates flare_ values for a user and pushes the change to every screen
  that SAME user currently has open — other tabs, other devices, any
  screen name. Scoped strictly to this one user_id; it cannot reach any
  other user's connections (see Flare.UserState.update/2 for why).

  You always name the exact key(s) you want synced — there is no "sync
  everything" mode. Pass one key, or several, in either form:

  1. MAP — when you already know the final value(s):

        # one variable
        Flare.sync_user_state("user_123", %{flare_theme: "dark"})

        # several variables in one call
        Flare.sync_user_state("user_123", %{
          flare_theme: "dark",
          flare_active_tab: "counter"
        })

  2. FUNCTION (1-arity) — when a new value depends on the current one
     (a counter, an accumulator). Runs inside this user's UserState
     process, so if this same user has two devices calling it around the
     same time, they're processed one at a time — the second call sees
     the first call's result, not a stale value. Return a map from the
     function with one key or several, same as above:

        Flare.sync_user_state("user_123", fn data ->
          count = Map.get(data, :flare_cart_count, 0)
          %{flare_cart_count: count + 1}
        end)

     Do NOT split the read and the write into two steps yourself — that
     brings back the exact race this form exists to avoid:

        # BAD — two devices can both read the same stale count:
        count = Flare.UserState.get_all(user_id)[:flare_cart_count] || 0
        Flare.sync_user_state(user_id, %{flare_cart_count: count + 1})

  Call this only where you actually want the value to reach this user's
  other screens/devices — most flare_ values are screen-local and should
  just use plain assign/2,3 with no call to this function at all.
  """
  def sync_user_state(user_id, changes) when is_map(changes) do
    Flare.UserState.update(user_id, changes)
  end

  def sync_user_state(user_id, fun) when is_function(fun, 1) do
    Flare.UserState.update_with(user_id, fun)
  end

  # ---------------------------------------------------------------------------
  # Push state changes to a single user's specific screen
  # ---------------------------------------------------------------------------

  @doc """
  Pushes state changes to a specific user's specific screen.

  The user must be connected and the screen must be open. If the user is not
  connected the call is a no-op (returns `:ok`).

  ## Example

      Flare.push_to_user("user_123", "store", %{flare_cart_count: 4})
  """
  def push_to_user(user_id, screen_name, state_changes) when is_map(state_changes) do
    Phoenix.PubSub.broadcast(
      Flare.PubSub,
      "screen:#{user_id}:#{screen_name}",
      {:flare_push, state_changes}
    )
  end

  # ---------------------------------------------------------------------------
  # Push a command to a single user's specific screen
  # ---------------------------------------------------------------------------

  @doc """
  Pushes a single directive to one specific client connection (a given
  user_id's currently-open screen_name).

  ## Example

      Flare.push_directive_to_client("user_123", "store", "navigate", %{screen: "cart"})
  """
  def push_directive_to_client(user_id, screen_name, directive_type, payload \\ %{}) do
    directive = %{"type" => directive_type, "payload" => stringify_keys(payload)}

    Phoenix.PubSub.broadcast(
      Flare.PubSub,
      "screen:#{user_id}:#{screen_name}",
      {:flare_directive, directive}
    )
  end

  # ---------------------------------------------------------------------------
  # Push a layout update to a single user's specific screen
  # ---------------------------------------------------------------------------

  @doc """
  Pushes a layout update to a specific user's specific screen.

  Used after deployments to refresh the UI without disconnecting the user.
  The client preserves current variable values across the layout update.

  ## Example

      Flare.update_layout("user_123", "store")
  """
  def update_layout(user_id, screen_name) do
    Phoenix.PubSub.broadcast(
      Flare.PubSub,
      "screen:#{user_id}:#{screen_name}",
      {:flare_layout_update}
    )
  end

  # ---------------------------------------------------------------------------
  # Broadcast state changes to ALL users currently on a screen
  # ---------------------------------------------------------------------------

  @doc """
  Broadcasts state changes to every user currently viewing a specific screen.

  Useful for global events like flash sales, announcements, or live counters.

  ## Example

      Flare.broadcast_to_screen("store", %{flare_sale_active: true})
  """
  def broadcast_to_screen(screen_name, state_changes) when is_map(state_changes) do
    Phoenix.PubSub.broadcast(
      Flare.PubSub,
      "broadcast:#{screen_name}",
      {:flare_broadcast, state_changes}
    )
  end

  @doc """
  Broadcasts state changes to every user currently on a screen whose
  topic/2 callback resolves to this exact topic string. Use this instead
  of broadcast_to_screen/2 when a screen needs per-instance scoping
  (e.g. one queue_view screen serving many different window codes).

  ## Example

      # In your screen module:
      def topic(_user_id, params), do: "window:\#{params["code"]}"

      # Anywhere else in your app:
      Flare.broadcast_to_topic("window:AH7K2P", %{flare_count: 4})
  """
  def broadcast_to_topic(topic, state_changes) when is_map(state_changes) do
    Phoenix.PubSub.broadcast(
      Flare.PubSub,
      "broadcast:#{topic}",
      {:flare_broadcast, state_changes}
    )
  end

  # ---------------------------------------------------------------------------
  # cross_device_update — sync a state change to THIS SAME user's other open
  # connections. Different from global_keys (which auto-syncs specific keys on
  # every assign) and different from broadcast_to_screen/topic (which reaches
  # OTHER users). This is an explicit, per-call, same-user-only push.
  # ---------------------------------------------------------------------------

  @doc """
  Updates the given socket's own assigns AND pushes the same values to this
  SAME user's OTHER open connections (another tab, another device). Never
  reaches any other user — same scoping guarantee as push_to_user/3 and
  sync_user_state/2, just with a choice of how wide within this one user's
  connections to go.

  Plain in-memory PubSub message passing under the hood — no ETS, no disk,
  no database.

  Returns the UPDATED socket, so call it right before `{:noreply, socket}`.

  ## target (default: :same_screen)

    - `:same_screen` — only this user's OTHER connections on the SAME
      screen_name as this socket (e.g. "counter" open on another device).
      Uses the existing push_to_user/3 path — zero new wiring involved.
    - `"screen_name"` or `["a", "b"]` — also push to specific named other
      screens for this same user (uses push_to_user/3 for each).
    - `:auto` — push to every screen this user has open; each screen only
      applies the keys it actually declares in its own state/<screen>.json.
      A screen that doesn't declare a given key ignores the message
      entirely — no wasted patch, no stray variable created there. This is
      the "find the right screens automatically" option.

  ## Example

      def handle_event("increment", _payload, socket) do
        {:ok, counter} = CounterRecord.get_or_create_for_owner(socket.user_id)
        {:ok, updated} = CounterRecord.increment(counter)

        {:noreply,
          Flare.cross_device_update(socket, %{
            flare_count: updated.current_count,
            flare_last_updated: format_time(updated.last_updated_at)
          }, :auto)}
      end
  """
  def cross_device_update(socket, changes, target \\ :same_screen) when is_map(changes) do
    push_cross_device(socket.user_id, socket.screen_name, changes, target)
    Flare.Socket.assign(socket, Map.to_list(changes))
  end

  # broadcast_from/4 excludes the CALLING process from receiving its own
  # message. cross_device_update always runs inside the originating
  # channel process (called from handle_event/3), so self() here IS that
  # channel — this stops it from double-patching itself. The normal
  # handle_in("event", ...) → push_diff_and_commands flow already pushes
  # this same screen's own patch; only OTHER connections need this broadcast.
  defp push_cross_device(user_id, screen_name, changes, :same_screen) do
    Phoenix.PubSub.broadcast_from(
      Flare.PubSub, self(), "screen:#{user_id}:#{screen_name}", {:flare_push, changes}
    )
  end

  defp push_cross_device(user_id, _screen_name, changes, :auto) do
    Phoenix.PubSub.broadcast_from(
      Flare.PubSub, self(), "user:#{user_id}", {:flare_auto_sync, changes}
    )
  end

  defp push_cross_device(user_id, _screen_name, changes, screens) when is_list(screens) do
    Enum.each(screens, fn screen ->
      Phoenix.PubSub.broadcast_from(
        Flare.PubSub, self(), "screen:#{user_id}:#{screen}", {:flare_push, changes}
      )
    end)
  end

  defp push_cross_device(user_id, _screen_name, changes, screen) when is_binary(screen) do
    Phoenix.PubSub.broadcast_from(
      Flare.PubSub, self(), "screen:#{user_id}:#{screen}", {:flare_push, changes}
    )
  end

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  defp stringify_keys(map) when is_map(map) do
    Map.new(map, fn {k, v} -> {to_string(k), v} end)
  end
end
