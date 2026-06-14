# Location: flare_demo/lib/flare_demo/page_controller.ex

defmodule FlareDemo.PageController do
  use Phoenix.Controller, formats: [:html]

  def index(conn, _params) do
    conn
    |> put_resp_content_type("text/html")
    |> send_file(200, Application.app_dir(:flare_demo, "priv/static/index.html"))
  end
end

defmodule FlareDemo.ErrorJSON do
  def render(template, _assigns) do
    %{errors: %{detail: Phoenix.Controller.status_message_from_template(template)}}
  end
end
