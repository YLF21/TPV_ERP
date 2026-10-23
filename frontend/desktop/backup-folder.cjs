const fs = require("node:fs");
const path = require("node:path");

function isLocalBackendUrl(value) {
  try {
    const parsed = new URL(value);
    return ["127.0.0.1", "localhost", "[::1]"].includes(parsed.hostname.toLowerCase())
      && ["http:", "https:"].includes(parsed.protocol)
      && !parsed.username && !parsed.password && !parsed.search && !parsed.hash;
  } catch { return false; }
}

function isSafeBackupDirectory(value) {
  if (typeof value !== "string" || !path.isAbsolute(value) || !value.trim()) return false;
  const absolute = path.resolve(value);
  try {
    let current = path.parse(absolute).root;
    for (const part of absolute.slice(current.length).split(path.sep).filter(Boolean)) {
      current = path.join(current, part);
      const stat = fs.lstatSync(current);
      if (stat.isSymbolicLink()) return false;
    }
    return fs.statSync(absolute).isDirectory();
  } catch { return false; }
}

module.exports = { isLocalBackendUrl, isSafeBackupDirectory };
