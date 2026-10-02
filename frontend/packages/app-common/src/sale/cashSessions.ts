import { apiRequest } from "../api/client";
import type { SaleOperationCredentials } from "./operationSecurity";

export type CashSessionView = {
  id: string;
  terminalId: string;
  status: "ABIERTA" | "CERRADA";
  openedAt: string;
  openingFund: number;
  expectedOpeningFund?: number | null;
  openingDiscrepancy?: number | null;
  expectedCash?: number | null;
  availableCash?: number | null;
  retainedFund?: number | null;
  discrepancy?: number | null;
  closedAt?: string | null;
  reconciliationAttempt?: number | null;
  closedByAttempt: boolean;
};

export type CashSalesSessionReadiness = {
  cashSessionRequired: boolean;
  open: boolean;
  session: CashSessionView | null;
  requireEntryBreakdown?: boolean;
  entryDenominations?: number[];
  requireWithdrawalBreakdown: boolean;
  withdrawalDenominations: number[];
  requireClosingBreakdown?: boolean;
  closingDenominations?: number[];
};

export type CashDenominationCount = { denomination: number; quantity: number };

export type CashTimelineEntry = {
  id: string;
  occurredAt: string;
  userId: string;
  username: string;
  userName: string;
  action: string;
  concept: string | null;
  amount: number | null;
  balance: number | null;
  reference: string | null;
  sourceReference?: string | null;
  sessionId: string | null;
  cashState: "ABIERTA" | "CERRADA" | null;
};

export type CashTimeline = { businessDate: string; timezone: string; items: CashTimelineEntry[] };

export function loadCashTimeline(terminalId: string, token: string, request: RequestFunction = apiRequest) {
  return request<CashTimeline>(`/cash/timeline?${new URLSearchParams({ terminalId })}`, { token });
}

export type CashCloseOperationView = {
  operationId: string;
  sessionId: string;
  terminalId: string;
  status: "INICIADA" | "REQUIERE_ARQUEO" | "CERRADA";
  finalWithdrawalAmount: number;
  finalWithdrawalComment?: string | null;
  latestReconciliationAttemptId?: string | null;
  result?: CashSessionView | null;
};

type RequestFunction = typeof apiRequest;

export function prepareCashSessionForSales(
  terminalId: string,
  token: string,
  request: RequestFunction = apiRequest,
) {
  return request<CashSalesSessionReadiness>("/cash/sessions/prepare-sales", {
    token,
    method: "POST",
    body: { terminalId },
  });
}

export function loadCashSessionReadiness(
  terminalId: string,
  token: string,
  request: RequestFunction = apiRequest,
) {
  const query = new URLSearchParams({ terminalId });
  return request<CashSalesSessionReadiness>(`/cash/sessions/readiness?${query}`, {
    token,
  });
}

export function openCashSession(
  terminalId: string,
  token: string,
  request: RequestFunction = apiRequest,
  countedFund?: number,
  denominations: CashDenominationCount[] = [],
) {
  return request<CashSessionView>("/cash/sessions/open", {
    token,
    method: "POST",
    body: { terminalId, ...(countedFund == null ? {} : { countedFund, denominations }) },
  });
}

export function createCashCloseWithdrawalIdempotencyKey(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  if (typeof globalThis.crypto?.getRandomValues !== "function") {
    throw new Error("Secure random UUID generation is unavailable");
  }
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

export function closeCashSession(
  terminalId: string,
  retainedFund: number,
  finalWithdrawalAmount: number,
  finalWithdrawalComment: string,
  token: string,
  request: RequestFunction = apiRequest,
  authorization: SaleOperationCredentials = {},
  closeOperationId: string = createCashCloseWithdrawalIdempotencyKey(),
  reconciliationAttemptId: string = createCashCloseWithdrawalIdempotencyKey(),
  breakdowns: {
    retainedFundDenominations?: CashDenominationCount[];
    finalWithdrawalDenominations?: CashDenominationCount[];
  } = {},
) {
  return request<CashSessionView>("/cash/sessions/close", {
    token,
    method: "POST",
    body: {
      terminalId,
      retainedFund,
      retainedFundDenominations: breakdowns.retainedFundDenominations ?? [],
      finalWithdrawalAmount,
      finalWithdrawalComment: finalWithdrawalComment.trim() || null,
      finalWithdrawalDenominations: breakdowns.finalWithdrawalDenominations ?? [],
      closeOperationId,
      reconciliationAttemptId,
      ...authorization,
    },
  });
}

export function recoverCashCloseOperation(
  terminalId: string,
  operationId: string,
  token: string,
  request: RequestFunction = apiRequest,
) {
  const query = new URLSearchParams({ terminalId });
  return request<CashCloseOperationView>(
    `/cash/sessions/close-operations/${encodeURIComponent(operationId)}?${query}`,
    { token },
  );
}
