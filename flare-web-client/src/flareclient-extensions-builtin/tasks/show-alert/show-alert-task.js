export const ShowAlertTask = {
  id: "show_alert",
  execute(params) {
    alert(`${(params && params.title) || ""}\n\n${(params && params.message) || ""}`);
  }
};