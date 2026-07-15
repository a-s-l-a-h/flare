defmodule FlareAppTemplate.Http.Controllers.AuthController do
  use Phoenix.Controller, formats: [:json]

  alias FlareAppTemplate.Core.Accounts.User

  def login(conn, %{"email" => email, "password" => password}) do
    case User.authenticate(email, password) do
      {:ok, user_id} ->
        {:ok, token, _claims} =
          FlareAppTemplate.Core.Accounts.AuthToken.generate_token(user_id, 86_400)

        json(conn, %{token: token})

      {:error, reason} ->
        conn |> put_status(:unauthorized) |> json(%{error: reason})
    end
  end

  def register(conn, %{"email" => email, "password" => password} = params) do
    attrs = %{
      email: email,
      password: password,
      first_name: params["first_name"],
      last_name: params["last_name"]
    }

    case User.register(attrs) do
      {:ok, user} ->
        {:ok, token, _claims} =
          FlareAppTemplate.Core.Accounts.AuthToken.generate_token(user.id, 86_400)

        json(conn, %{token: token})

      {:error, _reason} ->
        conn |> put_status(:bad_request) |> json(%{error: "Email already taken or invalid data."})
    end
  end

  def guest(conn, _params) do
    guest_id = "guest_" <> Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)

    {:ok, token, _claims} =
      FlareAppTemplate.Core.Accounts.AuthToken.generate_token(guest_id, 2_592_000)

    json(conn, %{token: token})
  end
end
