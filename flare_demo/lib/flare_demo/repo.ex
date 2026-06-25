defmodule FlareDemo.Repo do
  use Ecto.Repo,
    otp_app: :flare_demo,
    adapter: Ecto.Adapters.SQLite3
end
