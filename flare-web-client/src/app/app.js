import "@divkitframework/divkit/dist/client.css";

import { mountLogin } from "./login.js";

// ── LOCAL ENGINE ADDITIONS ───────────────────────────────────────────────
// Register built-in (and community/app) tasks/plugins once, at app
// startup, before any layout can possibly try to invoke them.
import { FlareClient } from "../flareclient/index.js";
import { registerAll as registerFlareExtensions } from "./flareclient-extensions.js";

registerFlareExtensions();

const LOGIN_DIV = document.getElementById("flare-login");
const ROOT_DIV  = document.getElementById("flare-app");
let loginHandle = null;

function showApp() {
  LOGIN_DIV.style.display = "none";
  ROOT_DIV.style.display = "flex";
  loginHandle?.reset();
}

function startFlare(token) {
  if (token) { localStorage.setItem("flare_token", token); window.__flare__.token = token; }
  showApp();
  if (typeof window.__flare_start__ === "function") window.__flare_start__();
  else window.__flare__.autoStart = true;
}

window.__flare_start__ = function () {
  try {
    const cfg = window.__flare__ || {};
    const rootEl = document.getElementById("flare-root");

    if (!rootEl) return;

    const client = new FlareClient({
      wsUrl:             cfg.wsUrl             || "/socket",
      token:             cfg.token             || localStorage.getItem("flare_token"),
      rootEl:            rootEl,
      entryScreen:       cfg.entryScreen       || "home",
      persistentScreens: cfg.persistentScreens || [],
      scaffoldRegions:   cfg.scaffoldRegions   || [],
    });

    client.connect();

    const oauthScreen = localStorage.getItem("flare_oauth_screen");
    if (oauthScreen) {
      localStorage.removeItem("flare_oauth_screen");
      client.navigateTo(oauthScreen);
    } else {
      const urlScreen = client._screenFromUrl();
      const urlParams = client._paramsFromUrl();

      if (window.location.pathname === "/") {
        client.navigateTo(client.entryScreen, urlParams);
      } else {
        client.navigateTo(urlScreen, urlParams);
      }
    }

    window.__flareClient__ = client;
  } catch (error) {
    console.error("[Flare] Fatal startup error, clearing session:", error);
    localStorage.removeItem("flare_token");
    window.location.reload();
  }
};

// ── Boot sequence ─────────────────────────────────────────────────────────
if (localStorage.getItem("flare_token")) {
  startFlare(localStorage.getItem("flare_token"));
} else {
  LOGIN_DIV.style.display = "flex";
  loginHandle = mountLogin(LOGIN_DIV, { onAuthenticated: startFlare });
}

if (window.__flare__?.autoStart) {
  window.__flare_start__();
}