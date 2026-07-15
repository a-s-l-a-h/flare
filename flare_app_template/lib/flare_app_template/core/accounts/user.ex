defmodule FlareAppTemplate.Core.Accounts.User do
  @moduledoc """
  A person. Authentication method is NOT modeled here — see Identity.
  This keeps user profile data completely decoupled from how they log in,
  so adding Google/Apple/Keycloak later never touches this schema.
  """
  use Ecto.Schema
  import Ecto.Changeset
  alias FlareAppTemplate.Repo
  alias FlareAppTemplate.Core.Accounts.Identity

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "users" do
    field :email, :string
    field :role, :string, default: "user"
    field :first_name, :string
    field :last_name, :string
    timestamps()
  end

  def changeset(user, attrs) do
    user
    |> cast(attrs, [:email, :role, :first_name, :last_name])
    |> validate_required([:email])
    |> unique_constraint(:email)
  end

  def get(id), do: Repo.get(__MODULE__, id)

  @doc "Registers a brand-new user AND their password identity together."
  def register(%{email: email, password: password} = attrs) do
    Repo.transaction(fn ->
      with {:ok, user} <-
             %__MODULE__{}
             |> changeset(%{
               email: email,
               first_name: attrs[:first_name],
               last_name: attrs[:last_name]
             })
             |> Repo.insert(),
           {:ok, _identity} <- Identity.create_password(user.id, email, password) do
        user
      else
        {:error, reason} -> Repo.rollback(reason)
      end
    end)
  end

  @doc "Logs in via email/password. Returns {:ok, user_id} or {:error, reason}."
  def authenticate(email, password), do: Identity.authenticate_password(email, password)

  def update_profile(user_id, first_name, last_name) do
    case get(user_id) do
      nil ->
        {:error, :not_found}

      user ->
        user
        |> cast(%{first_name: first_name, last_name: last_name}, [:first_name, :last_name])
        |> Repo.update()
    end
  end

  def get_role("guest_" <> _), do: "guest"
  def get_role(user_id) do
    case get(user_id) do
      %{role: role} -> role
      nil -> nil
    end
  end
end
