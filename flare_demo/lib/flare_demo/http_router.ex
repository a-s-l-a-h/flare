# flare_demo/lib/flare_demo/http_router.ex

defmodule FlareDemo.HttpRouter do
  use Phoenix.Router

  pipeline :browser do
    plug :accepts, ["html"]
  end

  pipeline :api do
    plug :accepts, ["json"]
  end

  # ── Auth routes ─────────────────────────────────────────────────────────
  scope "/auth" do
    pipe_through :api
    post "/login",  FlareDemo.AuthController, :login
    post "/guest",  FlareDemo.AuthController, :guest
  end

  scope "/auth" do
    pipe_through :browser
    get  "/google",            FlareDemo.AuthController, :google_redirect
    get  "/google/callback",   FlareDemo.AuthController, :google_callback
    get  "/keycloak",          FlareDemo.AuthController, :keycloak_redirect
    get  "/keycloak/callback", FlareDemo.AuthController, :keycloak_callback
  end

  # ── App — catch all, serve index.html ───────────────────────────────────
  scope "/" do
    pipe_through :browser
    get "/*path", FlareDemo.PageController, :index
  end
end
