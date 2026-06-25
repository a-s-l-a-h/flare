defmodule FlareDemo.DashboardScreen do
  use Flare.Screen

  screen_dir __DIR__

  @impl true
  def mount(_params, socket) do
    users = FlareDemo.Users.User.count_all()
    notes = FlareDemo.Notes.Note.count_all()

    {:ok, assign(socket, flare_total_users: users, flare_total_notes: notes)}
  end

  @impl true
  def handle_event("go_back", _payload, socket) do
    {:noreply, navigate(socket, "welcome")}
  end

  @impl true
  def handle_event("toggle_theme", _payload, socket) do
    current = socket.assigns[:flare_dark_mode] || false
    {:noreply, assign(socket, :flare_dark_mode, !current)}
  end

  @impl true
  def handle_event("logout", _payload, socket) do
    {:noreply, clear_storage(socket)}
  end
end
