import { strToU8, zipSync } from "fflate";
import { apiBaseUrl } from "@tpverp/app-common";
import type { FiscalExport } from "./verifactuManagementApi";

export function buildFiscalExportArchive(fiscalExport: FiscalExport): Uint8Array {
  const documents = fiscalExport.batchXml
    ? [fiscalExport.batchXml]
    : fiscalExport.xml;
  const prefix = fiscalExport.kind === "EVENTS" ? "evento" : "registro-facturacion";
  const files: Record<string, Uint8Array> = {};
  documents.forEach((xml, index) => {
    const suffix = String(index + 1).padStart(6, "0");
    files[`${prefix}-${suffix}.xml`] = strToU8(xml);
  });
  files["manifest.json"] = strToU8(JSON.stringify({
    exportId: fiscalExport.exportId,
    kind: fiscalExport.kind,
    exportedAt: fiscalExport.exportedAt,
    periodStart: fiscalExport.periodStart ?? null,
    periodEnd: fiscalExport.periodEnd ?? null,
    recordCount: fiscalExport.recordCount,
    eventId: fiscalExport.eventId ?? null,
    contentHash: fiscalExport.contentHash ?? null,
    companyId: fiscalExport.companyId ?? null,
    storeId: fiscalExport.storeId ?? null,
    installationId: fiscalExport.installationId ?? null,
    firstRecord: fiscalExport.records?.[0] ?? null,
    lastRecord: fiscalExport.records?.at(-1) ?? null,
    records: fiscalExport.records ?? [],
    files: documents.length
  }, null, 2));
  return zipSync(files, { level: 6 });
}

export function downloadFiscalExport(fiscalExport: FiscalExport) {
  const bytes = buildFiscalExportArchive(fiscalExport);
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  const blob = new Blob([copy.buffer], { type: "application/zip" });
  downloadFiscalExportBlob(blob, `exportacion-fiscal-${fiscalExport.kind.toLowerCase()}-${fiscalExport.exportId}.zip`);
}

export function downloadFiscalExportBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  try {
    anchor.click();
  } finally {
    anchor.remove();
    URL.revokeObjectURL(url);
  }
}

/** Submits the one-use capability as form data so the browser streams the ZIP. */
export function submitFiscalExportDownload(token: string) {
  if (!token) throw new Error("fiscal_export_download_token_missing");
  const frameName = "fiscal-export-download-frame";
  let frame = document.querySelector<HTMLIFrameElement>(`iframe[name="${frameName}"]`);
  if (!frame) {
    frame = document.createElement("iframe");
    frame.name = frameName;
    frame.hidden = true;
    frame.title = "";
    document.body.appendChild(frame);
  }
  const form = document.createElement("form");
  form.method = "POST";
  form.action = `${apiBaseUrl}/fiscal/export-jobs/download`;
  form.target = frameName;
  form.hidden = true;
  const input = document.createElement("input");
  input.type = "hidden";
  input.name = "token";
  input.value = token;
  form.appendChild(input);
  document.body.appendChild(form);
  try {
    form.submit();
  } finally {
    form.remove();
  }
}
