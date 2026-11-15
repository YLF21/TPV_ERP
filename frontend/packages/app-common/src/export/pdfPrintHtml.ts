import type { PDFDocumentProxy } from "pdfjs-dist";
import { escapeHtml } from "./workbookPreview";

const PRINT_DPI_SCALE = 150 / 72;
const MAX_CANVAS_PIXELS = 16_000_000;
const MAX_HTML_LENGTH = 60 * 1024 * 1024;

function assertActive(signal?: AbortSignal) {
  if (signal?.aborted) throw new DOMException("Print preparation canceled", "AbortError");
}

/** Rasterize each PDF page in order. Electron's print API cannot print its PDF plugin. */
export async function pdfPrintHtml(pdf: PDFDocumentProxy, title: string, signal?: AbortSignal): Promise<string> {
  const pageRules: string[] = [];
  const pageMarkup: string[] = [];
  let approximateLength = 0;

  for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber++) {
    assertActive(signal);
    const page = await pdf.getPage(pageNumber);
    assertActive(signal);
    const points = page.getViewport({ scale: 1 });
    const pixels = points.width * points.height * PRINT_DPI_SCALE * PRINT_DPI_SCALE;
    const scale = PRINT_DPI_SCALE * Math.min(1, Math.sqrt(MAX_CANVAS_PIXELS / pixels));
    const viewport = page.getViewport({ scale });
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.ceil(viewport.width));
    canvas.height = Math.max(1, Math.ceil(viewport.height));
    const context = canvas.getContext("2d");
    if (!context) throw new Error("print_canvas_unavailable");
    const task = page.render({ canvasContext: context, canvas, viewport });
    const cancel = () => task.cancel();
    signal?.addEventListener("abort", cancel, { once: true });
    try {
      await task.promise;
      assertActive(signal);
      const image = canvas.toDataURL("image/png");
      approximateLength += image.length;
      if (approximateLength > MAX_HTML_LENGTH) throw new Error("print_too_large");
      const name = `pdf-page-${pageNumber}`;
      const width = Number(points.width.toFixed(3));
      const height = Number(points.height.toFixed(3));
      pageRules.push(`@page ${name}{size:${width}pt ${height}pt;margin:0}`);
      pageMarkup.push(`<section class="pdf-print-page page-${pageNumber}" style="width:${width}pt;height:${height}pt"><img src="${image}" alt="" style="width:${width}pt;height:${height}pt"></section>`);
    } finally {
      signal?.removeEventListener("abort", cancel);
      canvas.width = 0;
      canvas.height = 0;
      page.cleanup();
    }
  }

  assertActive(signal);
  return `<!doctype html><html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title><style>${pageRules.join("")}html,body{margin:0;padding:0}.pdf-print-page{break-after:page;page-break-after:always;width:100%;height:100%;overflow:hidden}.pdf-print-page:last-child{break-after:auto;page-break-after:auto}.pdf-print-page img{display:block;width:100%;height:100%}${pageMarkup.map((_, index) => `.page-${index + 1}{page:pdf-page-${index + 1}}`).join("")}</style></head><body>${pageMarkup.join("")}</body></html>`;
}
