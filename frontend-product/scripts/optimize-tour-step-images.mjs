import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

// Cua returned JPEG bytes for these accepted captures. Masters preserve those
// bytes unchanged, under their actual .jpg format. Only WebP derivatives enter
// public/. Normal builds require neither Sharp nor a capture/browser tool.
const projectRoot = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(import.meta.url);
const manifestPath = "assets/marketing-sources/tour-step-captures.json";
const webp = { quality: 90, effort: 6, smartSubsample: true };
const captureDate = "2026-10-27";
const captures = [
  { application: "venta", step: 0, scenario: "Cobro parcial de demostración: total de 2,50, importe cobrado de 1,00 y saldo restante de 1,50, diferenciados antes de finalizar.", sourceComponents: ["frontend/packages/app-common/src/components/SalePaymentCheckout.tsx"] },
  { application: "venta", step: 1, scenario: "Consulta de existencias desde el flujo de mostrador y sus controles de teclado.", sourceComponents: ["frontend/packages/app-common/src/components/SaleScreen.tsx", "frontend/packages/app-common/src/components/SaleProductConsultationDialog.tsx"] },
  { application: "venta", step: 2, scenario: "Consulta del estado operativo local y de las entregas a servicios externos.", sourceComponents: ["frontend/packages/app-common/src/components/OperationalStatusCard.tsx"] },
  { application: "gestion", step: 0, scenario: "Existencias de productos y almacenes con datos ficticios de demostración.", sourceComponents: ["frontend/packages/app-common/src/components/StockScreen.tsx"] },
  { application: "gestion", step: 1, scenario: "Mapeo Código/Nombre/Compra/Venta a las columnas A/B/C/D de una muestra Excel con tres filas; sin aplicar cambios.", sourceComponents: ["frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx"] },
  { application: "gestion", step: 2, scenario: "Revisión de roles y permisos del rol CAJA DEMO mediante la pantalla real de Gestión.", sourceComponents: ["frontend/apps/app-gestion/src/SecurityAdministrationScreen.tsx", "frontend/apps/app-gestion/src/GestionShell.tsx"] },
  { application: "pda", step: 0, scenario: "Recuento de inventario de demostración: cantidades esperadas, contadas y diferencias antes de confirmar.", sourceComponents: ["frontend/apps/app-pda/src/main.tsx", "frontend/apps/app-pda/src/PdaStockCount.tsx"] },
  { application: "pda", step: 1, scenario: "Comprobación de mercancía contra un documento de entrada confirmado, sin registrar otra entrada de stock.", sourceComponents: ["frontend/apps/app-pda/src/main.tsx", "frontend/packages/app-common/src/components/GoodsCheckPanel.tsx"] },
  { application: "pda", step: 2, scenario: "Preparación de una reposición de dos unidades entre almacenes de demostración, sin confirmar la transferencia.", sourceComponents: ["frontend/apps/app-pda/src/main.tsx", "frontend/apps/app-pda/src/PdaReplenishment.tsx"] },
  { application: "saas", step: 0, scenario: "Consulta de documentos recibidos de tiendas autorizadas en el portal cliente, con datos ficticios.", sourceComponents: ["frontend-saas/src/app/TenantApp.tsx", "frontend-saas/src/features/tenant/TenantWorkspace.tsx", "frontend-saas/src/features/tenant/TenantSupervision.tsx"] },
  { application: "saas", step: 1, scenario: "Consulta de los contadores de sincronización recibidos para una tienda en el portal cliente.", sourceComponents: ["frontend-saas/src/features/tenant/TenantSupervision.tsx"] },
  { application: "saas", step: 2, scenario: "Conversación de soporte del portal cliente sobre la referencia de un documento ficticio del servicio.", sourceComponents: ["frontend-saas/src/features/tenant/TenantPortal.tsx", "frontend-saas/src/features/tenant/TenantSupportConversation.tsx"] }
];

