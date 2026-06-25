defmodule FlareDemo.Profile do
  use Flare.Screen
  alias FlareDemo.Users.User

  screen_dir __DIR__

  @impl true
  def mount(_params, socket) do
    user = User.get(socket.user_id)

    # ⬇️ THE FIX: Fallback to "" if user is nil OR if first_name is nil!
    first = (user && user.first_name) || ""
    last = (user && user.last_name) || ""

    {:ok, assign(socket,
      flare_first_name: first,
      flare_last_name:  last,
      local_first_name: first,
      local_last_name:  last
    )}
  end

  @impl true
  def handle_event("save_profile", payload, socket) do
    first = payload |> Map.get("first_name", "") |> String.trim()
    last  = payload |> Map.get("last_name",  "") |> String.trim()

    # Update the database
    case User.update_profile(socket.user_id, first, last) do
      {:ok, _user} -> :ok
      _ -> :error
    end

    socket
    |> assign(:flare_first_name, first)
    |> assign(:flare_last_name,  last)
    |> haptic(:success)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("go_back", _payload, socket) do
    socket
    |> navigate("welcome")
    |> then(&{:noreply, &1})
  end
end
