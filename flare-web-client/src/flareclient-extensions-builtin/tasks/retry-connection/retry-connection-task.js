export const RetryConnectionTask = {
  id: "retry_connection",
  execute(_params) {
    if (window.__flareClient__) {
      window.__flareClient__.retryConnection();
    } else {
      console.warn("[RetryConnectionTask] no active FlareClient instance found");
    }
  }
};