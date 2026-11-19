import { readdir, readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const app = fileURLToPath(new URL("../apps/app-venta/", import.meta.url));
const distribution = path.join(app, "dist-web");
const sensitiveKeys = ["VITE_TPV_TERMINAL_ID", "VITE_TPV_TERMINAL_CREDENTIAL"];
const forbidden = new Set(sensitiveKeys.map(key => process.env[key]).filter(value => value?.length >= 8));
for (const name of [".env", ".env.local", ".env.production", ".env.production.local"]) {
  let source;
  try { source = await readFile(path.join(app, name), "utf8"); }
  catch (error) { if (error.code === "ENOENT") continue; throw error; }
  for (const line of source.split(/\r?\n/)) {
    const match = line.match(/^\s*(VITE_TPV_TERMINAL_ID|VITE_TPV_TERMINAL_CREDENTIAL)\s*=\s*(.*?)\s*$/);
    if (!match) continue;
    const value = match[2].replace(/^(["'])(.*)\1$/, "$2");
    if (value.length >= 8) forbidden.add(value);
  }
}

async function inspect(directory) {
  let count = 0;
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error("Browser distribution must not contain symlinks");
    if (entry.isDirectory()) { count += await inspect(target); continue; }
    if (/^\.env(?:\.|$)/i.test(entry.name) || /\.map$/i.test(entry.name)) {
      throw new Error("Browser distribution contains a configuration file or source map");
    }
    if (!/\.(?:js|mjs|css|html|json)$/i.test(entry.name)) continue;
    const source = await readFile(target, "utf8");
    if ([...forbidden].some(value => source.includes(value))) {
      // Never print the matched value, configuration source or bundle contents.
      throw new Error("Browser distribution contains a configured development terminal identity");
    }
    count += 1;
  }
  return count;
}
if (!(await stat(path.join(distribution, "index.html"))).isFile()) throw new Error("Browser build missing");
console.log(`Venta web build checked: ${await inspect(distribution)} text assets; no configured terminal identity or source maps.`);
