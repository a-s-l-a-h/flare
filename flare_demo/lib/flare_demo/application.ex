defmodule FlareDemo.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    children = [
      FlareDemo.Repo, # <-- UPDATED
      {Phoenix.PubSub, name: FlareDemo.PubSub},
      FlareDemo.Endpoint
    ]
    opts = [strategy: :one_for_one, name: FlareDemo.Supervisor]
    {:ok, pid} = Supervisor.start_link(children, opts)

    FlareDemo.Bootstrapper.setup() # <-- UPDATED

    {:ok, pid}
  end

  @impl true
  def config_change(changed, _new, removed) do
    FlareDemo.Endpoint.config_change(changed, removed)
    :ok
  end
end
