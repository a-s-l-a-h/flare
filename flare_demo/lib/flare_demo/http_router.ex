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

  # ── App — catch all, serve index.html ───────────────────────────────────
  scope "/" do
    pipe_through :browser
    get "/*path", FlareDemo.PageController, :index
  end
end
