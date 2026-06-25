defmodule FlareDemo.Notes.Note do
  use Ecto.Schema
  import Ecto.Changeset
  import Ecto.Query
  alias FlareDemo.Repo  # <--- FIXED: Now points to the correct new Repo!

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "notes" do
    field :user_id, :string
    field :content, :string
    timestamps()
  end

  def create(user_id, content) do
    %__MODULE__{}
    |> cast(%{user_id: user_id, content: content}, [:user_id, :content])
    |> validate_required([:user_id, :content])
    |> Repo.insert()
  end

  def list_for_user(user_id) do
    __MODULE__
    |> where(user_id: ^user_id)
    |> order_by(desc: :inserted_at)
    |> Repo.all()
  end

  def count_all, do: Repo.aggregate(__MODULE__, :count, :id)
end
