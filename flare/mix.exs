defmodule Flare.MixProject do
  use Mix.Project

  def project do
    [
      app:             :flare,
      version:         "0.1.0",
      elixir:          "~> 1.14",
      start_permanent: Mix.env() == :prod,
      description:     "Server-Driven UI library for Phoenix + DivKit. Build native iOS, Android, and Web apps from one Elixir codebase.",
      deps:            deps(),
      docs:            docs()
    ]
  end

  def application do
    [
      extra_applications: [:logger],
      mod: {Flare.Application, []}
    ]
  end

  defp deps do
    [
      {:phoenix,        "~> 1.8"},
      {:phoenix_pubsub, "~> 2.1"},
      {:jason,          "~> 1.4"},
      {:ex_doc,         "~> 0.34", only: :dev, runtime: false}
    ]
  end

  defp docs do
    [
      main:   "readme",
      extras: ["README.md"]
    ]
  end
end
