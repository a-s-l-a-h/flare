# Location: flare_app_template/config/dev.exs

import Config

config :flare_app_template, FlareAppTemplate.Http.Endpoint,
  http: [ip: {0, 0, 0, 0}, port: 4000],
  check_origin: false,
  code_reloader: false,
  debug_errors: true,
  secret_key_base: "dev_secret_key_base_min_64_chars_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

config :logger, :console,
  format: "[$level] $message\n"



config :phoenix, :stacktrace_depth, 20
config :phoenix, :plug_init_mode, :runtime
config :flare, user_state_timeout: 600_000   # 10 minutes (was 5)
