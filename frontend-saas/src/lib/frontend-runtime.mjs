export function parseDecimal(value) {
  if (typeof value === "number") return Number.isFinite(value) ? value : 0;
  if (typeof value !== "string" || !value.trim()) return 0;
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

export function formatCurrency(value, currency = "EUR", locale = "es-ES") {
  const normalizedCurrency = String(currency || "EUR").toUpperCase();
  try {
    return new Intl.NumberFormat(locale, { style: "currency", currency: normalizedCurrency }).format(parseDecimal(value));
  } catch {
    return new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(parseDecimal(value));
  }
}

export function formatQuantity(value, locale = "es-ES") {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }).format(parseDecimal(value));
}

export function outstandingAmount(invoice) {
  return Math.max(0, parseDecimal(invoice?.amount) - parseDecimal(invoice?.paidAmount));
}

export function isCurrentSelection(requestedCompanyId, selectedCompanyId) {
  return Boolean(requestedCompanyId) && requestedCompanyId === selectedCompanyId;
}

export function isCurrentSessionRequest(requestId, latestRequestId, requestedAccessToken, currentAccessToken) {
  return requestId === latestRequestId
    && Boolean(requestedAccessToken)
    && requestedAccessToken === currentAccessToken;
}

export function shouldInvalidateSession(failedAccessToken, currentAccessToken, pendingAccessToken) {
  return Boolean(failedAccessToken)
    && (failedAccessToken === currentAccessToken || failedAccessToken === pendingAccessToken);
}
export function paginateRows(rows, page, pageSize = 20) {
  const pages = Math.max(1, Math.ceil(rows.length / pageSize));
  const safePage = Math.min(Math.max(1, page), pages);
  return { rows: rows.slice((safePage - 1) * pageSize, safePage * pageSize), page: safePage, pages, total: rows.length, pageSize };
}