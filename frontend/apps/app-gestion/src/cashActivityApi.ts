import { apiRequest, type TableSort } from "@tpverp/app-common";

export type CashActivityRow = {
  id: string;
  occurredAt: string;
  userId: string | null;
  username: string | null;
  userName: string | null;
  action: string;
  concept: string | null;
  amount: number | null;
  balance: number | null;
  reference: string | null;
  sessionId: string | null;
  cashState: string | null;
  sourceReference: string | null;
  terminalId: string;
  terminalCode: string | null;
  terminalName: string;
};

export type CashActivityFilters = {
  from: string;
  to: string;
  terminalId: string;
  userId: string;
  action: string;
  cashState: string;
};

export type CashActivityFilterOptions = {
  businessDate: string;
  timezone: string;
  earliestDate: string;
  terminals: Array<{ id: string; name: string; secondaryName: string | null }>;
  users: Array<{ id: string; name: string; secondaryName: string | null }>;
};

export type CashActivityPage = {
  items: CashActivityRow[];
  nextCursor: string | null;
  hasMore: boolean;
};

export function loadCashActivityFilterOptions(token?: string, signal?: AbortSignal) {
  return apiRequest<CashActivityFilterOptions>("/cash/activity/filter-options", { token, signal });
}

export function loadCashActivity(filters: CashActivityFilters, cursor: string | null, token?: string, sort?: TableSort | null, signal?: AbortSignal) {
  const query = new URLSearchParams({ from: filters.from, to: filters.to, limit: "50" });
  for (const key of ["terminalId", "userId", "action", "cashState"] as const) {
    if (filters[key]) query.set(key, filters[key]);
  }
  if (cursor) query.set("cursor", cursor);
  if (sort) { query.set("sortBy", sort.column === "hour" ? "time" : sort.column); query.set("sortDirection", sort.direction); }
  return apiRequest<CashActivityPage>(`/cash/activity?${query.toString()}`, { token, signal });
}
