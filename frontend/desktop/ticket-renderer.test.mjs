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
});
