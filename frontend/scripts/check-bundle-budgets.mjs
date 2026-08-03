import { readdir, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const budgets = [
  { app: "app-gestion", extension: ".js", maximumBytes: 600_000 },
  { app: "app-gestion", extension: ".css", maximumBytes: 520_000 },
  { app: "app-venta", extension: ".js", maximumBytes: 800_000 },
  { app: "app-venta", extension: ".css", maximumBytes: 400_000 },
];

async function filesRecursively(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const value = path.join(directory, entry.name);
    return entry.isDirectory() ? filesRecursively(value) : [value];
  }));
  return nested.flat();
}

let failed = false;
for (const budget of budgets) {
  const output = path.join(frontendRoot, "apps", budget.app, "dist");
  const candidates = (await filesRecursively(output)).filter((file) => file.endsWith(budget.extension));
  const sizes = await Promise.all(candidates.map(async (file) => ({ file, bytes: (await stat(file)).size })));
  const largest = sizes.sort((left, right) => right.bytes - left.bytes)[0];
  if (!largest) throw new Error(`No se encontraron archivos ${budget.extension} para ${budget.app}`);
  const status = largest.bytes <= budget.maximumBytes ? "OK" : "EXCEDE";
  console.log(`${status} ${budget.app} ${budget.extension}: ${largest.bytes} / ${budget.maximumBytes} bytes (${path.basename(largest.file)})`);
  failed ||= largest.bytes > budget.maximumBytes;
}

if (failed) process.exitCode = 1;
