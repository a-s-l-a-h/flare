# Location: flare/lib/flare/serializer.ex
#
# Flare.Serializer — custom Phoenix channel serializer.
#
# WHY THIS EXISTS:
#   Phoenix channels normally send all messages as JSON text frames.
#   For large "init" and "layout_update" messages (the DivKit layout JSON
#   can be 60KB+ before compression), we switch to binary WebSocket frames
#   containing gzip-compressed data. This eliminates the 33% base64 overhead
#   that would occur if we embedded compressed bytes inside a JSON string.
#
# WHAT IT DOES:
#   - "init" and "layout_update" events → binary WebSocket frame (when optimize: true)
#   - All other events (patch, phx_reply, broadcasts) → normal JSON text frame
#   - Client→server messages are always decoded as normal JSON text (unchanged)
#
# BINARY FRAME FORMAT (version 1):
#   [1 byte ] version        = 1  (bump if format changes incompatibly)
#   [4 bytes] header_len     big-endian uint32
#   [N bytes] header_json    compact JSON — screen name, state, commands, encoding marker
#   [4 bytes] layout_len     big-endian uint32
#   [M bytes] layout_gz      gzip-compressed layout JSON bytes
#   [4 bytes] vars_len       big-endian uint32
#   [P bytes] vars_gz        gzip-compressed variables JSON array bytes
#
# The header is intentionally left UNCOMPRESSED because it is tiny (~50 bytes)
# and the client needs to read it to know what format the rest of the frame is in.
# layout and variables sections are independently compressed so the client can
# decompress them in parallel (Promise.all on the web client).
#
# SECURITY:
#   - Binary vs text frames has zero security difference — WebSocket runs over TLS.
#   - Client→server messages are NEVER binary; only server→client init/layout_update
#     are binary. The existing text JSON decoder handles all client messages.
#   - A malformed binary frame (e.g. version mismatch) is caught by the JS client
#     and falls back to showing an error message — no crash, no data leak.
#
# FALLBACK / COMPATIBILITY:
#   - When `config :flare, optimize: false` (the default), this serializer sends
#     ALL messages as normal JSON text frames, identical to the previous behavior.
#   - Screens with `use_cache false` always receive plain JSON (their cache entry
#     is tagged :plain), so they are completely unaffected by this serializer.
#   - The client detects binary frames via `instanceof ArrayBuffer`. If the
#     `encoding` field is absent in the header, it falls back to plain JSON.

