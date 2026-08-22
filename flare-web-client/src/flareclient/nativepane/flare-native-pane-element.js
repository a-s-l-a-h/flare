// Bridges a plain Flare pane provider object into a real W3C Custom
// Element — exactly what DivKit's Custom.svelte instantiates via
// `customElements` (confirmed: `<svelte:element this={desc.element}
// {...custom_props}>`). Every call into third-party provider code is
// try/caught so a bug there can never break DivKit's rendering or the
// rest of the page. The browser calls connectedCallback/
// disconnectedCallback outside of Svelte's own render cycle, so a thrown
// error here would otherwise surface as an uncaught console error and,
// in some browsers, abort custom element upgrade for that node — this
// wrapper prevents both.
import { FlareNativePaneVariables } from "./flare-native-pane-variables.js";

export function definePaneCustomElement(tagName, provider, paneContext) {
  if (customElements.get(tagName)) return;

  class FlarePaneElement extends HTMLElement {
    constructor() {
      super();
      this._view = null;
      this._paneKey = null;
      this._unsubscribeFns = [];
    }

    connectedCallback() {
      // IMPORTANT: custom_props are spread onto this element as real DOM
      // attributes by DivKit (Custom.svelte). HTML attributes are ALWAYS
      // strings — a numeric "max_value": 100 in your layout JSON arrives
      // here as the string "100", not the number 100. Pane authors must
      // coerce types themselves (Number(...), === "true", etc). Nested
      // objects/arrays in custom_props do not survive this path reliably
      // — keep custom_props flat and primitive-valued.
      const props = {};
      for (const attr of this.attributes) {
        props[attr.name] = attr.value;
      }
      this._paneKey = this.getAttribute("id") || tagName;

      try {
        this._view = provider.createView(this, props, paneContext) || this;
      } catch (e) {
        console.error(`[FlareNativePane] Crash intercepted in createView() for ${tagName}:`, e);
        try {
          this.innerHTML = `<div style="color:red;padding:8px;background:#ffe6e6;">Error in pane: ${tagName}</div>`;
        } catch (e2) {
          // Even the fallback markup must never throw further.
          console.error(`[FlareNativePane] Failed to render fallback for ${tagName}:`, e2);
        }
      }
    }

    // Official DivKit Web reactivity hook — confirmed from Custom.svelte's
    // onMount(): it calls customElem.divKitApiCallback(ctx) if the method
    // exists. `variables` is a Map<name, VariableHandle> scoped to this
    // component's context (the same globalVariablesController Flare
    // passes into render()), so 'flare_' / 'local_' prefixed vars are
    // reachable here directly — this is an ALTERNATIVE to the
    // FlareNativePaneVariables/FlareExportedVariables bridge below.
    // Prefer this native hook when your pane only needs to react to a
    // few named variables; use FlareNativePaneVariables when the pane
    // needs access from OUTSIDE the DivKit tree, mirroring Android's
    // FlareExportedVariables use case.
    divKitApiCallback(ctx) {
      try {
        const variables = ctx && ctx.variables;
        if (!variables || !Array.isArray(provider.watchedVariables)) return;

        provider.watchedVariables.forEach((varName) => {
          try {
            const v = variables.get(varName);
            if (v && typeof v.subscribe === "function") {
              const unsub = v.subscribe((newVal) => {
                try {
                  provider.bindView(this._view, { [varName]: newVal }, paneContext);
                } catch (err) {
                  console.debug(`[FlareNativePane] Non-fatal bindView error on ${tagName}:`, err);
                }
              });
              if (typeof unsub === "function") this._unsubscribeFns.push(unsub);
            }
          } catch (e) {
            console.error(`[FlareNativePane] Failed subscribing '${varName}' on ${tagName}:`, e);
          }
        });
      } catch (e) {
        console.error(`[FlareNativePane] divKitApiCallback crash intercepted for ${tagName}:`, e);
      }
    }

    disconnectedCallback() {
      this._unsubscribeFns.forEach((fn) => {
        try { fn(); } catch (e) { console.debug(`[FlareNativePane] unsubscribe error on ${tagName}:`, e); }
      });
      this._unsubscribeFns = [];

      if (this._view && typeof provider.release === "function") {
        try {
          provider.release(this._view);
        } catch (e) {
          console.error(`[FlareNativePane] Crash intercepted in release() for ${tagName}:`, e);
        }
      }

      try {
        if (this._paneKey) {
          FlareNativePaneVariables.unsubscribeAll(this._paneKey);
        }
      } catch (e) {
        console.error(`[FlareNativePane] Crash intercepted unsubscribing variables for ${tagName}:`, e);
      }
    }
  }

  customElements.define(tagName, FlarePaneElement);
}