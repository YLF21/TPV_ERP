import { createRequire } from "node:module";
import { describe, expect, it, vi } from "vitest";
const require = createRequire(import.meta.url);
const { createExportPreviewService } = require("./export-preview.cjs");

function setup(printSuccess = true, reason = "") {
  const windows = [];
  class BrowserWindow {
    constructor(options) { this.options = options; windows.push(this); }
    webContents = {
      setWindowOpenHandler: vi.fn(), on: vi.fn(),
      printToPDF: vi.fn().mockResolvedValue(Buffer.from("%PDF-1.7\nprepared")),
      print: vi.fn((_options, callback) => callback(printSuccess, reason)),
    };
    loadURL = vi.fn().mockResolvedValue(undefined);
    isDestroyed = () => false;
    destroy = vi.fn();
  }
  return { windows, service: createExportPreviewService({ BrowserWindow }) };
}

describe("export preview preparation", () => {
  it.each(["ticket", "a4"])("preserves the exact authoritative %s PDF without rendering or printing", async (kind) => {
    const { service, windows } = setup();
    const original = Buffer.from("%PDF-1.7\noriginal signed fiscal snapshot\n%%EOF");
    const result = await service.preparePdf({ kind, document: {
      requireRenderedDocument: true,
      renderedPdf: { contentType: "application/pdf", base64: original.toString("base64") },
    } });
    expect(result).toEqual({ ok: true, bytes: original });
    expect(windows).toHaveLength(0);
  });

  it.each(["ticket", "a4"])("does not replace a missing required %s Jasper document with HTML", async (kind) => {
    const { service, windows } = setup();
    expect(await service.preparePdf({ kind, document: { requireRenderedDocument: true } }))
      .toMatchObject({ ok: false, code: "PDF_PREVIEW_FAILED" });
    expect(windows).toHaveLength(0);
  });

  it("renders a report in a sandbox without printing or saving and destroys the temporary window", async () => {
    const { service, windows } = setup();
    const result = await service.preparePdf({ kind: "table", document: {
      title: "Clientes", columns: [{ key: "name", label: "Nombre" }], rows: [["<script>bad()</script>"]], filters: [], totals: [],
    } });
    expect(result.ok).toBe(true);
    expect(windows).toHaveLength(1);
    const window = windows[0];
    expect(window.options.webPreferences).toEqual({ contextIsolation: true, nodeIntegration: false, sandbox: true, javascript: false });
    expect(decodeURIComponent(window.loadURL.mock.calls[0][0])).toContain("script-src 'none'");
    expect(window.webContents.printToPDF).toHaveBeenCalledWith(expect.objectContaining({ pageSize: "A4", printBackground: true }));
    expect(window.webContents.print).not.toHaveBeenCalled();
    expect(window.destroy).toHaveBeenCalledOnce();
    expect(window.webContents.setWindowOpenHandler.mock.calls[0][0]()).toEqual({ action: "deny" });
  });

  it("uses landscape only for marked wide directory reports", async () => {
    const { service, windows } = setup();
    const columns = Array.from({ length: 7 }, (_, index) => ({ key: String(index), label: `Campo ${index}` }));
    await service.preparePdf({ kind: "table", document: { title: "Clientes", reportStyle: "partyDirectory", columns, rows: [] } });
    await service.preparePdf({ kind: "table", document: { title: "Otro", columns, rows: [] } });
    expect(windows[0].webContents.printToPDF.mock.calls[0][0].landscape).toBe(true);
    expect(windows[1].webContents.printToPDF.mock.calls[0][0].landscape).toBe(false);
  });

  it("rejects an unsupported renderer before opening a window", async () => {
    const { service, windows } = setup();
    expect(await service.preparePdf({ kind: "raw", document: {} })).toMatchObject({ ok: false });
    expect(windows).toHaveLength(0);
  });
});

describe("explicit export printing", () => {
  it("always asks for a printer and never uses silent ticket printing", async () => {
    const { service, windows } = setup();
    expect(await service.printExport({ html: "<html><head></head><body>Excel</body></html>" })).toEqual({ ok: true });
    expect(windows[0].webContents.print).toHaveBeenCalledWith({ silent: false, printBackground: true, margins: { marginType: "none" } }, expect.any(Function));
    expect(windows[0].destroy).toHaveBeenCalledOnce();
  });
  it("preserves cancellation and releases the print window", async () => {
    const { service, windows } = setup(false, "Print job canceled");
    expect(await service.printExport({ html: "<p>Preview</p>" })).toEqual({ ok: true, canceled: true });
    expect(windows[0].destroy).toHaveBeenCalledOnce();
  });
  it.each([{ pdfBytes: Buffer.from("HTML") }, { html: "x", pdfBytes: Buffer.from("%PDF-") }, {}])("rejects invalid print input", async (request) => {
    const { service, windows } = setup();
    expect(await service.printExport(request)).toMatchObject({ ok: false, code: "PRINT_FAILED" });
    expect(windows).toHaveLength(0);
  });
});
