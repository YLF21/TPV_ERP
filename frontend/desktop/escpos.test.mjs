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

  it("prints issuer and customer fiscal identities on raw commercial documents", () => {
    const text = buildTicketBuffer({
      documentNumber: "FV-1", storeName: "Tienda", terminalCode: "01", issuedAt: "2026-07-16",
      issuer: { name: "TPV ERP SL", taxId: "B12345678", address: "Calle Mayor 1, 28001 Madrid, ES" },
      customer: { name: "Cliente Fiscal SL", taxId: "B87654321", address: "Avenida Sur 2, 41001 Sevilla, ES" },
      lines: [], payments: [], total: 0
    }).toString("latin1");

    expect(text).toContain("TPV ERP SL");
    expect(text).toContain("B12345678");
    expect(text).toContain("Calle Mayor 1, 28001 Madrid, ES");
    expect(text).toContain("Cliente Fiscal SL");
    expect(text).toContain("B87654321");
    expect(text).toContain("Avenida Sur 2, 41001 Sevilla, ES");
  });

  it("prints commercial fiscal totals in a stable base, tax, total order", () => {
    const text = buildTicketBuffer({
      documentNumber: "FV-2", storeName: "Tienda", terminalCode: "01", issuedAt: "2026-07-18",
      lines: [], payments: [], subtotal: 100, tax: 21, total: 121,
      escposLabels: {
        terminal: "Terminal", item: "Articulo", quantity: "Cant.", price: "Precio",
        base: "Base imponible", tax: "IVA", total: "TOTAL"
      }
    }).toString("latin1");

    const baseLine = text.indexOf("Base imponible");
    const taxLine = text.indexOf("IVA");
    const totalLine = text.lastIndexOf("TOTAL");
    expect(baseLine).toBeGreaterThan(-1);
    expect(taxLine).toBeGreaterThan(baseLine);
    expect(totalLine).toBeGreaterThan(taxLine);
    expect(text).toContain("Base imponible                      100.00");
    expect(text).toContain("IVA                                  21.00");
    expect(text).toContain("TOTAL                               121.00");
  });

  it("builds the standard cash drawer pulse command", () => {
    expect([...buildCashDrawerBuffer()]).toEqual([0x1b, 0x70, 0x00, 0x19, 0xfa]);
  });

  it("preserves the exact legacy layout when labels are absent", () => {
    const text = buildTicketBuffer({ storeName: "Shop", terminalCode: "01", lines: [], payments: [], total: 0 }).toString("latin1");
    expect(text).toContain("Terminal 01");
    expect(text).toContain("TOTAL                                 0.00");
    expect(text).not.toMatch(/Item|Qty\.|Price|Articulo|Cant\.|Precio/);
  });

  it.each([
    [{ terminal: "Terminal", item: "Artículo", quantity: "Cant.", price: "Precio", total: "TOTAL" }, ["Terminal 01", "Artículo", "Cant.", "Precio", "TOTAL"]],
    [{ terminal: "Terminal", item: "Item", quantity: "Qty.", price: "Price", total: "Total" }, ["Terminal 01", "Item", "Qty.", "Price", "Total"]],
    [{ terminal: "Zhongduan", item: "Shangpin", quantity: "Shuliang", price: "Jiage", total: "Heji" }, ["Zhongduan 01", "Shangpin", "Shuliang", "Jiage", "Heji"]]
  ])("uses localized printable labels in raw buffers", (labels, expected) => {
    const text = buildTicketBuffer({ storeName: "Shop", terminalCode: "01", lines: [], payments: [], total: 0, escposLabels: labels }).toString("latin1");
    for (const label of expected) expect(text).toContain(label.normalize("NFD").replace(/[\u0300-\u036f]/g, ""));
    if (labels.item !== "Artículo") expect(text).not.toContain("Articulo");
  });

  it("replaces unsupported latin1 glyphs deterministically instead of corrupting raw output", () => {
    const text = buildTicketBuffer({ terminalCode: "01", lines: [], payments: [], total: 0,
      labels: { terminal: "终端", item: "商品", quantity: "数量", price: "价格", total: "合计" } }).toString("latin1");
    expect(text).toContain("?? 01");
    expect(text).not.toContain("Terminal 01");
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
