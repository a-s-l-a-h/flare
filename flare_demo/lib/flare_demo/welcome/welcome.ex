defmodule FlareDemo.Welcome do
  use Flare.Screen
  alias FlareDemo.Users.User

  screen_dir __DIR__

  @impl true
  def mount(_params, socket) do
    user = User.get(socket.user_id)
    is_admin = User.get_role(socket.user_id) == "admin"

    # ⬇️ THE FIX: Fallback to "Guest" if first_name is nil
    first_name = (user && user.first_name) || "Guest"

    {:ok, assign(socket,
      flare_first_name: first_name,
      flare_is_admin: is_admin
    )}
  end

  @impl true
  def handle_event("go_to_notes", _payload, socket) do
    {:noreply, navigate(socket, "notes")}
  end

  @impl true
  def handle_event("go_to_admin", _payload, socket) do
    {:noreply, navigate(socket, "admin")}
  end

  @impl true
  def handle_event("go_to_profile", _payload, socket) do
    {:noreply, navigate(socket, "profile")}
  end
end
