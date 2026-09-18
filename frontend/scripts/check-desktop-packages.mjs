import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { execFileSync } from "node:child_process";
import { extractFile, listPackage, statFile } from "@electron/asar";
import desktopBuild from "../build/desktop-build-config.cjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const apps = [
  { key: "venta", dist: "apps/app-venta/dist", main: "main-venta.cjs", title: "APP VENTA" },
  { key: "gestion", dist: "apps/app-gestion/dist", main: "main-gestion.cjs", title: "APP GESTION" }
];
const forbiddenName = /(^|[\\/])(?:vite|\.env|.*\.map$|.*\.(?:pem|key|p12|pfx|crt|cer|der)$)/i;
const forbiddenText = /(?:127\.0\.0\.1:517[34]|localhost:517[34]|\/\@vite\/client|vite\/dist\/client|import\.meta\.env\.DEV)/i;

export function validateDirectory(directory, app, metadata = desktopBuild.readBuildMetadata()) {
  const archive = path.join(directory, "resources", "app.asar");
  if (!fs.existsSync(archive)) throw new Error(`${app.title}: falta resources/app.asar`);
  const files = listPackage(archive).map((file) => file.replaceAll("\\", "/"));
  const manifest = JSON.parse(extractFile(archive, "package.json").toString("utf8"));
  if (manifest.version !== metadata.version || manifest.main !== `desktop/${app.main}`
      || manifest.tpvBuild?.electronVersion !== metadata.electronVersion) {
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
    const archiveName = name.slice(1).split("/").join(path.sep);
    const info = statFile(archive, archiveName);
    if (!['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.ico', '.woff', '.woff2'].includes(extension) && info.size > 0 && info.size < 20 * 1024 * 1024) {
      const content = extractFile(archive, archiveName);
      if (forbiddenText.test(content.toString("utf8"))) throw new Error(`${app.title}: ruta de desarrollo en ${name}`);
    }
  }
  return files.length;
}

export async function verifyChecksum(file) {
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0) {
    throw new Error("Artefacto desktop vacío o no regular");
  }
  const expected = `${await desktopBuild.sha256(file)}  ${path.basename(file)}\n`;
  const actual = fs.readFileSync(`${file}.sha256`, "utf8").replaceAll("\r\n", "\n");
  if (actual !== expected) throw new Error(`Checksum SHA-256 incorrecto: ${path.basename(file)}`);
}

export function verifyAuthenticode(file, thumbprint, execute = execFileSync) {
  const normalized = thumbprint?.replaceAll(" ", "").toUpperCase();
  if (!/^[0-9A-F]{40}$/.test(normalized ?? "")) {
    throw new Error("Configure TPV_DESKTOP_SIGNER_THUMBPRINT con la huella del firmante autorizado.");
  }
  const command = [
    "$ErrorActionPreference = 'Stop'",
    "$signature = Get-AuthenticodeSignature -LiteralPath $env:TPV_DESKTOP_VERIFY_FILE",
    "if ($signature.Status -ne 'Valid') { throw 'Firma Authenticode ausente o no valida' }",
    "if ($null -eq $signature.SignerCertificate -or $signature.SignerCertificate.Thumbprint -ne $env:TPV_DESKTOP_VERIFY_SIGNER) { throw 'Firmante Authenticode inesperado' }",
    "if ($null -eq $signature.TimeStamperCertificate) { throw 'La firma requiere sello de tiempo' }"
  ].join("; ");
  execute("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", command], {
    windowsHide: true,
    stdio: "pipe",
    timeout: 60_000,
    env: { ...process.env, TPV_DESKTOP_VERIFY_FILE: path.resolve(file), TPV_DESKTOP_VERIFY_SIGNER: normalized }
  });
}

export async function checkPackages(args = process.argv.slice(2)) {
  const installer = args.includes("--installer");
  const requested = args.filter((arg) => arg !== "--installer");
  if (requested.length > 1 || (requested.length === 1 && !apps.some((app) => app.key === requested[0]))) {
    throw new Error("Uso: node scripts/check-desktop-packages.mjs [--installer] [venta|gestion]");
  }
  if (installer && process.platform !== "win32") throw new Error("La verificación Authenticode requiere Windows.");
  const selectedApps = requested.length ? apps.filter((app) => app.key === requested[0]) : apps;
  const metadata = desktopBuild.readBuildMetadata();
  let checked = 0;
  for (const app of selectedApps) {
    const output = path.join(root, "output", "desktop-production", app.key);
    const packageDirectory = path.join(output, "win-unpacked");
    checked += validateDirectory(packageDirectory, app, metadata);
    if (installer) {
      const product = desktopBuild.apps[app.key];
      const setup = path.join(output, `${product.artifactPrefix}-${metadata.version}-setup.exe`);
      await verifyChecksum(setup);
      verifyAuthenticode(setup, process.env.TPV_DESKTOP_SIGNER_THUMBPRINT);
      verifyAuthenticode(path.join(packageDirectory, `${product.productName}.exe`), process.env.TPV_DESKTOP_SIGNER_THUMBPRINT);
    }
  }
  console.log(`Paquetes Electron validados: ${selectedApps.length} (${checked} archivos; ${installer ? "instalador firmado y SHA-256 verificados" : "unpacked, sin aceptación de firma"})`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await checkPackages();
}
