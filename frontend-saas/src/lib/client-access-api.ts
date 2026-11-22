import { ApiError, extractApiErrorMessage, inboxRequest as request } from "./api";
import { RequestDeadlineError, withinRequestDeadline } from "./request-deadline.mjs";
import type { Credentials } from "./types";
import type { RequestEmailDelivery } from "./mail-api";

export type ClientAccessRequestStatus = "PENDING" | "APPROVED" | "REJECTED";
export type ClientRequestSubmission = {
  locale?: "es" | "en" | "zh";
  username: string;
  password: string;
  fullName: string;
  taxId: string;
  email: string;
  phone: string;
  address: string;
  postalCode: string;
  city: string;
  province: string;
  country: string;
  requestedStores: string[];
  notes?: string;
};
export type ClientAccessRequestSummary = {
  id: string;
  username: string;
  fullName: string;
  email: string;
  status: ClientAccessRequestStatus;
  version: number;
  createdAt: string;
  reviewedAt: string | null;
  reviewedBy: string | null;
};
export type ClientAccessRequestDetail = ClientAccessRequestSummary & Omit<ClientRequestSubmission, "password"> & {
  reviewReason: string | null;
  tenantUserId: string | null;
  delivery: RequestEmailDelivery | null;
};
export type ClientUserSummary = {
  id: string;
  username: string;
  fullName: string | null;
  email: string | null;
  active: boolean;
  createdAt: string;
  companyCount: number;
  storeCount: number;
};
export type ClientPage<T> = { items: T[]; total: number };
export type ClientDirectoryQuery = { page?: number; size?: number; q?: string; sort?: string; direction?: "asc" | "desc" };
export type ClientRequestQuery = ClientDirectoryQuery & { status?: ClientAccessRequestStatus };
export type ClientApproval = { version: number; grants: { companyId: string; storeIds: string[] }[]; reason?: string };
export type ClientRejection = { version: number; reason: string };

const requestPath = "/api/v1/admin/client-access-requests";
function queryPath(path: string, query: ClientRequestQuery) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  return `${path}?${params}`;
}

export const clientAccessApi = {
  requests: (credentials: Credentials, query: ClientRequestQuery = {}) =>
    request<ClientPage<ClientAccessRequestSummary>>(credentials, queryPath(requestPath, query)),
  request: (credentials: Credentials, id: string) =>
    request<ClientAccessRequestDetail>(credentials, `${requestPath}/${encodeURIComponent(id)}`),
  approve: (credentials: Credentials, id: string, body: ClientApproval) =>
    request<ClientAccessRequestDetail>(credentials, `${requestPath}/${encodeURIComponent(id)}/approve`, { method: "POST", body }),
  reject: (credentials: Credentials, id: string, body: ClientRejection) =>
    request<ClientAccessRequestDetail>(credentials, `${requestPath}/${encodeURIComponent(id)}/reject`, { method: "POST", body }),
  retryEmail: (credentials: Credentials, id: string) =>
    request<ClientAccessRequestDetail>(credentials, `${requestPath}/${encodeURIComponent(id)}/retry-email`, { method: "POST" }),
  users: (credentials: Credentials, query: ClientDirectoryQuery = {}) =>
    request<ClientPage<ClientUserSummary>>(credentials, queryPath("/api/v1/admin/tenant-users", query)),
};

// A public submission never carries an administrative session or persists credentials.
export async function submitClientAccessRequest(payload: ClientRequestSubmission, submissionId: string): Promise<void> {
  try {
    await withinRequestDeadline(async signal => {
      const response = await fetch(`${import.meta.env.VITE_SAAS_API_BASE_URL ?? ""}/api/v1/public/client-access-requests`, {
        method: "POST", signal, cache: "no-store", credentials: "omit", redirect: "error",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...payload, submissionId }),
      });
      if (!response.ok) {
        const text = await response.text();
        throw new ApiError(response.status, extractApiErrorMessage(text) || response.statusText);
      }
      if (response.status !== 202) throw new ApiError(502, "Unexpected submission response");
      await response.text();
    }, 20_000);
  } catch (error) {
    if (error instanceof RequestDeadlineError) throw new ApiError(408, "Request timed out");
    throw error;
  }
}
