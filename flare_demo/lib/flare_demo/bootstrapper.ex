defmodule FlareDemo.Bootstrapper do
  def setup do
    Ecto.Adapters.SQL.query!(FlareDemo.Repo, """
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        email TEXT UNIQUE,
        password_hash TEXT,
        role TEXT,
        first_name TEXT,
        last_name TEXT,
        inserted_at DATETIME,
        updated_at DATETIME
      )
    """)

    Ecto.Adapters.SQL.query!(FlareDemo.Repo, """
      CREATE TABLE IF NOT EXISTS notes (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        content TEXT,
        inserted_at DATETIME,
        updated_at DATETIME
      )
    """)

    admin_email = System.get_env("ADMIN_EMAIL")
    admin_pass = System.get_env("ADMIN_PASS")
    if admin_email && admin_pass do
      FlareDemo.Users.User.upsert_admin(admin_email, admin_pass)
    end
  end
end
