export function parseDecimal(value: string | number | null | undefined): number;
export function formatCurrency(value: string | number | null | undefined, currency?: string, locale?: string): string;
export function formatQuantity(value: string | number | null | undefined, locale?: string): string;
export function outstandingAmount(invoice: { amount?: string | null; paidAmount?: string | null } | null | undefined): number;
export function canInvoiceBePaid(fiscalDetail: { fiscalStatus?: string | null } | null | undefined): boolean;
export function validateFiscalDecision(form: { fiscalStatus?: string; taxBase?: string; taxRate?: string; taxAmount?: string; reason?: string; legalBasis?: string; evidenceReference?: string } | null | undefined): boolean;
export function hasVerifiableFiscalEvidence(value: unknown, minimumLength: number): boolean;
export function settleWithConcurrency<T, R>(items: T[], worker: (item: T, index: number) => Promise<R>, concurrency?: number): Promise<Array<PromiseSettledResult<R>>>;
export function isCurrentSelection(requestedCompanyId: string, selectedCompanyId: string): boolean;

export function isCurrentSessionRequest(requestId: number, latestRequestId: number, requestedAccessToken: string, currentAccessToken: string | null | undefined): boolean;
export function isCurrentAuthRequest(requestId: number, latestRequestId: number): boolean;
export function retainCompanyOperationsAfterFailure<T extends { companyId: string }>(current: T | null, companyId: string): T | null;
export function shouldInvalidateSession(failedAccessToken: string, currentAccessToken: string | null | undefined, pendingAccessToken: string | null | undefined): boolean;

export function paginateRows<T>(rows: T[], page: number, pageSize?: number): { rows: T[]; page: number; pages: number; total: number; pageSize: number };
