import type { PendingPaymentAllocation, PendingSaleDraft } from "./customerReceivables";

export type PendingSaleRecoveryPhase = "CARD_IN_FLIGHT" | "CARD_FINAL_FAILURE" | "READY_TO_CREATE";

export type PendingSaleRecoveryEnvelope = {
  version: 2;
  phase: PendingSaleRecoveryPhase;
  terminalCode: string;
  customer: { id: string; name: string };
  draft: PendingSaleDraft;
  quoteCents: number;
  quoteReady: true;
  payments: PendingPaymentAllocation[];
  createAttempted?: boolean;
  savedAt: string;
};

export type PendingSaleRecoveryLoadResult =
  | { status: "empty" }
  | { status: "valid"; envelope: PendingSaleRecoveryEnvelope }
  | { status: "blocked"; reason: "CORRUPT" | "UNSUPPORTED_VERSION" | "TERMINAL_MISMATCH" | "IDENTITY_MISMATCH"; raw: string; identifiers: string[] };

// Keep the original storage address so version 1 is detected and blocked rather than silently ignored.
const RECOVERY_PREFIX = "tpverp.pending-sale.v1";
const UNCERTAIN_CARD = new Set(["PENDING", "SENT", "TIMEOUT"]);
const FINAL_CARD_FAILURE = new Set(["DECLINED", "ERROR", "CANCELLED"]);
const ALL_STATUSES = new Set(["APPROVED", ...UNCERTAIN_CARD, ...FINAL_CARD_FAILURE]);
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const MONEY = /^\d+(?:\.\d{1,2})?$/;

export function pendingSaleRecoveryKey(terminalCode: string) {
  return `${RECOVERY_PREFIX}.${encodeURIComponent(terminalCode.trim())}`;
}

export function pendingSaleRecoveryPhase(payments: PendingPaymentAllocation[]): PendingSaleRecoveryPhase {
  const card = payments.find((payment) => payment.kind === "INTEGRATED_CARD");
  if (!card || card.status === "APPROVED") return "READY_TO_CREATE";
  return UNCERTAIN_CARD.has(card.status) ? "CARD_IN_FLIGHT" : "CARD_FINAL_FAILURE";
}

export function pendingSaleRecoveryRequiresAttention(envelope: PendingSaleRecoveryEnvelope) {
  return envelope.createAttempted === true
    || envelope.payments.some((payment) => payment.kind === "INTEGRATED_CARD");
}

export function savePendingSaleRecovery(storage: Storage, envelope: PendingSaleRecoveryEnvelope) {
  if (!validEnvelopeShape(envelope) || !identityMatches(envelope)) throw new Error("invalid_pending_sale_recovery");
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
  if (!isRecord(value) || value.version !== 2) return { status: "blocked", reason: "UNSUPPORTED_VERSION", raw, identifiers };
  if (!validEnvelopeShape(value)) return { status: "blocked", reason: "CORRUPT", raw, identifiers };
  if (value.terminalCode !== terminalCode) return { status: "blocked", reason: "TERMINAL_MISMATCH", raw, identifiers };
  if (!identityMatches(value)) return { status: "blocked", reason: "IDENTITY_MISMATCH", raw, identifiers };
  return { status: "valid", envelope: value };
}

export function extractRecoveryIdentifiers(raw: string) {
  const identifiers: string[] = [];
  const pattern = /"(?:checkoutId|requestId|operationId|id)"\s*:\s*"([^"]+)"/g;
  for (const match of raw.matchAll(pattern)) if (!identifiers.includes(match[1])) identifiers.push(match[1]);
  return identifiers;
}

function identityMatches(envelope: PendingSaleRecoveryEnvelope) {
  if (envelope.customer.id !== envelope.draft.customerId) return false;
  return envelope.payments.every((payment) => payment.kind !== "INTEGRATED_CARD" || (
    payment.id === envelope.draft.checkoutId && payment.operationId === envelope.draft.checkoutId
  ));
}

