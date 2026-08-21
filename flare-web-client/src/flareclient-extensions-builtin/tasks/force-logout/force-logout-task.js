// Delegates to FlareClient's own _handleAuthFailure() — the one place
// logout logic lives on web. Never reimplement it here.
export const ForceLogoutTask = {
  id: "force_logout",
  execute(_params) {
    if (window.__flareClient__) {
      window.__flareClient__._handleAuthFailure();
    } else {
      console.warn("[ForceLogoutTask] no active FlareClient instance found");
    }
  }
};