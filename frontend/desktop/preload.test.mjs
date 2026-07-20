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
});
