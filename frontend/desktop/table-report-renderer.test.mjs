import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { renderTableReportHtml } = require("./table-report-renderer.cjs");

describe("table report renderer", () => {
  it("renders a printable table, totals and an optional product image safely", () => {
    const html = renderTableReportHtml({
      title: "Historial de ventas",
      subject: "Producto <demo>",
      code: "ABC-1",
      imageDataUrl: "data:image/png;base64,AAAA",
      filters: [{ label: "Estado", value: "Todos" }],
      columns: [{ key: "quantity", label: "Cantidad" }],
      rows: [["2,00"]],
      totals: [{ label: "Cantidad total vendida", value: "2,00" }],
    });

    expect(html).toContain("data:image/png;base64,AAAA");
    expect(html).toContain("Cantidad total vendida");
    expect(html).toContain("2,00");
    expect(html).toContain("@page { size: A4 portrait;");
    expect(html).toContain("Producto &lt;demo&gt;");
    expect(html).not.toContain("Producto <demo>");
    expect(html.indexOf('<h1 class="document-title">Historial de ventas</h1>'))
      .toBeLessThan(html.indexOf('<header class="title-band">'));
    expect(html).not.toMatch(/<header class="title-band">[\s\S]*?<h1/);
  });

  it("uses a compact fallback and rejects non-data image sources", () => {
    const html = renderTableReportHtml({
      title: "Historial",
      imageDataUrl: "https://example.invalid/image.png",
      imageFallback: "p",
      columns: [],
      rows: [],
    });

    expect(html).toContain('product-fallback">P</div>');
    expect(html).not.toContain("example.invalid");
  });
});
