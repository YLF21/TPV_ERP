import type { LocaleCode } from "@tpverp/app-common";

export type VerifactuTranslator = (key: string) => string;

export function verifactuStatusLabel(status: string, t: VerifactuTranslator) {
  return translatedEnum("status", status, t);
}

export function verifactuOperationLabel(operation: string, t: VerifactuTranslator) {
  return translatedEnum("operation", operation, t);
}

export function verifactuEndpointLabel(
  mode: string | null | undefined,
  t: VerifactuTranslator
) {
  if (!mode) return t("verifactu.management.unavailable");
  return translatedEnum("endpoint", mode, t);
}

export function formatVerifactuDateTime(
  value: string | null | undefined,
  locale: LocaleCode
) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" }).format(date);
}

export function humanizeVerifactuValue(value: string) {
  return value.toLowerCase().replaceAll("_", " ").replace(/^./, (character) => character.toUpperCase());
}

function translatedEnum(group: string, value: string, t: VerifactuTranslator) {
  const key = `verifactu.management.${group}.${value}`;
  const translated = t(key);
  return translated === key ? humanizeVerifactuValue(value) : translated;
}
