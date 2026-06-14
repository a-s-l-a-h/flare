# Location: flare_demo/lib/flare_demo/application.ex

defmodule FlareDemo.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    children = [
      # Phoenix needs this for its own internal channel mechanics.
      # Flare.PubSub (started by Flare.Application) is separate —
      # used for Flare's state broadcasting between screens.
      {Phoenix.PubSub, name: FlareDemo.PubSub},
      FlareDemo.Endpoint
    ]

    opts = [strategy: :one_for_one, name: FlareDemo.Supervisor]
    Supervisor.start_link(children, opts)
  end

  @impl true
  def config_change(changed, _new, removed) do
    FlareDemo.Endpoint.config_change(changed, removed)
    :ok
  end
end
