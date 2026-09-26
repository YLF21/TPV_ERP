import { request } from "./api";
import type { CommercialProfile, Credentials, FiscalAddress, LicenseStatus } from "./types";

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
};
export type CursorPage<T> = {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
  size?: number;
};
export type StoreRow = {
  id: string;
  companyId: string;
  companyName: string;
  code: string;
  name: string;
  internalCode: string | null;
  active: boolean;
  storeAddress: FiscalAddress | null;
  timeZoneId: string;
  installations: number;
  activeInstallations: number;
  lastSyncAt: string | null;
  createdAt: string;
  taxRegime: "IVA" | "IGIC";
  commercialProfile: CommercialProfile;
  taxRegimeLocked: boolean;
  servicePrice: string | null;
  billingPeriod: "MONTHLY" | "ANNUAL" | null;
  maxWindows: number;
  maxPda: number;
  validUntil: string | null;
};
export type LicenseRow = {
  id: string;
  reference: string;
  companyId: string;
  companyName: string;
  taxId: string;
  status: LicenseStatus;
  validUntil: string;
  maxWindows: number;
  maxPda: number;
  activeInstallations: number;
  lastValidatedAt: string | null;
  lastSyncAt: string | null;
  stores: Pick<StoreRow, "id" | "code" | "name" | "internalCode" | "active">[];
  billingScope: "COMPANY";
  companyBillingStatus: string | null;
  companyDebt: {
    currency: string;
    outstanding: string | number;
    overdue: string | number;
  }[];
};
export type FailureRow = {
  id: string;
  source: string;
  sourceId: string;
  companyId: string | null;
  companyName: string | null;
  storeId: string | null;
  storeName: string | null;
  internalCode: string | null;
  installationId: string | null;
  installationReference: string | null;
  status: string;
  severity: string;
  code: string;
  detail: string;
  module?: string | null;
  appVersion?: string | null;
  traceId?: string | null;
  exceptionType?: string | null;
  errorLocation?: string | null;
  receivedAt?: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  occurrences: number;
  central: boolean;
  storeActive: boolean | null;
};
export type FailureRepairCommand = {
  commandId: string; requestId: string; action: string; eventId: string; expectedVersion: number;
  status: string; resultCode: string | null; requestedBy: string; reason: string;
  createdAt: string; expiresAt: string; updatedAt: string;
};
export type FailureRepairs = {
  remoteEligible: boolean; ineligibleReason: string | null;
  commands: FailureRepairCommand[]; manualTicketId: string | null;
};
export type InterventionAction = "START_REMOTE" | "REQUIRE_ONSITE" | "START_ONSITE" | "RESOLVE" | "REOPEN";
export type TicketInterventionRequest = {
  requestId: string; expectedVersion: number; expectedTicketStatus: string;
  action: InterventionAction; note: string; teamViewerId: string | null;
};
export type TicketInterventionEvent = {
  requestId: string; version: number; action: string; status: string; note: string;
  teamViewerId: string | null; actor: string; createdAt: string;
};
export type TicketInterventionState = {
  ticketId: string; companyId: string; status: string; version: number;
  ticketStatus: string; teamViewerId: string | null; events: TicketInterventionEvent[];
};
export const COMPANY_PRIVILEGES = [
  "READ_COMPANY",
  "READ_BILLING",
  "READ_MASTERS",
  "WRITE_MASTERS",
  "SUPPORT",
] as const;
export type CompanyPrivilege = (typeof COMPANY_PRIVILEGES)[number];
export type AccessCompany = {
  companyId: string;
  companyName: string;
  roleName: string;
  companyPrivileges: CompanyPrivilege[];
  stores: {
    storeId: string;
    code: string;
    name: string;
    internalCode?: string | null;
  }[];
};
export type TenantAccess = { username: string; companies: AccessCompany[] };

export function query(
  values: Record<string, string | number | boolean | null | undefined>,
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== "" && value != null) params.set(key, String(value));
  return `?${params}`;
}

export const workspaceApi = {
  stores: (
    c: Credentials,
    filters: Record<string, string | number | boolean>,
  ) => request<Page<StoreRow>>(c, `/api/v1/admin/stores${query(filters)}`),
  licenses: (
    c: Credentials,
    filters: Record<string, string | number | boolean>,
  ) =>
    request<Page<LicenseRow>>(
      c,
      `/api/v1/admin/license-workspace${query(filters)}`,
    ),
  failures: (
    c: Credentials,
    filters: Record<string, string | number | boolean>,
  ) =>
    request<CursorPage<FailureRow>>(
      c,
      `/api/v1/admin/supervision/failures${query(filters)}`,
    ),
  failureRepairs: (c: Credentials, key: string) => request<FailureRepairs>(c,
    `/api/v1/admin/supervision/failures/${encodeURIComponent(key)}/repairs`),
  requestFailureRepair: (c: Credentials, key: string, body: { requestId: string; reason: string }) => request<FailureRepairCommand>(c,
    `/api/v1/admin/supervision/failures/${encodeURIComponent(key)}/repairs`, { method: "POST", body }),
  requestManualRepair: (c: Credentials, key: string, reason: string) => request<{ ticketId: string }>(c,
    `/api/v1/admin/supervision/failures/${encodeURIComponent(key)}/manual`, { method: "POST", body: { reason } }),
  ticketInterventions: (c: Credentials, ticketId: string) => request<TicketInterventionState>(c,
    `/api/v1/admin/tickets/${encodeURIComponent(ticketId)}/interventions`),
  recordTicketIntervention: (c: Credentials, ticketId: string, body: TicketInterventionRequest) => request<TicketInterventionState>(c,
    `/api/v1/admin/tickets/${encodeURIComponent(ticketId)}/interventions`, { method: "POST", body }),
  access: (c: Credentials) => request<TenantAccess>(c, "/api/v1/tenant/access"),
  userAccess: (c: Credentials, username: string) =>
    request<TenantAccess>(
      c,
      `/api/v1/admin/tenant-users/${encodeURIComponent(username)}/access`,
    ),
};
