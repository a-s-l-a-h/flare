# Location: flare_demo/lib/flare_demo/flare_router.ex

defmodule FlareDemo.FlareRouter do
  use Flare.Router

  view "welcome",   FlareDemo.Welcome
  view "profile",   FlareDemo.Profile
end
