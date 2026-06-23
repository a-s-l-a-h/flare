defmodule FlareDemo.UserSocket do
  use Phoenix.Socket

  channel "flare:*", Flare.Channel

  @impl true
  def connect(%{"token" => token}, socket, _connect_info) do
    endpoint = Application.get_env(:flare, :endpoint) ||
      raise "Missing config: config :flare, endpoint: MyApp.Endpoint"

    case Phoenix.Token.verify(endpoint, "flare_session", token, max_age: 86_400) do
      {:ok, user_id} ->
        {:ok, assign(socket, :user_id, user_id)}

      {:error, _} ->
        case Phoenix.Token.verify(endpoint, "flare_guest", token, max_age: 2_592_000) do
          {:ok, guest_id} ->
            {:ok, assign(socket, :user_id, guest_id)}

          {:error, :expired} ->
            {:error, %{reason: "session_expired"}}

          {:error, _} ->
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
end
