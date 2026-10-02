import type { CashCloseRecoveryFlow } from "./cashCloseRecovery";
import { createCashCloseWithdrawalIdempotencyKey } from "./cashSessions";

export type CashCloseUiPhase = CashCloseRecoveryFlow["phase"];
export type CashCloseUiFlow = CashCloseRecoveryFlow;

export function createCashCloseUiFlow(): CashCloseUiFlow {
  return {
    closeOperationId: createCashCloseWithdrawalIdempotencyKey(),
    reconciliationAttemptId: createCashCloseWithdrawalIdempotencyKey(),
    phase: "READY",
    retainedFund: "0",
    finalWithdrawal: "0",
    comment: "",
  };
}
