defmodule FlareAppTemplate.Http.Controllers.PageController do
  use Phoenix.Controller, formats: [:html]

  @dist_path Path.expand("../../../../../flare-web-client/dist", __DIR__)


  IO.puts("🔥 FLARE DIST PATH: #{@dist_path}")
  IO.puts("🔥 EXISTS?: #{File.exists?(@dist_path)}")
  IO.puts("🔥 INDEX EXISTS?: #{File.exists?(Path.join(@dist_path, "index.html"))}")

  def index(conn, _params) do
    conn
    |> put_resp_content_type("text/html")
    |> send_file(200, Path.join(@dist_path, "index.html"))
  end
end

defmodule FlareAppTemplate.Http.Controllers.ErrorJSON do
  def render(template, _assigns) do
    %{errors: %{detail: Phoenix.Controller.status_message_from_template(template)}}
  end
end
