defmodule FlareDemo.Welcome do
  use Flare.Screen
  alias FlareDemo.Users.User

  screen_dir __DIR__
  # 2. Add this line to turn off caching for this screen
  #use_cache false
  @impl true
  def mount(_params, socket) do
    user = User.get(socket.user_id)
    is_admin = User.get_role(socket.user_id) == "admin"

    first_name = (user && user.first_name) || "Guest"
    last_name = (user && user.last_name) || ""

    socket
    |> assign(flare_first_name: first_name, flare_last_name: last_name, flare_is_admin: is_admin)
    |> ensure_theme_default()
    |> then(&{:ok, &1})
  end

  @impl true
  def handle_event("go_to_notes", _payload, socket) do
    {:noreply, navigate(socket, "notes")}
  end

  @impl true
  def handle_event("go_to_dashboard", _payload, socket) do
    {:noreply, navigate(socket, "dashboard")}
  end

  @impl true
  def handle_event("go_to_profile", _payload, socket) do
    {:noreply, navigate(socket, "profile")}
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

  defp ensure_theme_default(socket) do
    if Map.has_key?(socket.assigns, :flare_dark_mode) do
      socket
    else
      assign(socket, :flare_dark_mode, false)
    end
  end
end
