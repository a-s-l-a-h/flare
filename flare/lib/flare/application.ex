defmodule Flare.Application do
  @moduledoc """
  Starts the three infrastructure processes Flare needs before accepting
  any WebSocket connections.

  Started automatically when `:flare` is listed as a dependency.
  You do not need to start this manually.

  ## Processes started

  - `Flare.Registry` — maps `user_id` strings to GenServer PIDs
  - `Flare.UserSupervisor` — supervises per-user GenServers, restarts on crash
  - `Flare.PubSub` — broadcasts global state between screens
  """

  use Application

  def start(_type, _args) do
    Flare.Logger.info(__MODULE__, "Starting Flare infrastructure")

    children = [
      # Maps user_id strings to their UserState GenServer PID.
      # Any code can find a user's process with:
      #   Registry.lookup(Flare.Registry, user_id)
      {Registry, keys: :unique, name: Flare.Registry},

      # Supervises one UserState GenServer per connected user.
      # Uses one_for_one so a single user crash never affects others.
      {DynamicSupervisor, strategy: :one_for_one, name: Flare.UserSupervisor},

      # PubSub for broadcasting global state changes between screens.
      # Used by UserState and the public Flare.broadcast_to_screen API.
      {Phoenix.PubSub, name: Flare.PubSub}
    ]

    # one_for_one: if Registry crashes, only Registry restarts.
    # Other children and all connected users are unaffected.
    opts = [strategy: :one_for_one, name: Flare.Supervisor]
    Supervisor.start_link(children, opts)
  end
end
