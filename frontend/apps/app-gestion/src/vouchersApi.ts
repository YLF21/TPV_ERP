import { apiRequest, type IssuedVoucherPrintSnapshot } from "@tpverp/app-common";

export type VoucherStatus = "ACTIVE" | "EXPIRED" | "CONSUMED" | "INVALIDATED";

export type Voucher = {
  code: string;
  familyIdentifier: string;
  initialAmount: number;
  balance: number;
  status: VoucherStatus;
  createdAt: string;
  expiresOn: string | null;
  originTickets: string[];
};

export type VoucherManagementEvent = {
  type: "REACTIVATED" | "REPRINTED" | "REPRINT_FAILED";
  userId: string;
  operatorUsername: string | null;
  terminalId: string | null;
  occurredAt: string;
  reason: string | null;
};

export type VoucherDetail = {
  voucher: Voucher;
  events: VoucherManagementEvent[];
};

export type VoucherPage = {
  items: Voucher[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type VoucherFilters = {
  query: string;
  status: "" | VoucherStatus;
  from: string;
  to: string;
};

export type VoucherConfiguration = {
  storeId: string;
  expirationMode: "DAYS" | "NEVER";
  validityDays: number;
};

export function loadVouchers(
  filters: VoucherFilters,
  page: number,
  token?: string
) {
  const query = new URLSearchParams({ page: String(page), size: "50" });
  if (filters.query.trim()) query.set("query", filters.query.trim());
  if (filters.status) query.set("status", filters.status);
  if (filters.from) query.set("from", filters.from);
  if (filters.to) query.set("to", filters.to);
  return apiRequest<VoucherPage>(`/vouchers/management?${query.toString()}`, { token });
}

export function loadVoucherDetail(code: string, token?: string) {
  return apiRequest<VoucherDetail>(`/vouchers/${encodeURIComponent(code)}/management`, { token });
}

export function loadVoucherConfiguration(token?: string) {
  return apiRequest<VoucherConfiguration>("/vouchers/configuration", { token });
}

export function saveVoucherConfiguration(
  expirationMode: VoucherConfiguration["expirationMode"],
  validityDays: number,
  token?: string
) {
  return apiRequest<VoucherConfiguration>("/vouchers/configuration", {
    token,
    method: "PUT",
    body: { expirationMode, validityDays }
  });
}

export function reactivateVoucher(
  code: string,
  expiresOn: string,
  reason: string,
  token?: string
) {
  return apiRequest<VoucherDetail>(`/vouchers/${encodeURIComponent(code)}/reactivate`, {
    token,
    method: "POST",
    body: { expiresOn, reason }
  });
}

export function loadVoucherPrintDocument(code: string, token?: string) {
  return apiRequest<IssuedVoucherPrintSnapshot>(
    `/vouchers/${encodeURIComponent(code)}/print-document`,
    { token }
  );
}

export function recordVoucherPrintResult(code: string, success: boolean, token?: string) {
  return apiRequest<VoucherDetail>(`/vouchers/${encodeURIComponent(code)}/print-events`, {
    token,
    method: "POST",
    body: { success }
  });
}
