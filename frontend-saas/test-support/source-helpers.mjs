import { readFile, readdir } from "node:fs/promises";

const sourceRoot = new URL("../src/", import.meta.url);

export async function readSources(...paths) {
  return (await Promise.all(paths.map((path) => readFile(new URL(path, sourceRoot), "utf8")))).join("\n");
}

export async function readFrontendSources() {
  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    return (await Promise.all(entries.map((entry) => {
      const location = new URL(entry.name + (entry.isDirectory() ? "/" : ""), directory);
      if (entry.isDirectory()) return visit(location);
      return /\.(?:tsx?|mjs)$/.test(entry.name) ? readFile(location, "utf8") : "";
    }))).join("\n");
  }
  return visit(sourceRoot);
}

export async function readDictionary(language) {
  const source = await readSources(`i18n/${language}.ts`);
  return Object.fromEntries([...source.matchAll(/^\s+(\w+): ("(?:\\.|[^"\\])*")/gm)]
    .map(([, key, value]) => [key, JSON.parse(value)]));
}
