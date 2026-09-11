import type { LocaleCode } from "./types";

// Read the decimal value sent in JSON, avoiding binary floating-point products.
function decimalFraction(value: number): [bigint, bigint] {
  const [mantissa, exponent = "0"] = String(value).split("e");
  const decimals = mantissa.split(".")[1]?.length ?? 0;
  const scale = decimals - Number(exponent);
  const integer = BigInt(mantissa.replace(".", ""));
  return scale >= 0 ? [integer, 10n ** BigInt(scale)] : [integer * 10n ** BigInt(-scale), 1n];
}

function divideHalfUp(numerator: bigint, denominator: bigint): bigint {
  const absolute = numerator < 0n ? -numerator : numerator;
  const rounded = absolute / denominator + (absolute % denominator * 2n >= denominator ? 1n : 0n);
  return numerator < 0n ? -rounded : rounded;
}

/** Multiply before rounding to cents, with the backend's decimal HALF_UP policy. */
export function roundMoneyProduct(left: number, right: number): number {
  if (!Number.isFinite(left) || !Number.isFinite(right)) return 0;
  const [leftInteger, leftScale] = decimalFraction(left);
  const [rightInteger, rightScale] = decimalFraction(right);
  const result = Number(divideHalfUp(leftInteger * rightInteger * 100n, leftScale * rightScale)) / 100;
  return Number.isFinite(result) ? result : 0;
}

/** Apply a percentage to a cent-rounded subtotal, matching warehouse Money.euros. */
export function applyMoneyDiscount(subtotal: number, discountPercent: number): number {
  if (!Number.isFinite(subtotal) || !Number.isFinite(discountPercent)) return 0;
  const [amountInteger, amountScale] = decimalFraction(subtotal);
  const [discountInteger, discountScale] = decimalFraction(Math.min(100, Math.max(0, discountPercent)));
  const cents = divideHalfUp(amountInteger * 100n, amountScale);
  // The persisted discount has scale 2; normalize it before applying it.
  const discountBasisPoints = divideHalfUp(discountInteger * 100n, discountScale);
  return Number(divideHalfUp(cents * (10000n - discountBasisPoints), 10000n)) / 100;
}

/** Round derived unit prices before storing them, not monetary line totals. */
export function roundUnitPrice(value: number): number {
  return Math.sign(value) * Math.round((Math.abs(value) + Number.EPSILON) * 1000) / 1000;
}

export function localeTag(locale: LocaleCode) {
  if (locale === "zh") return "zh-CN";
  if (locale === "en") return "en-GB";
  return "es-ES";
}

export function parseMoneyValue(value: unknown, decimalPlaces: 2 | 3 = 2): number | null {
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
  if (decimalPlaces === 3 && (comma >= 0) !== (dot >= 0)) {
    const separator = comma >= 0 ? "," : ".";
    if (normalized.indexOf(separator) !== normalized.lastIndexOf(separator)) {
      const grouping = separator === "," ? /^[+-]?\d{1,3}(,\d{3})+$/ : /^[+-]?\d{1,3}(\.\d{3})+$/;
      if (!grouping.test(normalized)) return null;
      const grouped = Number(normalized.replaceAll(separator, ""));
      return negativeByParentheses ? -Math.abs(grouped) : grouped;
    }
    const decimal = Number(normalized.replace(",", "."));
    if (!Number.isFinite(decimal)) return null;
    return negativeByParentheses ? -Math.abs(decimal) : decimal;
  }
  if (comma >= 0 && dot >= 0) {
    const decimalSeparator = comma > dot ? "," : ".";
    const groupingSeparator = decimalSeparator === "," ? /\./g : /,/g;
    normalized = normalized.replace(groupingSeparator, "");
    if (decimalSeparator === ",") normalized = normalized.replace(",", ".");
  } else if (comma >= 0) {
    const decimals = normalized.length - comma - 1;
    normalized = decimals >= 1 && decimals <= decimalPlaces
      ? normalized.replace(/\./g, "").replace(",", ".")
      : normalized.replace(/,/g, "");
  } else if (dot >= 0) {
    const decimals = normalized.length - dot - 1;
    if (decimals < 1 || decimals > decimalPlaces) normalized = normalized.replace(/\./g, "");
  }
  const amount = Number(normalized);
  if (!Number.isFinite(amount)) {
    return null;
  }
  return negativeByParentheses ? -Math.abs(amount) : amount;
}

export function formatEuroAmount(value: unknown, locale: LocaleCode, decimalPlaces: 2 | 3 = 2) {
  const amount = parseMoneyValue(value, decimalPlaces);
  if (amount == null) {
    return typeof value === "string" ? value : "";
  }
  return new Intl.NumberFormat(localeTag(locale), {
    style: "currency",
    currency: "EUR",
    useGrouping: true,
    minimumFractionDigits: 2,
    maximumFractionDigits: decimalPlaces
  }).format(amount);
}

/** Prices keep the third decimal; monetary totals continue using formatEuroAmount. */
export function formatEuroUnitPrice(value: unknown, locale: LocaleCode) {
  return formatEuroAmount(value, locale, 3);
}