function parseArguments(args) {
  const result = { check: false, sharpModule: undefined };
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--check") result.check = true;
    else if (args[index] === "--sharp-module") {
      result.sharpModule = args[++index];
      if (!result.sharpModule || !path.isAbsolute(result.sharpModule)) throw new Error("--sharp-module requires an absolute path to a trusted installed Sharp module.");
    } else throw new Error(`Unsupported argument: ${args[index]}. Use --check and/or --sharp-module <absolute path>.`);
  }
  return result;
}

function projectFile(relative) {
  const absolute = path.resolve(projectRoot, relative);
  const resolved = path.relative(projectRoot, absolute);
  assert(resolved && !resolved.startsWith("..") && !path.isAbsolute(resolved), "Asset must stay inside frontend-product.");
  return absolute;
}

const sha256 = buffer => createHash("sha256").update(buffer).digest("hex");
async function storeGenerated(relative, data, check) {
  const absolute = projectFile(relative);
  if (check) return assert.deepEqual(await readFile(absolute), data, `Generated file is not reproducible: ${relative}`);
  await mkdir(path.dirname(absolute), { recursive: true });
  await writeFile(absolute, data);
}

const options = parseArguments(process.argv.slice(2));
let sharp;
try { sharp = require(options.sharpModule ?? "sharp"); }
catch (cause) { throw new Error("Pass --sharp-module <absolute path to trusted installed Sharp>. Normal builds do not require this encoder.", { cause }); }
sharp.concurrency(1);
sharp.cache(false);

// Read and validate every master before writing any derivative or manifest.
const masters = await Promise.all(captures.map(async capture => {
  const sourcePath = `assets/marketing-sources/tour-steps/${capture.application}-${capture.step + 1}.jpg`;
  const original = await readFile(projectFile(sourcePath));
  assert(original[0] === 0xff && original[1] === 0xd8 && original[2] === 0xff, `Expected unchanged JPEG master: ${sourcePath}`);
  const metadata = await sharp(original).metadata();
  assert.equal(metadata.format, "jpeg", `Wrong source format: ${sourcePath}`);
  assert(metadata.width > 0 && metadata.height > 0, `Missing source dimensions: ${sourcePath}`);
  return { capture, sourcePath, original, metadata };
}));

const manifest = {
  schemaVersion: 1,
  capturedAt: captureDate,
  method: "cua-repl",
  sourceInterfaceLanguage: "es",
  data: "synthetic",
  generator: {
    script: "scripts/optimize-tour-step-images.mjs",
    tool: "sharp",
    version: sharp.versions.sharp,
    encoderVersions: { vips: sharp.versions.vips, webp: sharp.versions.webp },
    transform: { resize: false, crop: false, colourspace: "srgb", metadata: "stripped" },
    webp
  },
  captures: []
};
let totalBytes = 0;
for (const { capture, sourcePath, original, metadata } of masters) {
  const result = await sharp(original).toColourspace("srgb").webp(webp).toBuffer({ resolveWithObject: true });
  assert.equal(result.info.width, metadata.width, "Captures must not be resized.");
  assert.equal(result.info.height, metadata.height, "Captures must not be cropped.");
  const filename = `${capture.application}-${capture.step + 1}.webp`;
  const outputPath = `public/marketing/steps/${filename}`;
  await storeGenerated(outputPath, result.data, options.check);
  totalBytes += result.data.length;
  manifest.captures.push({
    application: capture.application,
    step: capture.step,
    source: { path: sourcePath, width: metadata.width, height: metadata.height, bytes: original.length, sha256: sha256(original), format: "jpeg" },
    output: { path: outputPath, url: `/marketing/steps/${filename}`, width: result.info.width, height: result.info.height, bytes: result.data.length, sha256: sha256(result.data), format: "webp" },
    scenario: capture.scenario,
    sourceComponents: capture.sourceComponents
  });
  console.log(`${options.check ? "Checked" : "Generated"} ${filename}: ${result.info.width} × ${result.info.height}, ${result.data.length} bytes`);
}
await storeGenerated(manifestPath, Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`), options.check);
console.log(`${options.check ? "Checked" : "Generated"} ${manifestPath}; 12 WebP captures, ${totalBytes} bytes combined.`);
