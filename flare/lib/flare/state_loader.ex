# Location: flare/lib/flare/state_loader.ex

defmodule Flare.StateLoader do
  @moduledoc """
  Behaviour for loading user state from your database.

  Implement this in your application and configure it:

      config :flare, state_loader: MyApp.FlareStateLoader

  ## Example implementation

      defmodule MyApp.FlareStateLoader do
        @behaviour Flare.StateLoader

        @impl true
        def load(user_id, missing_keys) do
          # Group related keys into one query
          cart_keys = [:flare_cart_count, :flare_cart_total]

          result = %{}

          result =
            if Enum.any?(missing_keys, &(&1 in cart_keys)) do
              cart = MyApp.Cart.get_for_user(user_id)
              Map.merge(result, %{
                flare_cart_count: cart.item_count,
                flare_cart_total: cart.total_string
              })
            else
              result
            end

          result
        end
      end

  The `load/2` function receives the `user_id` and a list of keys that
  are missing from the cache. Return a map with the values for as many
  of those keys as you can load. Keys you don't return stay missing.

  Group related keys into single DB queries — don't query per key.
  """

  @doc """
  Load missing state keys for a user.

  Receives `user_id` (string or nil) and `missing_keys` (list of atoms).
  Return a map of key-value pairs for the keys you loaded.
  """
  @callback load(user_id :: String.t() | nil, missing_keys :: [atom()]) :: map()
end
