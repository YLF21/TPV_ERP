import type { LocaleCode } from "../types";

export type ProductQuantityType = "UNIT" | "WEIGHT" | "SERVICE";

export function normalizedProductQuantityType(value?: string | null): ProductQuantityType {
  return value === "WEIGHT" || value === "SERVICE" ? value : "UNIT";
}

export function parseProductQuantityInput(value: string) {
  const normalized = value.trim().replace(",", ".");
  if (!/^-?\d+(?:\.\d{1,3})?$/.test(normalized)) return Number.NaN;
  return Number(normalized);
}

export function isProductQuantityPrecisionValid(
  quantity: number,
  productType?: string | null,
) {
  if (!Number.isFinite(quantity)) return false;
  if (normalizedProductQuantityType(productType) === "UNIT") {
    return Number.isInteger(quantity);
  }
  return Math.abs(quantity * 1_000 - Math.round(quantity * 1_000)) < 1e-7;
}

export function productQuantityStep(productType?: string | null) {
  return normalizedProductQuantityType(productType) === "UNIT" ? 1 : 0.001;
}

export function normalizeProductQuantity(quantity: number) {
  return Math.round(quantity * 1_000) / 1_000;
}

export function canonicalProductQuantity(value: number | string) {
  const quantity = Number(value);
  return Number.isFinite(quantity) ? String(quantity) : "";
}

export function formatProductQuantity(
  value: number | string | null | undefined,
  productType?: string | null,
  locale: LocaleCode = "es",
) {
  return formatQuantityValue(
    value,
    locale,
    normalizedProductQuantityType(productType) === "UNIT" ? 0 : 3,
  );
}

export function formatQuantityValue(
  value: number | string | null | undefined,
  locale: LocaleCode = "es",
  maximumFractionDigits = 3,
) {
  const quantity = Number(value ?? 0);
  if (!Number.isFinite(quantity)) return "0";
  return quantity.toLocaleString(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    {
      minimumFractionDigits: 0,
      maximumFractionDigits,
    },
  );
}
