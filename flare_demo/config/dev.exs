# Location: flare_demo/config/dev.exs

import Config

config :flare_demo, FlareDemo.Endpoint,
  http: [ip: {0, 0, 0, 0}, port: 4000],
  check_origin: false,
  code_reloader: true,
  debug_errors: true,
  secret_key_base: "dev_secret_key_base_min_64_chars_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  watchers: [
    esbuild: {Esbuild, :install_and_run, [:flare_demo, ~w(--sourcemap=inline --watch)]}
  ]

config :logger, :console,
  format: "[$level] $message\n"

config :phoenix, :stacktrace_depth, 20
config :phoenix, :plug_init_mode, :runtime
config :flare, user_state_timeout: 600_000   # 10 minutes (was 5)
