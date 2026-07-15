defmodule FlareAppTemplate.Http.HttpRouter do
  use Phoenix.Router
  pipeline :browser do plug :accepts, ["html"] end
  pipeline :api do plug :accepts, ["json"] end

  scope "/auth" do
    pipe_through :api
    post "/login",    FlareAppTemplate.Http.Controllers.AuthController, :login
    post "/register", FlareAppTemplate.Http.Controllers.AuthController, :register
    post "/guest",    FlareAppTemplate.Http.Controllers.AuthController, :guest
  end

  scope "/" do
    pipe_through :browser
    get "/*path", FlareAppTemplate.Http.Controllers.PageController, :index
  end
end
