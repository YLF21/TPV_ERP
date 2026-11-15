import type { LocaleCode } from "../types";

type PromotionScope = "SELECTED" | "ACTIVE";

const reportFileNames: Record<string, string> = {
  "stock.current": "Stock",
  "stock.topSales": "Top_ventas",
  "stock.offers": "Ofertas",
  "stock.memberPrice": "Precio_de_socio",
  "stock.noDiscount": "Sin_descuento",
};

const viewFileNames: Record<LocaleCode, Record<string, string>> = {
  es: {
    "stock.current": "stock",
    "stock.offers": "productos-con-oferta",
    "stock.memberPrice": "productos-precio-miembro",
    "stock.noDiscount": "productos-sin-descuento",
    "stock.topSales": "top-ventas",
  },
  en: {
    "stock.current": "stock",
    "stock.offers": "products-on-offer",
    "stock.memberPrice": "member-price-products",
    "stock.noDiscount": "non-discountable-products",
    "stock.topSales": "top-sales",
  },
  zh: {
    "stock.current": "库存",
    "stock.offers": "优惠商品",
    "stock.memberPrice": "会员价商品",
    "stock.noDiscount": "不可折扣商品",
    "stock.topSales": "热销商品",
  },
};

function safePromotionName(value: string): string {
  const cleaned = value.replace(/[<>:"/\\|?*\p{Cc}]+/gu, " ").replace(/\s+/gu, " ")
    .replace(/^[. ]+|[. ]+$/g, "");
  return Array.from(cleaned).slice(0, 100).join("").replace(/^[. ]+|[. ]+$/g, "") || "Promocion";
}

function promotionDatePart(value?: string | null): string | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const [year, month, day] = value.split("-").map(Number);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() + 1 !== month || parsed.getUTCDate() !== day) return null;
  return `${value.slice(8, 10)}${value.slice(5, 7)}${value.slice(2, 4)}`;
}

function localDate(date: Date): string {
  const year = String(date.getFullYear()).padStart(4, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function stockExcelDefaultFileName(
  view: string,
  locale: LocaleCode,
  promotionName?: string,
  promotionScope?: PromotionScope,
  date: Date = new Date(),
  promotionStartDate?: string | null,
  promotionEndDate?: string | null,
): string {
  const datePart = localDate(date);
  if (reportFileNames[view]) {
    return `${reportFileNames[view]}_${datePart.slice(8, 10)}${datePart.slice(5, 7)}${datePart.slice(2, 4)}.xlsx`;
  }
  if (view === "stock.promotions") {
    if (promotionScope === "SELECTED") {
      const name = safePromotionName(promotionName ?? "");
      const start = promotionDatePart(promotionStartDate);
      if (!start) return `${name}.xlsx`;
      const end = promotionEndDate == null ? "Sin_fin" : promotionDatePart(promotionEndDate);
      return `${name}_${start}${end ? `-${end}` : ""}.xlsx`;
    }
    return `Promociones_${datePart.slice(8, 10)}${datePart.slice(5, 7)}${datePart.slice(2, 4)}.xlsx`;
  }
  const baseName = viewFileNames[locale][view] ?? viewFileNames[locale]["stock.current"];
  return `${baseName}-${datePart}.xlsx`;
}
