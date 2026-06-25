defmodule FlareDemo.Users.User do
  use Ecto.Schema
  import Ecto.Changeset
  alias FlareDemo.Repo

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "users" do
    field :email, :string
    field :password_hash, :string
    field :role, :string, default: "user"
    field :first_name, :string
    field :last_name, :string
    timestamps()
    field :password, :string, virtual: true
  end

  # --- 1. SCHEMA LOGIC ---
  def changeset(user, attrs) do
    user
    |> cast(attrs, [:email, :password, :role, :first_name, :last_name])
    |> validate_required([:email, :password])
    |> unique_constraint(:email)
    |> put_change(:password_hash, Pbkdf2.hash_pwd_salt(attrs[:password] || attrs["password"]))
  end

  # --- 2. DB LOGIC (Django Style) ---
  def register(attrs) do
    %__MODULE__{} |> changeset(attrs) |> Repo.insert()
  end

  def authenticate(email, password) do
    user = Repo.get_by(__MODULE__, email: email)
    cond do
      user && Pbkdf2.verify_pass(password, user.password_hash) -> {:ok, user.id}
      user -> {:error, "Invalid password"}
      true -> {:error, "User not found"}
    end
  end

  def get(id), do: Repo.get(__MODULE__, id)
  def count_all, do: Repo.aggregate(__MODULE__, :count, :id)

  # ⬇️ ADDED THIS: Safely update user profile details ⬇️
  def update_profile(user_id, first_name, last_name) do
    case get(user_id) do
      nil -> {:error, :not_found}
      user ->
        user
        |> cast(%{first_name: first_name, last_name: last_name}, [:first_name, :last_name])
        |> Repo.update()
    end
  end

  # --- 3. FLARE AUTH LOGIC ---
  def get_role("guest_" <> _), do: "guest"
  def get_role(user_id) do
    case get(user_id) do
      %{role: role} -> role
      nil -> nil
    end
  end

  # --- 4. ADMIN BOOTSTRAPPER ---
  def upsert_admin(email, password) do
    case Repo.get_by(__MODULE__, email: email) do
      nil ->
        register(%{email: email, password: password, role: "admin", first_name: "Super", last_name: "Admin"})
        IO.puts("✅ Admin created: #{email}")
      user ->
        user |> changeset(%{password: password, role: "admin"}) |> Repo.update()
        IO.puts("✅ Admin updated: #{email}")
    end
  end
end
