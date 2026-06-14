defmodule FlareDemo.Profile do
  use Flare.View

  view_files __DIR__

  @impl true
  def mount(_params, socket) do
    {:ok, assign(socket,
      flare_first_name: Map.get(socket.assigns, :flare_first_name, ""),
      flare_last_name:  Map.get(socket.assigns, :flare_last_name, "")
    )}
  end

  @impl true
  def handle_event("save_profile", payload, socket) do
    first = payload |> Map.get("first_name", "") |> String.trim()
    last  = payload |> Map.get("last_name",  "") |> String.trim()

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
