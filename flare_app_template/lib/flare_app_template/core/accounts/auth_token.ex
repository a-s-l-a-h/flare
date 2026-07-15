defmodule FlareAppTemplate.Core.Accounts.AuthToken do
  @moduledoc """
  Issues and verifies JWTs for real users AND guests.
  """
  use Joken.Config

  @impl true
  def token_config do
    default_claims(skip: [:exp])
    |> add_claim("exp", nil, fn claim_value, _claims_map ->
      is_integer(claim_value) and claim_value > Joken.current_time()
    end)
  end

  def signer do
    secret =
      Application.get_env(:flare_app_template, :jwt_secret) ||
        raise "Missing config: config :flare_app_template, jwt_secret: \"...\""

    Joken.Signer.create("HS256", secret)
  end

  # RENAMED to generate_token/2
  def generate_token(user_id, max_age_seconds) do
    generate_and_sign(
      %{"sub" => user_id, "exp" => Joken.current_time() + max_age_seconds},
      signer()
    )
  end

  # RENAMED to verify_token/1 to prevent Joken macro conflict!
  def verify_token(token) do
    case verify_and_validate(token, signer()) do
      {:ok, %{"sub" => user_id}} -> {:ok, user_id}
      {:error, reason} -> {:error, reason}
    end
  end
end
