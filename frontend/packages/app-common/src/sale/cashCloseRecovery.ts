export type CashCloseRecoveryPhase = "READY" | "ATTEMPTED" | "RECONCILIATION_REQUIRED";

export type CashCloseRecoveryFlow = {
  closeOperationId: string;
  reconciliationAttemptId: string;
  phase: CashCloseRecoveryPhase;
  retainedFund: string;
  finalWithdrawal: string;
  comment: string;
};

export type CashCloseRecoveryEnvelope = {
  version: 1;
  terminalCode: string;
  flow: CashCloseRecoveryFlow;
  savedAt: string;
};

export type CashCloseRecoveryLoadResult =
  | { status: "empty" }
  | { status: "valid"; envelope: CashCloseRecoveryEnvelope }
  | { status: "blocked"; raw: string };

const PREFIX = "tpverp.cash-close.v1";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function cashCloseRecoveryKey(terminalCode: string) {
  return `${PREFIX}.${encodeURIComponent(terminalCode.trim())}`;
}

export function saveCashCloseRecovery(
  storage: Storage,
  terminalCode: string,
  flow: CashCloseRecoveryFlow,
) {
  const envelope: CashCloseRecoveryEnvelope = {
    version: 1,
    terminalCode,
    flow,
    savedAt: new Date().toISOString(),
  };
  if (!validEnvelope(envelope)) throw new Error("invalid_cash_close_recovery");
  storage.setItem(cashCloseRecoveryKey(terminalCode), JSON.stringify(envelope));
}

export function clearCashCloseRecovery(storage: Storage, terminalCode: string) {
  storage.removeItem(cashCloseRecoveryKey(terminalCode));
}

export function loadCashCloseRecovery(
  storage: Storage,
  terminalCode: string,
): CashCloseRecoveryLoadResult {
  const raw = storage.getItem(cashCloseRecoveryKey(terminalCode));
  if (raw == null) return { status: "empty" };
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { status: "blocked", raw };
  }
  if (!validEnvelope(value) || value.terminalCode !== terminalCode) {
    return { status: "blocked", raw };
  }
  return { status: "valid", envelope: value };
}

function validEnvelope(value: unknown): value is CashCloseRecoveryEnvelope {
  if (!isRecord(value) || value.version !== 1 || typeof value.terminalCode !== "string"
    || value.terminalCode.trim() === "" || typeof value.savedAt !== "string"
    || !Number.isFinite(Date.parse(value.savedAt)) || !isRecord(value.flow)) return false;
  const flow = value.flow;
  return typeof flow.closeOperationId === "string" && UUID.test(flow.closeOperationId)
    && typeof flow.reconciliationAttemptId === "string" && UUID.test(flow.reconciliationAttemptId)
    && (flow.phase === "READY" || flow.phase === "ATTEMPTED" || flow.phase === "RECONCILIATION_REQUIRED")
    && typeof flow.retainedFund === "string" && flow.retainedFund.length <= 32
    && typeof flow.finalWithdrawal === "string" && flow.finalWithdrawal.length <= 32
    && typeof flow.comment === "string" && flow.comment.length <= 500;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
