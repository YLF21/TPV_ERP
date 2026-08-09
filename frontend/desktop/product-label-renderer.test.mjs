import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { eanBits, renderProductLabelHtml } = require("./product-label-renderer.cjs");

describe("product label renderer", () => {
  it("encodes EAN-8 and EAN-13 with their guard bars", () => {
    expect(eanBits("73513537")).toHaveLength(67);
    expect(eanBits("4006381333931")).toHaveLength(95);
    expect(eanBits("4006381333931")).toMatch(/^101/);
    expect(() => eanBits("4006381333932")).toThrow("PRODUCT_LABEL_EAN_INVALID");
  });

  it("renders an A4 grid from the selected starting label", () => {
    const html = renderProductLabelHtml({
      storeName: "Tienda",
      product: { name: "Producto", code: "P1", barcode: "4006381333931", price: 12.5 },
      copies: 2,
      startPosition: 3,
      profile: { destination: "A4", widthMm: 58, heightMm: 40, showStoreName: true },
    });
    expect(html.match(/class="label empty"/g)).toHaveLength(3);
    expect(html.match(/<article class="label(?: with-company)?">/g)).toHaveLength(2);
    expect(html).toContain("12.50 &euro;");
  });

  it("creates as many A4 sheets as needed without repeating the starting offset", () => {
    const html = renderProductLabelHtml({
      product: { name: "Producto", code: "P1", barcode: "4006381333931", price: 1 },
      copies: 23,
      startPosition: 2,
      profile: {
        destination: "A4",
        widthMm: 100,
        heightMm: 50,
        marginTopMm: 5,
        marginRightMm: 5,
        marginBottomMm: 5,
        marginLeftMm: 5,
        horizontalGapMm: 0,
        verticalGapMm: 0,
      },
    });
    expect(html.match(/<main class="sheet">/g)).toHaveLength(3);
    expect(html.match(/class="label empty"/g)).toHaveLength(2);
    expect(html.match(/<article class="label(?: with-company)?">/g)).toHaveLength(23);
  });

  it("renders a sequential V2 batch with company identity", () => {
    const html = renderProductLabelHtml({
      version: 2,
      kind: "SEQUENTIAL",
      storeName: "Tienda",
      issuer: {
        name: "TPV ERP SL",
        taxId: "B12345678",
        address: { line1: "Calle Mayor 1", postalCode: "35001", city: "Las Palmas", province: "Las Palmas", country: "ES" },
      },
      profile: { destination: "TICKET_PRINTER", widthMm: 58, heightMm: 40, showStoreName: true },
      items: [
        { id: "one", copies: 2, product: { name: "Producto 1", code: "P1", barcode: "4006381333931", price: 1 } },
        { id: "two", copies: 1, product: { name: "Producto 2", code: "P2", barcode: "8435606744034", price: 2 } },
      ],
    });

    expect(html.match(/<article class="label(?: with-company)?">/g)).toHaveLength(3);
    expect(html).toContain("TPV ERP SL");
    expect(html).toContain("CIF: B12345678");
    expect(html).toContain("Calle Mayor 1, 35001 Las Palmas, Las Palmas, ES");
    expect(html).toContain("-webkit-line-clamp:2");
    expect(html).toContain('class="barcode-block"><svg class="barcode"');
    expect(html).toContain("padding:0 1.2mm .8mm");
  });

  it("renders V2 A4 placements at their exact millimetre coordinates", () => {
    const html = renderProductLabelHtml({
      version: 2,
      kind: "A4_LAYOUT",
      storeName: "Tienda",
      profile: {
        destination: "A4", widthMm: 58, heightMm: 40, showStoreName: false,
        marginTopMm: 5, marginRightMm: 5, marginBottomMm: 5, marginLeftMm: 5,
      },
      items: [{ id: "one", copies: 1, product: { name: "Producto", code: "P1", barcode: "4006381333931", price: 1 } }],
      pages: [{ placements: [{ instanceId: "one::1", itemId: "one", xMm: 12, yMm: 18, widthMm: 60, heightMm: 42 }] }],
    });

    expect(html).toContain("left:12mm;top:18mm;width:60mm;height:42mm");
    expect(html.match(/<main class="sheet">/g)).toHaveLength(1);
  });

  it("renders a monochrome offer and promotion layout without trusting its text", () => {
    const html = renderProductLabelHtml({
      version: 2,
      kind: "SEQUENTIAL",
      profile: { destination: "TICKET_PRINTER", widthMm: 58, heightMm: 40, showStoreName: false },
      items: [{
        id: "one",
        copies: 1,
        product: {
          name: "Producto promocionado",
          code: "P1",
          barcode: "4006381333931",
          price: 10,
          commercial: {
            badge: "OFERTA + PROMO",
            offer: { regularPrice: 10, offerPrice: 8, discountPercent: 20, validUntil: "hasta 31/08" },
            promotionLines: ["3x2 <combinable>", "2.ª unidad -50%"],
          },
        },
      }],
    });

    expect(html).toContain('class="label with-promotions"');
    expect(html).toContain('class="commercial-badge">OFERTA + PROMO');
    expect(html).toContain("<del>10.00 &euro;</del>");
    expect(html).toContain("<strong>8.00 &euro;</strong>");
    expect(html).toContain("3x2 &lt;combinable&gt;");
    expect(html).toContain(".promotion-summary");
  });

  it("does not strike the sale price when a product has a promotion but no offer", () => {
    const html = renderProductLabelHtml({
      version: 2,
      kind: "SEQUENTIAL",
      profile: { destination: "TICKET_PRINTER", widthMm: 58, heightMm: 40, showStoreName: false },
      items: [{
        id: "one",
        copies: 1,
        product: {
          name: "Producto con promocion",
          code: "P1",
          barcode: "4006381333931",
          price: 10,
          commercial: {
            badge: "PROMO",
            promotionLines: ["3x2"],
          },
        },
      }],
    });

    expect(html).toContain('<strong>10.00 &euro;</strong>');
    expect(html).not.toContain("<del>");
    expect(html).toContain('class="promotion-summary"');
    expect(html).toContain("3x2");
  });

  it("rejects incomplete and overlapping V2 layouts at the Electron boundary", () => {
    const base = {
      version: 2,
      kind: "A4_LAYOUT",
      profile: { destination: "A4", widthMm: 58, heightMm: 40, showStoreName: false },
      items: [{ id: "one", copies: 2, product: { name: "Producto", code: "P1", barcode: "4006381333931", price: 1 } }],
    };
    expect(() => renderProductLabelHtml({ ...base, pages: [{ placements: [
      { instanceId: "one::1", itemId: "one", xMm: 5, yMm: 5, widthMm: 58, heightMm: 40 },
    ] }] })).toThrow("PRODUCT_LABEL_INVALID_REQUEST");
    expect(() => renderProductLabelHtml({ ...base, pages: [{ placements: [
      { instanceId: "one::1", itemId: "one", xMm: 5, yMm: 5, widthMm: 58, heightMm: 40 },
      { instanceId: "one::2", itemId: "one", xMm: 10, yMm: 10, widthMm: 58, heightMm: 40 },
    ] }] })).toThrow("PRODUCT_LABEL_INVALID_REQUEST");
  });
});
