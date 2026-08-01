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
    expect(html.match(/<article class="label">/g)).toHaveLength(2);
    expect(html).toContain("12.50 €");
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
    expect(html.match(/<article class="label">/g)).toHaveLength(23);
  });
});
