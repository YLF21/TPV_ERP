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

export function canInvoiceBePaid(fiscalDetail) {
  return fiscalDetail?.fiscalStatus === "CALCULATED"
    || fiscalDetail?.fiscalStatus === "NOT_APPLICABLE";
}

export function validateFiscalDecision(form) {
  if (form?.fiscalStatus === "CALCULATED") {
    const values = [form.taxBase, form.taxRate, form.taxAmount];
    if (!values.every((value) => typeof value === "string" && /^\d+(?:\.\d{1,2})?$/.test(value.trim()))) {
      return false;
    }
    return values.every((value) => Number(value) >= 0);
  }
  if (form?.fiscalStatus === "NOT_APPLICABLE") {
    return hasVerifiableFiscalEvidence(form.reason, 10)
      && hasVerifiableFiscalEvidence(form.legalBasis, 8)
      && hasVerifiableFiscalEvidence(form.evidenceReference, 8);
  }
  return false;
}

export function hasVerifiableFiscalEvidence(value, minimumLength) {
  const normalized = String(value ?? "").trim();
  if (normalized.length < minimumLength) return false;
  const folded = normalized.toLocaleLowerCase().normalize("NFD").replace(/\p{M}+/gu, "");
  const key = folded.replace(/[^a-z0-9]/g, "");
  const words = folded.split(/[^a-z0-9]+/).filter(Boolean);
  const placeholders = new Set(["na", "none", "noaplica", "notapplicable", "test", "dummy", "sindatos", "pendiente", "desconocido", "unknown", "placeholder"]);
  const repeatedWord = words.length > 0 && words.every((word) => word === words[0]);
  const repeatedPattern = /^(.{1,4})\1{2,}$/.test(key);
  return !placeholders.has(key) && new Set(key).size >= 4 && !repeatedWord && !repeatedPattern;
}

export async function settleWithConcurrency(items, worker, concurrency = 4) {
  const results = new Array(items.length);
  let nextIndex = 0;
  const count = Math.max(1, Math.min(concurrency, items.length || 1));
  await Promise.all(Array.from({ length: count }, async () => {
    while (nextIndex < items.length) {
      const index = nextIndex++;
      try {
        results[index] = { status: "fulfilled", value: await worker(items[index], index) };
      } catch (reason) {
        results[index] = { status: "rejected", reason };
      }
    }
  }));
  return results;
}

export function isCurrentSelection(requestedCompanyId, selectedCompanyId) {
  return Boolean(requestedCompanyId) && requestedCompanyId === selectedCompanyId;
}

export function isCurrentSessionRequest(requestId, latestRequestId, requestedAccessToken, currentAccessToken) {
  return requestId === latestRequestId
    && Boolean(requestedAccessToken)
    && requestedAccessToken === currentAccessToken;
}

export function isCurrentAuthRequest(requestId, latestRequestId) {
  return requestId === latestRequestId;
}

export function retainCompanyOperationsAfterFailure(current, companyId) {
  return current?.companyId === companyId ? current : null;
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
