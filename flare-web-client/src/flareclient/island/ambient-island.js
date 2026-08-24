import "./ambient-island.css";

/**
 * Purely cosmetic controller for the top Dynamic Island.
 * Mirrors dev.flareframework.client.island.AmbientIslandView on Android:
 * - Minimum display duration to prevent 1-frame flickers on instant cache loads
 * - Graceful exit timing
 */
const MIN_DISPLAY_MS = 600;

let isLoading = false;
let loadStartTime = 0;
let pendingExitTimeout = null;

export const AmbientIsland = {
  setLoading(loading) {
    if (isLoading === loading) return;

    if (pendingExitTimeout) {
      clearTimeout(pendingExitTimeout);
      pendingExitTimeout = null;
    }

    const el = document.getElementById("flare-ambient-island");
    if (!el) return;

    if (loading) {
      isLoading = true;
      loadStartTime = Date.now();
      el.classList.add("loading");
    } else {
      const elapsed = Date.now() - loadStartTime;
      if (elapsed < MIN_DISPLAY_MS) {
        // Complete the minimum cycle before smoothly collapsing
        pendingExitTimeout = setTimeout(() => {
          this._stopLoading(el);
        }, MIN_DISPLAY_MS - elapsed);
      } else {
        this._stopLoading(el);
      }
    }
  },

  _stopLoading(el) {
    isLoading = false;
    if (el) {
      el.classList.remove("loading");
    }
  }
};