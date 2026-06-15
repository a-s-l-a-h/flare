defmodule Flare.Router do
  @moduledoc """
  Maps page name strings to screen modules.

  ## Usage

      defmodule MyApp.FlareRouter do
        use Flare.Router

        screen "welcome", MyApp.Welcome
        screen "login",   MyApp.Login
        screen "store",   MyApp.Store
        screen "product", MyApp.Product
        screen "cart",    MyApp.Cart
      end

  Then configure Flare to use your router in `config/config.exs`:

      config :flare, router: MyApp.FlareRouter

  ## How it works

  Each `screen/2` call generates a `screen_for!/1` function clause. When a
  client joins `"flare:product"`, Flare calls `router.screen_for!("product")`
  to resolve which Elixir module handles that screen.

  If no screen is registered for a page, a clear error is raised at runtime
  telling you exactly what line to add to your router.
  """

  defmacro __using__(_opts) do
    quote do
      import Flare.Router

      # Fallback clause — called when no screen is registered for `page`.
      # Raises a clear, actionable error message.
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

  @doc """
  Registers a screen module for a page name string.

      screen "product", MyApp.Product

  This generates:

      def screen_for!("product"), do: MyApp.Product
  """
  defmacro screen(page_name, module) do
    quote do
      def screen_for!(unquote(page_name)), do: unquote(module)
    end
  end
end
