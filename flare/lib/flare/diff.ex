defmodule Flare.Diff do
  @moduledoc """
  Pure flat-map diffing for Flare socket assigns.

  All three functions are pure — no side effects, no process communication.
  Takes maps, returns maps.

  ## Key filtering rules

  - `local_` prefixed keys are **never** included in diffs. The client
    owns local variables; the server must never overwrite them.
  - Keys with changed values are included with the new value.
  - Keys that existed in `old` but not in `new` are included with `nil`
    (signals removal to the client).
  - Unchanged keys are excluded (keeps wire messages small).

  ## Note on key types

  Assigns use atom keys (`:flare_count`). `global_keys` in your config
  must also use atoms. String keys in assigns are supported but ensure
  your `global_keys` list matches the type you use.
  """

  @doc """
  Compares two assigns maps and returns only what changed.

  Excludes `local_` prefixed keys (client-owned variables).
  Includes `nil` for keys removed from `new_assigns`.

  ## Examples

      iex> Flare.Diff.compute(%{flare_a: 1, flare_b: 2}, %{flare_a: 1, flare_b: 3})
      %{flare_b: 3}

      iex> Flare.Diff.compute(%{flare_a: 1}, %{})
      %{flare_a: nil}

      iex> Flare.Diff.compute(%{local_tab: "x"}, %{local_tab: "y"})
      %{}
  """
  def compute(old_assigns, new_assigns) do
    Flare.Logger.debug(__MODULE__, "Computing diff")

    # Keys that changed or were added (excluding local_ keys)
    changed =
      Enum.reduce(new_assigns, %{}, fn {k, v}, acc ->
        if local_key?(k) do
          acc
        else
          if Map.get(old_assigns, k) != v do
            Map.put(acc, k, v)
          else
            acc
          end
        end
      end)

    # Keys that were removed (existed in old, missing from new, not local_)
    removed =
      Enum.reduce(old_assigns, %{}, fn {k, _v}, acc ->
        if not local_key?(k) and not Map.has_key?(new_assigns, k) do
          Map.put(acc, k, nil)
        else
          acc
        end
      end)

    Map.merge(changed, removed)
  end

  @doc """
  Returns `true` if the diff is empty.

  Used to skip unnecessary network messages — if nothing changed,
  send nothing.
  """
  def empty?(diff) when is_map(diff), do: map_size(diff) == 0

  @doc """
  Splits a diff into global keys and page-local keys.

  `global_keys` is the list configured via `config :flare, global_keys: [...]`.
  Global keys (e.g. `:flare_cart_count`) are broadcast to all open screens
  for this user via UserState + PubSub.
  Page-local keys are sent only to the current screen's channel.

  Returns `{global_diff, page_diff}`.

  ## Example

      iex> Flare.Diff.split(
      ...>   %{flare_cart_count: 3, flare_price: "29.99"},
      ...>   [:flare_cart_count]
      ...> )
      {%{flare_cart_count: 3}, %{flare_price: "29.99"}}
  """
  def split(diff, global_keys) do
    Flare.Logger.debug(__MODULE__, "Splitting diff into global/page")

    Enum.reduce(diff, {%{}, %{}}, fn {k, v}, {global, page} ->
      if k in global_keys do
        {Map.put(global, k, v), page}
      else
        {global, Map.put(page, k, v)}
      end
    end)
  end

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  # Works for both atom keys (:local_tab) and string keys ("local_tab").
  defp local_key?(key), do: String.starts_with?(to_string(key), "local_")
end
