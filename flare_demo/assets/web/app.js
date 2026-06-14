import "@divkitframework/divkit/dist/client.css";
import { FlareClient } from "./flare-client";

document.addEventListener("DOMContentLoaded", () => {

  const client = new FlareClient({
    wsUrl: `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/socket`,
    token:        localStorage.getItem("flare_token") || null,
    rootEl:       document.getElementById("flare-root"),  // ← was "app", now "flare-root"
    entryScreen:  "welcome"
  });

  client.connect();
  client.navigateTo(client._screenFromUrl());

  window.flare = client;

});