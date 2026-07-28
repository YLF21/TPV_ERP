import type { LocaleCode } from "./types";

export function localeTag(locale: LocaleCode) {
  if (locale === "zh") return "zh-CN";
  if (locale === "en") return "en-GB";
  return "es-ES";
}

export function parseMoneyValue(value: unknown): number | null {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  if (typeof value !== "string") {
    return null;
  }
  let normalized = value
    .trim()
    .replace(/[€\s\u00a0\u202f']/g, "");
  if (!normalized) {
    return null;
  }
  const negativeByParentheses = normalized.startsWith("(") && normalized.endsWith(")");
  normalized = normalized.replace(/[()]/g, "");
  const comma = normalized.lastIndexOf(",");
  const dot = normalized.lastIndexOf(".");
  if (comma >= 0 && dot >= 0) {
    const decimalSeparator = comma > dot ? "," : ".";
    const groupingSeparator = decimalSeparator === "," ? /\./g : /,/g;
    normalized = normalized.replace(groupingSeparator, "");
    if (decimalSeparator === ",") normalized = normalized.replace(",", ".");
  } else if (comma >= 0) {
    const decimals = normalized.length - comma - 1;
    normalized = decimals >= 1 && decimals <= 2
      ? normalized.replace(/\./g, "").replace(",", ".")
      : normalized.replace(/,/g, "");
  } else if (dot >= 0) {
    const decimals = normalized.length - dot - 1;
    if (decimals < 1 || decimals > 2) normalized = normalized.replace(/\./g, "");
  }
  const amount = Number(normalized);
  if (!Number.isFinite(amount)) {
    return null;
  }
  return negativeByParentheses ? -Math.abs(amount) : amount;
}

export function formatEuroAmount(value: unknown, locale: LocaleCode) {
  const amount = parseMoneyValue(value);
  if (amount == null) {
    return typeof value === "string" ? value : "";
  }
  return new Intl.NumberFormat(localeTag(locale), {
    style: "currency",
    currency: "EUR",
    useGrouping: true,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(amount);
}
