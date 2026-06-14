defmodule Flare.Logger do
  @moduledoc """
  Internal logger for Flare library events.

  Control verbosity in `config/config.exs`:

      # Show all Flare internal logs (default: true in dev, false in prod)
      config :flare, enable_logging: true

      # Silence all Flare logs
      config :flare, enable_logging: false

  Errors are always logged regardless of this setting.
  """

  require Logger

  defp logging_enabled?, do: Application.get_env(:flare, :enable_logging, true)

  @doc "Logs verbose debugging information (disabled by default in production)."
  def debug(module, message, data \\ nil) do
    if logging_enabled?() do
      msg = if data, do: "#{message} | #{inspect(data)}", else: message
      Logger.debug("[FLARE DEBUG] [#{inspect(module)}] #{msg}")
    end
  end

  @doc "Logs important lifecycle events (channel joins, mounts, pushes)."
  def info(module, message) do
    if logging_enabled?() do
      Logger.info("[FLARE INFO] [#{inspect(module)}] #{message}")
    end
  end

  @doc "Always logged, regardless of the enable_logging setting."
  def error(module, message, error \\ nil) do
    msg = if error, do: "#{message} | #{inspect(error)}", else: message
    Logger.error("[FLARE ERROR] [#{inspect(module)}] #{msg}")
  end
end
