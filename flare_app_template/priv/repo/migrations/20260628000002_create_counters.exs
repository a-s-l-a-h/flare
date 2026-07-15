defmodule FlareAppTemplate.Repo.Migrations.CreateCounters do
  use Ecto.Migration

  def change do
    create table(:counters, primary_key: false) do
      add :id, :binary_id, primary_key: true
      # Plain string, not a references() to :users — Flare's user_id includes
      # guest ids ("guest_xxxx") that never get a row in the users table.
      add :owner_id, :string, null: false
      add :current_count, :integer, default: 0, null: false
      add :last_updated_at, :utc_datetime
      timestamps()
    end

    create unique_index(:counters, [:owner_id])
  end
end
