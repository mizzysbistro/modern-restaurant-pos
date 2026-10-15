const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = process.cwd();
const pairs = [
  ["browser POS preview", "index.html", "app/src/main/assets/index.html"],
  ["backup validation helper", "backup-schema.js", "app/src/main/assets/backup-schema.js"],
  ["sales reporting helper", "reporting.js", "app/src/main/assets/reporting.js"]
];

for (const [label, previewPath, androidPath] of pairs) {
  test(`${label} matches the Android-bundled source`, () => {
    const preview = fs.readFileSync(path.join(root, previewPath), "utf8");
    const android = fs.readFileSync(path.join(root, androidPath), "utf8");
    assert.equal(
      preview,
      android,
      `Keep ${previewPath} in sync with ${androidPath}; the web preview must exercise the shipped POS code.`
    );
  });
}
