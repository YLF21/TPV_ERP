import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const appKey = process.argv[2];
const apps = {
  venta: { name: "tpv-erp-app-venta", main: "desktop/main-venta.cjs", dist: "apps/app-venta/dist" },
  gestion: { name: "tpv-erp-app-gestion", main: "desktop/main-gestion.cjs", dist: "apps/app-gestion/dist" }
};
const app = apps[appKey];
if (!app) throw new Error("Uso: node scripts/prepare-desktop-app.mjs venta|gestion");

const staging = path.join(root, "desktop-staging", appKey);
fs.rmSync(staging, { recursive: true, force: true });
fs.mkdirSync(staging, { recursive: true });
fs.cpSync(path.join(root, "desktop"), path.join(staging, "desktop"), {
  recursive: true,
  filter: (source) => !/\.test\.[^.]+$/.test(source)
    && !(/main-(venta|gestion)\.cjs$/.test(source)
      && !source.replaceAll("\\", "/").endsWith(app.main))
});
fs.cpSync(path.join(root, app.dist), path.join(staging, app.dist), {
  recursive: true,
  filter: (source) => !source.endsWith(".map") && !path.basename(source).startsWith(".env")
});
fs.writeFileSync(path.join(staging, "package.json"), JSON.stringify({
  name: app.name,
  version: "4.2.0",
  private: true,
  description: `TPV ERP ${appKey}`,
  author: "TPV ERP",
  main: app.main
}, null, 2) + "\n");
fs.mkdirSync(path.join(staging, "node_modules"), { recursive: true });
fs.writeFileSync(path.join(staging, "package-lock.json"), JSON.stringify({
  name: app.name,
  version: "4.2.0",
  lockfileVersion: 3,
  requires: true,
  packages: { "": { name: app.name, version: "4.2.0" } }
}, null, 2) + "\n");
console.log(`Staging Electron preparado: ${path.relative(root, staging)}`);
