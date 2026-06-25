# Location: flare/lib/flare/router.ex

defmodule Flare.Router do
  @moduledoc """
  Maps screen name strings to screen modules, with optional role-based access.

  ## Usage

      defmodule MyApp.FlareRouter do
        use Flare.Router

        screen "welcome", MyApp.Welcome
        screen "profile", MyApp.Profile, roles: [:admin]
        screen "orders",  MyApp.Orders,  roles: [:admin, :staff]
        screen "account", MyApp.Account, roles: [:user, :admin]
      end

  Then configure Flare to use your router in config/config.exs:

      config :flare, router: MyApp.FlareRouter

  ## Access control — roles: option

  Adding `roles: [...]` to a screen declaration restricts it so only users
  whose role is in the list can join. Users not in the list receive an
  `unauthorized` error on the WebSocket join — no layout, no state, no
  mount ever runs.

  Flare calls your application's role resolver to check the user's role.
  Configure it in config/config.exs:

      config :flare, role_resolver: {MyApp.Accounts, :get_role, 1}

  The resolver must be a function that accepts a user_id string and returns
  an atom role (:admin, :user, :guest, etc.) or nil if unknown:

      defmodule MyApp.Accounts do
        def get_role("guest_" <> _), do: :guest
        def get_role(user_id) do
          case MyApp.Repo.get(MyApp.User, user_id) do
            %{role: role} -> role
            nil           -> nil
          end
        end
      end

  ## Priority — authorize/2 vs roles:

  If a screen module defines its own `authorize/2` callback, it takes
  FULL priority over any `roles:` declared in the router. The router
  roles are skipped entirely for that screen.

  Use `roles:` in the router for simple role checks.
  Use `authorize/2` in the screen module for complex custom logic.

      # Simple — declare in router:
      screen "admin", MyApp.Admin, roles: [:admin]

      # Complex — override in screen module:
      defmodule MyApp.Admin do
        use Flare.Screen
        screen_dir __DIR__

        @impl true
        def authorize(user_id, params) do
          # Custom logic — checked IP, time-of-day, feature flags, etc.
          # This runs instead of the router roles: check.
          if MyApp.Accounts.admin?(user_id) and MyApp.FeatureFlags.enabled?(:admin_panel),
            do: :ok,
            else: {:error, :unauthorized}
        end
      end

  ## No roles: — open to all

  A screen with no `roles:` option and no `authorize/2` override is open
  to any authenticated user. Authentication (valid token) is always
  enforced by UserSocket — that layer is separate from screen-level roles.

  ## registered_screens/0

  Returns all registered screens as `{name, module, opts}` tuples.
  Used internally by Flare.Application for ETS cache warming at startup.
  You do not need to call this directly.
  """

  defmacro __using__(_opts) do
    quote do
      import Flare.Router

      # Accumulates {name, module, opts} tuples as screens are declared.
      # Read by @before_compile to generate registered_screens/0.
      Module.register_attribute(__MODULE__, :flare_screens, accumulate: true)

      @before_compile Flare.Router

      # Fallback — raised when a client joins a screen not in this router.
      def screen_for!(page) do
        raise """
        Flare: No screen registered for "#{page}".

        Add this line to your router:

            screen "#{page}", MyApp.YourScreenModule

        """
      end

      defoverridable screen_for!: 1
    end
  end

  defmacro __before_compile__(_env) do
    quote do
      @doc """
      Returns all registered screens as {name, module, opts} tuples.
      Used by Flare.Application for ETS cache warming at startup.
      """
      def registered_screens, do: @flare_screens
    end
  end

  @doc """
  Registers a screen module for a screen name string.

  ## Examples

      # Open to all authenticated users
      screen "welcome", MyApp.Welcome

      # Admin only
      screen "admin", MyApp.Admin, roles: [:admin]

      # Multiple roles allowed
      screen "orders", MyApp.Orders, roles: [:admin, :staff]

  The `roles:` option is optional. Without it the screen is open to all.

  If the screen module defines its own `authorize/2` callback, it takes
  full priority and the `roles:` option here is ignored for that screen.
  """
  defmacro screen(page_name, module, opts \\ []) do
    quote do
      @flare_screens {unquote(page_name), unquote(module), unquote(opts)}
      def screen_for!(unquote(page_name)), do: unquote(module)
    end
  end
end
