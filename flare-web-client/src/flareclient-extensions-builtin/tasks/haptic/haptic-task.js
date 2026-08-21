export const HapticTask = {
  id: "haptic",
  execute(params) {
    if (!navigator.vibrate) return;
    const style = (params && params.style) || "success";
    const patterns = {
      light: 30, medium: 60, heavy: 100, success: 50,
      warning: [40, 80, 40], error: [40, 60, 40, 60, 80]
    };
    navigator.vibrate(patterns[style] || patterns.success);
  }
};