defmodule Flare.Serializer do
  @behaviour Phoenix.Socket.Serializer

  alias Phoenix.Socket.{Broadcast, Message, Reply}

  # Only these two event types ever carry large layout payloads.
  # Everything else (patch, commands, acks) is small — no compression needed.
  @binary_events ["init", "layout_update"]

  # Version byte — increment if the binary frame format changes incompatibly.
  # Clients must reject frames with unknown version bytes.
  @version 1

  # ---------------------------------------------------------------------------
  # fastlane!/1 — called for Broadcast messages (broadcast_to_screen)
  # These are always small state diffs — always text JSON, never compressed.
  # ---------------------------------------------------------------------------
  @impl true
  def fastlane!(%Broadcast{} = broadcast) do
    data = encode_text(broadcast)
    {:socket_push, :text, data}
  end

  # ---------------------------------------------------------------------------
  # encode!/1 — called for Reply messages (ACKs to client push events)
  # Always tiny — never compressed.
  # ---------------------------------------------------------------------------
  @impl true
  def encode!(%Reply{} = reply) do
    msg = %Message{
      topic: reply.topic,
      event: "phx_reply",
      ref: reply.ref,
      join_ref: reply.join_ref,
      payload: %{status: reply.status, response: reply.payload}
    }
    data = encode_text(msg)
    {:socket_push, :text, data}
  end

  # ---------------------------------------------------------------------------
  # encode!/1 — called for all server→client push messages
  #
  # Binary path: init + layout_update when optimize: true
  #   → build_binary_frame/1 pulls layout and variables out of the payload,
  #     compresses them, and builds the binary frame described in the header above.
  #
  # Text path: everything else, OR when optimize: false
  #   → standard Phoenix V2 text JSON frame, identical to before this serializer.
  # ---------------------------------------------------------------------------
  @impl true
  def encode!(%Message{} = msg) do
    # Only use the binary/gzip path if the cache already pre-compressed it.
    # If use_cache is false, the layout is a plain map, so we skip compression entirely.
    is_precompressed = is_map(msg.payload) and match?({:gz, _}, msg.payload["layout"])

    if msg.event in @binary_events and optimize_enabled?() and is_precompressed do
      frame = build_binary_frame(msg)
      {:socket_push, :binary, frame}
    else
      {:socket_push, :text, encode_text(msg)}
    end
  end

  # ---------------------------------------------------------------------------
  # decode!/2 — called for all client→server messages
  # Clients NEVER send binary frames to Flare. Only the server sends binary.
  # We delegate entirely to Phoenix V2 JSON decoder — zero change here.
  # ---------------------------------------------------------------------------
  @impl true
  def decode!(raw, opts) do
    Phoenix.Socket.V2.JSONSerializer.decode!(raw, opts)
  end

  # ---------------------------------------------------------------------------
  # Private — binary frame builder
  # ---------------------------------------------------------------------------

  # Builds the binary frame from the message payload.
  #
  # The payload from layout.ex will look like ONE OF:
  #
  # Plain path (use_cache false OR optimize false):
  #   %{"screen" => "notes", "layout" => %{...map...}, "variables" => [...], "state" => %{}}
  #
  # Optimized path (use_cache true AND optimize true):
  #   %{"screen" => "notes", "layout" => {:gz, <<...>>}, "variables" => {:gz, <<...>>}, "state" => %{}}
  #
  # In the optimized path, the {:gz, binary} values are pre-compressed binaries
  # from ETS. We extract them directly — zero re-compression work.
  # In the plain path, we compress on the fly here (only for use_cache false
  # screens that somehow reach this code, which only happens if optimize: true
  # and use_cache false — in practice use_cache false screens return :plain from
  # load_files so they always take the text path in encode!/1, but we handle
  # it here defensively anyway).
  # Change this to accept %Message{} instead of msg.payload
  defp build_binary_frame(%Message{} = msg) do
    {layout_gz, payload_without_layout} = extract_gz_field(msg.payload, "layout", %{})
    {vars_gz, payload_without_layout_vars} = extract_gz_field(payload_without_layout, "variables", [])

    # The header now includes all the data Phoenix JS needs to route the event correctly
    header = %{
      "join_ref" => msg.join_ref,
      "ref"      => msg.ref,
      "topic"    => msg.topic,
      "event"    => msg.event,
      "payload"  => Map.put(payload_without_layout_vars, "encoding", "gzip")
    }

    header_json = Jason.encode!(header)

    header_len = byte_size(header_json)
    layout_len = byte_size(layout_gz)
    vars_len   = byte_size(vars_gz)

    <<
      @version    :: unsigned-integer-size(8),
      header_len  :: unsigned-big-integer-size(32),
      header_json :: binary,
      layout_len  :: unsigned-big-integer-size(32),
      layout_gz   :: binary,
      vars_len    :: unsigned-big-integer-size(32),
      vars_gz     :: binary
    >>
  end

  # Extracts a field from the payload map.
  # If the value is {:gz, binary} — pre-compressed — use it directly.
  # If the value is a plain Elixir term (map, list) — compress it now.
  # If the key is missing — compress the default value (empty map or list).
  # Returns {gz_binary, payload_without_this_key}.
  defp extract_gz_field(payload, key, default) do
    case Map.pop(payload, key, default) do
      {{:gz, gz_binary}, rest} ->
        # Pre-compressed by warm_cache — use as-is, zero work
        {gz_binary, rest}

      {plain_term, rest} ->
        # Plain Elixir term — compress now (happens for use_cache false screens
        # when optimize: true, which is unusual but handled correctly)
        {plain_term |> Jason.encode!() |> :zlib.gzip(), rest}
    end
  end

  # Standard Phoenix V2 text frame format is a JSON array.
  # Matches Phoenix.Socket.V2.JSONSerializer exactly for full compatibility.
  defp encode_text(%Broadcast{} = msg) do
    # Only pretty-print if optimization is turned off (dev mode)
    pretty? = not optimize_enabled?()
    Jason.encode_to_iodata!([nil, nil, msg.topic, msg.event, msg.payload], pretty: pretty?)
  end

  defp encode_text(%Message{} = msg) do
    # Only pretty-print if optimization is turned off (dev mode)
    pretty? = not optimize_enabled?()
    Jason.encode_to_iodata!([msg.join_ref, msg.ref, msg.topic, msg.event, msg.payload], pretty: pretty?)
  end

  defp optimize_enabled? do
    Application.get_env(:flare, :optimize, false)
  end
end
