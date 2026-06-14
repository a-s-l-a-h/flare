defmodule Flare do
  @moduledoc """
  Flare — Server-Driven UI library for Phoenix + DivKit.

  ## Public API

  These functions let you push updates to connected clients from anywhere in
  your application — background jobs, LiveDashboard, external webhooks, etc.

      # Push a state change to one user's specific screen
      Flare.push_to_user("user_123", "store", %{flare_cart_count: 4})

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

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  defp stringify_keys(map) when is_map(map) do
    Map.new(map, fn {k, v} -> {to_string(k), v} end)
  end
end
