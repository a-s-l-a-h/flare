defmodule FlareAppTemplate.Core.Accounts.Identity do
  use Ecto.Schema
  import Ecto.Changeset
  alias FlareAppTemplate.Repo

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "identities" do
    field :user_id, :binary_id
    field :provider, :string
    field :provider_uid, :string
    field :credential, :string
    timestamps()
  end

  def changeset(identity, attrs) do
    identity
    |> cast(attrs, [:user_id, :provider, :provider_uid, :credential])
    |> validate_required([:user_id, :provider, :provider_uid])
    |> unique_constraint([:provider, :provider_uid])
  end

  def find(provider, provider_uid) do
    Repo.get_by(__MODULE__, provider: provider, provider_uid: provider_uid)
  end

  def create_password(user_id, email, plain_password) do
    %__MODULE__{}
    |> changeset(%{
      user_id: user_id,
      provider: "password",
      provider_uid: String.downcase(email),
      credential: Pbkdf2.hash_pwd_salt(plain_password)
    })
    |> Repo.insert()
  end

  def authenticate_password(email, plain_password) do
    case find("password", String.downcase(email)) do
      nil ->
        {:error, "User not found"}

      %__MODULE__{credential: hash, user_id: user_id} ->
        if Pbkdf2.verify_pass(plain_password, hash) do
          {:ok, user_id}
        else
          {:error, "Invalid password"}
        end
    end
  end
end
