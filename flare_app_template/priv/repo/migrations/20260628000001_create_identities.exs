defmodule FlareAppTemplate.Repo.Migrations.CreateIdentities do
  use Ecto.Migration

  @moduledoc """
  Splits "how someone logs in" away from "who they are".

  `users` = the person (profile, role).
  `identities` = one row per login method they've connected
                 (password today; google/apple/keycloak rows later
                 with zero changes to the users table or existing data).
  """

  def change do
    create table(:users, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :email, :string, null: false
      add :role, :string, default: "user", null: false
      add :first_name, :string
      add :last_name, :string
      timestamps()
    end

    create unique_index(:users, [:email])

    create table(:identities, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false

      # "password" today. Later: "google", "apple", "keycloak", "guest".
      add :provider, :string, null: false

      # For provider "password": holds the pbkdf2 hash.
      # For provider "google"/"apple"/etc: holds their subject/sub id.
      # Never put raw secrets here for OAuth providers — only their stable user id.
      add :provider_uid, :string, null: false
      add :credential, :string

      timestamps()
    end

    # One identity row per (provider, provider_uid) — e.g. one row per
    # Google account ever linked, one row per email/password pair.
    create unique_index(:identities, [:provider, :provider_uid])
    create index(:identities, [:user_id])
  end
end
