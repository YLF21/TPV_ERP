import { apiRequest } from "@tpverp/app-common";

export type CashAlertStatus = "PENDING" | "REVIEWED";
export type CashAlertType = "OPENING" | "CLOSING";

export type CashAlert = {
  id: string;
  type: CashAlertType;
  sessionId: string;
  terminalId: string;
  terminalName: string;
  userId: string;
  username: string;
  userName: string;
  occurredAt: string;
  attemptNumber: number | null;
  sessionClosed: boolean | null;
  expectedFund: number | null;
  countedFund: number | null;
  difference: number | null;
  status: CashAlertStatus;
  reviewerId: string | null;
  reviewerUsername: string | null;
  reviewerName: string | null;
  reviewedAt: string | null;
  comment: string | null;
  version: number;
};

export type CashAlertFilters = {
  from: string;
  to: string;
  terminalId: string;
  userId: string;
  status: CashAlertStatus | "";
};

export type CashAlertPage = {
  items: CashAlert[];
  nextCursor: string | null;
  hasMore: boolean;
  pendingCount: number;
};

export type CashAlertAttempt = {
  id: string;
  attemptNumber: number;
  occurredAt: string;
  userId: string;
  username: string;
  userName: string;
  expectedFund: number | null;
  countedFund: number | null;
  difference: number | null;
  sessionClosed: boolean | null;
};

export type CashAlertDetail = {
  alert: CashAlert;
  attempts: CashAlertAttempt[];
};

export async function loadCashAlerts(filters: CashAlertFilters, cursor: string | null, token?: string, limit = 50) {
  const query = new URLSearchParams({ limit: String(limit) });
  if (filters.from) query.set("from", filters.from);
  if (filters.to) query.set("to", filters.to);
  if (filters.terminalId) query.set("terminalId", filters.terminalId);
  if (filters.userId) query.set("userId", filters.userId);
  if (filters.status) query.set("status", filters.status);
  if (cursor) query.set("cursor", cursor);
  return apiRequest<CashAlertPage>(`/cash/alerts?${query.toString()}`, { token });
}

export async function loadCashAlertDetail(id: string, type: CashAlertType, token?: string, signal?: AbortSignal) {
  const query = new URLSearchParams({ type });
  return apiRequest<CashAlertDetail>(`/cash/alerts/${encodeURIComponent(id)}?${query.toString()}`, { token, signal });
}

export async function reviewCashAlert(id: string, type: CashAlertType, comment: string, expectedVersion: number, token?: string) {
  return apiRequest<CashAlert>(`/cash/alerts/${encodeURIComponent(id)}/review`, {
    token,
    method: "POST",
    body: { type, comment, expectedVersion }
  });
}
