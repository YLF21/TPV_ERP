import { describe, expect, it } from "vitest";
import { stockExcelDefaultFileName } from "./stockExcelExportNames";

describe("stock Excel export file names", () => {
  const date = new Date(2026, 6, 7, 0, 30);

  it.each([
    ["stock.current", "es", "stock-2026-07-07.xlsx"],
    ["stock.offers", "es", "productos-con-oferta-2026-07-07.xlsx"],
    ["stock.memberPrice", "es", "productos-precio-miembro-2026-07-07.xlsx"],
    ["stock.noDiscount", "es", "productos-sin-descuento-2026-07-07.xlsx"],
    ["stock.topSales", "es", "top-ventas-2026-07-07.xlsx"],
    ["stock.current", "en", "stock-2026-07-07.xlsx"],
    ["stock.offers", "en", "products-on-offer-2026-07-07.xlsx"],
    ["stock.memberPrice", "en", "member-price-products-2026-07-07.xlsx"],
    ["stock.noDiscount", "en", "non-discountable-products-2026-07-07.xlsx"],
    ["stock.topSales", "en", "top-sales-2026-07-07.xlsx"],
    ["stock.current", "zh", "库存-2026-07-07.xlsx"],
    ["stock.offers", "zh", "优惠商品-2026-07-07.xlsx"],
    ["stock.memberPrice", "zh", "会员价商品-2026-07-07.xlsx"],
    ["stock.noDiscount", "zh", "不可折扣商品-2026-07-07.xlsx"],
    ["stock.topSales", "zh", "热销商品-2026-07-07.xlsx"],
  ] as const)("localizes %s in %s", (view, locale, expected) => {
    expect(stockExcelDefaultFileName(view, locale, undefined, undefined, date)).toBe(expected);
  });

  it("normalizes accents and preserves Chinese promotion names", () => {
    expect(stockExcelDefaultFileName("stock.promotions", "es", "  Ofértá Verano  ", "SELECTED", date))
      .toBe("productos-promocion-oferta-verano-2026-07-07.xlsx");
    expect(stockExcelDefaultFileName("stock.promotions", "zh", "促销😀活动", "SELECTED", date))
      .toBe("促销商品-促销-活动-2026-07-07.xlsx");
  });

  it("converts Windows-invalid characters and traversal punctuation to slug separators", () => {
    expect(stockExcelDefaultFileName(
      "stock.promotions",
      "es",
      `../ÁRBOL <B> \\ C:\\Árbol?*|`,
      "SELECTED",
      date,
    )).toBe("productos-promocion-arbol-b-c-arbol-2026-07-07.xlsx");
  });

  it("truncates the normalized slug to 60 Unicode codepoints and trims a trailing hyphen", () => {
    expect(stockExcelDefaultFileName("stock.promotions", "en", `${"A".repeat(59)} 中!tail`, "SELECTED", date))
      .toBe(`promotion-products-${"a".repeat(59)}-2026-07-07.xlsx`);
  });

  it.each([
    ["es", "productos-promocion-seleccionada-2026-07-07.xlsx"],
    ["en", "promotion-products-selected-2026-07-07.xlsx"],
    ["zh", "促销商品-已选促销-2026-07-07.xlsx"],
  ] as const)("uses the localized selected fallback for %s", (locale, expected) => {
    expect(stockExcelDefaultFileName("stock.promotions", locale, "...", "SELECTED", date)).toBe(expected);
  });

  it.each([
    ["es", "productos-promociones-activas-2026-07-07.xlsx"],
    ["en", "active-promotions-products-2026-07-07.xlsx"],
    ["zh", "有效促销商品-2026-07-07.xlsx"],
  ] as const)("defaults stock.promotions to active promotions in %s", (locale, expected) => {
    expect(stockExcelDefaultFileName("stock.promotions", locale, undefined, undefined, date)).toBe(expected);
  });

  it("ignores a promotion scope for every other stock view", () => {
    expect(stockExcelDefaultFileName("stock.offers", "es", "Oferta", "SELECTED", date))
      .toBe("productos-con-oferta-2026-07-07.xlsx");
    expect(stockExcelDefaultFileName("stock.current", "en", undefined, "ACTIVE", date))
      .toBe("stock-2026-07-07.xlsx");
  });
});
