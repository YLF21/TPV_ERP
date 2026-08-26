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
  locale: LocaleCode,
  timeZone?: string | null
) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  const validatedTimeZone = validatedIanaTimeZone(timeZone);
  if (timeZone !== undefined && !validatedTimeZone) return "—";
  const options: Intl.DateTimeFormatOptions = {
    dateStyle: "short",
    timeStyle: "short"
  };
  if (validatedTimeZone) options.timeZone = validatedTimeZone;
  return new Intl.DateTimeFormat(intlLocale(locale), options).format(date);
}

/** Returns the supplied IANA zone only when the runtime accepts it. */
export function validatedIanaTimeZone(timeZone?: string | null) {
  const candidate = timeZone?.trim();
  if (!candidate) return undefined;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: candidate }).format();
    return candidate;
  } catch {
    return undefined;
  }
}

export function formatVerifactuDate(
  value: string | null | undefined,
  locale: LocaleCode
) {
  const match = value?.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!match) return "—";
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return new Intl.DateTimeFormat(intlLocale(locale), {
    dateStyle: "short",
    timeZone: "UTC"
  }).format(date);
}

export function humanizeVerifactuValue(value: string) {
  return value.toLowerCase().replaceAll("_", " ").replace(/^./, (character) => character.toUpperCase());
}

function translatedEnum(group: string, value: string, t: VerifactuTranslator) {
  const key = `verifactu.management.${group}.${value}`;
  const translated = t(key);
  return translated === key ? humanizeVerifactuValue(value) : translated;
}

function intlLocale(locale: LocaleCode) {
  return locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
}
