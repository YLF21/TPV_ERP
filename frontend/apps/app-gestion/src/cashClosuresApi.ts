import { apiRequest } from "@tpverp/app-common";
import type { TableSort } from "@tpverp/app-common";

export type CashClosure = {
  id: string;
  terminalId: string;
  terminalName: string;
  closingUserId: string;
  closingUserName: string;
  closingUsername: string;
  closedAt: string;
  expectedCash: number;
  retainedFund: number;
  discrepancy: number;
  lateClosing: boolean;
};

export type CashClosureFilterOption = {
  id: string;
  name: string;
  secondaryName: string;
};

export type CashClosureFilterOptions = {
  businessDate: string;
  timezone: string;
  terminals: CashClosureFilterOption[];
  users: CashClosureFilterOption[];
};

export type CashClosureFilters = {
  from: string;
  to: string;
  terminalId: string;
  userId: string;
  onlyDiscrepancies: boolean;
};

export type CashClosurePage = {
  items: CashClosure[];
  nextCursor: string | null;
  hasMore: boolean;
};

export async function loadCashClosureFilterOptions(token?: string) {
  return apiRequest<CashClosureFilterOptions>("/cash/closures/filter-options", { token });
}

export async function loadCashClosures(
  filters: CashClosureFilters,
  cursor: string | null,
  token?: string,
  sort?: TableSort | null
) {
  const query = new URLSearchParams({
    from: filters.from,
    to: filters.to,
    onlyDiscrepancies: String(filters.onlyDiscrepancies),
    limit: "50"
  });
  if (filters.terminalId) query.set("terminalId", filters.terminalId);
  if (filters.userId) query.set("userId", filters.userId);
  if (cursor) query.set("cursor", cursor);
  if (sort) {
    query.set("sortBy", sort.column);
    query.set("sortDirection", sort.direction);
  }
  return apiRequest<CashClosurePage>(`/cash/closures?${query.toString()}`, { token });
}
