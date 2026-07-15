# Location: flare/lib/flare/presence.ex
#
# Tracks which user_ids are currently connected to a given "shared screen"
# topic — i.e. a screen whose topic/2 callback returns a custom value
# (multiple users viewing the same logical instance, e.g. "window:AH7K2P").
#
# Screens using the default topic (:default) never get tracked here —
# see Flare.Channel.do_mount/5 for where tracking is conditionally applied.
defmodule Flare.Presence do
  use Phoenix.Presence,
    otp_app: :flare,
    pubsub_server: Flare.PubSub
end
