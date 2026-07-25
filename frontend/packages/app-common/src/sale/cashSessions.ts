import { apiRequest } from "../api/client";

export type CashSessionView = {
  id: string;
  terminalId: string;
  status: "ABIERTA" | "CERRADA";
  openedAt: string;
  openingFund: number;
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

export function openCashSession(
  terminalId: string,
  token: string,
  request: RequestFunction = apiRequest,
) {
  return request<CashSessionView>("/cash/sessions/open", {
    token,
    method: "POST",
    body: { terminalId },
  });
}

export function closeCashSession(
  terminalId: string,
  retainedFund: number,
  finalWithdrawalAmount: number,
  finalWithdrawalComment: string,
  token: string,
  request: RequestFunction = apiRequest,
) {
  return request<CashSessionView>("/cash/sessions/close", {
    token,
    method: "POST",
    body: {
      terminalId,
      retainedFund,
      retainedFundDenominations: [],
      finalWithdrawalAmount,
      finalWithdrawalComment: finalWithdrawalComment.trim() || null,
      finalWithdrawalDenominations: [],
    },
  });
}
