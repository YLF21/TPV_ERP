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
      labels: { terminal: "Terminal", description: "Description", quantity: "Quantity",
        unitPrice: "Unit price", base: "Base", tax: "Tax", taxIncluded: "Tax included",
        yes: "Yes", no: "No", total: "Total" }
    });
    expect(html).toContain("Description");
    expect(html).toContain("Tax included");
    expect(html).toContain("Yes");
    expect(html).toContain("3.47");
    expect(html).toContain("Invoice &lt;script&gt;");
    expect(html).toContain("Coffee &lt;b&gt;");
    expect(html).toContain("Issuer &amp; Co");
    expect(html).toContain("B123");
    expect(html).toContain("Customer &lt;SL&gt;");
    expect(html).toContain("B987");
    expect(html).toContain("Customer Street");
    expect(html).not.toContain("Descripcion");
    expect(html).not.toContain("<script>");
  });
});
