import type { LocaleCode } from "@tpverp/app-common";
import type { VerifactuTranslator } from "./verifactuPresentation";

/** Stable fiscal codes are kept visible for support, but always paired with an actionable explanation. */
const knownCodes = new Set([
  "NETWORK_ERROR", "INVALID_XSD", "INVALID_AEAT_RESPONSE", "AEAT_ERROR",
  "LEGACY_IDENTITY_UNRESOLVED", "CERTIFICATE_EXPIRED", "CERTIFICATE_NOT_YET_VALID",
  "CERTIFICATE_NOT_CONFIGURED", "CLOCK_STATUS_UNAVAILABLE", "TRANSITION_APPLICATION_FAILED",
  "VERIFACTU_CERTIFICATE_REQUIRED", "VERIFACTU_CERTIFICATE_TOO_LARGE"
]);

export function fiscalErrorMessage(code: string | null | undefined, t: VerifactuTranslator, locale?: LocaleCode) {
  const normalized = code?.trim().toUpperCase();
  if (!normalized) return null;
  const key = `verifactu.error.${knownCodes.has(normalized) ? normalized : "UNKNOWN"}`;
  const translated = t(key);
  const action = translated === key ? fallback(normalized, locale) : translated;
  return `${action} (${normalized})`;
}

function fallback(code: string, locale: LocaleCode = "es") {
  if (locale === "en") return code === "NETWORK_ERROR" ? "Check the network connection and retry" : "Review the fiscal details and retry if allowed";
  if (locale === "zh") return code === "NETWORK_ERROR" ? "请检查网络连接后重试" : "请检查税务信息；如允许，请重试";
  return code === "NETWORK_ERROR" ? "Compruebe la conexión y reintente" : "Revise los datos fiscales y reintente si está permitido";
}
