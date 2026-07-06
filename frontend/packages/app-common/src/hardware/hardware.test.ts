import { describe, expect, it } from "vitest";
import {
  createHardwareUnavailableResult,
  createCustomerDisplayIdleState,
  createCustomerDisplayPaymentState,
  createCustomerDisplaySaleState,
  createA4TestDocument,
  createTestTicket,
  defaultHardwareConfig,
  getHardwareBridge
} from "./hardware";

describe("hardware facade", () => {
  it("uses safe default hardware configuration", () => {
    expect(defaultHardwareConfig).toEqual({
      scannerMode: "KEYBOARD",
      scannerSubmitKey: "ENTER",
      ticketPrinterMode: "WINDOWS_PRINTER",
      ticketPrinterName: "",
      openCashDrawerWithTicket: true,
      cashDrawerCommandProfile: "ESCPOS_STANDARD",
      escposConnectionType: "USB",
      escposDevicePath: "",
      escposSerialBaudRate: 9600,
      escposHost: "",
      escposPort: 9100,
      customerDisplayEnabled: false,
      customerDisplayMode: "COMPACT",
      customerDisplayIdleLine1: "BIENVENIDO",
      customerDisplayIdleLine2: "GRACIAS POR SU COMPRA",
      customerDisplayScreenId: "",
      a4PrinterName: "",
      documentPrintRoutes: [
        {
          documentType: "TICKET",
          printerTarget: "TICKET_PRINTER",
          printerName: "",
          paperSize: "TICKET_80",
          orientation: "PORTRAIT",
          copies: 1,
          printAutomatically: true,
          showPrintDialog: false
        },
        {
          documentType: "INVOICE",
          printerTarget: "A4_PRINTER",
          printerName: "",
          paperSize: "A4",
          orientation: "PORTRAIT",
          copies: 1,
          printAutomatically: false,
          showPrintDialog: true
        },
        {
          documentType: "DELIVERY_NOTE",
          printerTarget: "A4_PRINTER",
          printerName: "",
          paperSize: "A4",
          orientation: "PORTRAIT",
          copies: 1,
          printAutomatically: false,
          showPrintDialog: true
        },
        {
          documentType: "REPORT",
          printerTarget: "A4_PRINTER",
          printerName: "",
          paperSize: "A4",
          orientation: "PORTRAIT",
          copies: 1,
          printAutomatically: false,
          showPrintDialog: true
        }
      ]
    });
  });

  it("returns a controlled unavailable result outside Electron", async () => {
    const bridge = getHardwareBridge();

    await expect(bridge.listPrinters()).resolves.toEqual({
      ok: false,
      code: "HARDWARE_UNAVAILABLE",
      message: "Hardware local no disponible"
    });
  });

  it("creates a printable test ticket payload", () => {
    const ticket = createTestTicket({
      storeName: "Tienda Principal",
      terminalCode: "01"
    });

    expect(ticket.documentNumber).toBe("TEST-01");
    expect(ticket.storeName).toBe("Tienda Principal");
    expect(ticket.terminalCode).toBe("01");
    expect(ticket.lines).toHaveLength(0);
    expect(ticket.payments).toEqual([]);
    expect(ticket.total).toBe(0);
  });

  it("builds unavailable results with a stable shape", () => {
    expect(createHardwareUnavailableResult("TEST")).toEqual({
      ok: false,
      code: "HARDWARE_UNAVAILABLE",
      message: "TEST"
    });
  });

  it("creates idle customer display state with two configured lines", () => {
    expect(createCustomerDisplayIdleState("HOLA", "VUELVA PRONTO")).toEqual({
      line1: "HOLA",
      line2: "VUELVA PRONTO"
    });
  });

  it("creates sale customer display state with item and quantity price line", () => {
    expect(createCustomerDisplaySaleState({ name: "Arroz", quantity: 2, price: 1.25 })).toEqual({
      line1: "Arroz",
      line2: "2 x 1.25"
    });
  });

  it("creates payment customer display state with total and change", () => {
    expect(createCustomerDisplayPaymentState({ total: 12.5, change: 2.5 })).toEqual({
      line1: "TOTAL: 12.50",
      line2: "CAMBIO: 2.50"
    });
  });

  it("creates an A4 test document with sample lines and totals", () => {
    const document = createA4TestDocument({ storeName: "Tienda Principal", terminalCode: "01" });

    expect(document.title).toBe("Prueba A4");
    expect(document.documentType).toBe("REPORT");
    expect(document.lines).toHaveLength(0);
    expect(document.total).toBe(0);
    expect(document.taxIncluded).toBe(true);
  });
});
