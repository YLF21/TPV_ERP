const { renderedPdfBuffer } = require("./pdf-document.cjs");
const { renderTicketHtml } = require("./ticket-renderer.cjs");
const { renderA4DocumentHtml } = require("./a4-renderer.cjs");
const { renderTableReportHtml } = require("./table-report-renderer.cjs");
const { renderProductLabelHtml, normalizedProfile } = require("./product-label-renderer.cjs");

// These windows have no preload, scripts, network resources or application privileges.
const CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; script-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'";
function isolatedHtml(html) {
  const policy = `<meta http-equiv="Content-Security-Policy" content="${CSP}">`;
  return /<head(?:\s[^>]*)?>/i.test(html)
    ? html.replace(/<head(?:\s[^>]*)?>/i, (head) => head + policy)
    : policy + html;
}

function createExportPreviewService({ BrowserWindow, icon }) {
  function createWindow() {
    const window = new BrowserWindow({
      show: false, icon,
      webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, javascript: false }
    });
    window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
    window.webContents.on("will-navigate", (event) => event.preventDefault());
    return window;
  }

  async function preparePdf(request) {
    let window;
    try {
      const { kind, document } = request || {};
      if (!["table", "ticket", "a4", "labels"].includes(kind) || !document || typeof document !== "object") {
        throw new Error("INVALID_PDF_REQUEST");
      }
      const options = { printBackground: true, landscape: false, pageSize: "A4" };
      let html;
      if (kind === "ticket" || kind === "a4") {
        const original = renderedPdfBuffer(document);
        if (original) return { ok: true, bytes: original };
        if (document.requireRenderedDocument) throw new Error("REQUIRED_RENDERED_PDF_MISSING");
        html = kind === "ticket" ? renderTicketHtml(document) : renderA4DocumentHtml(document);
      } else if (kind === "table") {
        html = renderTableReportHtml(document);
        if (document.reportStyle === "partyDirectory") {
          options.landscape = Array.isArray(document.columns) && document.columns.length > 6;
        }
        options.margins = { top: 0, bottom: 0, left: 0, right: 0 };
      } else {
        const profile = normalizedProfile(document.profile);
        html = renderProductLabelHtml({ ...document, profile });
        options.landscape = profile.orientation === "LANDSCAPE";
        options.pageSize = profile.destination === "A4" ? "A4"
          : { width: Math.round(profile.widthMm * 1000), height: Math.round(profile.heightMm * 1000) };
        options.margins = { top: 0, bottom: 0, left: 0, right: 0 };
      }
      window = createWindow();
      await window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(isolatedHtml(html))}`);
      return { ok: true, bytes: await window.webContents.printToPDF(options) };
    } catch (error) {
      return { ok: false, code: "PDF_PREVIEW_FAILED", message: error instanceof Error ? error.message : "PDF_PREVIEW_FAILED" };
    } finally {
      if (window && !window.isDestroyed()) window.destroy();
    }
  }

  async function printExport(request) {
    let window;
    try {
      let url;
      // PDF pages arrive as a paginated image document. Chromium's embedded PDF
      // viewer cannot reliably be printed through webContents.print().
      if (typeof request?.html === "string" && request?.pdfBytes == null && request.html.length <= 64 * 1024 * 1024) {
        url = `data:text/html;charset=utf-8,${encodeURIComponent(isolatedHtml(request.html))}`;
      } else {
        throw new Error("INVALID_PRINT_REQUEST");
      }
      window = createWindow();
      await window.loadURL(url);
      return await new Promise((resolve) => {
        // Always let the user select/confirm the printer. Never use the ticket route or cash drawer.
        window.webContents.print({ silent: false, printBackground: true, margins: { marginType: "none" } }, (success, reason) => {
          if (success) resolve({ ok: true });
          else if (/cancel/i.test(reason || "")) resolve({ ok: true, canceled: true });
          else resolve({ ok: false, code: "PRINT_FAILED", message: reason || "PRINT_FAILED" });
        });
      });
    } catch (error) {
      return { ok: false, code: "PRINT_FAILED", message: error instanceof Error ? error.message : "PRINT_FAILED" };
    } finally {
      if (window && !window.isDestroyed()) window.destroy();
    }
  }
  return { preparePdf, printExport };
}

module.exports = { createExportPreviewService };
