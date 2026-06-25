defmodule FlareDemo.AdminScreen do
  use Flare.Screen

  screen_dir __DIR__

  @impl true
  def mount(_params, socket) do
    # Load live counts from DB!
    users = FlareDemo.Users.User.count_all()
    notes = FlareDemo.Notes.Note.count_all()

    {:ok, assign(socket, flare_total_users: users, flare_total_notes: notes)}
  end

  @impl true
  def handle_event("go_back", _payload, socket) do
    {:noreply, navigate(socket, "welcome")}
  end
end
