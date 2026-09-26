import type { LocaleCode } from "../types";
import type { PromotionView } from "./PromotionForm";

export type PromotionTranslator = (key: string) => string;

export function formatPromotionDate(value: string, locale: LocaleCode) {
  const parsed = new Date(`${value}T12:00:00`);
  return Number.isNaN(parsed.getTime()) ? value : new Intl.DateTimeFormat(locale, { day: "2-digit", month: "2-digit", year: "numeric" }).format(parsed);
}

export function formatPromotionDateRange(promotion: PromotionView, locale: LocaleCode, noEndLabel: string) {
  return `${formatPromotionDate(promotion.startDate, locale)} – ${promotion.endDate ? formatPromotionDate(promotion.endDate, locale) : noEndLabel}`;
}

export function promotionTypeLabel(promotion: PromotionView, t: PromotionTranslator, locale: LocaleCode) {
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 3 });
  if (promotion.type === "BUY_X_PAY_Y" && promotion.buyQuantity != null && promotion.payQuantity != null) {
    return `${number.format(Number(promotion.buyQuantity))}×${number.format(Number(promotion.payQuantity))}`;
  }
  return t(`promotion.type.${promotion.type}`);
}

export function promotionConditionSentences(promotion: PromotionView, t: PromotionTranslator, locale: LocaleCode) {
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 3 });
  const money = new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" });
  const rows: string[] = [];
  const sentence = (key: string, values: Record<string, string> = {}) =>
    Object.entries(values).reduce((text, [key, value]) => text.replaceAll(`{${key}}`, value), t(key));
  const add = (key: string, value: string | number | null | undefined, format: "number" | "money" = "number") => {
    if (value == null || value === "") return;
    const numeric = Number(value);
    rows.push(sentence(key, { value: Number.isFinite(numeric)
      ? format === "money" ? money.format(numeric) : number.format(numeric) : String(value) }));
  };
  if (promotion.type === "BUY_X_PAY_Y" && promotion.buyQuantity != null && promotion.payQuantity != null) {
    rows.push(sentence(promotion.buyXPayYMode === "SAME_PRODUCT" ? "promotion.rule.buyPaySame" : "promotion.rule.buyPayMixed", { buy: number.format(Number(promotion.buyQuantity)), pay: number.format(Number(promotion.payQuantity)) }));
    rows.push(t("promotion.rule.cheapestFree"));
  } else if (promotion.type === "SECOND_UNIT_PERCENT") {
    add("promotion.rule.secondUnit", promotion.discountPercent);
  } else if (promotion.type === "FIXED_PACK_PRICE") {
    rows.push(sentence("promotion.rule.pack", {
      quantity: number.format(Number(promotion.buyQuantity ?? 0)), price: money.format(Number(promotion.packPrice ?? 0))
    }));
  } else {
    add("promotion.rule.minimumAmount", promotion.minimumAmount, "money");
    add("promotion.rule.minimumQuantity", promotion.minimumQuantity);
    add("promotion.rule.discountAmount", promotion.discountAmount, "money");
    add("promotion.rule.discountPercent", promotion.discountPercent);
  }
  add("promotion.rule.maximumDiscount", promotion.maximumDiscount, "money");
  add("promotion.rule.couponAmount", promotion.couponAmount, "money");
  add("promotion.rule.couponPercent", promotion.couponPercent);
  add("promotion.rule.couponMaximum", promotion.couponMaximumDiscount, "money");
  add("promotion.rule.couponMinimum", promotion.couponMinimumAmount, "money");
  if (promotion.couponValidFromDate) rows.push(sentence("promotion.rule.couponFrom", { value: formatPromotionDate(promotion.couponValidFromDate, locale) }));
  if (promotion.couponValidUntilDate) rows.push(sentence("promotion.rule.couponUntil", { value: formatPromotionDate(promotion.couponValidUntilDate, locale) }));
  add("promotion.rule.couponFromDays", promotion.couponValidFromDays);
  add("promotion.rule.couponDays", promotion.couponValidDays);
  rows.push(t(`promotion.rule.scope.${promotion.scope ?? "SALE"}`));
  rows.push(t(`promotion.rule.segment.${promotion.customerSegment ?? "ALL"}`));
  return rows;
}

export function promotionDateRange(promotion: PromotionView) {
  return promotion.endDate ? `${promotion.startDate} - ${promotion.endDate}` : promotion.startDate;
}
