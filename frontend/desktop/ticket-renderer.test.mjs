import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";
const require = createRequire(import.meta.url);
const { renderTicketHtml } = require("./ticket-renderer.cjs");

describe("ticket desktop renderer", () => {
  it.each([
    [{ item: "Item", quantity: "Qty.", price: "Price", total: "Total", terminal: "Terminal" }, "Item", "Qty."],
    [{ item: "商品", quantity: "数量", price: "价格", total: "合计", terminal: "终端" }, "商品", "数量"]
  ])("uses payload labels and escapes dynamic copy", (labels, item, quantity) => {
    const html = renderTicketHtml({ storeName: "Shop <x>", documentNumber: "R-1", terminalCode: "01", issuedAt: "now",
      lines: [{ name: "Coffee <b>", quantity: 1, price: 2, total: 2 }], payments: [], total: 2, labels });
    expect(html).toContain(item); expect(html).toContain(quantity);
    expect(html).toContain("Shop &lt;x&gt;"); expect(html).toContain("Coffee &lt;b&gt;");
    expect(html).not.toContain("Articulo"); expect(html).not.toContain("<b>");
  });

  it("renders one F11 summary row before taxes and fiscal total", () => {
    const html = renderTicketHtml({
      storeName: "Shop", documentNumber: "R-2", terminalCode: "01",
      lines: [{ name: "Articulo", quantity: 1, price: 30, total: 30 }],
      payments: [], discount: 10, subtotal: 16.53, tax: 3.47, total: 20,
      labels: {
        item: "Articulo", quantity: "Cant.", price: "Precio", discount: "Descuento",
        base: "Base", tax: "Impuesto", total: "Total", terminal: "Terminal"
      }
    });

    expect(html.match(/Descuento/g)).toHaveLength(1);
    expect(html).toContain("-10.00");
    expect(html.indexOf("Descuento")).toBeLessThan(html.indexOf("Impuesto"));
    expect(html.indexOf("Impuesto")).toBeLessThan(html.lastIndexOf("Total"));
  });

  it("renders a non-fiscal cancellation receipt without an item table", () => {
    const html = renderTicketHtml({
      layout: "CANCELLATION_RECEIPT",
      title: "COMPROBANTE DE ANULACIÓN",
      notice: "DOCUMENTO NO FISCAL",
      documentNumber: "AN-T-1",
      storeName: "Tienda",
      terminalCode: "01",
      issuedAt: "09/08/2026 21:00",
      details: [{ label: "Ticket original", value: "T-1" }],
      lines: [],
      payments: [{ method: "Tarjeta", amount: 25, reference: "AUTH-1" }],
      total: 25,
      labels: { terminal: "Terminal", item: "", quantity: "", price: "", total: "Total anulado" }
    });

    expect(html).toContain("COMPROBANTE DE ANULACIÓN");
    expect(html).toContain("Ticket original");
    expect(html).toContain("AUTH-1");
    expect(html).toContain("Total anulado");
    expect(html).toContain("DOCUMENTO NO FISCAL");
    expect(html).not.toContain("<table>");
  });

  it("renders the store logo and the ticket observations", () => {
    const html = renderTicketHtml({
      logo: "data:image/png;base64,AA==",
      notes: ["Gracias <cliente>"],
      storeName: "Tienda", documentNumber: "T-1", terminalCode: "01",
      lines: [], payments: [], total: 0,
    });

    expect(html).toContain('class="logo"');
    expect(html).toContain("data:image/png;base64,AA==");
    expect(html).toContain("Gracias &lt;cliente&gt;");
  });
});
