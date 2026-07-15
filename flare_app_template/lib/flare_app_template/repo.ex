defmodule FlareAppTemplate.Repo do
  use Ecto.Repo,
    otp_app: :flare_app_template,
    adapter: Ecto.Adapters.SQLite3
end
