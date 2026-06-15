# Location: flare/lib/flare/layout.ex

defmodule Flare.Layout do
  @moduledoc """
  Builds the JSON envelopes sent to Flare client SDKs.

  Files sit alongside your view module:

      welcome/
      ├── welcome.ex       ← declares view_files __DIR__
      ├── layout/
      │   └── welcome.json ← DivKit card JSON (required)
      └── state/
          └── welcome.json ← variable definitions (optional)

  The `state/` folder is optional. If absent, the INIT envelope is sent
  with an empty variables list — valid for screens with no server variables.
  """

  def build_init_envelope(screen_module, screen_name, assigns) do
    Flare.Logger.info(__MODULE__, "Building INIT for: #{screen_name}")
    {layout_json, state_json} = load_screen_files(screen_module, screen_name)

    %{
      "screen"    => screen_name,
      "layout"    => layout_json,
      "variables" => state_json["variables"] || [],
      "state"     => stringify_keys(assigns)
    }
  end

  def build_patch_envelope(screen_name, diff) do
    Flare.Logger.debug(__MODULE__, "Building PATCH for: #{screen_name}")
    %{
      "screen" => screen_name,
      "state"  => stringify_keys(diff)
    }
  end

  def build_patch_with_commands_envelope(screen_name, diff, commands) do
    base = build_patch_envelope(screen_name, diff)
    Map.put(base, "commands", commands)
  end

  def build_layout_update_envelope(screen_module, screen_name) do
    Flare.Logger.info(__MODULE__, "Building LAYOUT_UPDATE for: #{screen_name}")
    {layout_json, state_json} = load_screen_files(screen_module, screen_name)

    %{
      "screen"    => screen_name,
      "layout"    => layout_json,
      "variables" => state_json["variables"] || []
    }
  end

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  defp load_screen_files(screen_module, screen_name) do
    screen_dir = get_screen_dir!(screen_module)

    layout_path = Path.join([screen_dir, "layout", "#{screen_name}.json"])
    state_path  = Path.join([screen_dir, "state",  "#{screen_name}.json"])

    layout_json = read_json_required!(layout_path, screen_module)
    state_json  = read_json_optional(state_path)

    {layout_json, state_json}
  end

  defp get_screen_dir!(screen_module) do
    if function_exported?(screen_module, :__screen_dir__, 0) do
      screen_module.__screen_dir__()
    else
      raise """
      Flare: #{inspect(screen_module)} is missing `screen_dir __DIR__`.

      Add this directly below `use Flare.Screen`:

          screen_dir __DIR__

      This tells Flare where to find your layout/ and state/ JSON files.
      """
    end
  end

  defp read_json_required!(path, screen_module) do
    case File.read(path) do
      {:ok, contents} ->
        Jason.decode!(contents)

      {:error, reason} ->
        raise """
            Flare: Could not read layout file for #{inspect(screen_module)}.

        Expected path: #{path}
        Error: #{inspect(reason)}

        The layout/ folder must sit alongside your view's .ex file.
        """
    end
  end

  # State file is optional — screens with no server variables need none.
  defp read_json_optional(path) do
    case File.read(path) do
      {:ok, contents} -> Jason.decode!(contents)
      {:error, _}     -> %{"variables" => []}
    end
  end

  # Atom keys (:flare_count) → string keys ("flare_count") for JSON
  defp stringify_keys(map) when is_map(map) do
    Map.new(map, fn {k, v} -> {to_string(k), v} end)
  end
end
