import Config

config :flare_demo, FlareDemo.Endpoint,
  url: [host: "localhost"],
  adapter: Bandit.PhoenixAdapter,
  secret_key_base: "a_very_long_secret_key_base_for_flare_demo_auth_1234567890",
  render_errors: [formats: [json: FlareDemo.ErrorJSON], layout: false],
  pubsub_server: FlareDemo.PubSub,
  live_view: [signing_salt: "changeme"]

config :flare,
  router:         FlareDemo.FlareRouter,
  global_keys:    [:flare_dark_mode],
  enable_logging: true,
  endpoint:       FlareDemo.Endpoint,
  # ⬇️ NOW IT POINTS DIRECTLY TO THE USER MODEL ⬇️
  role_resolver:  {FlareDemo.Users.User, :get_role, 1}

config :flare, user_state_timeout: 600_000

# ⬇️ UPDATED REPO ⬇️
config :flare_demo, ecto_repos: [FlareDemo.Repo]
config :flare_demo, FlareDemo.Repo,
  database: Path.expand("../flare_demo_dev.db", Path.dirname(__ENV__.file)),
  pool_size: 5

config :phoenix, :json_library, Jason

config :esbuild,
  version: "0.17.11",
  flare_demo: [
    args: ~w(web/app.js --bundle --target=es2017 --outdir=../priv/static/assets),
    cd: Path.expand("../assets", __DIR__),
    env: %{"NODE_PATH" => Path.expand("../deps", __DIR__)}
  ]

import_config "#{config_env()}.exs"
