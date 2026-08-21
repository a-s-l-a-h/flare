export const HideScaffoldTask = {
  id: "hide_scaffold",
  execute(params) {
    if (window.__flareClient__ && params && params.region) {
      window.__flareClient__._setScaffoldVisible(params.region, false);
    }
  }
};