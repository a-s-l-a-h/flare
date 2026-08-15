defmodule FlareAppTemplate.FlareApp.Screens.Counter do
  @moduledoc """
  Reference screen for: reading a value from the DB on mount, and writing
  back to the DB on every button press. No sharing between users — each
  person's counter is their own row, looked up by owner_id.
  """
  use Flare.Screen
  alias FlareAppTemplate.Core.Counters.Counter, as: CounterRecord

  screen_dir __DIR__
  use_cache false # set false in development time

  @impl true
  def mount(_params, socket) do
    {:ok, counter} = CounterRecord.get_or_create_for_owner(socket.user_id)
    {:ok, load_counter(socket, counter)}
  end

  @impl true
  def handle_event("go_back", _payload, socket) do
    {:noreply, navigate(socket, "home")}
  end

  @impl true
  def handle_event("increment", _payload, socket) do
    {:ok, counter} = CounterRecord.get_or_create_for_owner(socket.user_id)
    {:ok, updated} = CounterRecord.increment(counter)
    sync_counter(socket.user_id, updated)
    {:noreply, load_counter(socket, updated)}
  end

  @impl true
  def handle_event("decrement", _payload, socket) do
    {:ok, counter} = CounterRecord.get_or_create_for_owner(socket.user_id)
    {:ok, updated} = CounterRecord.decrement(counter)
    sync_counter(socket.user_id, updated)
    {:noreply, load_counter(socket, updated)}
  end

  @impl true
  def handle_event("reset_count", _payload, socket) do
    {:ok, counter} = CounterRecord.get_or_create_for_owner(socket.user_id)
    {:ok, updated} = CounterRecord.reset(counter)
    sync_counter(socket.user_id, updated)
    {:noreply, load_counter(socket, updated)}
  end

  defp load_counter(socket, counter) do
    socket
    |> assign(:flare_count, counter.current_count)
    |> assign(:flare_last_updated, format_time(counter.last_updated_at))
  end

  # Pushes the freshly-written DB value to this SAME user's other open
  # screens/devices. The DB write already happened (CounterRecord.increment
  # etc. above) — this is not a second read-modify-write, it's just telling
  # UserState "here is the value that just became true, hand it to whoever
  # else is connected as this user." load_counter/2 above still handles
  # THIS screen's own display, unchanged.
  defp sync_counter(user_id, counter) do
    Flare.sync_user_state(user_id, %{
      flare_count: counter.current_count,
      flare_last_updated: format_time(counter.last_updated_at)
    })
  end

  defp format_time(nil), do: ""
  defp format_time(%DateTime{} = dt), do: Calendar.strftime(dt, "%b %-d, %Y, %I:%M %p")
end
