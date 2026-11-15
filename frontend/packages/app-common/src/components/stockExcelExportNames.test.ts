import { describe, expect, it } from "vitest";
import { stockExcelDefaultFileName } from "./stockExcelExportNames";

describe("stock Excel export file names", () => {
  const date = new Date(2026, 6, 7, 0, 30);

  it.each([
    ["stock.current", "es", "Stock_070726.xlsx"],
    ["stock.offers", "es", "Ofertas_070726.xlsx"],
    ["stock.memberPrice", "es", "Precio_de_socio_070726.xlsx"],
    ["stock.noDiscount", "es", "Sin_descuento_070726.xlsx"],
    ["stock.topSales", "es", "Top_ventas_070726.xlsx"],
    ["stock.current", "en", "Stock_070726.xlsx"],
    ["stock.offers", "en", "Ofertas_070726.xlsx"],
    ["stock.memberPrice", "en", "Precio_de_socio_070726.xlsx"],
    ["stock.noDiscount", "en", "Sin_descuento_070726.xlsx"],
    ["stock.topSales", "en", "Top_ventas_070726.xlsx"],
    ["stock.current", "zh", "Stock_070726.xlsx"],
    ["stock.offers", "zh", "Ofertas_070726.xlsx"],
    ["stock.memberPrice", "zh", "Precio_de_socio_070726.xlsx"],
    ["stock.noDiscount", "zh", "Sin_descuento_070726.xlsx"],
    ["stock.topSales", "zh", "Top_ventas_070726.xlsx"],
  ] as const)("names %s consistently in %s", (view, locale, expected) => {
    expect(stockExcelDefaultFileName(view, locale, undefined, undefined, date)).toBe(expected);
  });

  it("preserves promotion case, accents, spaces and Unicode", () => {
    expect(stockExcelDefaultFileName("stock.promotions", "es", "  Ofértá Verano  ", "SELECTED", date, "2026-01-03", "2026-09-30"))
      .toBe("Ofértá Verano_030126-300926.xlsx");
    expect(stockExcelDefaultFileName("stock.promotions", "zh", "促销😀活动", "SELECTED", date, "2026-07-07", null))
      .toBe("促销😀活动_070726-Sin_fin.xlsx");
  });

  it("replaces Windows-invalid characters and controls without creating a path", () => {
    expect(stockExcelDefaultFileName(
      "stock.promotions",
      "es",
      `../ÁRBOL <B> \\ C:\\Árbol?*|\u0001`,
      "SELECTED",
      date,
      "2026-01-03", "2026-09-30",
    )).toBe("ÁRBOL B C Árbol_030126-300926.xlsx");
  });

  it("truncates to 100 Unicode codepoints", () => {
    expect(stockExcelDefaultFileName("stock.promotions", "en", `${"😀".repeat(101)} tail`, "SELECTED", date, "2026-07-07", null))
      .toBe(`${"😀".repeat(100)}_070726-Sin_fin.xlsx`);
  });

  it.each([
    "es", "en", "zh",
  ] as const)("uses the common selected fallback for %s", locale => {
    expect(stockExcelDefaultFileName("stock.promotions", locale, "...", "SELECTED", date, "2026-07-07", null))
      .toBe("Promocion_070726-Sin_fin.xlsx");
  });

  it.each([
    ["es", "Promociones_070726.xlsx"],
    ["en", "Promociones_070726.xlsx"],
    ["zh", "Promociones_070726.xlsx"],
  ] as const)("defaults stock.promotions to active promotions in %s", (locale, expected) => {
    expect(stockExcelDefaultFileName("stock.promotions", locale, undefined, undefined, date)).toBe(expected);
  });

  it("does not invent a promotion date when the start or end is invalid", () => {
    expect(stockExcelDefaultFileName("stock.promotions", "es", "Oferta", "SELECTED", date))
      .toBe("Oferta.xlsx");
    expect(stockExcelDefaultFileName("stock.promotions", "es", "Oferta", "SELECTED", date, "2026-02-30", "2026-03-01"))
      .toBe("Oferta.xlsx");
    expect(stockExcelDefaultFileName("stock.promotions", "es", "Oferta", "SELECTED", date, "2026-02-28", "bad"))
      .toBe("Oferta_280226.xlsx");
  });

  it("ignores a promotion scope for every other stock view", () => {
    expect(stockExcelDefaultFileName("stock.offers", "es", "Oferta", "SELECTED", date))
      .toBe("Ofertas_070726.xlsx");
    expect(stockExcelDefaultFileName("stock.current", "en", undefined, "ACTIVE", date))
      .toBe("Stock_070726.xlsx");
  });
});
