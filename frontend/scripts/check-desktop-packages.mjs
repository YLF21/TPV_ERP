import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { extractFile, listPackage, statFile } from "@electron/asar";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const apps = [
  { key: "venta", dist: "apps/app-venta/dist", main: "main-venta.cjs", title: "APP VENTA" },
  { key: "gestion", dist: "apps/app-gestion/dist", main: "main-gestion.cjs", title: "APP GESTION" }
];
const forbiddenName = /(^|[\\/])(?:vite|\.env|.*\.map$|.*\.(?:pem|key|p12|pfx|crt|cer|der)$)/i;
const forbiddenText = /(?:127\.0\.0\.1:517[34]|localhost:517[34]|\/\@vite\/client|vite\/dist\/client|import\.meta\.env\.DEV)/i;

function filesUnder(directory) {
  if (!fs.existsSync(directory)) return [];
  const result = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...filesUnder(file));
    else result.push(file);
  }
  return result;
}

function validateDirectory(directory, app) {
  const archive = path.join(directory, "resources", "app.asar");
  if (!fs.existsSync(archive)) throw new Error(`${app.title}: falta resources/app.asar`);
  const files = listPackage(archive).map((file) => file.replaceAll("\\", "/"));
  const manifest = JSON.parse(extractFile(archive, "package.json").toString("utf8"));
  if (manifest.version !== "4.2.0" || manifest.main !== `desktop/${app.main}`) {
    throw new Error(`${app.title}: manifest de versión/entrypoint inválido`);
  }
  const required = [`/desktop/${app.main}`, "/desktop/preload.cjs", `/${app.dist}/index.html`];
  for (const target of required) {
    if (!files.includes(target)) throw new Error(`${app.title}: falta ${target}`);
  }
  for (const name of files) {
    if (forbiddenName.test(name)) throw new Error(`${app.title}: archivo prohibido ${name}`);
    const extension = path.extname(name).toLowerCase();
    if (!extension) continue;
    const archiveName = name.slice(1).replaceAll("/", "\\");
    const info = statFile(archive, archiveName);
    if (!['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.ico', '.woff', '.woff2'].includes(extension) && info.size > 0 && info.size < 20 * 1024 * 1024) {
      const content = extractFile(archive, archiveName);
      if (forbiddenText.test(content.toString("utf8"))) throw new Error(`${app.title}: ruta de desarrollo en ${name}`);
    }
  }
  return files.length;
}

let checked = 0;
for (const app of apps) {
  const output = path.join(root, "output", "desktop-production", app.key);
  const packageDirectory = path.join(output, "win-unpacked");
  if (!packageDirectory) throw new Error(`${app.title}: no se encontró un paquete --dir en ${output}`);
  checked += validateDirectory(packageDirectory, app);
}
console.log(`Paquetes Electron validados: ${apps.length} (${checked} archivos)`);
