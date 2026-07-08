import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { buildCashDrawerBuffer, buildTicketBuffer, normalizeSerialPath, shouldOpenCashDrawerForTicket } = require("./escpos.cjs");

describe("escpos command builder", () => {
  it("builds a ticket with init, text, line feed and cut command", () => {
    const buffer = buildTicketBuffer({
      documentNumber: "T-1",
      storeName: "Tienda",
      terminalCode: "01",
      issuedAt: "2026-07-06T10:00:00.000Z",
      lines: [{ name: "Articulo", quantity: 2, price: 1.5, total: 3 }],
      payments: [{ method: "EFECTIVO", amount: 3 }],
      total: 3
    });

    expect([...buffer.subarray(0, 2)]).toEqual([0x1b, 0x40]);
    expect(buffer.toString("latin1")).toContain("Tienda");
    expect(buffer.toString("latin1")).toContain("T-1");
    expect([...buffer.subarray(-3)]).toEqual([0x1d, 0x56, 0x00]);
  });

  it("builds the standard cash drawer pulse command", () => {
    expect([...buildCashDrawerBuffer()]).toEqual([0x1b, 0x70, 0x00, 0x19, 0xfa]);
  });

  it("normalizes Windows COM ports for direct serial writes", () => {
    expect(normalizeSerialPath("COM3")).toBe("\\\\.\\COM3");
    expect(normalizeSerialPath("\\\\.\\COM4")).toBe("\\\\.\\COM4");
  });

  it("opens the cash drawer only for configured payment methods", () => {
    expect(
      shouldOpenCashDrawerForTicket(
        {
          openCashDrawerWithTicket: true,
          cashDrawerOpeningPaymentMethods: ["EFECTIVO"]
        },
        { payments: [{ method: "TARJETA", amount: 12 }] }
      )
    ).toBe(false);

    expect(
      shouldOpenCashDrawerForTicket(
        {
          openCashDrawerWithTicket: true,
          cashDrawerOpeningPaymentMethods: ["EFECTIVO"]
        },
        { payments: [{ method: "Efectivo", amount: 12 }] }
      )
    ).toBe(true);
  });
});
