defmodule FlareDemo.Endpoint do
  use Phoenix.Endpoint, otp_app: :flare_demo

  plug Plug.Static,
    at: "/",
    from: :flare_demo,
    gzip: not code_reloading?,
    only: ~w(assets images favicon.ico robots.txt index.html)

  if code_reloading? do
    plug Phoenix.CodeReloader
  end

  plug Plug.RequestId
  plug Plug.Parsers,
    parsers: [:urlencoded, :multipart, :json],
    pass: ["*/*"],
    json_decoder: Phoenix.json_library()

  plug Plug.MethodOverride
  plug Plug.Head

  # 👇 THIS IS FIXED: We declare the custom serializer here!
  socket "/socket", FlareDemo.UserSocket,
    websocket: [
      compress: true,
      serializer: [{Flare.Serializer, "~> 2.0.0"}]
    ],
    longpoll: false

  plug FlareDemo.HttpRouter
end
