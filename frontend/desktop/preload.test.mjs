import fs from "node:fs";
import vm from "node:vm";
import { describe, expect, it, vi } from "vitest";

describe("desktop preload hardware bridge", () => {
  it("exposes scoped connection recovery calls and unsubscribes its status listener", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true });
    const on = vi.fn();
    const removeListener = vi.fn();
    let desktopApi;
    vm.runInNewContext(fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8"), {
      require: () => ({ contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } },
        ipcRenderer: { invoke, on, removeListener } }),
    });
    expect(Object.keys(desktopApi.connectionRecovery)).toEqual(["status", "retry", "onStatus"]);
    await desktopApi.connectionRecovery.status();
    await desktopApi.connectionRecovery.retry();
    expect(invoke.mock.calls).toEqual([
      ["tpv:connection-recovery:status"], ["tpv:connection-recovery:retry"],
    ]);
    const callback = vi.fn();
    expect(() => desktopApi.connectionRecovery.onStatus(null)).toThrow("Callback obligatorio");
    const unsubscribe = desktopApi.connectionRecovery.onStatus(callback);
    expect(on).toHaveBeenCalledWith("tpv:connection-recovery:status", expect.any(Function));
    const listener = on.mock.calls[0][1];
    const status = { state: "RECOVERING", attempts: 2 };
    listener({ sender: "electron" }, status);
    expect(callback).toHaveBeenCalledExactlyOnceWith(status);
    unsubscribe();
    expect(removeListener).toHaveBeenCalledExactlyOnceWith("tpv:connection-recovery:status", listener);
  });
  it("exposes only scoped work recovery load, save and clear channels", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true });
    let desktopApi;
    vm.runInNewContext(fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8"), {
      require: () => ({ contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } },
        ipcRenderer: { invoke } }),
    });
    expect(Object.keys(desktopApi.workRecovery)).toEqual(["load", "save", "clear"]);
    const snapshot = { screen: "sale", quantity: 2 };
    await desktopApi.workRecovery.load();
    await desktopApi.workRecovery.save(snapshot);
    await desktopApi.workRecovery.clear();
    expect(invoke.mock.calls).toEqual([
      ["tpv:work-recovery:load"], ["tpv:work-recovery:save", snapshot], ["tpv:work-recovery:clear"],
    ]);
  });
  it("exposes only the two dedicated display calls", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true, mode: "WINDOWED" });
    let desktopApi;
    vm.runInNewContext(fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8"), {
      require: () => ({ contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } }, ipcRenderer: { invoke } }),
    });
    expect(Object.keys(desktopApi.display)).toEqual(["load", "setMode"]);
    await desktopApi.display.load();
    await desktopApi.display.setMode("WINDOWED");
    expect(invoke.mock.calls).toEqual([["tpv:display:load"], ["tpv:display:set-mode", "WINDOWED"]]);
  });
  it("exposes only scoped control-event storage operations through dedicated channels", async () => {
    const invoke = vi.fn().mockResolvedValue({ ok: true });
    let desktopApi;
    vm.runInNewContext(fs.readFileSync(new URL("./preload.cjs", import.meta.url), "utf8"), {
      require: () => ({ contextBridge: { exposeInMainWorld: (_name, api) => { desktopApi = api; } }, ipcRenderer: { invoke } }),
    });
    const context = { storeId: "store", terminalId: "terminal", userId: "user" };
    const event = { context, deletionOperationId: "event" };
    await desktopApi.saleControlOutbox.list(context);
    await desktopApi.saleControlOutbox.put(event);
    await desktopApi.saleControlOutbox.remove(context, "event");
    expect(invoke.mock.calls).toEqual([
      ["tpv:sale-control:list", context], ["tpv:sale-control:put", event], ["tpv:sale-control:remove", context, "event"],
    ]);
  });
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
