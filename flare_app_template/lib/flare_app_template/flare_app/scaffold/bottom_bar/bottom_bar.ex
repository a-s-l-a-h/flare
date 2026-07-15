defmodule FlareAppTemplate.FlareApp.Scaffold.BottomBar do
  use Flare.Screen
  screen_dir __DIR__
  use_cache true #set false in development time

  @impl true
  def mount(_params, socket) do
    {:ok, assign(socket, flare_bottom_bar_visible: true, flare_active_tab: "home")}
  end

  @impl true
  def handle_event("tab_tap", %{"tab" => tab}, socket) do
    socket
    |> assign(:flare_active_tab, tab)
    |> navigate(tab)
    |> then(&{:noreply, &1})
  end
end
