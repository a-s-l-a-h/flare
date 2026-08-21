export const OpenBrowserTask = {
  id: "open_browser",
  execute(params) {
    const rawUrl = params && params.url ? String(params.url) : "";
    if (!rawUrl) {
      console.warn("[OpenBrowserTask] received empty URL — ignoring");
      return;
    }
    let parsed;
    try {
      parsed = new URL(rawUrl, window.location.origin);
    } catch (e) {
      console.warn("[OpenBrowserTask] invalid URL — ignoring:", e.message);
      return;
    }
    if (parsed.protocol === "https:" || parsed.protocol === "http:") {
      window.open(parsed.toString(), "_blank", "noopener,noreferrer");
    } else {
      console.warn(`[OpenBrowserTask] rejected unsafe URL scheme='${parsed.protocol}' — only https/http allowed`);
    }
  }
};