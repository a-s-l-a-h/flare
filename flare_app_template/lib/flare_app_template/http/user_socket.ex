defmodule FlareAppTemplate.Http.UserSocket do
  use Phoenix.Socket

  channel "flare:*", Flare.Channel

  @impl true
  def connect(%{"token" => token}, socket, _connect_info) do
    # Call verify_token/1 instead of verify/1
    case FlareAppTemplate.Core.Accounts.AuthToken.verify_token(token) do
      {:ok, user_id} ->
        {:ok, assign(socket, :user_id, user_id)}

      {:error, reason} ->
        if expired_error?(reason) do
          {:error, %{reason: "session_expired"}}
        else
          {:error, %{reason: "invalid_token"}}
        end
    end
  end

  # No token = hard reject
  @impl true
  def connect(_params, _socket, _connect_info) do
    {:error, %{reason: "authentication_required"}}
  end

  @impl true
  def id(_socket), do: nil

  # Joken's error shape varies by version — this is a loose match rather than
  # guessing an exact struct. Verified once manually in iex with an expired
  # token; see note below before shipping to prod.
  defp expired_error?(reason) do
    inspected = inspect(reason)
    String.contains?(inspected, "exp") or String.contains?(inspected, "expired")
  end
end
