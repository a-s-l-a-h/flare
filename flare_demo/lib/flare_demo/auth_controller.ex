defmodule FlareDemo.AuthController do
  use Phoenix.Controller, formats: [:json]

  # 👇 THIS IS THE MAGIC LINE THAT WAS MISSING! 👇
  alias FlareDemo.Users.User

  def login(conn, %{"email" => email, "password" => password}) do
    case User.authenticate(email, password) do
      {:ok, user_id} ->
        token = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_session", user_id)
        json(conn, %{token: token})

      {:error, reason} ->
        conn
        |> put_status(:unauthorized)
        |> json(%{error: reason})
    end
  end

  def register(conn, %{"email" => email, "password" => password, "first_name" => first, "last_name" => last}) do
    case User.register(%{email: email, password: password, first_name: first, last_name: last}) do
      {:ok, user} ->
        token = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_session", user.id)
        json(conn, %{token: token})

      {:error, _changeset} ->
        conn
        |> put_status(:bad_request)
        |> json(%{error: "Email already taken or invalid data."})
    end
  end

  def guest(conn, _params) do
    guest_id = "guest_" <> Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)
    token = Phoenix.Token.sign(FlareDemo.Endpoint, "flare_guest", guest_id)
    json(conn, %{token: token})
  end
end
