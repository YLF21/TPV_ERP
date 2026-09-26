import type { LocaleCode } from "../types";

type PromotionScope = "SELECTED" | "ACTIVE";

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

const promotionWords: Record<LocaleCode, { selected: string; active: string; fallback: string }> = {
  es: { selected: "productos-promocion", active: "productos-promociones-activas", fallback: "seleccionada" },
  en: { selected: "promotion-products", active: "active-promotions-products", fallback: "selected" },
  zh: { selected: "促销商品", active: "有效促销商品", fallback: "已选促销" },
};

function promotionSlug(value: string, fallback: string): string {
  const slug = Array.from(value.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase())
    .join("")
    .replace(/[^\p{L}\p{N}]+/gu, "-")
    .replace(/^-+|-+$/g, "");
  const shortened = Array.from(slug).slice(0, 60).join("").replace(/-+$/g, "");
  return shortened || fallback;
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
): string {
  const datePart = localDate(date);
  if (view === "stock.promotions") {
    if (promotionScope === "SELECTED") {
      const slug = promotionSlug(promotionName ?? "", promotionWords[locale].fallback);
      return `${promotionWords[locale].selected}-${slug}-${datePart}.xlsx`;
    }
    return `${promotionWords[locale].active}-${datePart}.xlsx`;
  }
  const baseName = viewFileNames[locale][view] ?? viewFileNames[locale]["stock.current"];
  return `${baseName}-${datePart}.xlsx`;
}
