import { describe, expect, it } from "vitest";
import {
  createHardwareUnavailableResult,
  createCustomerDisplayIdleState,
  createCustomerDisplayPaymentState,
  createCustomerDisplaySaleState,
  createA4TestDocument,
  createTestTicket,
  defaultHardwareConfig,
  getHardwareBridge,
  normalizeHardwareConfigForUi,
} from "./hardware";

describe("hardware facade", () => {
  it("uses safe default hardware configuration", () => {
    expect(defaultHardwareConfig).toEqual({
      scannerMode: "KEYBOARD",
      scannerSubmitKey: "ENTER",
      ticketPrinterDriver: "WINDOWS_DRIVER",
      ticketPrinterConnection: "WINDOWS_PRINTER",
      ticketPrinterName: "",
      openCashDrawerWithTicket: true,
      cashDrawerOpeningPaymentMethods: ["EFECTIVO"],
      cashDrawerConnection: "PRINTER",
      cashDrawerCommandProfile: "ESCPOS_STANDARD",
      escposDevicePath: "",
      escposSerialBaudRate: 9600,
      escposHost: "",
      escposPort: 9100,
      escposAdditionalFeedLines: 0,
      cashDrawerDevicePath: "",
      cashDrawerSerialBaudRate: 9600,
      cashDrawerHost: "",
      cashDrawerPort: 9100,
      customerDisplayEnabled: false,
      customerDisplayMode: "DIGITAL_SCREEN",
      customerDisplayIdleLine1: "BIENVENIDO",
      customerDisplayIdleLine2: "GRACIAS POR SU COMPRA",
      customerDisplayScreenId: "",
      customerDisplayConnection: "SERIAL",
      customerDisplayProtocol: "ESC_POS_DISPLAY",
      customerDisplayEncoding: "CP858",
      customerDisplayColumns: 20,
      customerDisplayDevicePath: "",
      customerDisplaySerialBaudRate: 9600,
      customerDisplayHost: "",
      customerDisplayPort: 9100,
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
      ],
      defaultProductLabelProfileId: "ticket-58x40",
      productLabelProfiles: [
        {
          id: "ticket-58x40",
          name: "Ticket 58 x 40 mm",
          destination: "TICKET_PRINTER",
          printerName: "",
          widthMm: 58,
          heightMm: 40,
          orientation: "PORTRAIT",
          marginTopMm: 5,
          marginRightMm: 5,
          marginBottomMm: 5,
          marginLeftMm: 5,
          horizontalGapMm: 2,
          verticalGapMm: 2,
          copies: 1,
          showStoreName: true
        }
      ]
    });
  });

  it("keeps the current ESC/POS feed as the minimum and clamps additional lines", () => {
    expect(normalizeHardwareConfigForUi({ escposAdditionalFeedLines: -4 }).escposAdditionalFeedLines).toBe(0);
    expect(normalizeHardwareConfigForUi({ escposAdditionalFeedLines: 7.9 }).escposAdditionalFeedLines).toBe(7);
    expect(normalizeHardwareConfigForUi({ escposAdditionalFeedLines: 99 }).escposAdditionalFeedLines).toBe(12);
  });

  it("migrates legacy compact displays and normalizes physical display parameters", () => {
    expect(normalizeHardwareConfigForUi({ customerDisplayMode: "COMPACT" } as never).customerDisplayMode).toBe("DIGITAL_SCREEN");
    expect(normalizeHardwareConfigForUi({ customerDisplayMode: "LINE_DISPLAY", customerDisplayColumns: 5,
      customerDisplayConnection: "NETWORK", customerDisplayProtocol: "PLAIN_TEXT" }).customerDisplayColumns).toBe(10);
    expect(normalizeHardwareConfigForUi({ customerDisplayColumns: 200 }).customerDisplayColumns).toBe(80);
  });

  it("defaults physical display encoding to CP858 and preserves explicit ASCII", () => {
    expect(normalizeHardwareConfigForUi({}).customerDisplayEncoding).toBe("CP858");
    expect(normalizeHardwareConfigForUi({ customerDisplayEncoding: "ASCII" }).customerDisplayEncoding).toBe("ASCII");
    expect(normalizeHardwareConfigForUi({ customerDisplayEncoding: "UTF8" } as never).customerDisplayEncoding).toBe("CP858");
  });

  it("preserves positive safe integer display baud rates and defaults invalid values", () => {
    for (const baud of [300, 600, 12345, 31250, Number.MAX_SAFE_INTEGER]) {
      expect(normalizeHardwareConfigForUi({ customerDisplaySerialBaudRate: baud }).customerDisplaySerialBaudRate).toBe(baud);
    }
    for (const baud of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, "9600 & calc"]) {
      expect(normalizeHardwareConfigForUi({ customerDisplaySerialBaudRate: baud } as never).customerDisplaySerialBaudRate).toBe(9600);
    }
  });

  it("returns a controlled unavailable result outside Electron", async () => {
    const bridge = getHardwareBridge();

    await expect(bridge.listPrinters()).resolves.toEqual({
      ok: false,
      code: "HARDWARE_UNAVAILABLE",
      message: "Hardware local no disponible"
    });
    await expect(bridge.listSerialPorts()).resolves.toEqual(createHardwareUnavailableResult());
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
