defmodule FlareDemo.MixProject do
  use Mix.Project

  def project do
    [
      app:             :flare_demo,
      version:         "0.1.0",
      elixir:          "~> 1.15",
      elixirc_paths:   elixirc_paths(Mix.env()),
      start_permanent: Mix.env() == :prod,
      aliases:         aliases(),
      deps:            deps(),
      listeners:       [Phoenix.CodeReloader]
    ]
  end

  def application do
    [
      mod:                {FlareDemo.Application, []},
      extra_applications: [:logger, :runtime_tools]
    ]
  end

  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_),     do: ["lib"]

  defp deps do
    [
      {:phoenix,  "~> 1.8"},
      {:bandit,   "~> 1.5"},
      {:jason,    "~> 1.4"},
      {:esbuild,  "~> 0.10", runtime: Mix.env() == :dev},
      {:flare,    path: "../flare"},
      {:ecto_sql, "~> 3.10"},
      {:ecto_sqlite3, "~> 0.13"},

      # REPLACE bcrypt_elixir with pbkdf2_elixir
      {:pbkdf2_elixir, "~> 2.0"}
    ]
  end

  defp aliases do
    [
      setup:           ["deps.get", "assets.build"],
      "assets.build":  ["compile", "esbuild flare_demo"],
      "assets.deploy": ["esbuild flare_demo --minify", "phx.digest"]
    ]
  end
end
