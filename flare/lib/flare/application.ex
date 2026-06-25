# Location: flare/lib/flare/application.ex

defmodule Flare.Application do
  @moduledoc """
  Starts all Flare infrastructure before accepting any WebSocket connections.

  Started automatically when :flare is listed as a dependency.
  You do not need to start this manually.

  ## Startup sequence

  1. ETS layout cache table is created (Flare.Layout.init_cache/0)
  2. Supervisor starts: Registry, UserSupervisor, PubSub
  3. All registered screens' JSON files are loaded into ETS
     (Flare.Layout.warm_cache/1)

  Step 3 happens AFTER the supervisor starts because warm_cache reads
  the router from application config, which is available at runtime.
  ETS is created in step 1 (before supervisor) so the table exists
  before any channel could theoretically try to use it.

  ## Processes started

  - `Flare.Registry`       — maps user_id strings to GenServer PIDs
  - `Flare.UserSupervisor` — supervises per-user GenServers, one_for_one
  - `Flare.PubSub`         — broadcasts global state changes between screens

  ## Layout cache

  Every screen registered in the router with `use_cache true` (default)
  has its layout/ and state/ JSON files read from disk and stored in ETS
  during startup. Channel joins for those screens read from RAM only —
  zero disk reads at runtime.

  Screens with `use_cache false` are skipped — they always read from disk.
  """

  use Application

  def start(_type, _args) do
    Flare.Logger.info(__MODULE__, "Starting Flare infrastructure")

    # Step 1: Create ETS table BEFORE supervisor starts.
    # The table must exist before any channel process could try to read it.
    # init_cache/0 is idempotent — safe if called again on test restarts.
    :ok = Flare.Layout.init_cache()

    children = [
      # Maps user_id strings to their UserState GenServer PID.
      # Any code can find a user's process with:
      #   Registry.lookup(Flare.Registry, user_id)
      {Registry, keys: :unique, name: Flare.Registry},

      # Supervises one UserState GenServer per connected user.
      # one_for_one: a single user crash never affects other users.
      {DynamicSupervisor, strategy: :one_for_one, name: Flare.UserSupervisor},

      # PubSub for broadcasting global state changes between screens.
      # Used by UserState and the public Flare.broadcast_to_screen API.
      {Phoenix.PubSub, name: Flare.PubSub}
    ]

    # Step 2: Start supervisor tree.
    opts = [strategy: :one_for_one, name: Flare.Supervisor]
    {:ok, pid} = Supervisor.start_link(children, opts)

    # Step 3: Warm the ETS cache with all registered screens.
    # Must happen AFTER supervisor starts (router is read from app config).
    # Must happen BEFORE any WebSocket connections are accepted.
    # The Phoenix endpoint starts AFTER Flare.Application returns {:ok, pid},
    # so no channel can join before warm_cache completes.
    warm_layout_cache()

    {:ok, pid}
  end

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  defp warm_layout_cache do
    case Application.get_env(:flare, :router) do
      nil ->
        # Router not configured yet — possible in test environments or when
        # Flare is loaded but the host app hasn't set config yet.
        # Not an error at startup — the channel will raise at join time
        # if the router is still missing.
        Flare.Logger.info(__MODULE__, "No router configured — skipping layout cache warm")

      router ->
        screens = router.registered_screens()
        Flare.Logger.info(__MODULE__, "Warming cache for router: #{inspect(router)}")
        Flare.Layout.warm_cache(screens)
    end
  end
end
