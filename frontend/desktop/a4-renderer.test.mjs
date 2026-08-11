import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { renderA4DocumentHtml } = require("./a4-renderer.cjs");

describe("A4 desktop renderer", () => {
  it("uses localized payload labels and escapes untrusted document text", () => {
    const html = renderA4DocumentHtml({
      title: "Invoice <script>", storeName: "Shop & Co", terminalCode: "01", issuedAt: "2026-07-16",
      issuer: { name: "Issuer & Co", taxId: "B123", address: { line1: "Issuer Street", postalCode: "28001", city: "Madrid", province: "Madrid", country: "ES" } },
      customer: { name: "Customer <SL>", taxId: "B987", address: { line1: "Customer Street", postalCode: "41001", city: "Sevilla", province: "Sevilla", country: "ES" } },
      lines: [{ name: "Coffee <b>", quantity: 2, price: 10, total: 20 }],
      subtotal: 16.53, tax: 3.47, taxIncluded: true, total: 20,
      metadata: [{ label: "Warehouse", value: "General <north>" }],
      notes: ["Handle with care & verify"],
      labels: { terminal: "Terminal", description: "Description", quantity: "Quantity",
        unitPrice: "Unit price", base: "Base", tax: "Tax", taxIncluded: "Tax included",
        yes: "Yes", no: "No", total: "Total" }
    });
    expect(html).toContain("Description");
    expect(html).toContain("Tax");
    expect(html).toContain("3.47");
    expect(html).toContain("Invoice &lt;script&gt;");
    expect(html).toContain("Coffee &lt;b&gt;");
    expect(html).toContain("Issuer &amp; Co");
    expect(html).toContain("B123");
    expect(html).toContain("Customer &lt;SL&gt;");
    expect(html).toContain("B987");
    expect(html).toContain("Customer Street");
    expect(html).toContain("Handle with care &amp; verify");
    expect(html).not.toContain("Descripcion");
    expect(html).not.toContain("<script>");
  });

  it("omits rate and charged tax for IGIC retail invoices", () => {
    const html = renderA4DocumentHtml({
      title: "FACTURA FV-1",
      issuedAt: "2026-08-10",
      fiscalProfile: "IGIC_MINORISTA",
      lines: [{ name: "Articulo", quantity: 1, price: 10, taxPercentage: 7, total: 10 }],
      subtotal: 10,
      tax: 0,
      total: 10,
      labels: { taxRate: "IGIC %" },
    });

    expect(html).toContain("Comerciante minorista");
    expect(html).toContain('<th class="right tax-rate"></th>');
    expect(html).toContain('<td class="right tax-rate"></td>');
    expect(html).not.toContain('class="total-row tax-total"');
    expect(html).not.toContain(">IGIC %<");
  });

  it("renders delivery notes without requiring a customer or exposing invoice-only fiscal and payment data", () => {
    const html = renderA4DocumentHtml({
      documentType: "ALBARAN_VENTA",
      title: "ALBARÁN AV-1",
      documentNumber: "AV-1",
      issuedAt: "2026-08-10",
      issuer: { name: "Empresa", taxId: "B123", address: {} },
      customer: null,
      qrImage: "data:image/png;base64,should-not-appear",
      payments: [{ method: "Tarjeta", amount: 12 }],
      bankAccounts: [{ bankName: "Banco", iban: "ES001234" }],
      lines: [{ code: "A1", barcode: "8412345678901", name: "Artículo", quantity: 1, price: 12, taxPercentage: 7, total: 12 }],
      subtotal: 11.21,
      tax: 0.79,
      total: 12,
    });

    expect(html).toContain("ALBARÁN");
    expect(html).toContain("AV-1");
    expect(html).toContain("8412345678901");
    expect(html).not.toContain("QR AEAT");
    expect(html).not.toContain("should-not-appear");
    expect(html).not.toContain("Factura verificable");
    expect(html).not.toContain("Forma de pago");
    expect(html).not.toContain("Tarjeta");
    expect(html).not.toContain("Datos bancarios");
    expect(html).not.toContain("ES001234");
  });
});
