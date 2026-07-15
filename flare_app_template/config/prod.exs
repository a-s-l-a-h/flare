# Location: flare_app_template/config/prod.exs

import Config

config :flare_app_template, FlareAppTemplate.Http.Endpoint,
  cache_static_manifest: "priv/static/cache_manifest.json"

config :flare, enable_logging: false

config :logger, level: :info
