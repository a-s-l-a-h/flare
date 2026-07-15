defmodule FlareAppTemplate.Utils.FlareMemoryAudit do
  @moduledoc """
  Deep diagnostic audit of per-user resource cost in a Flare/Phoenix app.
  See previous version for full field docs. This revision fixes transport
  discovery: instead of guessing the transport pid via link-scanning +
  module-name regex (which silently produced 0.0 KB traffic when it
  missed), it reads `transport_pid` directly out of the channel's own
  %Phoenix.Socket{} state — that field is always correct, no heuristic
  needed. Falls back to a labeled estimate only if that pid's port can't
  be resolved (e.g. socket already closed mid-audit).

  to use  .. iex.bat -S mix phx.server .... iex(1)> FlareAppTemplate.Utils.FlareMemoryAudit.run()
  """

  def run do
    all_users = Registry.select(Flare.Registry, [{{:"$1", :"$2", :_}, [], [{{:"$1", :"$2"}}]}])
    reports = Enum.map(all_users, &audit_user/1)
    print_report(reports)
    reports
  end

  # ---------------------------------------------------------------------
  # Per-user audit
  # ---------------------------------------------------------------------

  defp audit_user({user_id, state_pid}) do
    :erlang.garbage_collect(state_pid)
    state_mem = proc_detail(state_pid)

    channel_entries = Registry.lookup(Flare.PubSub, "user:#{user_id}")

    channels =
      Enum.map(channel_entries, fn {pid, _} ->
        :erlang.garbage_collect(pid)
        {topic, transport_pid} = channel_topic_and_transport(pid)

        %{
          pid: pid,
          topic: topic,
          mem: proc_detail(pid),
          transport_pid: transport_pid
        }
      end)

    transport_pids =
      channels
      |> Enum.map(& &1.transport_pid)
      |> Enum.reject(&is_nil/1)
      |> Enum.uniq()

    transports =
      Enum.map(transport_pids, fn pid ->
        :erlang.garbage_collect(pid)
        socket = find_socket(pid)

        %{
          pid: pid,
          module: initial_call_module(pid),
          mem: proc_detail(pid),
          socket: socket,
          traffic: resolve_traffic(pid, socket)
        }
      end)

    %{user_id: user_id, state: state_mem, channels: channels, transports: transports}
  end

  # ---------------------------------------------------------------------
  # Process introspection
  # ---------------------------------------------------------------------

  defp proc_detail(pid) do
    keys = [:memory, :total_heap_size, :heap_size, :stack_size, :binary,
            :message_queue_len, :reductions, :garbage_collection, :status]

    case :erlang.process_info(pid, keys) do
      nil ->
        %{memory: 0, binary: 0, mailbox: 0, reductions: 0, minor_gcs: 0,
          heap_words: 0, stack_words: 0, status: :dead}

      info ->
        binary_bytes = Enum.reduce(info[:binary] || [], 0, fn {_, size, _}, acc -> acc + size end)
        gc = info[:garbage_collection] || []

        %{
          memory: info[:memory],
          binary: binary_bytes,
          mailbox: info[:message_queue_len],
          reductions: info[:reductions],
          heap_words: info[:total_heap_size],
          stack_words: info[:stack_size],
          minor_gcs: Keyword.get(gc, :minor_gcs, 0),
          fullsweep_after: Keyword.get(gc, :fullsweep_after, 0),
          status: info[:status]
        }
    end
  end

  # Reads topic AND transport_pid straight out of the channel's own
  # %Phoenix.Socket{} — this is the authoritative source, no guessing.
  defp channel_topic_and_transport(pid) do
    try do
      case :sys.get_state(pid, 200) do
        %{socket: %Phoenix.Socket{topic: t, transport_pid: tp}} -> {t, tp}
        %Phoenix.Socket{topic: t, transport_pid: tp} -> {t, tp}
        _ -> {"unknown", nil}
      end
    catch
      _, _ -> {"unknown", nil}
    end
  end

  defp initial_call_module(pid) do
    with {:dictionary, dict} <- :erlang.process_info(pid, :dictionary),
         {_, {mod, _, _}} <- List.keyfind(dict, :"$initial_call", 0) do
      mod
    else
      _ -> nil
    end
  end

  # ---------------------------------------------------------------------
  # Real socket discovery + REAL traffic stats, with graceful fallback
  # ---------------------------------------------------------------------

  defp find_socket(pid) do
    try do
      pid |> :sys.get_state(200) |> hunt_for_port()
    catch
      _, _ -> nil
    end
  end

  defp hunt_for_port(term, depth \\ 0)
  defp hunt_for_port(_term, depth) when depth > 6, do: nil
  defp hunt_for_port(term, _depth) when is_port(term), do: term

  defp hunt_for_port(term, depth) when is_map(term) do
    term |> Map.to_list() |> hunt_for_port(depth + 1)
  end

  defp hunt_for_port(term, depth) when is_tuple(term) do
    term |> Tuple.to_list() |> hunt_for_port(depth + 1)
  end

  defp hunt_for_port(term, depth) when is_list(term) do
    Enum.find_value(term, fn
      {_k, v} -> hunt_for_port(v, depth + 1)
      v -> hunt_for_port(v, depth + 1)
    end)
  end

  defp hunt_for_port(_term, _depth), do: nil

  # Tier 1: exact socket found directly -> real getstat numbers.
  # Tier 2: socket not extractable from :sys.get_state, but the transport
  #         pid is alive -> scan node-wide ports for one whose :connected
  #         owner is this pid (works even if the struct shape changed).
  # Tier 3: nothing resolvable -> return :estimated so callers/reporting
  #         never confuse this with a real, verified 0.0 KB.
  defp resolve_traffic(transport_pid, port) when is_port(port) do
    case :inet.getstat(port, [:recv_oct, :send_oct, :recv_cnt, :send_cnt, :send_pend]) do
      {:ok, stats} -> {:real, Map.new(stats)}
      {:error, _} -> resolve_traffic(transport_pid, nil)
    end
  end

  defp resolve_traffic(transport_pid, nil) do
    case find_port_owned_by(transport_pid) do
      nil ->
        :estimated

      port ->
        case :inet.getstat(port, [:recv_oct, :send_oct, :recv_cnt, :send_cnt, :send_pend]) do
          {:ok, stats} -> {:approximate, Map.new(stats)}
          {:error, _} -> :estimated
        end
    end
  end

  defp find_port_owned_by(pid) do
    Enum.find(:erlang.ports(), fn port ->
      case :erlang.port_info(port, :connected) do
        {:connected, ^pid} -> true
        _ -> false
      end
    end)
  end

  # ---------------------------------------------------------------------
  # Node-wide extras
  # ---------------------------------------------------------------------

  defp node_wide_traffic do
    :erlang.ports()
    |> Enum.filter(fn port ->
      case :erlang.port_info(port, :name) do
        {:name, name} -> to_string(name) =~ ~r/tcp_inet|inet_ssl|ssl_gen/
        _ -> false
      end
    end)
    |> Enum.reduce(%{recv_oct: 0, send_oct: 0, recv_cnt: 0, send_cnt: 0, count: 0}, fn port, acc ->
      case :inet.getstat(port, [:recv_oct, :send_oct, :recv_cnt, :send_cnt]) do
        {:ok, stats} ->
          m = Map.new(stats)
          %{acc |
            recv_oct: acc.recv_oct + m.recv_oct,
            send_oct: acc.send_oct + m.send_oct,
            recv_cnt: acc.recv_cnt + m.recv_cnt,
            send_cnt: acc.send_cnt + m.send_cnt,
            count: acc.count + 1}
        _ -> acc
      end
    end)
  end

  defp ets_cache_stats do
    case :ets.info(:flare_layout_cache) do
      :undefined ->
        nil

      info ->
        %{
          entries: Keyword.get(info, :size),
          words: Keyword.get(info, :memory),
          bytes: Keyword.get(info, :memory) * :erlang.system_info(:wordsize)
        }
    end
  end

  defp scheduler_load do
    try do
      :scheduler.utilization(1)
      |> Enum.filter(fn {type, _, _, _} -> type == :total end)
    catch
      _, _ -> nil
    end
  end

  # ---------------------------------------------------------------------
  # Reporting
  # ---------------------------------------------------------------------

  defp print_report(reports) do
    Enum.each(reports, &print_user/1)

    total_state = Enum.sum(Enum.map(reports, & &1.state.memory))
    total_chan = reports |> Enum.flat_map(& &1.channels) |> Enum.map(& &1.mem.memory) |> Enum.sum()

    all_transports =
      reports |> Enum.flat_map(& &1.transports) |> Enum.uniq_by(& &1.pid)

    total_transport_mem = Enum.sum(Enum.map(all_transports, & &1.mem.memory))

    {real_traffic, real_count} = sum_traffic(all_transports, :real)
    {approx_traffic, approx_count} = sum_traffic(all_transports, :approximate)
    estimated_count = Enum.count(all_transports, &(&1.traffic == :estimated))

    combined_traffic = %{
      recv_oct: real_traffic.recv_oct + approx_traffic.recv_oct,
      send_oct: real_traffic.send_oct + approx_traffic.send_oct
    }

    n = length(reports)
    grand = total_state + total_chan + total_transport_mem
    per_user_mem = if n > 0, do: div(grand, n), else: 0
    tracked_users = real_count + approx_count
    per_user_traffic =
      if tracked_users > 0,
        do: div(combined_traffic.recv_oct + combined_traffic.send_oct, tracked_users),
        else: 0

    node_mem = :erlang.memory()
    node_traffic = node_wide_traffic()
    cache = ets_cache_stats()

    traffic_note =
      cond do
        estimated_count == 0 and approx_count == 0 ->
          "✅ Socket located directly for all tracked transports (exact kernel numbers)."

        estimated_count == 0 ->
          "⚠️  #{approx_count} transport(s) resolved via node-wide port scan (approximate but real bytes)."

        true ->
          "⚠️  #{estimated_count} transport(s) could not be resolved at all — excluded from the sum below " <>
            "(not shown as 0, since that would misreport them as verified-zero traffic)."
      end

    IO.puts("""

    ══════════════════════════════════════════════════════════
    📊 SYSTEM SUMMARY — #{n} connected users
    ══════════════════════════════════════════════════════════
    MEMORY
      UserState:            #{kb(total_state)} KB
      Channels:              #{kb(total_chan)} KB
      Transport processes:   #{kb(total_transport_mem)} KB
      ──────────────────────────────────
      Process-visible total: #{kb(grand)} KB   (avg #{kb(per_user_mem)} KB/user)
      Projected @ 1,000 users:  #{mb(per_user_mem * 1000)} MB
      Projected @ 10,000 users: #{mb(per_user_mem * 10000)} MB

    SOCKET TRAFFIC (measured via :inet.getstat, cumulative since connect)
      Real (exact):        ⬇ #{kb(real_traffic.recv_oct)} KB in  |  ⬆ #{kb(real_traffic.send_oct)} KB out  (#{real_count} sockets)
      Approximate:          ⬇ #{kb(approx_traffic.recv_oct)} KB in  |  ⬆ #{kb(approx_traffic.send_oct)} KB out  (#{approx_count} sockets)
      Unresolved/estimated: #{estimated_count} sockets (excluded from sums above)
      Avg per tracked user: #{kb(per_user_traffic)} KB total
      #{traffic_note}

    ETS LAYOUT CACHE (:flare_layout_cache)
      #{if cache, do: "#{cache.entries} entries, #{kb(cache.bytes)} KB", else: "not found / not initialized"}

    NODE-WIDE TCP SOCKETS (ALL connections on this BEAM, not just Flare users)
      Open TCP ports: #{node_traffic.count}
      Total ⬇ #{kb(node_traffic.recv_oct)} KB in  |  ⬆ #{kb(node_traffic.send_oct)} KB out  (since each socket opened)

    🖥  NODE-WIDE MEMORY (ground truth — includes ETS/code/atoms/everything)
      total:     #{kb(node_mem[:total])} KB
      processes: #{kb(node_mem[:processes])} KB
      binary:    #{kb(node_mem[:binary])} KB
      ets:       #{kb(node_mem[:ets])} KB
      atom:      #{kb(node_mem[:atom])} KB
      code:      #{kb(node_mem[:code])} KB

    #{scheduler_line(scheduler_load())}

    ⚠️  Traffic figures above are CUMULATIVE totals since each socket was
        opened, not a live "current bitrate". To get bytes/sec, run this
        script twice a few seconds apart and diff recv_oct/send_oct.
    ══════════════════════════════════════════════════════════
    """)
  end

  defp sum_traffic(transports, tag) do
    matches =
      Enum.filter(transports, fn
        %{traffic: {^tag, _stats}} -> true
        _ -> false
      end)

    sum =
      Enum.reduce(matches, %{recv_oct: 0, send_oct: 0}, fn %{traffic: {_, stats}}, acc ->
        %{recv_oct: acc.recv_oct + (stats[:recv_oct] || 0), send_oct: acc.send_oct + (stats[:send_oct] || 0)}
      end)

    {sum, length(matches)}
  end

  defp scheduler_line(nil), do: "SCHEDULER UTILIZATION: unavailable (enable :scheduler module / run under distillery-less dev)"

  defp scheduler_line(util) do
    lines =
      Enum.map(util, fn {:total, id, active, total} ->
        pct = if total > 0, do: Float.round(active / total * 100, 1), else: 0.0
        "  scheduler #{id}: #{pct}%"
      end)

    "SCHEDULER UTILIZATION (1s sample)\n" <> Enum.join(lines, "\n")
  end

  defp print_user(%{user_id: uid, state: s, channels: chans, transports: tports}) do
    chan_lines =
      Enum.map_join(chans, "\n", fn c ->
        "     - #{c.topic}: #{kb(c.mem.memory)} KB " <>
          "(binary #{kb(c.mem.binary)} KB, mailbox #{c.mem.mailbox}, gc x#{c.mem.minor_gcs}, status #{c.mem.status})"
      end)

    tport_lines =
      if tports == [] do
        "     (no transport process identified — channel had no live transport_pid)"
      else
        Enum.map_join(tports, "\n", fn t ->
          traffic_str = format_traffic(t.traffic)
          "     - #{inspect(t.pid)} [#{inspect(t.module)}]: mem #{kb(t.mem.memory)} KB | #{traffic_str}"
        end)
      end

    chan_sub = Enum.sum(Enum.map(chans, & &1.mem.memory))
    tport_mem_sub = Enum.sum(Enum.map(tports, & &1.mem.memory))

    traffic_sub =
      tports
      |> Enum.map(fn
        %{traffic: {_, stats}} -> (stats[:recv_oct] || 0) + (stats[:send_oct] || 0)
        _ -> 0
      end)
      |> Enum.sum()

    IO.puts("""
    ══════════════════════════════════════════════════
    🧠 USER: #{uid}
    ══════════════════════════════════════════════════
    1. UserState: #{kb(s.memory)} KB (binary #{kb(s.binary)} KB, mailbox #{s.mailbox}, reductions #{s.reductions})
    2. Channels (#{length(chans)}):
    #{chan_lines}
       Subtotal: #{kb(chan_sub)} KB
    3. Transport (#{length(tports)}):
    #{tport_lines}
       Memory subtotal:  #{kb(tport_mem_sub)} KB
       Traffic subtotal: #{kb(traffic_sub)} KB (excludes unresolved sockets)
    ──────────────────────────────────────────────────
    🔥 MEMORY TOTAL:  #{kb(s.memory + chan_sub + tport_mem_sub)} KB
    📡 TRAFFIC TOTAL: #{kb(traffic_sub)} KB since connect
    """)
  end

  defp format_traffic({:real, stats}),
    do: "⬇ #{kb(stats.recv_oct)} KB in / ⬆ #{kb(stats.send_oct)} KB out (real, #{stats.recv_cnt}+#{stats.send_cnt} pkts)"

  defp format_traffic({:approximate, stats}),
    do: "⬇ #{kb(stats.recv_oct)} KB in / ⬆ #{kb(stats.send_oct)} KB out (approx — matched via node-wide port scan)"

  defp format_traffic(:estimated),
    do: "traffic: unresolved (socket could not be located by any method — not counted as 0)"

  #defp kb(nil), do: 0.0
  defp kb(b), do: Float.round(b / 1024, 2)
  #defp mb(nil), do: 0.0
  defp mb(b), do: Float.round(b / 1024 / 1024, 2)
end
