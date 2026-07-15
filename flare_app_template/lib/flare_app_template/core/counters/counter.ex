defmodule FlareAppTemplate.Core.Counters.Counter do
  @moduledoc """
  A single per-user counter row. This is the demo's stand-in for "any simple
  piece of user data" — the point isn't the counter itself, it's showing the
  pattern: mount reads from the DB, handle_event writes back to the DB.
  """
  use Ecto.Schema
  import Ecto.Changeset
  alias FlareAppTemplate.Repo

  @primary_key {:id, :binary_id, autogenerate: true}
schema "counters" do
  field :owner_id, :string
  field :current_count, :integer, default: 0
  field :last_updated_at, :utc_datetime
  timestamps()
end

def changeset(counter, attrs) do
  counter
  |> cast(attrs, [:owner_id, :current_count, :last_updated_at])
  |> validate_required([:owner_id])
  |> unique_constraint(:owner_id)
end

  @doc "Returns this user's counter, creating one at 0 if they don't have one yet."
  def get_or_create_for_owner(owner_id) do
    case Repo.get_by(__MODULE__, owner_id: owner_id) do
      nil ->
        %__MODULE__{}
        |> changeset(%{owner_id: owner_id, current_count: 0})
        |> Repo.insert()

      counter ->
        {:ok, counter}
    end
  end

  def increment(counter), do: apply_changes(counter, %{current_count: counter.current_count + 1})

  def decrement(counter) do
    apply_changes(counter, %{current_count: max(counter.current_count - 1, 0)})
  end

  def reset(counter), do: apply_changes(counter, %{current_count: 0})

  # This is the actual DB write. Every button press ends up here.
  defp apply_changes(counter, attrs) do
    attrs = Map.put(attrs, :last_updated_at, DateTime.truncate(DateTime.utc_now(), :second))

    counter
    |> cast(attrs, [:current_count, :last_updated_at])
    |> Repo.update()
  end
end
