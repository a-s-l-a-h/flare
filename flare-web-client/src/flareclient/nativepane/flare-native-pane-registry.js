import { definePaneCustomElement } from "./flare-native-pane-element.js";

// Map handed straight to DivKit's render({ customComponents }) — confirmed
// shape (`Map<string, CustomComponentDescription>` with an `.element` tag
// name) from client.ts / custom.d.ts in the DivKit source.
const customComponentsMap = new Map();
const registeredProviders = new Map();
let globalPaneContext = null;

export function initNativePaneRegistry(paneContext) {
  globalPaneContext = paneContext;
}

export function registerNativePane(provider) {
  if (!provider || !provider.id) {
    console.error("[FlareNativePaneRegistry] register() called with an invalid provider (missing id) — ignoring");
    return;
  }
  if (registeredProviders.has(provider.id)) {
    console.warn(`[FlareNativePaneRegistry] pane '${provider.id}' overridden by app-level registration`);
  }

  const tagName = `flare-pane-${provider.id.replace(/_/g, "-")}`;
  definePaneCustomElement(tagName, provider, globalPaneContext);

  registeredProviders.set(provider.id, provider);
  customComponentsMap.set(provider.id, { element: tagName });
}

export function getCustomComponentsMap() {
  return customComponentsMap;
}

export function registeredNativePaneIds() {
  return Array.from(registeredProviders.keys());
}