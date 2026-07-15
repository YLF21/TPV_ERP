import { createRequire } from "node:module";
import { describe, expect, it, vi } from "vitest";

const require = createRequire(import.meta.url);
const {
  buildTicketCopyBuffers,
  executeEscposTicketPrint,
  executeWindowsTicketPrint,
  resolveExternalDrawerAction,
  resolveTicketPrintRoute,
  withTicketPrinterRoute
} = require("./ticket-print-route.cjs");

describe("ticket print route", () => {
  it("uses the configured TICKET route printer and copies", () => {
    expect(resolveTicketPrintRoute({
      ticketPrinterName: "Legacy",
      documentPrintRoutes: [{
        documentType: "TICKET",
        printerName: "Caja 1",
        copies: 2,
        printAutomatically: true
      }]
    })).toEqual({ printerName: "Caja 1", copies: 2, printAutomatically: true });
  });

  it("falls back to the legacy printer and normalizes copies", () => {
    expect(resolveTicketPrintRoute({
      ticketPrinterName: "Legacy",
      documentPrintRoutes: [{ documentType: "TICKET", printerName: "", copies: 0, printAutomatically: true }]
    })).toEqual({ printerName: "Legacy", copies: 1, printAutomatically: true });
  });

  it("trims route printer whitespace before falling back to the trimmed legacy printer", () => {
    expect(resolveTicketPrintRoute({
      ticketPrinterName: "  Legacy  ",
      documentPrintRoutes: [{ documentType: "TICKET", printerName: "   ", copies: 1 }]
    })).toEqual({ printerName: "Legacy", copies: 1, printAutomatically: true });

    expect(resolveTicketPrintRoute({
      ticketPrinterName: "Legacy",
      documentPrintRoutes: [{ documentType: "TICKET", printerName: "  Caja 1  ", copies: 1 }]
    }).printerName).toBe("Caja 1");
  });

  it("builds multiple ticket copies with the cash drawer pulse only on the first copy", () => {
    const ticketBuffer = Buffer.from("ticket");
    const drawerBuffer = Buffer.from("drawer");

    expect(buildTicketCopyBuffers(ticketBuffer, 3, drawerBuffer)).toEqual([
      Buffer.from("drawerticket"),
      ticketBuffer,
      ticketBuffer
    ]);
  });

  it("propagates the resolved route printer to ticket printer operations", () => {
    expect(withTicketPrinterRoute(
      { ticketPrinterName: "Legacy", cashDrawerConnection: "PRINTER" },
      { printerName: "Caja 1", copies: 2, printAutomatically: true }
    )).toEqual({ ticketPrinterName: "Caja 1", cashDrawerConnection: "PRINTER" });
  });

  it("passes the routed Windows device and copies to Electron and opens the routed drawer", async () => {
    const print = vi.fn((_options, callback) => callback(true));
    const openDrawer = vi.fn().mockResolvedValue(undefined);

    await expect(executeWindowsTicketPrint({
      webContents: { print },
      printerName: "Caja 1",
      copies: 3,
      openDrawer,
      structuredError: (code, message) => ({ ok: false, code, message })
    })).resolves.toEqual({ ok: true });

    expect(print).toHaveBeenCalledWith({
      silent: true,
      deviceName: "Caja 1",
      copies: 3,
      printBackground: true
    }, expect.any(Function));
    expect(openDrawer).toHaveBeenCalledOnce();
  });

  it("returns the structured Windows failure without opening the drawer", async () => {
    const openDrawer = vi.fn();
    const result = await executeWindowsTicketPrint({
      webContents: { print: (_options, callback) => callback(false, "driver offline") },
      printerName: "Caja 1",
      copies: 2,
      openDrawer,
      structuredError: (code, message) => ({ ok: false, code, message })
    });

    expect(result).toEqual({ ok: false, code: "PRINT_FAILED", message: "driver offline" });
    expect(openDrawer).not.toHaveBeenCalled();
  });

  it("writes every ESC/POS copy, pulses the routed printer drawer once and preserves structured errors", async () => {
    const sent = [];
    const result = await executeEscposTicketPrint({
      sendBuffer: async (buffer) => sent.push(buffer.toString()),
      ticketBuffer: Buffer.from("ticket"),
      drawerBuffer: Buffer.from("drawer"),
      copies: 3,
      openExternalDrawer: vi.fn(),
      structuredError: (code, message) => ({ ok: false, code, message })
    });
    expect(result).toEqual({ ok: true });
    expect(sent).toEqual(["drawerticket", "ticket", "ticket"]);

    const failure = await executeEscposTicketPrint({
      sendBuffer: async () => { throw new Error("usb detached"); },
      ticketBuffer: Buffer.from("ticket"),
      copies: 2,
      structuredError: (code, message) => ({ ok: false, code, message })
    });
    expect(failure).toEqual({ ok: false, code: "ESCPOS_NOT_AVAILABLE", message: "usb detached" });
  });

  it("prints all ESC/POS copies successfully without creating a drawer action for NONE", async () => {
    const operations = [];
    const openDrawer = vi.fn(async () => operations.push("drawer"));
    const externalDrawer = resolveExternalDrawerAction(true, "NONE", openDrawer);

    const result = await executeEscposTicketPrint({
      sendBuffer: async (buffer) => operations.push(buffer.toString()),
      ticketBuffer: Buffer.from("ticket"),
      copies: 2,
      openExternalDrawer: externalDrawer,
      structuredError: (code, message) => ({ ok: false, code, message })
    });

    expect(result).toEqual({ ok: true });
    expect(operations).toEqual(["ticket", "ticket"]);
    expect(openDrawer).not.toHaveBeenCalled();
  });

  it("opens an external drawer once and only after every ESC/POS copy is written", async () => {
    const operations = [];
    const openDrawer = vi.fn(async () => operations.push("drawer"));

    const result = await executeEscposTicketPrint({
      sendBuffer: async (buffer) => operations.push(buffer.toString()),
      ticketBuffer: Buffer.from("ticket"),
      copies: 3,
      openExternalDrawer: resolveExternalDrawerAction(true, "NETWORK", openDrawer),
      structuredError: (code, message) => ({ ok: false, code, message })
    });

    expect(result).toEqual({ ok: true });
    expect(operations).toEqual(["ticket", "ticket", "ticket", "drawer"]);
    expect(openDrawer).toHaveBeenCalledOnce();
  });
});
