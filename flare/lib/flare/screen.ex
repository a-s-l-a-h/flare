# Location: flare/lib/flare/screen.ex

defmodule Flare.Screen do
  @moduledoc """
  The behaviour and macro injected into every developer's screen module.

  Think of it like a Django class-based view — one module per screen,
  with lifecycle callbacks you only implement when needed.

  ## Usage

      defmodule MyApp.Welcome do
        use Flare.Screen

        screen_dir __DIR__

        @impl true
        def mount(_params, socket) do
          {:ok, assign(socket, flare_title: "Welcome!")}
        end

        @impl true
        def handle_event("button_clicked", _payload, socket) do
          current = socket.assigns[:flare_clicks] || 0
          {:noreply, assign(socket, flare_clicks: current + 1)}
        end
      end

  ## Callbacks

  - `mount/2` — **required**. Called once when a client joins the screen.
    Query your database here and assign values to the socket.
  - `handle_event/3` — optional. Called when the client fires a `flare_action`.
  - `handle_info/2` — optional. Called for process messages and timers.

  ## File convention

  Call `screen_dir __DIR__` directly below `use Flare.Screen`. Expected structure:

      welcome/
      ├── welcome.ex          ← your screen module
      ├── layout/
      │   └── welcome.json    ← DivKit card JSON
      └── state/
          └── welcome.json    ← variable definitions (optional)
  """

  @callback mount(params :: map(), socket :: Flare.Socket.t()) ::
                {:ok, Flare.Socket.t()} | {:error, term()}

  @callback handle_event(event :: String.t(), payload :: map(), socket :: Flare.Socket.t()) ::
                {:noreply, Flare.Socket.t()}

  @callback handle_info(message :: term(), socket :: Flare.Socket.t()) ::
                {:noreply, Flare.Socket.t()}

  defmacro __using__(_opts) do
    quote do
      @behaviour Flare.Screen
      import Flare.Socket, only: [assign: 2, assign: 3]
      import Flare.Commands
      import Flare.Screen, only: [screen_dir: 1]

      def handle_event(_event, _payload, socket), do: {:noreply, socket}
      def handle_info(_msg, socket), do: {:noreply, socket}

      defoverridable handle_event: 3, handle_info: 2
    end
  end

  @doc """
  Registers the directory where this screen's `layout/` and `state/` folders live.

      screen_dir __DIR__

  Call this once, directly below `use Flare.Screen`.
  """
  defmacro screen_dir(dir) do
    quote do
      @__screen_dir__ unquote(dir)
      @doc false
      def __screen_dir__, do: @__screen_dir__
    end
  end
end
