defmodule FlareDemo.Notes.Note do
  use Ecto.Schema
  import Ecto.Changeset
  import Ecto.Query
  alias FlareDemo.Repo

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

  @doc """
  Returns exactly one page of notes (5 rows), newest first, using a real
  SQL OFFSET — this is true pagination, not "reveal more pre-built slots".
  page 1 -> OFFSET 0, page 2 -> OFFSET 5, page 3 -> OFFSET 10, etc.
  """
  def page_for_user(user_id, page, per_page) when page >= 1 do
    offset = (page - 1) * per_page

    __MODULE__
    |> where(user_id: ^user_id)
    |> order_by(desc: :inserted_at)
    |> limit(^per_page)
    |> offset(^offset)
    |> Repo.all()
  end

  def count_for_user(user_id) do
    __MODULE__
    |> where(user_id: ^user_id)
    |> Repo.aggregate(:count, :id)
  end

  def count_all, do: Repo.aggregate(__MODULE__, :count, :id)

  @doc """
  Deletes a note, but only if it belongs to `user_id` — prevents one user
  from deleting another user's note by forging/guessing an id client-side.
  """
  def delete(user_id, note_id) do
    case Repo.get(__MODULE__, note_id) do
      %__MODULE__{user_id: ^user_id} = note -> Repo.delete(note)
      %__MODULE__{} -> {:error, :unauthorized}
      nil -> {:error, :not_found}
    end
  end
end
