defmodule FlareAppTemplate.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    children = [
      FlareAppTemplate.Repo,
      {Phoenix.PubSub, name: FlareAppTemplate.PubSub},
      FlareAppTemplate.Http.Endpoint
    ]

    opts = [strategy: :one_for_one, name: FlareAppTemplate.Supervisor]
    Supervisor.start_link(children, opts)
  end

  @impl true
  def config_change(changed, _new, removed) do
    FlareAppTemplate.Http.Endpoint.config_change(changed, removed)
    :ok
  end
end
