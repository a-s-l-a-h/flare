# Location: flare_demo/config/config.exs

import Config

config :flare_demo, FlareDemo.Endpoint,
  url: [host: "localhost"],
  adapter: Bandit.PhoenixAdapter,
  render_errors: [
    formats: [json: FlareDemo.ErrorJSON],
    layout: false
  ],
  pubsub_server: FlareDemo.PubSub,
  live_view: [signing_salt: "changeme"]

config :flare,
  router:       FlareDemo.FlareRouter,
  global_keys:  [],
  enable_logging: true,
  endpoint:     FlareDemo.Endpoint 

config :esbuild,
  version: "0.17.11",
  flare_demo: [
    args: ~w(web/app.js --bundle --target=es2017 --outdir=../priv/static/assets),
    cd: Path.expand("../assets", __DIR__),
    env: %{"NODE_PATH" => Path.expand("../deps", __DIR__)}
  ]

import_config "#{config_env()}.exs"
config :flare, user_state_timeout: 600_000   # 10 minutes (was 5)
