defmodule Flare.Router do
  @moduledoc """
  Maps page name strings to view modules.

  ## Usage

      defmodule MyApp.FlareRouter do
        use Flare.Router

        view "welcome", MyApp.Welcome
        view "login",   MyApp.Login
        view "store",   MyApp.Store
        view "product", MyApp.Product
        view "cart",    MyApp.Cart
      end

  Then configure Flare to use your router in `config/config.exs`:

      config :flare, router: MyApp.FlareRouter

  ## How it works

  Each `view/2` call generates a `view_for!/1` function clause. When a
  client joins `"flare:product"`, Flare calls `router.view_for!("product")`
  to resolve which Elixir module handles that screen.

  If no view is registered for a page, a clear error is raised at runtime
  telling you exactly what line to add to your router.
  """

  defmacro __using__(_opts) do
    quote do
      import Flare.Router

      # Fallback clause — called when no view is registered for `page`.
      # Raises a clear, actionable error message.
      def view_for!(page) do
        raise """
        Flare: No view registered for page "#{page}".

        Add this line to your router:

            view "#{page}", MyApp.YourViewModule

        """
      end

      defoverridable view_for!: 1
    end
  end

  @doc """
  Registers a view module for a page name string.

      view "product", MyApp.Product

  This generates:

      def view_for!("product"), do: MyApp.Product
  """
  defmacro view(page_name, module) do
    quote do
      def view_for!(unquote(page_name)), do: unquote(module)
    end
  end
end
