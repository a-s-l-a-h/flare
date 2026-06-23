# Location: flare/lib/flare/user_state.ex

defmodule Flare.UserState do
  @moduledoc """
  One GenServer process per connected user.

  Caches server-side state,

  ## Idle timeout

  When no messages arrive for `user_state_timeout` milliseconds (default 5 min),
  the GenServer shuts itself down cleanly. The next channel join for this user
  starts a fresh GenServer automatically.

  Every handle_call and handle_cast resets the timeout by returning it as the
  fourth element of the reply tuple. This is standard GenServer behaviour —
  the timeout countdown restarts after every message processed.
  """

  use GenServer

  # Read once at module level so every callback uses the same value per process.
  # Cannot be a module attribute because it reads runtime config.
  # Instead we use a private helper that reads it once per callback.
  @default_timeout 300_000

  # ---------------------------------------------------------------------------
  # Public API
  # ---------------------------------------------------------------------------

  @doc false
  def ensure_started(nil), do: :ok
  def ensure_started(user_id) do
    case DynamicSupervisor.start_child(Flare.UserSupervisor, {__MODULE__, user_id}) do
      {:ok, _pid}                        -> :ok
      {:error, {:already_started, _pid}} -> :ok
      err -> Flare.Logger.error(__MODULE__, "Failed to start UserState for #{user_id}", err)
    end
  end



  @doc """
  Merges new values into the cache and broadcasts any changes via PubSub.
  Keys set to `nil` are removed from the cache.
  Returns the diff map.
  """
  def update(nil, _values), do: %{}
  def update(user_id, new_values) do
    GenServer.call(via(user_id), {:update, new_values})
  end

  @doc """
  Removes specific keys from the cache so they reload on next access.
  Call this after a database write that changes user data.
  """
  def invalidate(nil, _keys), do: :ok
  def invalidate(user_id, keys) do
    GenServer.cast(via(user_id), {:invalidate, keys})
  end

  @doc """
  Gracefully stops the GenServer. Call on logout.
  """
  def stop(nil), do: :ok
  def stop(user_id) do
    case Registry.lookup(Flare.Registry, user_id) do
      [{pid, _}] -> GenServer.stop(pid, :normal)
      []         -> :ok
    end
  end

  @doc """
Returns the entire cached state map for a user.
Used by Channel on join to restore state across navigation.
Does not trigger any DB loading — returns only what is cached.
"""
def get_all(nil), do: %{}
def get_all(user_id) do
  GenServer.call(via(user_id), :get_all)
end

@doc """
  Saves values to the cache without broadcasting to other screens.
  Used by Channel to persist page-local state across navigation.
  Unlike update/2, this never triggers PubSub.

  ## Rationale
  Uses GenServer.call instead of cast.
  GenServer.cast is fire-and-forget — if the server node crashes between
  the cast being sent and the GenServer processing it, the state is silently
  lost. GenServer.call blocks until the GenServer has actually written the state.
  """
def save(nil, _values), do: :ok
def save(user_id, values) do
  # Changed from cast to call — ensures state is written before we return.
  # See module doc above for full rationale.
  GenServer.call(via(user_id), {:save, values})
end

  # ---------------------------------------------------------------------------
  # GenServer callbacks
  # ---------------------------------------------------------------------------

  def start_link(user_id) do
    GenServer.start_link(__MODULE__, user_id, name: via(user_id))
  end

  @impl true
  def init(user_id) do
    Flare.Logger.info(__MODULE__, "Started UserState for: #{user_id}")
    {:ok, %{user_id: user_id, data: %{}}, timeout()}
    # The third element starts the idle countdown immediately.
    # If no message arrives within timeout(), handle_info(:timeout, state) fires.
  end

  # ---------------------------------------------------------------------------
  # Idle timeout shutdown
  # Called automatically by the BEAM when no messages arrive within timeout().
  # Shuts down cleanly — DynamicSupervisor removes the process from the tree.
  # The next channel join for this user calls ensure_started/1 which starts fresh.
  # ---------------------------------------------------------------------------
  @impl true
  def handle_info(:timeout, state) do
    Flare.Logger.info(__MODULE__, "UserState idle timeout, shutting down: #{state.user_id}")
    {:stop, :normal, state}
    # :normal reason means the DynamicSupervisor does NOT restart this process.
    # That is correct — we want it gone until the user reconnects.
  end



  # ---------------------------------------------------------------------------
  # handle_call — {:update, new_values}
  # Merges values into cache. Broadcasts diff to all open screens via PubSub.
  # Fourth element resets the idle timeout after every call.
  # ---------------------------------------------------------------------------
  @impl true
  def handle_call({:update, new_values}, _from, state) do
    new_data =
      state.data
      |> Map.merge(new_values)
      |> Map.reject(fn {_, v} -> is_nil(v) end)
      # nil values are intentional removals — purge from cache entirely.
     

    diff = Flare.Diff.compute(state.data, new_data)

    unless Flare.Diff.empty?(diff) do
      Phoenix.PubSub.broadcast(
        Flare.PubSub,
        "user:#{state.user_id}",
        {:state_update, diff}
      )
    end

    {:reply, diff, %{state | data: new_data}, timeout()}
    # timeout() resets the countdown. An active user making frequent updates
    # will never time out because each update pushes the deadline forward.
  end

@impl true
  def handle_call(:get_all, _from, state) do
    {:reply, state.data, state, timeout()}
  end

  # ─────────────────────────────────────────────────────────────────────────
  # handle_call — {:save, values}
  #
  #  Change: Converted from handle_cast to handle_call so the caller
  # blocks until state is actually written. See save/2 doc for full rationale.
  #
  # Filters to only flare_ prefixed keys — prevents accidentally persisting
  # Phoenix internal assigns or other non-Flare data.
  #
  # nil values are intentional deletions — they are removed from the cache.
  # This mirrors the behavior in handle_call {:update, new_values}.
  # ─────────────────────────────────────────────────────────────────────────
  @impl true
  def handle_call({:save, values}, _from, state) do
    new_data =
      values
      |> Map.filter(fn {k, _v} -> flare_key?(k) end)
      |> then(&Map.merge(state.data, &1))
      |> Map.reject(fn {_k, v} -> is_nil(v) end)

    # :ok reply — the caller (Channel.push_diff_and_commands) unblocks immediately
    # after receiving this. The timeout() fourth element resets the idle countdown.
    {:reply, :ok, %{state | data: new_data}, timeout()}
  end

  # ---------------------------------------------------------------------------
  # handle_cast — {:invalidate, keys}
  # Removes keys from cache. No reply needed (cast, not call).
  # Third element resets the idle timeout after this message.
  # Note: cast uses {:noreply, state, timeout} not {:reply, ...}
  # ---------------------------------------------------------------------------
  @impl true
  def handle_cast({:invalidate, keys}, state) do
    new_data = Map.drop(state.data, List.wrap(keys))
    {:noreply, %{state | data: new_data}, timeout()}
    # timeout() resets here too. invalidate/2 is called after writes,
    # which means the user is active — don't shut down after an invalidation.
  end

  # ---------------------------------------------------------------------------
  # Private helpers
  # ---------------------------------------------------------------------------

  defp via(user_id), do: {:via, Registry, {Flare.Registry, user_id}}

  defp flare_key?(key), do: String.starts_with?(to_string(key), "flare_")

  # Read the configured timeout at call time.
  # Doing it here (not in init) means a config change takes effect on the
  # next message without restarting the process.
  defp timeout do
    Application.get_env(:flare, :user_state_timeout, @default_timeout)
  end


end
