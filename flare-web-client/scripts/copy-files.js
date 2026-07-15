const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");

// index.html + app.css → dist
fs.copyFileSync(path.join(root, "src", "index.html"), path.join(root, "dist", "index.html"));
fs.copyFileSync(path.join(root, "src", "css", "app.css"), path.join(root, "dist", "assets", "app.css"));

// everything in static/ (images, favicon.ico, robots.txt) → dist
fs.cpSync(path.join(root, "static"), path.join(root, "dist"), { recursive: true });

console.log("Copied index.html, app.css, and static/ files into dist/");