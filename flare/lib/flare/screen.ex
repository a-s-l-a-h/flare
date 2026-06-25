# Location: flare/lib/flare/screen.ex

defmodule Flare.Screen do
  @moduledoc """
  The behaviour and macro injected into every developer's screen module.

  ## Minimal usage (open screen, files next to .ex file)

      defmodule MyApp.Welcome do
        use Flare.Screen
        screen_dir __DIR__

        @impl true
        def mount(_params, socket) do
          {:ok, assign(socket, flare_title: "Welcome!")}
        end
      end

  ## Access control — authorize/2

  Override `authorize/2` to restrict which users can join this screen.
  Return `:ok` to allow, `{:error, reason}` to reject.

  This is enforced entirely on the server. The channel join itself is
  rejected before mount runs, before any layout is sent, before any
  state is touched. There is no client-side bypass possible.

      defmodule MyApp.AdminDashboard do
        use Flare.Screen
        screen_dir __DIR__

        @impl true
        def authorize(user_id, _params) do
          # Check one role
          if MyApp.Accounts.admin?(user_id),
            do: :ok,
            else: {:error, :unauthorized}
        end

        @impl true
        def mount(_params, socket), do: {:ok, socket}
      end

  ## Multiple roles on one screen

      @impl true
      def authorize(user_id, _params) do
        case MyApp.Accounts.get_role(user_id) do
          role when role in [:admin, :moderator, :staff] -> :ok
          _ -> {:error, :unauthorized}
        end
      end

  ## Sharing the same rule across many screens

      # Define once:
      defmodule MyApp.Guards do
        def require_admin(user_id, _params) do
          if MyApp.Accounts.admin?(user_id), do: :ok, else: {:error, :unauthorized}
        end

        def require_staff(user_id, _params) do
          case MyApp.Accounts.get_role(user_id) do
            r when r in [:admin, :moderator, :staff] -> :ok
            _ -> {:error, :unauthorized}
          end
        end
      end

      # Use in any screen with one line:
      defmodule MyApp.AdminOrders do
        use Flare.Screen
        screen_dir __DIR__
        defdelegate authorize(user_id, params), to: MyApp.Guards, as: :require_admin
        @impl true
        def mount(_params, socket), do: {:ok, socket}
      end

  ## Layout caching — use_cache

  By default every screen's layout and state JSON files are loaded into
  ETS (RAM) at app startup. All joins read from RAM — zero disk reads
  during runtime.

  If you need fresh disk reads on every join (e.g. during development
  when you edit JSON files frequently and want changes without restart):

      defmodule MyApp.Welcome do
        use Flare.Screen
        screen_dir __DIR__
        use_cache false   # always read from disk, never use ETS for this screen
      end

  IMPORTANT: `use_cache false` only affects this screen. All other
  screens still use ETS. The real user state (socket.assigns) is NEVER
  cached — each user always gets their own isolated state. Only the
  static JSON template files are shared via ETS.

  ## Callbacks

  - `mount/2`        — required. Called once per join. Query DB here.
  - `handle_event/3` — optional. Called when client fires a flare action.
  - `handle_info/2`  — optional. Called for process messages and timers.
  - `authorize/2`    — optional. Called before mount to gate access.
  """

  @callback mount(params :: map(), socket :: Flare.Socket.t()) ::
                {:ok, Flare.Socket.t()} | {:error, term()}

  @callback handle_event(
                event   :: String.t(),
                payload :: map(),
                socket  :: Flare.Socket.t()
              ) :: {:noreply, Flare.Socket.t()}

  @callback handle_info(message :: term(), socket :: Flare.Socket.t()) ::
                {:noreply, Flare.Socket.t()}

  @doc """
  Access gate called before mount/2 on every channel join.

  Return :ok to allow the join, {:error, reason} to reject it.

  The default implementation returns :ok — all users are allowed.
  Override this on any screen that requires authentication or a specific
  role. The channel join is rejected server-side; no layout or state is
  ever sent to rejected users.

  Receives:
  - user_id — the string user ID set by the host app's UserSocket.connect/3
  - params  — the join params map sent by the client (same as mount params)
  """
  @callback authorize(user_id :: String.t(), params :: map()) ::
                :ok | {:error, term()}

  defmacro __using__(_opts) do
    quote do
      @behaviour Flare.Screen

      import Flare.Socket,    only: [assign: 2, assign: 3]
      import Flare.Commands
      import Flare.Screen,    only: [screen_dir: 1, use_cache: 1]

      def authorize(_user_id, _params), do: :ok
      def handle_event(_event, _payload, socket), do: {:noreply, socket}
      def handle_info(_msg, socket), do: {:noreply, socket}
      def __use_cache__, do: true

      # Sentinel — overridden to true by screen_authorize/0 macro when
      # the developer calls it, or detected via Module.defines? at compile time.
      # Default: false — no custom authorize, channel uses router roles.
      def __has_custom_authorize__, do: false

      defoverridable authorize: 2,
                    handle_event: 3,
                    handle_info: 2,
                    __use_cache__: 0,
                    __has_custom_authorize__: 0

      # Register a @before_compile hook on the USING module.
      # At compile time of the screen module, this checks if the developer
      # defined their own authorize/2 and sets __has_custom_authorize__ accordingly.
      @before_compile Flare.Screen
    end
  end

  defmacro __before_compile__(env) do
    # Check if the developer's module defined authorize/2 themselves.
    # Module.defines? returns true only for functions explicitly written
    # in that module — not inherited defaults from defoverridable.
    has_custom = Module.defines?(env.module, {:authorize, 2})

    quote do
      def __has_custom_authorize__, do: unquote(has_custom)
    end
  end

  @doc """
  Registers the directory where this screen's layout/ and state/ folders live.

  Always call this once, directly below `use Flare.Screen`:

      screen_dir __DIR__

  This tells Flare where to find:
      <your_dir>/layout/<screen_name>.json   ← required
      <your_dir>/state/<screen_name>.json    ← optional
  """
  defmacro screen_dir(dir) do
    quote do
      @__screen_dir__ unquote(dir)

      @doc false
      def __screen_dir__, do: @__screen_dir__
    end
  end

  @doc """
  Controls whether this screen reads from ETS cache or always from disk.

  Default is true — JSON files are loaded into ETS at app startup and
  all joins read from RAM with zero disk access.

  Set to false ONLY if you need fresh disk reads on every join:

      use_cache false

  Typical use: during local development when you edit layout JSON files
  frequently and want to see changes without restarting the server.
  In production, always leave this as the default (true).

  This only controls the static layout/state JSON template files.
  User state (socket.assigns) is NEVER cached — every user always gets
  their own isolated, fresh state regardless of this setting.
  """
  defmacro use_cache(value) when is_boolean(value) do
    quote do
      # Override the default __use_cache__ generated by __using__
      def __use_cache__, do: unquote(value)
      defoverridable __use_cache__: 0
    end
  end
end
