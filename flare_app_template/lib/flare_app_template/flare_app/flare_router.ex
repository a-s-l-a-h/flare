defmodule FlareAppTemplate.FlareApp.FlareRouter do
  use Flare.Router

  screen "home",    FlareAppTemplate.FlareApp.Screens.Home,    scaffold: [:bottom_bar]
  screen "counter", FlareAppTemplate.FlareApp.Screens.Counter, scaffold: [:bottom_bar]

  screen "bottom_bar", FlareAppTemplate.FlareApp.Scaffold.BottomBar, region: :bottom_bar
end
