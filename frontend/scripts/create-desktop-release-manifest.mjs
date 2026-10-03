import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import desktopBuild from "../build/desktop-build-config.cjs";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const products = ["venta", "gestion"];

function required(value, name) {
  const normalized = value?.trim();
  if (!normalized) throw new Error(`Falta ${name}`);
  return normalized;
}

function readChecksum(file, expectedName) {
  const content = fs.readFileSync(file, "utf8").replaceAll("\r\n", "\n");
  const match = /^([0-9a-f]{64})  ([^\r\n]+)\n$/i.exec(content);
  if (!match || match[2] !== expectedName) throw new Error(`Checksum inválido para ${expectedName}`);
  return match[1].toLowerCase();
}

export function createDesktopReleaseManifest({
  root = frontendRoot,
  repository,
  tag,
  commit = "unknown",
  publishedAt = new Date().toISOString()
}) {
  const normalizedRepository = required(repository, "repository");
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(normalizedRepository)) throw new Error("Repositorio GitHub inválido");
  const metadata = desktopBuild.readBuildMetadata(root);
  const normalizedTag = required(tag, "tag");
  if (normalizedTag !== `desktop-v${metadata.version}`) {
    throw new Error(`El tag debe ser desktop-v${metadata.version}`);
  }
  const releaseBase = `https://github.com/${normalizedRepository}/releases/download/${encodeURIComponent(normalizedTag)}`;
  const artifacts = Object.fromEntries(products.map((id) => {
    const product = desktopBuild.apps[id];
    const fileName = `${product.artifactPrefix}-${metadata.version}-setup.exe`;
    const artifact = path.join(root, "output", "desktop-production", id, fileName);
    if (!fs.statSync(artifact, { throwIfNoEntry: false })?.isFile()) throw new Error(`Falta ${fileName}`);
    const checksumFile = `${artifact}.sha256`;
    if (!fs.statSync(checksumFile, { throwIfNoEntry: false })?.isFile()) throw new Error(`Falta ${fileName}.sha256`);
    return [id, {
      id,
      kind: "installer",
      platform: "windows-x64",
      version: metadata.version,
      fileName,
      url: `${releaseBase}/${encodeURIComponent(fileName)}`,
      checksumFileName: `${fileName}.sha256`,
      checksumUrl: `${releaseBase}/${encodeURIComponent(`${fileName}.sha256`)}`,
      sha256: readChecksum(checksumFile, fileName),
      signature: { type: "authenticode", required: true, timestampRequired: true }
    }];
  }));
  return {
    schemaVersion: 1,
    release: { repository: normalizedRepository, tag: normalizedTag, version: metadata.version, commit, publishedAt },
    artifacts
  };
}

function argument(name) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const output = path.resolve(required(argument("output"), "--output"));
  const manifest = createDesktopReleaseManifest({
    repository: argument("repository") ?? process.env.GITHUB_REPOSITORY,
    tag: argument("tag") ?? process.env.TPV_DESKTOP_RELEASE_TAG,
    commit: argument("commit") ?? process.env.GITHUB_SHA ?? "unknown",
    publishedAt: argument("published-at") ?? process.env.TPV_DESKTOP_RELEASED_AT ?? new Date().toISOString()
  });
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  console.log(`Manifiesto desktop creado: ${output}`);
}
