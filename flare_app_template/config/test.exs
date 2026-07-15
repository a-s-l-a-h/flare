# file: flare_app_template/config/test.exs

import Config

config :flare_app_template, FlareAppTemplate.Http.Endpoint,
  http: [ip: {127, 0, 0, 1}, port: 4002],
  secret_key_base: "test_secret_key_base_at_least_64_chars_long_for_testing_only!!",
  server: false

config :flare, enable_logging: false
config :logger, level: :warning
