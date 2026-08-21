// Uses the "region" key, matching the server's show_scaffold command and
// FlareClient's own _setScaffoldVisible(region, bool) — never a
// different lookup mechanism.
export const ShowScaffoldTask = {
  id: "show_scaffold",
  execute(params) {
    if (window.__flareClient__ && params && params.region) {
      window.__flareClient__._setScaffoldVisible(params.region, true);
    }
  }
};