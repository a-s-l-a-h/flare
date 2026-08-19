const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");

// index.html + app.css → dist
fs.copyFileSync(path.join(root, "src", "index.html"), path.join(root, "dist", "index.html"));

// everything in static/ (images, favicon.ico, robots.txt) → dist
fs.cpSync(path.join(root, "static"), path.join(root, "dist"), { recursive: true });

console.log("Copied index.html,, and static/ files into dist/");