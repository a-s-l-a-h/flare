defmodule FlareDemo.FlareRouter do
  use Flare.Router

  screen "welcome", FlareDemo.Welcome
  screen "profile", FlareDemo.Profile
  screen "notes",   FlareDemo.NotesScreen

  # Only admins can enter this screen!
  screen "admin",   FlareDemo.AdminScreen, roles: ["admin"]
end
