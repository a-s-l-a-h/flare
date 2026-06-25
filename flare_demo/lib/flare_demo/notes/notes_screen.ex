defmodule FlareDemo.NotesScreen do
  use Flare.Screen
  alias FlareDemo.Notes.Note

  screen_dir __DIR__

  @impl true
  def authorize("guest_" <> _, _params), do: {:error, "Please create an account to use Notes."}
  def authorize(_user_id, _params), do: :ok

  @impl true
  def mount(_params, socket) do
    {:ok, refresh_notes(socket)}
  end

  @impl true
  def handle_event("add_note", %{"content" => content}, socket) do
    if String.trim(content) != "" do
      Note.create(socket.user_id, content)
    end

    socket
    |> assign(:local_new_note, "")
    |> haptic(:success)
    |> refresh_notes()
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("go_back", _payload, socket) do
    socket
    # ⬇️ MEMORY CLEANUP: Delete the heavy notes text from RAM before we leave! ⬇️
    |> assign(:flare_notes_text, nil)
    |> navigate("welcome")
    |> then(&{:noreply, &1})
  end

  defp refresh_notes(socket) do
    notes = Note.list_for_user(socket.user_id)
    formatted = if notes == [], do: "No notes yet. Add one below!", else: Enum.map_join(notes, "\n\n", & "📝 #{&1.content}")
    assign(socket, :flare_notes_text, formatted)
  end
end
