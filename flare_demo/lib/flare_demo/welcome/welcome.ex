defmodule FlareDemo.Welcome do
  use Flare.View

  view_files __DIR__

  @impl true
  def mount(_params, socket) do
    {:ok, assign(socket,
      flare_count:      Map.get(socket.assigns, :flare_count, 0),
      flare_first_name: Map.get(socket.assigns, :flare_first_name, ""),
      flare_last_name:  Map.get(socket.assigns, :flare_last_name, "")
    )}
  end

  @impl true
  def handle_event("increment", _payload, socket) do
    current = Map.get(socket.assigns, :flare_count, 0)
    socket
    |> assign(:flare_count, current + 1)
    |> haptic(:success)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("decrement", _payload, socket) do
    current = Map.get(socket.assigns, :flare_count, 0)
    socket
    |> assign(:flare_count, max(current - 1, 0))
    |> haptic(:light)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("go_to_profile", _payload, socket) do
    socket
    |> navigate("profile")
    |> then(&{:noreply, &1})
  end
end
