defmodule FlareAppTemplate.Http.Endpoint do
  use Phoenix.Endpoint, otp_app: :flare_app_template

  plug Plug.Static,
  at: "/",
  from: Path.expand("../../../../flare-web-client/dist", __DIR__),
  gzip: false,
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
  socket "/socket", FlareAppTemplate.Http.UserSocket,
    websocket: [
      compress: true,
      serializer: [{Flare.Serializer, "~> 2.0.0"}]
    ],
    longpoll: false

  plug FlareAppTemplate.Http.HttpRouter
end
