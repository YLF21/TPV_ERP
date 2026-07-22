export type PaymentTranslator = (key: string) => string;

function translated(t: PaymentTranslator, key: string, fallback: string) {
  const value = t(key);
  return value === key ? fallback : value;
}

function normalize(value: string) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").trim().toLowerCase();
}

export function localizePaymentStatus(t: PaymentTranslator, status: string) {
  return translated(t, `paymentTerminal.status.${status}`, status);
}

export function localizePaymentEventCode(t: PaymentTranslator, code: string | null | undefined) {
  if (!code) return "";
  return translated(t, `payment.operation.eventCode.${code}`, code);
}

export function localizePaymentDiagnostic(
  t: PaymentTranslator,
  diagnostic: string | null | undefined,
  status?: string
) {
  if (!diagnostic) return "";
  const value = normalize(diagnostic);
  if (value.includes("recuperada por idempotencia") || value.includes("recovered by idempotency")) {
    return t("payment.operation.diagnostic.idempotencyRecovered");
  }
  if (value.includes("simulada") && value.includes("timeout")) {
    return t("payment.operation.diagnostic.simulatedTimeout");
  }
  if (value.includes("resultado incierto") || value.includes("uncertain result")) {
    return t("payment.operation.diagnostic.uncertainResult");
  }
  if (value.includes("sdk") && (value.includes("no instalado") || value.includes("not installed"))) {
    return t("payment.operation.diagnostic.sdkNotInstalled");
  }
  if (status) {
    const statusKey = `payment.operation.diagnostic.status.${status}`;
    const statusMessage = t(statusKey);
    if (statusMessage !== statusKey) return statusMessage;
  }
  return t("payment.operation.diagnostic.generic");
}
