defmodule FlareAppTemplate.FlareApp.Screens.Home do
  @moduledoc """
  Plain welcome screen — no DB reads here on purpose, so it's obvious this
  screen is just navigation + the dark-mode toggle. All the "read from DB /
  write to DB" logic lives in the Counter screen.
  """
  use Flare.Screen
  screen_dir __DIR__
  use_cache true # set false in development time

  @impl true
  def mount(_params, socket) do
    {:ok, assign(socket, :flare_greeting, greeting_for(socket.user_id))}
  end

  @impl true
  def handle_event("go_to_counter", _payload, socket) do
    {:noreply, navigate(socket, "counter")}
  end

  @impl true
  def handle_event("logout", _payload, socket) do
    {:noreply, clear_storage(socket)}
  end

  defp greeting_for("guest_" <> _), do: "Welcome, guest 👋"
  defp greeting_for(_user_id), do: "Welcome back 👋"
end