function validEnvelopeShape(value: unknown): value is PendingSaleRecoveryEnvelope {
  if (!isRecord(value) || value.version !== 2 || !isPhase(value.phase) || !nonBlank(value.terminalCode)) return false;
  if (!isRecord(value.customer) || !nonBlank(value.customer.id) || !nonBlank(value.customer.name)) return false;
  if (!validDraft(value.draft)) return false;
  if (!safePositiveInteger(value.quoteCents) || value.quoteReady !== true) return false;
  if (typeof value.savedAt !== "string" || !Number.isFinite(Date.parse(value.savedAt))) return false;
  if (value.createAttempted !== undefined && typeof value.createAttempted !== "boolean") return false;
  if (!Array.isArray(value.payments) || !value.payments.every(validAllocation)) return false;
  if (new Set(value.payments.map((payment) => payment.id)).size !== value.payments.length) return false;
  let allocatedCents = 0;
  for (const payment of value.payments) {
    allocatedCents += payment.amountCents;
    if (!Number.isSafeInteger(allocatedCents) || allocatedCents > value.quoteCents) return false;
  }
  return validPhasePayments(value.phase, value.payments);
}

function validDraft(value: unknown): value is PendingSaleDraft {
  if (!isRecord(value) || !nonBlank(value.checkoutId) || !nonBlank(value.warehouseId) || !nonBlank(value.customerId)) return false;
  if (!validDate(value.date) || !validDate(value.dueDate)) return false;
  if (!['ALBARAN_VENTA', 'FACTURA_VENTA'].includes(String(value.type)) || !percentage(value.globalDiscount)) return false;
  return Array.isArray(value.lines) && value.lines.length > 0 && value.lines.every((line) => {
    if (!isRecord(line) || !nonBlank(line.productId) || typeof line.code !== "string" || typeof line.name !== "string") return false;
    if (!safePositiveInteger(line.quantity)) return false;
    if (!money(line.price) || !percentage(line.discount) || typeof line.taxesIncluded !== "boolean") return false;
    if (!nonBlank(line.taxRegime) || !percentage(line.taxPercentage)) return false;
    return line.rate === undefined || line.rate === null || typeof line.rate === "string";
  });
}

function validAllocation(value: unknown): value is PendingPaymentAllocation {
  if (!isRecord(value) || !nonBlank(value.id) || !nonBlank(value.methodId)) return false;
  if (!safePositiveInteger(value.amountCents) || !ALL_STATUSES.has(String(value.status))) return false;
  if (value.kind === "CASH") return value.status === "APPROVED"
    && safePositiveInteger(value.deliveredCents) && value.deliveredCents >= value.amountCents
    && safeNonNegativeInteger(value.changeCents) && value.changeCents === value.deliveredCents - value.amountCents
    && value.reference === undefined && value.operationId === undefined;
  if (value.kind === "TRANSFER") return value.status === "APPROVED" && nonBlank(value.reference)
    && value.deliveredCents === undefined && value.changeCents === undefined && value.operationId === undefined;
  if (value.kind === "INTEGRATED_CARD") return value.mode === "INTEGRATED" && nonBlank(value.operationId)
    && value.deliveredCents === undefined && value.changeCents === undefined && value.reference === undefined;
  return false;
}

function validPhasePayments(phase: PendingSaleRecoveryPhase, payments: PendingPaymentAllocation[]) {
  const cards = payments.filter((payment) => payment.kind === "INTEGRATED_CARD");
  const standardsApproved = payments.filter((payment) => payment.kind !== "INTEGRATED_CARD").every((payment) => payment.status === "APPROVED");
  if (!standardsApproved || cards.length > 1) return false;
  if (phase === "READY_TO_CREATE") return cards.every((card) => card.status === "APPROVED");
  if (cards.length !== 1) return false;
  return phase === "CARD_IN_FLIGHT" ? UNCERTAIN_CARD.has(cards[0].status) : FINAL_CARD_FAILURE.has(cards[0].status);
}

function money(value: unknown) { return typeof value === "string" && MONEY.test(value) && Number.isFinite(Number(value)); }
function percentage(value: unknown) { return money(value) && Number(value) <= 100; }
function safePositiveInteger(value: unknown): value is number { return Number.isSafeInteger(value) && Number(value) > 0; }
function safeNonNegativeInteger(value: unknown): value is number { return Number.isSafeInteger(value) && Number(value) >= 0; }
function validDate(value: unknown) {
  if (typeof value !== "string" || !DATE.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}
function nonBlank(value: unknown): value is string { return typeof value === "string" && value.trim().length > 0; }
function isPhase(value: unknown): value is PendingSaleRecoveryPhase { return value === "CARD_IN_FLIGHT" || value === "CARD_FINAL_FAILURE" || value === "READY_TO_CREATE"; }
function isRecord(value: unknown): value is Record<string, any> { return typeof value === "object" && value !== null && !Array.isArray(value); }
