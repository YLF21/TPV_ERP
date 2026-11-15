import type { LocaleCode } from "../types";
import { previewExport } from "./previewExport";

/** Prepare the existing document renderer without opening a save or print dialog. */
export async function previewPreparedPdf(
  kind: "table" | "ticket" | "a4" | "labels", document: unknown, fileName: string, locale: LocaleCode = "es",
) {
  const reports = window.tpvDesktop?.reports;
  if (!reports?.preparePdf) throw new Error("PDF_PREVIEW_UNAVAILABLE");
  const result = await reports.preparePdf({ kind, document });
  if (!result.ok) throw new Error(result.message || result.code);
  return previewExport({ blob: new Blob([Uint8Array.from(result.bytes).buffer], { type: "application/pdf" }), fileName, locale });
}
