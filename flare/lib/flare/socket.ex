# Location: flare/lib/flare/socket.ex

defmodule Flare.Socket do
  @moduledoc """
  The state container passed to every view callback.

  You never create this struct directly. Flare creates it when a client
  joins a channel and passes it to `mount/2`, `handle_event/3`, and
  `handle_info/2`.

  Use `assign/3` for a single value or `assign/2` with a keyword list:

      socket = assign(socket, :flare_count, 5)
      socket = assign(socket, flare_count: 5, flare_name: "Ahmad")

  Both return a new socket — the original is never modified.
  """

  defstruct [
    :user_id,      # String user ID, or nil for anonymous sessions
    :screen_module,  # The Elixir module handling this screen e.g. MyApp.Welcome
    :screen_name,    # String screen name, e.g. "welcome", "product"
    assigns: %{},  # flare_ prefixed state values
    commands: []   # Commands to send to the client this cycle
  ]

  @type t :: %__MODULE__{
    user_id:  String.t() | nil,
    screen_module: module(),
    screen_name:   String.t(),
    assigns:  map(),
    commands: list()
  }

  @doc """
  Assigns a single key-value pair to the socket.
  Returns a new socket — the original is never modified.
  """
  def assign(%__MODULE__{assigns: assigns} = socket, key, value) when is_atom(key) do
    Flare.Logger.debug(__MODULE__, "Assigning key: #{key}")
    %{socket | assigns: Map.put(assigns, key, value)}
  end

  @doc """
  Assigns multiple key-value pairs at once from a keyword list.
  Returns a new socket — the original is never modified.
  """
  def assign(%__MODULE__{assigns: assigns} = socket, keyword_list) when is_list(keyword_list) do
    Flare.Logger.debug(__MODULE__, "Assigning #{length(keyword_list)} keys")
    new_assigns = Enum.into(keyword_list, assigns)
    %{socket | assigns: new_assigns}
  end
end
