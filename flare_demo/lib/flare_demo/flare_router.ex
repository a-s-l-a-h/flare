# Location: flare_demo/lib/flare_demo/flare_router.ex

defmodule FlareDemo.FlareRouter do
  use Flare.Router

  screen "welcome", FlareDemo.Welcome
  screen "profile", FlareDemo.Profile
end
