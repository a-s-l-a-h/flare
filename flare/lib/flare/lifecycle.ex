# Location: flare/lib/flare/lifecycle.ex

defmodule Flare.Lifecycle do
  @moduledoc """
  Safely executes developer screen callbacks and formats error messages.

  Wraps mount/handle_event/handle_info in try/rescue so a bug in one
  user's screen never crashes the entire node — and the error message
  tells the developer exactly where to look.
  """

  def mount(view_module, params, socket) do
    Flare.Logger.info(__MODULE__, "Mounting #{inspect(view_module)}")
    try do
      view_module.mount(params, socket)
    rescue
      e ->
        reraise(
          "Flare error in #{inspect(view_module)}.mount/2:\n#{Exception.message(e)}\nCheck your mount function.",
          __STACKTRACE__
        )
    end
  end

  def handle_event(view_module, event, payload, socket) do
    Flare.Logger.info(__MODULE__, "Handling event '#{event}' in #{inspect(view_module)}")
    try do
      view_module.handle_event(event, payload, socket)
    rescue
      e ->
        reraise(
          "Flare error in #{inspect(view_module)}.handle_event/3:\nEvent: #{event}\n#{Exception.message(e)}",
          __STACKTRACE__
        )
    end
  end

  def handle_info(view_module, message, socket) do
    try do
      view_module.handle_info(message, socket)
    rescue
      e ->
        reraise(
          "Flare error in #{inspect(view_module)}.handle_info/2:\n#{Exception.message(e)}",
          __STACKTRACE__
        )
    end
  end
end
