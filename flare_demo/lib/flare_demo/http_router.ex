defmodule FlareDemo.HttpRouter do
  use Phoenix.Router

  pipeline :browser do
    plug :accepts, ["html"]
  end

  scope "/" do
    pipe_through :browser
    # Was: get "/", FlareDemo.PageController, :index
    # Now: catch ALL paths and serve index.html
    # The JS will read the URL and join the right channel
    get "/*path", FlareDemo.PageController, :index
  end
end
