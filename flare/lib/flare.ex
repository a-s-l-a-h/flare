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

      # Push a command to one user's screen
      Flare.push_command_to_user("user_123", "store", "show_alert", %{
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
  Pushes a single command to a specific user's specific screen.

  ## Example

      Flare.push_command_to_user("user_123", "store", "navigate", %{screen: "cart"})
  """
  def push_command_to_user(user_id, screen_name, command_type, payload \\ %{}) do
    command = %{"type" => command_type, "payload" => stringify_keys(payload)}

    Phoenix.PubSub.broadcast(
      Flare.PubSub,
      "screen:#{user_id}:#{screen_name}",
      {:flare_command, command}
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
  # Private
  # ---------------------------------------------------------------------------

  defp stringify_keys(map) when is_map(map) do
    Map.new(map, fn {k, v} -> {to_string(k), v} end)
  end
end
