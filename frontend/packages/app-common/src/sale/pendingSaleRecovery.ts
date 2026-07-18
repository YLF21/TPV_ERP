import type { PendingPaymentAllocation, PendingSaleDraft } from "./customerReceivables";

export type PendingSaleRecoveryEnvelope = {
  version: 1;
  terminalCode: string;
  customer: { id: string; name: string };
  draft: PendingSaleDraft;
  quoteCents: number;
  quoteReady: true;
  payments: PendingPaymentAllocation[];
  savedAt: string;
};

export type PendingSaleRecoveryLoadResult =
  | { status: "empty" }
  | { status: "valid"; envelope: PendingSaleRecoveryEnvelope }
  | { status: "blocked"; reason: "CORRUPT" | "UNSUPPORTED_VERSION" | "TERMINAL_MISMATCH" | "IDENTITY_MISMATCH"; raw: string; identifiers: string[] };

const RECOVERY_PREFIX = "tpverp.pending-sale.v1";
const CARD_STATUSES = new Set(["APPROVED", "PENDING", "SENT", "TIMEOUT", "DECLINED", "ERROR", "CANCELLED"]);

export function pendingSaleRecoveryKey(terminalCode: string) {
  return `${RECOVERY_PREFIX}.${encodeURIComponent(terminalCode.trim())}`;
}

export function savePendingSaleRecovery(storage: Storage, envelope: PendingSaleRecoveryEnvelope) {
  if (!validEnvelopeShape(envelope) || !identityMatches(envelope)) {
    throw new Error("invalid_pending_sale_recovery");
  }
  storage.setItem(pendingSaleRecoveryKey(envelope.terminalCode), JSON.stringify(envelope));
}

export function clearPendingSaleRecovery(storage: Storage, terminalCode: string) {
  storage.removeItem(pendingSaleRecoveryKey(terminalCode));
}

export function loadPendingSaleRecovery(storage: Storage, terminalCode: string): PendingSaleRecoveryLoadResult {
  const raw = storage.getItem(pendingSaleRecoveryKey(terminalCode));
  if (raw == null) return { status: "empty" };
  const identifiers = extractRecoveryIdentifiers(raw);
  let value: unknown;
  try { value = JSON.parse(raw); }
  catch { return { status: "blocked", reason: "CORRUPT", raw, identifiers }; }
  if (!isRecord(value) || value.version !== 1) {
    return { status: "blocked", reason: "UNSUPPORTED_VERSION", raw, identifiers };
  }
  if (!validEnvelopeShape(value)) {
    return { status: "blocked", reason: "CORRUPT", raw, identifiers };
  }
  if (value.terminalCode !== terminalCode) {
    return { status: "blocked", reason: "TERMINAL_MISMATCH", raw, identifiers };
  }
  if (!identityMatches(value)) {
    return { status: "blocked", reason: "IDENTITY_MISMATCH", raw, identifiers };
  }
  return { status: "valid", envelope: value };
}

export function extractRecoveryIdentifiers(raw: string) {
  const identifiers: string[] = [];
  const pattern = /"(?:checkoutId|requestId|operationId)"\s*:\s*"([^"]+)"/g;
  for (const match of raw.matchAll(pattern)) {
    if (!identifiers.includes(match[1])) identifiers.push(match[1]);
  }
  return identifiers;
}

function identityMatches(envelope: PendingSaleRecoveryEnvelope) {
  if (envelope.customer.id !== envelope.draft.customerId) return false;
  return envelope.payments.every((payment) => payment.kind !== "INTEGRATED_CARD" || (
    payment.id === envelope.draft.checkoutId
    && payment.operationId === envelope.draft.checkoutId
  ));
}

function validEnvelopeShape(value: unknown): value is PendingSaleRecoveryEnvelope {
  if (!isRecord(value) || value.version !== 1 || typeof value.terminalCode !== "string" || !value.terminalCode.trim()) return false;
  if (!isRecord(value.customer) || typeof value.customer.id !== "string" || typeof value.customer.name !== "string") return false;
  if (!isRecord(value.draft) || typeof value.draft.checkoutId !== "string" || typeof value.draft.customerId !== "string"
      || typeof value.draft.warehouseId !== "string" || !Array.isArray(value.draft.lines)) return false;
  if (!Number.isInteger(value.quoteCents) || value.quoteCents < 0 || value.quoteReady !== true || typeof value.savedAt !== "string") return false;
  if (!Array.isArray(value.payments)) return false;
  return value.payments.every((payment) => isRecord(payment)
    && typeof payment.id === "string"
    && typeof payment.methodId === "string"
    && Number.isInteger(payment.amountCents)
    && payment.amountCents > 0
    && ["CASH", "TRANSFER", "INTEGRATED_CARD"].includes(String(payment.kind))
    && CARD_STATUSES.has(String(payment.status))
    && (payment.operationId === undefined || typeof payment.operationId === "string"));
}

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
