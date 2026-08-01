import fs from "node:fs";
import vm from "node:vm";
import { describe, expect, it, vi } from "vitest";

describe("desktop preload hardware bridge", () => {
  it("forwards fiscal ticket totals without transforming the payload", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true });
    let desktopApi;
    const code = fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8");
    vm.runInNewContext(code, {
      require: (moduleName) => {
        if (moduleName !== "electron") throw new Error(`unexpected module ${moduleName}`);
        return {
          contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } },
          ipcRenderer: { invoke }
        };
      }
    });
    const request = { documentNumber: "FV-1", subtotal: 100, tax: 21, total: 121 };
    const config = { ticketPrinterDriver: "ESCPOS_RAW" };

    await desktopApi.hardware.printTicket(request, config);

    expect(invoke).toHaveBeenCalledWith("tpv:hardware:print-ticket", request, config);
  });

  it("forwards A4 PDF exports through the dedicated desktop channel", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true, canceled: false });
    let desktopApi;
    const code = fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8");
    vm.runInNewContext(code, {
      require: () => ({
        contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } },
        ipcRenderer: { invoke },
      }),
    });
    const request = { documentType: "INVOICE", title: "Factura FV-1", total: 121 };

    await desktopApi.hardware.exportA4DocumentPdf(request, "FV-1.pdf");

    expect(invoke).toHaveBeenCalledWith(
      "tpv:hardware:export-a4-document-pdf",
      request,
      "FV-1.pdf",
    );
  });

  it("forwards modal sales utility lifecycle calls", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true });
    let desktopApi;
    const code = fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8");
    vm.runInNewContext(code, {
      require: () => ({
        contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } },
        ipcRenderer: { invoke },
      }),
    });
    const bootstrap = { kind: "PRODUCT_LABEL", session: { accessToken: "token" } };

    await desktopApi.salesUtilities.open(bootstrap);
    await desktopApi.salesUtilities.consumeBootstrap();
    await desktopApi.salesUtilities.complete({ printed: true, pdf: true });
    await desktopApi.salesUtilities.close();

    expect(invoke).toHaveBeenNthCalledWith(1, "tpv:sales-utility:open", bootstrap);
    expect(invoke).toHaveBeenNthCalledWith(2, "tpv:sales-utility:consume-bootstrap");
    expect(invoke).toHaveBeenNthCalledWith(3, "tpv:sales-utility:complete", { printed: true, pdf: true });
    expect(invoke).toHaveBeenNthCalledWith(4, "tpv:sales-utility:close");
  });

  it("forwards isolated table report PDF exports", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true, canceled: false });
    let desktopApi;
    const code = fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8");
    vm.runInNewContext(code, {
      require: () => ({
        contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } },
        ipcRenderer: { invoke },
      }),
    });
    const request = { title: "Historial", columns: [], rows: [], filters: [], totals: [] };

    await desktopApi.reports.exportTablePdf(request, "historial.pdf");

    expect(invoke).toHaveBeenCalledWith(
      "tpv:reports:export-table-pdf",
      request,
      "historial.pdf",
    );
  });
});
