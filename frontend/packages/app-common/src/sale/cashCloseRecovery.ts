export type CashCloseRecoveryPhase = "READY" | "ATTEMPTED" | "RECONCILIATION_REQUIRED";

export type CashCloseRecoveryFlow = {
  closeOperationId: string;
  reconciliationAttemptId: string;
  phase: CashCloseRecoveryPhase;
  retainedFund: string;
  finalWithdrawal: string;
  comment: string;
  retainedFundDenominations?: { denomination: number; quantity: number }[];
  finalWithdrawalDenominations?: { denomination: number; quantity: number }[];
};

export type CashCloseRecoveryEnvelope = {
  version: 1 | 2;
  terminalCode: string;
  installationId?: string;
  terminalId?: string;
  bindingId?: string;
  flow: CashCloseRecoveryFlow;
  savedAt: string;
};

export type CashCloseRecoveryLoadResult =
  | { status: "empty" }
  | { status: "valid"; envelope: CashCloseRecoveryEnvelope }
  | { status: "blocked"; raw: string };

const PREFIX = "tpverp.cash-close.v1";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type CashCloseRecoveryIdentity = string | {
  terminalCode: string; installationId: string; terminalId: string; bindingId: string;
  legacyTerminalCode?: string;
};

export function cashCloseRecoveryKey(identity: CashCloseRecoveryIdentity) {
  if (typeof identity === "string") return `${PREFIX}.${encodeURIComponent(identity.trim())}`;
  if (![identity.installationId, identity.terminalId, identity.bindingId].every(value => UUID.test(value))) {
    throw new Error("invalid_cash_close_identity");
  }
  return `tpverp.cash-close.v2.${identity.installationId}.${identity.terminalId}.${identity.bindingId}`;
}

export function saveCashCloseRecovery(
  storage: Storage,
  identity: CashCloseRecoveryIdentity,
  flow: CashCloseRecoveryFlow,
) {
  const envelope: CashCloseRecoveryEnvelope = {
    version: typeof identity === "string" ? 1 : 2,
    terminalCode: typeof identity === "string" ? identity : identity.terminalCode,
    ...(typeof identity === "string" ? {} : {
      installationId: identity.installationId, terminalId: identity.terminalId, bindingId: identity.bindingId,
    }),
    flow,
    savedAt: new Date().toISOString(),
  };
  if (!validEnvelope(envelope)) throw new Error("invalid_cash_close_recovery");
  storage.setItem(cashCloseRecoveryKey(identity), JSON.stringify(envelope));
}

export function clearCashCloseRecovery(storage: Storage, identity: CashCloseRecoveryIdentity) {
  storage.removeItem(cashCloseRecoveryKey(identity));
}

export function loadCashCloseRecovery(
  storage: Storage,
  identity: CashCloseRecoveryIdentity,
): CashCloseRecoveryLoadResult {
  const key = cashCloseRecoveryKey(identity);
  const raw = storage.getItem(key);
  if (raw == null) {
    // Only a proven adoption of the same legacy credential supplies this marker.
    // New bindings must never pick up a close draft merely because they reuse 002.
    if (typeof identity !== "string" && identity.legacyTerminalCode && storage.getItem(`${key}.migrated`) !== "1") {
      const legacy = loadCashCloseRecovery(storage, identity.legacyTerminalCode);
      if (legacy.status === "blocked") return legacy;
      if (legacy.status === "valid") {
        saveCashCloseRecovery(storage, identity, legacy.envelope.flow);
        storage.setItem(`${key}.migrated`, "1");
        clearCashCloseRecovery(storage, identity.legacyTerminalCode);
        return loadCashCloseRecovery(storage, identity);
      }
    }
    return { status: "empty" };
  }
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { status: "blocked", raw };
  }
  if (!validEnvelope(value) || (typeof identity === "string"
    ? value.version !== 1 || value.terminalCode !== identity
    : value.version !== 2 || value.installationId !== identity.installationId
      || value.terminalId !== identity.terminalId || value.bindingId !== identity.bindingId)) {
    return { status: "blocked", raw };
  }
  return { status: "valid", envelope: value };
}

function validEnvelope(value: unknown): value is CashCloseRecoveryEnvelope {
  if (!isRecord(value) || (value.version !== 1 && value.version !== 2) || typeof value.terminalCode !== "string"
    || value.terminalCode.trim() === "" || typeof value.savedAt !== "string"
    || !Number.isFinite(Date.parse(value.savedAt)) || !isRecord(value.flow)) return false;
  if (value.version === 2 && ![value.installationId, value.terminalId, value.bindingId]
    .every(item => typeof item === "string" && UUID.test(item))) return false;
  const flow = value.flow;
  return typeof flow.closeOperationId === "string" && UUID.test(flow.closeOperationId)
    && typeof flow.reconciliationAttemptId === "string" && UUID.test(flow.reconciliationAttemptId)
    && (flow.phase === "READY" || flow.phase === "ATTEMPTED" || flow.phase === "RECONCILIATION_REQUIRED")
    && typeof flow.retainedFund === "string" && flow.retainedFund.length <= 32
    && typeof flow.finalWithdrawal === "string" && flow.finalWithdrawal.length <= 32
    && typeof flow.comment === "string" && flow.comment.length <= 500
    && validDenominations(flow.retainedFundDenominations)
    && validDenominations(flow.finalWithdrawalDenominations);
}

function validDenominations(value: unknown): boolean {
  return value === undefined || (Array.isArray(value) && value.length <= 32 && value.every(row =>
    isRecord(row) && typeof row.denomination === "number" && Number.isFinite(row.denomination)
    && row.denomination > 0 && typeof row.quantity === "number"
    && Number.isSafeInteger(row.quantity) && row.quantity >= 0));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
