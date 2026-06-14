# Location: flare_demo/config/prod.exs

import Config

config :flare_demo, FlareDemo.Endpoint,
  cache_static_manifest: "priv/static/cache_manifest.json"

config :flare, enable_logging: false

config :logger, level: :info
