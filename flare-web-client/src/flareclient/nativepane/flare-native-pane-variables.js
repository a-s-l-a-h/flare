import { FlareExportedVariables } from "../export/flare-exported-variables.js";

const subscriptionsByPane = new Map();

export const FlareNativePaneVariables = {
  get(name) {
    return FlareExportedVariables.get(name);
  },
  subscribe(paneKey, name, callback) {
    if (!paneKey || !name || typeof callback !== "function") return;
    if (!subscriptionsByPane.has(paneKey)) subscriptionsByPane.set(paneKey, new Set());
    subscriptionsByPane.get(paneKey).add({ name, callback });
    FlareExportedVariables.subscribe(name, callback);
  },
  unsubscribeAll(paneKey) {
    const records = subscriptionsByPane.get(paneKey);
    if (!records) return;
    records.forEach(({ name, callback }) => FlareExportedVariables.unsubscribe(name, callback));
    subscriptionsByPane.delete(paneKey);
  }
};