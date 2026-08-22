export const PlaceholderPaneProvider = {
  id: "placeholder_pane",

  createView(container, props) {
    container.style.background = "#2D3436";
    container.style.color = "#fff";
    container.style.display = "flex";
    container.style.alignItems = "center";
    container.style.justifyContent = "center";
    container.style.padding = "16px";
    container.style.cursor = "pointer";
    this.bindView(container, props);
    return container;
  },

  bindView(container, props) {
    container.textContent = (props && props.title) || "Native Placeholder Pane";
  },

  release(_container) {}
};