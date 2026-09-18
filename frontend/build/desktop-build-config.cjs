const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const frontendRoot = path.resolve(__dirname, "..");
const apps = {
  venta: { productName: "TPV ERP - APP VENTA", artifactPrefix: "TPV-ERP-APP-VENTA" },
  gestion: { productName: "TPV ERP - APP GESTION", artifactPrefix: "TPV-ERP-APP-GESTION" }
};

function readBuildMetadata(root = frontendRoot, checkInstalled = false) {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
  const lock = JSON.parse(fs.readFileSync(path.join(root, "package-lock.json"), "utf8"));
  const electronVersion = lock.packages?.["node_modules/electron"]?.version;
  if (!/^\d+\.\d+\.\d+$/.test(electronVersion ?? "")
      || lock.packages?.[""]?.devDependencies?.electron !== manifest.devDependencies?.electron
      || lock.packages?.[""]?.version !== manifest.version) {
    throw new Error("Metadatos desktop inconsistentes: package.json y package-lock.json deben coincidir.");
  }
  if (checkInstalled) {
    const installed = JSON.parse(fs.readFileSync(path.join(root, "node_modules/electron/package.json"), "utf8"));
    if (installed.version !== electronVersion) {
      throw new Error(`Electron instalado (${installed.version}) no coincide con el lock (${electronVersion}); ejecute npm ci antes de empaquetar.`);
    }
  }
  return { version: manifest.version, electronVersion };
}

async function sha256(file) {
  const digest = crypto.createHash("sha256");
  for await (const chunk of fs.createReadStream(file)) digest.update(chunk);
  return digest.digest("hex");
}

async function writeArtifactChecksums(context) {
  const checksums = [];
  for (const file of context.artifactPaths.filter((artifact) => artifact.toLowerCase().endsWith(".exe"))) {
    const checksumFile = `${file}.sha256`;
    fs.writeFileSync(checksumFile, `${await sha256(file)}  ${path.basename(file)}\n`, "utf8");
    checksums.push(checksumFile);
  }
  return checksums;
}

function createDesktopConfig(appKey) {
  const app = apps[appKey];
  if (!app) throw new Error("Aplicación desktop no soportada");
  const metadata = readBuildMetadata();
  return {
    appId: `com.tpverp.app.${appKey}`,
    productName: app.productName,
    artifactName: `${app.artifactPrefix}-\${version}-\${arch}.\${ext}`,
    directories: { app: ".", output: `../../output/desktop-production/${appKey}` },
    files: ["**/*", "!**/*.map", "!**/.env*", "!node_modules/**"],
    extraMetadata: {
      name: `tpv-erp-app-${appKey}`,
      version: metadata.version,
      main: `desktop/main-${appKey}.cjs`,
      dependencies: {},
      tpvBuild: { electronVersion: metadata.electronVersion }
    },
    asar: true,
    electronVersion: metadata.electronVersion,
    npmRebuild: false,
    publish: null,
    win: { target: [{ target: "nsis", arch: ["x64"] }] },
    nsis: {
      oneClick: false,
      perMachine: false,
      allowToChangeInstallationDirectory: true,
      artifactName: `${app.artifactPrefix}-\${version}-setup.\${ext}`
    },
    afterAllArtifactBuild: writeArtifactChecksums
  };
}

module.exports = { apps, frontendRoot, readBuildMetadata, sha256, writeArtifactChecksums, createDesktopConfig };
