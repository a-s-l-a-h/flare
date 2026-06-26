# Location: flare/lib/flare/layout.ex

defmodule Flare.Layout do
  @moduledoc """
  Builds the JSON envelopes sent to Flare client SDKs.

  ## File structure

  Each screen's JSON files must sit in layout/ and state/ folders
  relative to the directory declared with screen_dir in the screen module:

      welcome/
      ├── welcome.ex           ← declares screen_dir __DIR__
      ├── layout/
      │   └── welcome.json     ← DivKit card JSON (required)
      └── state/
          └── welcome.json     ← variable definitions (optional)

  ## ETS cache — eager load at startup

  At app startup, Flare.Application calls `Flare.Layout.warm_cache/1`
  with the list of all registered screens from the router. Every screen
  whose module declares `use_cache true` (the default) has its layout
  and state JSON files read from disk ONCE and stored in ETS.

  All subsequent joins for those screens read from ETS — zero disk reads
  during runtime.

  Screens that declare `use_cache false` always read from disk on every
  join. This is useful during development when layout files change often.

  ## Hot layout updates

  `Flare.update_layout/2` triggers `build_layout_update_envelope/2`.
  That function ALWAYS reads fresh from disk (bypasses ETS) and then
  writes the fresh data back into ETS so new joins after the hot-push
  also get the updated layout. This keeps the cache consistent with
  what connected users received.

  ## User state isolation

  The ETS cache holds ONLY static JSON template files (layout + state
  variable definitions). It never holds any user-specific data.
  Each user's runtime state (socket.assigns) lives exclusively in their
  own UserState GenServer. There is zero risk of user state leaking
  between users via the cache.
  """

  # ETS table name — one global table, all channel processes read from it.
  # :public + read_concurrency: true means any process can read without
  # going through a bottleneck GenServer.
  @cache_table :flare_layout_cache

  # ---------------------------------------------------------------------------
  # Cache lifecycle — called from Flare.Application
  # ---------------------------------------------------------------------------

  @doc """
  Creates the ETS cache table.

  Called once by Flare.Application before the supervisor tree starts.
  Safe to call multiple times (test restarts) — detects existing table.
  """
  def init_cache do
    case :ets.whereis(@cache_table) do
      :undefined ->
        # :set          — one entry per key, no duplicates
        # :public       — any process (channel, etc.) can read directly
        # :named_table  — referenced by atom, not PID
        # read_concurrency: true — BEAM optimises for parallel readers
        :ets.new(@cache_table, [:set, :public, :named_table, read_concurrency: true])
        Flare.Logger.info(__MODULE__, "ETS layout cache created")

      _ ->
        # Already exists — happens on application restart in test env.
        # Reuse the existing table rather than crashing.
        Flare.Logger.info(__MODULE__, "ETS layout cache already exists, reusing")
    end
    :ok
  end

  @doc """
  Eager-loads layout and state JSON for all screens that have use_cache true.

  Called by Flare.Application after the supervisor tree starts, using the
  list from `router.registered_screens()`. Each screen's module is checked
  for `use_cache true` — if so, files are read from disk and inserted into
  ETS immediately.

  Screens with `use_cache false` are skipped here; they always read from
  disk at join time.

  Any file read error at startup raises immediately with a clear message
  so the developer knows before any user connects — not at the first join.
  """
  @doc """
Eager-loads layout and state JSON for all screens that have use_cache true.

Called by Flare.Application after the supervisor tree starts, using the
list from `router.registered_screens()`. Each screen's module is checked
for `use_cache true` — if so, files are read from disk and inserted into
ETS immediately.

When `config :flare, optimize: true`, layout and variables are also
gzip-compressed at this point and stored alongside the decoded maps in ETS.
Compression happens ONCE here at startup — every subsequent join for that
screen reads the pre-compressed binary directly from ETS and hands it to
the serializer without any repeated compression work.

Screens with `use_cache false` are completely unaffected — they are skipped
here and always read fresh from disk at join time with no compression.

Any file read error at startup raises immediately with a clear message
so the developer knows before any user connects — not at the first join.
"""
  def warm_cache(registered_screens) do
    optimize = Application.get_env(:flare, :optimize, false)
    Flare.Logger.info(__MODULE__, "Warming layout cache for #{length(registered_screens)} screens (optimize: #{optimize})")

    Enum.each(registered_screens, fn {screen_name, screen_module, _opts} ->
      if screen_module.__use_cache__() do
        Flare.Logger.info(__MODULE__, "Loading to ETS: #{screen_name} (#{inspect(screen_module)})")
        {layout_json, state_json} = load_files_from_disk!(screen_module, screen_name)

        # Build the cache entry — plain maps, or plain maps + pre-compressed binaries.
        # The serializer (Flare.Serializer) reads the pre-compressed binaries and
        # writes them directly into binary WebSocket frames without re-compressing.
        entry = build_cache_entry(layout_json, state_json, optimize)
        :ets.insert(@cache_table, {screen_name, entry})
      else
        Flare.Logger.info(__MODULE__, "Skipping ETS (use_cache false): #{screen_name}")
      end
    end)

    Flare.Logger.info(__MODULE__, "Layout cache warm — all screens ready")
    :ok
  end

  @doc """
  Removes one screen's entry from ETS so the next join reloads from disk.

  Not needed for normal deploys (node restart clears ETS automatically).
  Useful if you edit a layout file on a live node without using
  Flare.update_layout/2. After calling this, the next join for that
  screen will read from disk and repopulate the cache.
  """
  def invalidate(screen_name) do
    :ets.delete(@cache_table, screen_name)
    Flare.Logger.info(__MODULE__, "ETS cache invalidated for: #{screen_name}")
    :ok
  end

  # ---------------------------------------------------------------------------
  # Public envelope builders — called by Flare.Channel
  # ---------------------------------------------------------------------------

  @doc false
  def build_init_envelope(screen_module, screen_name, assigns) do
    Flare.Logger.info(__MODULE__, "Building INIT for: #{screen_name}")

    # IMPORTANT: assigns is this user's own runtime state from their
    # UserState GenServer. It is NEVER stored in ETS. We only read
    # the static layout/state JSON from cache here.
    #
    # load_files/2 returns a tagged cache entry:
    #   {:plain, layout_map, state_map}           — use_cache false OR optimize false
    #   {:optimized, layout_gz, vars_gz, state_map} — use_cache true AND optimize true
    #
    # For :plain entries, we put the decoded maps directly into the envelope map.
    # Phoenix JSON-encodes them normally → text WebSocket frame. Identical to before.
    #
    # For :optimized entries, we put a special {:gz, binary} tagged tuple for
    # layout and variables. Flare.Serializer detects these tags, extracts the
    # pre-compressed binaries, and writes them into a binary WebSocket frame.
    # The maps themselves never go through Jason.encode! in this path — the
    # compressed bytes stored in ETS are written directly to the wire.
    entry = load_files(screen_module, screen_name)

    base = %{
      "screen" => screen_name,
      "state"  => stringify_keys(assigns)
    }

    base
    |> put_layout_field(entry)
    |> put_variables_field(entry)
  end

  @doc false
  def build_patch_envelope(screen_name, diff) do
    Flare.Logger.debug(__MODULE__, "Building PATCH for: #{screen_name}")
    %{
      "screen" => screen_name,
      "state"  => stringify_keys(diff)
    }
  end

  @doc false
  def build_patch_with_commands_envelope(screen_name, diff, commands) do
    base = build_patch_envelope(screen_name, diff)
    Map.put(base, "commands", commands)
  end

  @doc false
  def build_layout_update_envelope(screen_module, screen_name) do
    Flare.Logger.info(__MODULE__, "Building LAYOUT_UPDATE for: #{screen_name}")

    # Always bypass ETS for hot layout updates — read the latest file from
    # disk unconditionally, regardless of use_cache setting.
    # This ensures the pushed layout is always the current file on disk.
    {layout_json, state_json} = load_files_from_disk!(screen_module, screen_name)

    # Re-build the cache entry with current optimize setting so the refreshed
    # ETS entry matches what we are about to send. If optimize was true when
    # the server started, it is still true now — we compress the fresh layout.
    optimize = Application.get_env(:flare, :optimize, false)
    entry    = build_cache_entry(layout_json, state_json, optimize)

    # Refresh ETS so NEW joins AFTER this hot-push also get the fresh layout.
    # We store the fully-built entry (which may include pre-compressed binaries)
    # so new joins are consistent with what currently-connected users received.
    if screen_module.__use_cache__() do
      :ets.insert(@cache_table, {screen_name, entry})
      Flare.Logger.info(__MODULE__, "ETS refreshed after hot update: #{screen_name}")
    end

    # Build the envelope from the entry — same tagged-tuple logic as init.
    # The channel pushes this via push(socket, "layout_update", envelope)
    # which routes through Flare.Serializer automatically.
    base = %{"screen" => screen_name}

    base
    |> put_layout_field(entry)
    |> put_variables_field(entry)
  end


  # ---------------------------------------------------------------------------
  # Private — cache entry construction
  # ---------------------------------------------------------------------------

  # Cache entry tagged tuples — the tag tells load_files_from_ets what is stored,
  # and tells put_layout_field / put_variables_field how to build the envelope.
  #
  # :plain   — optimize false OR use_cache false screens (always plain decoded maps)
  #   Stored as: {:plain, layout_map, state_map}
  #   Envelope:  "layout" => layout_map, "variables" => [...] (normal JSON encoding)
  #
  # :optimized — use_cache true AND optimize true
  #   Stored as: {:optimized, layout_gz_binary, vars_gz_binary, state_map}
  #   Envelope:  "layout" => {:gz, layout_gz}, "variables" => {:gz, vars_gz}
  #   The {:gz, binary} tags are detected by Flare.Serializer which extracts
  #   the raw bytes and writes them into binary WebSocket frames — no re-compression,
  #   no base64 encoding, zero overhead beyond 9 bytes of frame header.

  defp build_cache_entry(layout_json, state_json, false = _optimize) do
    # optimize false — store plain decoded Elixir maps.
    # Jason.encode! runs at send time for each join (normal Phoenix behavior).
    {:plain, layout_json, state_json}
  end

  defp build_cache_entry(layout_json, state_json, true = _optimize) do
    # optimize true — compress both layout and variables right now, once.
    # Jason.encode! produces minified (compact) JSON — no whitespace, smallest possible.
    # :zlib.gzip/1 then compresses the UTF-8 bytes.
    # These compressed binaries are stored in ETS and reused for every join
    # with zero repeated work — the serializer writes them to the wire as-is.
    variables = state_json["variables"] || []

    layout_gz = layout_json |> Jason.encode!() |> :zlib.gzip()
    vars_gz   = variables   |> Jason.encode!() |> :zlib.gzip()

    Flare.Logger.debug(
      __MODULE__,
      "Compressed layout: #{byte_size(layout_gz)}b " <>
      "(was #{byte_size(Jason.encode!(layout_json))}b), " <>
      "variables: #{byte_size(vars_gz)}b"
    )

    {:optimized, layout_gz, vars_gz, state_json}
  end

  # Put the layout field into the envelope map.
  # :plain  → standard "layout" key with decoded map (normal Phoenix JSON path)
  # :optimized → {:gz, binary} tagged value; Flare.Serializer extracts and writes raw
  defp put_layout_field(envelope, {:plain, layout_json, _state_json}) do
    Map.put(envelope, "layout", layout_json)
  end

  defp put_layout_field(envelope, {:optimized, layout_gz, _vars_gz, _state_json}) do
    # {:gz, binary} is a private Flare contract between layout.ex and serializer.ex.
    # No other code ever sees or uses this tuple format.
    # The serializer pattern-matches on it in build_binary_frame/1.
    Map.put(envelope, "layout", {:gz, layout_gz})
  end

  # Put the variables field into the envelope map.
  # :plain  → standard "variables" key with decoded list
  # :optimized → {:gz, binary} tagged value
  defp put_variables_field(envelope, {:plain, _layout_json, state_json}) do
    Map.put(envelope, "variables", state_json["variables"] || [])
  end

  defp put_variables_field(envelope, {:optimized, _layout_gz, vars_gz, _state_json}) do
    Map.put(envelope, "variables", {:gz, vars_gz})
  end

  # ---------------------------------------------------------------------------
  # Private — cache-aware file loading
  # ---------------------------------------------------------------------------

  # Entry point for build_init_envelope.
  # Checks use_cache on the screen module and routes accordingly.
  defp load_files(screen_module, screen_name) do
    if screen_module.__use_cache__() do
      # use_cache true — read from ETS (always a hit after warm_cache).
      # Returns {:plain, ...} or {:optimized, ...} depending on optimize setting.
      load_files_from_ets(screen_module, screen_name)
    else
      # use_cache false — always read from disk, never touch ETS, never compress.
      # Returns {:plain, layout_map, state_map} so the rest of the pipeline
      # (put_layout_field, put_variables_field) works identically for both cases.
      # This screen will always send plain JSON regardless of the optimize setting.
      Flare.Logger.debug(__MODULE__, "use_cache false — reading disk (no compression): #{screen_name}")
      {layout_json, state_json} = load_files_from_disk!(screen_module, screen_name)
      {:plain, layout_json, state_json}
    end
  end

  # Read from ETS. Should always be a hit since warm_cache ran at startup.
  # If somehow a miss occurs (e.g. ETS cleared manually), falls back to
  # disk and repopulates ETS so subsequent joins are fast.
  defp load_files_from_ets(screen_module, screen_name) do
    case :ets.lookup(@cache_table, screen_name) do
      [{^screen_name, entry}] ->
        Flare.Logger.debug(__MODULE__, "ETS cache HIT: #{screen_name}")
        # entry is either {:plain, ...} or {:optimized, ...} — both are valid.
        # The caller (put_layout_field / put_variables_field) handles both shapes.
        entry

      [] ->
        # Should not happen after warm_cache — log a warning, recover gracefully.
        Flare.Logger.error(
          __MODULE__,
          "ETS cache MISS for '#{screen_name}' — was warm_cache called? " <>
          "Reading from disk and repopulating cache. This is a one-time recovery."
        )
        optimize = Application.get_env(:flare, :optimize, false)
        {layout_json, state_json} = load_files_from_disk!(screen_module, screen_name)
        entry = build_cache_entry(layout_json, state_json, optimize)
        :ets.insert(@cache_table, {screen_name, entry})
        entry
    end
  end

  # ---------------------------------------------------------------------------
  # Private — disk reading (the only place File.read is ever called)
  # ---------------------------------------------------------------------------

  # Reads layout/ and state/ JSON files from disk for the given screen.
  # Raises with a clear developer message if the required layout file is missing.
  # Returns {layout_json, state_json} where both are decoded Elixir maps.
  defp load_files_from_disk!(screen_module, screen_name) do
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
        File error:    #{inspect(reason)}

        Make sure this file exists. The layout/ folder must sit inside the
        directory declared with `screen_dir` in your screen module.
        """
    end
  end

  # State file is optional — screens with no server-side variables need none.
  # Returns an empty variables map if absent rather than raising.
  defp read_json_optional(path) do
    case File.read(path) do
      {:ok, contents} -> Jason.decode!(contents)
      {:error, _}     -> %{"variables" => []}
    end
  end

  # Atom keys (:flare_count) → string keys ("flare_count") for JSON wire format.
  # Only called on user assigns (runtime state) — never on cached template data.
  defp stringify_keys(map) when is_map(map) do
    Map.new(map, fn {k, v} -> {to_string(k), v} end)
  end
end
