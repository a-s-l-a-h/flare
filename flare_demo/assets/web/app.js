import "@divkitframework/divkit/dist/client.css";
import { FlareClient } from "./flare-client";


// __flare_start__ is called by index.html once auth is resolved.
// We expose it on window so the inline script in index.html can call it
// regardless of whether app.js has finished loading yet.

window.__flare_start__ = function () {
  const cfg = window.__flare__ || {};

  const client = new FlareClient({
    wsUrl:        cfg.wsUrl        || "/socket",
    token:        cfg.token        || localStorage.getItem("flare_token"),
    rootEl:       document.getElementById("flare-root"),
    entryScreen:  cfg.entryScreen  || "welcome",
  });

  client.connect();

  // Restore the screen the user was on before an OAuth redirect (if any)
  const oauthScreen = localStorage.getItem("flare_oauth_screen");
  if (oauthScreen) {
    localStorage.removeItem("flare_oauth_screen");
    client.navigateTo(oauthScreen);
  } else {
    client.navigateTo(client._screenFromUrl());
  }

  // Expose client globally for logout etc.
  window.__flareClient__ = client;
};

// If index.html already called __flare_start__ before app.js loaded,
// the function was replaced above — call it now to actually start.
// (Rare race condition on very fast loads, belt-and-suspenders.)
if (window.__flare__?.autoStart) {
  window.__flare_start__();
}