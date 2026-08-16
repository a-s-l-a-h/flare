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
    {:noreply, load_counter(socket, updated, :auto)}
  end

  @impl true
  def handle_event("decrement", _payload, socket) do
    {:ok, counter} = CounterRecord.get_or_create_for_owner(socket.user_id)
    {:ok, updated} = CounterRecord.decrement(counter)
    {:noreply, load_counter(socket, updated, :auto)}
  end

  @impl true
  def handle_event("reset_count", _payload, socket) do
    {:ok, counter} = CounterRecord.get_or_create_for_owner(socket.user_id)
    {:ok, updated} = CounterRecord.reset(counter)
    {:noreply, load_counter(socket, updated, :auto)}
  end

  # ONE function, same shape everywhere.
  #
  #   load_counter(socket, counter)          -> local-only assign (used by mount/2:
  #                                              first load, nothing else to notify yet)
  #   load_counter(socket, counter, target)  -> assign + push to other devices
  #                                              (:auto, :same_screen, "name", or [names])
  #
  # Same two values (flare_count, flare_last_updated) either way — the only
  # difference is whether it also notifies this user's other open screens.
  defp load_counter(socket, counter) do
    socket
    |> assign(:flare_count, counter.current_count)
    |> assign(:flare_last_updated, format_time(counter.last_updated_at))
  end

  defp load_counter(socket, counter, target) do
    Flare.cross_device_update(
      socket,
      %{
        flare_count: counter.current_count,
        flare_last_updated: format_time(counter.last_updated_at)
      },
      target
    )
  end

  defp format_time(nil), do: ""
  defp format_time(%DateTime{} = dt), do: Calendar.strftime(dt, "%b %-d, %Y, %I:%M %p")
end
