defmodule FlareDemo.NotesScreen do
  use Flare.Screen
  alias FlareDemo.Notes.Note

  screen_dir __DIR__
  # 2. Add this line to turn off caching for this screen
  #use_cache false

  @per_page 5

  @impl true
  def authorize("guest_" <> _, _params), do: {:error, "Please create an account to use Notes."}
  def authorize(_user_id, _params), do: :ok

  @impl true
  def mount(_params, socket) do
    {:ok, load_page(socket, 1)}
  end

  @impl true
  def handle_event("add_note", %{"content" => content}, socket) do
    if String.trim(content) != "" do
      Note.create(socket.user_id, content)
    end

    socket
    |> assign(:local_new_note, "")
    |> haptic(:success)
    |> load_page(1)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("next_page", _payload, socket) do
    current = socket.assigns[:flare_current_page] || 1
    total = Note.count_for_user(socket.user_id)
    max_page = max(ceil_div(total, @per_page), 1)
    target = min(current + 1, max_page)

    socket
    |> load_page(target)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("prev_page", _payload, socket) do
    current = socket.assigns[:flare_current_page] || 1
    target = max(current - 1, 1)

    socket
    |> load_page(target)
    |> then(&{:noreply, &1})
  end

  # flare_confirm_slot is server-synced (flare_ prefix) because whether the
  # confirm UI is showing is a server decision the client must be told about
  # — local_ variables never get sent to the client by Flare.Diff, so this
  # MUST be flare_, not local_.
  @impl true
  def handle_event("request_delete_" <> slot_index, _payload, socket) do
    socket
    |> assign(:flare_confirm_slot, String.to_integer(slot_index))
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("cancel_delete", _payload, socket) do
    socket
    |> assign(:flare_confirm_slot, -1)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("confirm_delete_" <> slot_index, _payload, socket) do
    note_id = socket.assigns[:"local_note_#{slot_index}_id"]
    current_page = socket.assigns[:flare_current_page] || 1

    if note_id && note_id != "" do
      Note.delete(socket.user_id, note_id)
    end

    new_total = Note.count_for_user(socket.user_id)
    max_page = max(ceil_div(new_total, @per_page), 1)
    landing_page = min(current_page, max_page)

    socket
    |> assign(:flare_confirm_slot, -1)
    |> haptic(:warning)
    |> load_page(landing_page)
    |> then(&{:noreply, &1})
  end

  @impl true
  def handle_event("toggle_theme", _payload, socket) do
    current = socket.assigns[:flare_dark_mode] || false
    {:noreply, assign(socket, :flare_dark_mode, !current)}
  end

  @impl true
  def handle_event("go_back", _payload, socket) do
    {:noreply, navigate(socket, "welcome")}
  end

  @impl true
  def handle_event("logout", _payload, socket) do
    {:noreply, clear_storage(socket)}
  end

  # ---------------------------------------------------------------------------
  # Private
  # ---------------------------------------------------------------------------

  defp load_page(socket, page) do
    notes = Note.page_for_user(socket.user_id, page, @per_page)
    total = Note.count_for_user(socket.user_id)
    max_page = max(ceil_div(total, @per_page), 1)

    slot_assigns =
      0..(@per_page - 1)
      |> Enum.flat_map(fn i ->
        case Enum.at(notes, i) do
          nil ->
            [
              {:"flare_note_#{i}_visible", false},
              {:"flare_note_#{i}_text", ""},
              {:"flare_note_#{i}_date", ""},
              {:"local_note_#{i}_id", ""}
            ]

          note ->
            [
              {:"flare_note_#{i}_visible", true},
              {:"flare_note_#{i}_text", note.content},
              {:"flare_note_#{i}_date", format_date(note.inserted_at)},
              {:"local_note_#{i}_id", note.id}
            ]
        end
      end)

    socket
    |> assign(slot_assigns)
    |> assign(:flare_current_page, page)
    |> assign(:flare_total_pages, max_page)
    |> assign(:flare_notes_total, total)
    |> assign(:flare_has_next, page < max_page)
    |> assign(:flare_has_prev, page > 1)
    |> assign(:flare_notes_empty, total == 0)
    # Changing page always clears any open confirm prompt — confirming a
    # delete on a row that's about to be replaced by different data makes
    # no sense.
    |> assign(:flare_confirm_slot, -1)
  end

  defp ceil_div(a, b), do: div(a + b - 1, b)

  defp format_date(%NaiveDateTime{} = dt) do
    months = ~w(Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec)
    month = Enum.at(months, dt.month - 1)
    hour = String.pad_leading(Integer.to_string(dt.hour), 2, "0")
    minute = String.pad_leading(Integer.to_string(dt.minute), 2, "0")
    "#{month} #{dt.day}, #{dt.year} · #{hour}:#{minute}"
  end
end
