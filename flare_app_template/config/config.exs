import Config

config :flare_app_template, FlareAppTemplate.Http.Endpoint,
  url: [host: "localhost"],
  adapter: Bandit.PhoenixAdapter,
  secret_key_base: "a_very_long_secret_key_base_for_flare_app_template_auth_1234567890",
  render_errors: [formats: [json: FlareAppTemplate.Http.Controllers.ErrorJSON], layout: false],
  pubsub_server: FlareAppTemplate.PubSub,
  live_view: [signing_salt: "changeme"]

config :flare,
  router:         FlareAppTemplate.FlareApp.FlareRouter,
  global_keys:    [:flare_overlay_visible, :flare_active_tab],
  enable_logging: true,
  endpoint:       FlareAppTemplate.Http.Endpoint,
  # ⬇️ NOW IT POINTS DIRECTLY TO THE USER MODEL ⬇️
  role_resolver:  {FlareAppTemplate.Core.Accounts.User, :get_role, 1}

config :flare, user_state_timeout: 600_000

# ⬇️ UPDATED REPO ⬇️
config :flare_app_template, ecto_repos: [FlareAppTemplate.Repo]
config :flare_app_template, FlareAppTemplate.Repo,
  database: Path.expand("../flare_app_template_dev.db", Path.dirname(__ENV__.file)),
  pool_size: 1,
  timeout: 60_000,
  journal_mode: :wal,
  pool_timeout: 60_000

config :phoenix, :json_library, Jason


# Flare optimization: gzip-compress layout and variables JSON at startup.
# Screens with use_cache true get their layout compressed once at startup.
# Binary WebSocket frames are used for init and layout_update events.
# Patch/state diffs are always plain JSON — they are tiny and never compressed.
# Set to false (or remove) to revert to plain JSON for all messages.
config :flare, optimize: true



config :flare_app_template, jwt_secret: "CHANGE_ME_TO_A_LONG_RANDOM_SECRET"

config :esbuild, :version, "0.25.0"

import_config "#{config_env()}.exs"
