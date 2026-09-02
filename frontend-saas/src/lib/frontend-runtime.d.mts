export function parseDecimal(value: string | number | null | undefined): number;
export function formatCurrency(value: string | number | null | undefined, currency?: string, locale?: string): string;
export function formatQuantity(value: string | number | null | undefined, locale?: string): string;
export function outstandingAmount(invoice: { amount?: string | null; paidAmount?: string | null } | null | undefined): number;
export function isCurrentSelection(requestedCompanyId: string, selectedCompanyId: string): boolean;

export function isCurrentSessionRequest(requestId: number, latestRequestId: number, requestedAccessToken: string, currentAccessToken: string | null | undefined): boolean;
export function shouldInvalidateSession(failedAccessToken: string, currentAccessToken: string | null | undefined, pendingAccessToken: string | null | undefined): boolean;

export function paginateRows<T>(rows: T[], page: number, pageSize?: number): { rows: T[]; page: number; pages: number; total: number; pageSize: number };
