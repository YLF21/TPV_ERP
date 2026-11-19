import { SPANISH_PROVINCES } from "../../shared/provinces/spanish-provinces.mjs";
import type { StatisticsFilters, StatisticsGrouping, StatisticsMode, StatisticsPeriod } from "./statistics-types";

export const statisticsPath = "/api/v1/admin/reports/statistics";
export const reportPageSize = 25;
export const comparisons = ["NONE", "PREVIOUS_PERIOD", "PREVIOUS_YEAR", "CUSTOM"] as const;
export const groupings = ["DAY", "WEEK", "MONTH", "COMPANY", "STORE", "PROVINCE"] as const;
export const provinces = new Map(SPANISH_PROVINCES.map(({ code, name }) => [code, name]));
export const missing = "—";
const dayMs = 86_400_000;

export function initialFilters(mode?: StatisticsMode): StatisticsFilters {
  const now = new Date();
  return {
    from: new Date(now.getTime() - 29 * dayMs).toISOString().slice(0, 10),
    to: now.toISOString().slice(0, 10),
    comparison: "PREVIOUS_PERIOD",
    comparisonFrom: "",
    comparisonTo: "",
    companyId: "",
    storeId: "",
    province: "",
    groupBy: mode === "provinces" ? "PROVINCE" : "DAY",
  };
}

export function isTimeGrouping(grouping: StatisticsGrouping) {
  return grouping === "DAY" || grouping === "WEEK" || grouping === "MONTH";
}

function validRange(from: string, to: string) {
  const start = Date.parse(from + "T00:00:00Z");
  const end = Date.parse(to + "T00:00:00Z");
  return Number.isFinite(start) && end >= start && end - start < 366 * dayMs;
}

export function validateFilters(filters: StatisticsFilters) {
  if (!validRange(filters.from, filters.to)) return "rangeInvalid";
  if (filters.comparison === "CUSTOM" && !validRange(filters.comparisonFrom, filters.comparisonTo)) return "comparisonRangeInvalid";
  return null;
}

export function restorePeriodFilters(saved: Record<string, string>, defaults: StatisticsFilters): StatisticsFilters | null {
  const restored = { ...defaults };
  for (const key of ["from", "to", "comparisonFrom", "comparisonTo", "companyId", "storeId"] as const) {
    if (typeof saved[key] === "string") restored[key] = saved[key];
  }
  const province = saved.province;
  if (provinces.has(province) || province === "UNKNOWN") restored.province = province;
  const comparison = comparisons.find((value) => value === saved.comparison);
  const groupBy = groupings.find((value) => value === saved.groupBy);
  if (comparison) restored.comparison = comparison;
  if (groupBy) restored.groupBy = groupBy;
  return validateFilters(restored) ? null : restored;
}

export function filtersQuery(filters: StatisticsFilters, includeGrouping = true) {
  const query = new URLSearchParams({ from: filters.from, to: filters.to, comparison: filters.comparison });
  if (includeGrouping) query.set("groupBy", filters.groupBy);
  if (filters.comparison === "CUSTOM") {
    query.set("comparisonFrom", filters.comparisonFrom);
    query.set("comparisonTo", filters.comparisonTo);
  }
  for (const key of ["companyId", "storeId", "province"] as const) {
    if (filters[key]) query.set(key, filters[key]);
  }
  return query;
}

export function reportQuery(query: URLSearchParams, page: number, size = reportPageSize) {
  const params = new URLSearchParams(query);
  params.set("page", String(page));
  params.set("size", String(size));
  return params.toString();
}

export function reportLinkFilters() {
  return Object.fromEntries(new URLSearchParams(window.location.hash.split("?")[1] ?? ""));
}

type DocumentLink = Pick<StatisticsPeriod, "from" | "to"> & Pick<StatisticsFilters, "companyId" | "storeId" | "province"> & {
  currency?: string;
  productId?: string;
  productCode?: string;
  productName?: string;
};

export function documentReportLink(filters: DocumentLink, back: string) {
  const params = new URLSearchParams();
  for (const key of ["from", "to", "companyId", "storeId", "province", "currency", "productId", "productCode", "productName"] as const) {
    const value = filters[key];
    if (value) params.set(key, value);
  }
  params.set("return", back);
  return "#/reports/documents?" + params;
}

export function formatDecimal(value: string | null | undefined, locale: string, digits: number, signed = false) {
  if (value == null) return missing;
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) return missing;
  const [, sign, integerDigits, decimal = ""] = match;
  const places = Math.max(digits, decimal.length);
  const integer = BigInt(integerDigits);
  const whole = sign === "-" ? (integer === 0n ? -0 : -integer) : integer;
  const positive = signed && sign !== "-" && (integer !== 0n || /[1-9]/.test(decimal));
  const number = new Intl.NumberFormat(locale, { minimumFractionDigits: digits, maximumFractionDigits: digits, signDisplay: positive ? "always" : "auto" });
  const decimalFormatter = new Intl.NumberFormat(locale, { minimumIntegerDigits: 1, useGrouping: false });
  const fraction = decimal.padEnd(places, "0").replace(/\d/g, (digit) => decimalFormatter.format(Number(digit)));
  const parts = number.formatToParts(whole);
  if (digits === 0 && places > 0) {
    const separator = new Intl.NumberFormat(locale).formatToParts(0.1).find((part) => part.type === "decimal")?.value ?? ".";
    return parts.map((part) => part.value).join("") + separator + fraction;
  }
  return parts.map((part) => part.type === "fraction" ? fraction : part.value).join("");
}

export function chartAmount(amount: string | null | undefined, locale: string) {
  const number = amount == null ? NaN : Number(amount);
  return {
    value: Number.isFinite(number) ? number : null,
    valueLabel: formatDecimal(amount, locale, 2),
  };
}

export function decimalChange(currentValue: string | null | undefined, previousValue: string | null | undefined) {
  if (currentValue == null || previousValue == null) return { amount: null, percent: null };
  const [currentInteger, currentFraction = ""] = currentValue.split(".");
  const [previousInteger, previousFraction = ""] = previousValue.split(".");
  const precision = Math.max(currentFraction.length, previousFraction.length);
  const current = BigInt(currentInteger + currentFraction.padEnd(precision, "0"));
  const previous = BigInt(previousInteger + previousFraction.padEnd(precision, "0"));
  const change = current - previous;
  const amount = fixed(change, precision);
  if (previous === 0n) return { amount, percent: null };
  const base = previous < 0n ? -previous : previous;
  const magnitude = change < 0n ? -change : change;
  const percentage = (magnitude * 10_000n + base / 2n) / base;
  return { amount, percent: fixed(change < 0n ? -percentage : percentage, 2) };
}

function fixed(value: bigint, digits: number) {
  if (!digits) return value.toString();
  const magnitude = (value < 0n ? -value : value).toString().padStart(digits + 1, "0");
  return `${value < 0n ? "-" : ""}${magnitude.slice(0, -digits)}.${magnitude.slice(-digits)}`;
}
