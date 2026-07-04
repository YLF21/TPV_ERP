import type {
  AdminLicenseResponse,
  AdminUser,
  AuditLog,
  CreateCompanyRequest,
  CreateCompanyResponse,
  Credentials,
  DashboardData,
  InstallationSummary,
  LicenseSummary,
  PairingCodeResponse,
  SalesSummary,
  StockSnapshot,
  SyncEventView,
  TaxRegime,
  TaxpayerType
} from "./types";

const API_BASE = import.meta.env.VITE_SAAS_API_BASE_URL ?? "";

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
};

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function authHeader(credentials: Credentials) {
  return `Basic ${window.btoa(`${credentials.username}:${credentials.password}`)}`;
}

async function request<T>(credentials: Credentials, path: string, options: RequestOptions = {}): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: options.method ?? "GET",
    headers: {
      Authorization: authHeader(credentials),
      ...(options.body ? { "Content-Type": "application/json" } : {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  });

  if (!response.ok) {
    const text = await response.text();
    throw new ApiError(response.status, text || response.statusText || "Error de comunicacion");
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export const api = {
  dashboard(credentials: Credentials): Promise<DashboardData> {
    return Promise.all([
      this.licenses(credentials),
      this.installations(credentials),
      this.users(credentials),
      this.audit(credentials),
      this.salesSummary(credentials),
      this.stockCurrent(credentials),
      this.events(credentials)
    ]).then(([licenses, installations, users, audit, salesSummary, stockCurrent, events]) => ({
      licenses,
      installations,
      users,
      audit,
      salesSummary,
      stockCurrent,
      events
    }));
  },

  licenses(credentials: Credentials) {
    return request<LicenseSummary[]>(credentials, "/api/v1/admin/licenses");
  },

  installations(credentials: Credentials) {
    return request<InstallationSummary[]>(credentials, "/api/v1/admin/installations");
  },

  users(credentials: Credentials) {
    return request<AdminUser[]>(credentials, "/api/v1/admin/users");
  },

  audit(credentials: Credentials) {
    return request<AuditLog[]>(credentials, "/api/v1/admin/audit");
  },

  createCompany(credentials: Credentials, payload: CreateCompanyRequest) {
    return request<CreateCompanyResponse>(credentials, "/api/v1/admin/companies", {
      method: "POST",
      body: payload
    });
  },

  editCompany(
    credentials: Credentials,
    companyId: string,
    payload: { name: string; taxpayerType: TaxpayerType; impuestos: TaxRegime }
  ) {
    return request<LicenseSummary>(credentials, `/api/v1/admin/companies/${companyId}`, {
      method: "PUT",
      body: payload
    });
  },

  renewLicense(credentials: Credentials, reference: string, payload: { validUntil: string; maxWindows: number; maxPda: number }) {
    return request<AdminLicenseResponse>(credentials, `/api/v1/admin/licenses/${encodeURIComponent(reference)}/renew`, {
      method: "POST",
      body: payload
    });
  },

  blockLicense(credentials: Credentials, reference: string) {
    return request<AdminLicenseResponse>(credentials, `/api/v1/admin/licenses/${encodeURIComponent(reference)}/block`, {
      method: "POST"
    });
  },

  unblockLicense(credentials: Credentials, reference: string) {
    return request<AdminLicenseResponse>(credentials, `/api/v1/admin/licenses/${encodeURIComponent(reference)}/unblock`, {
      method: "POST"
    });
  },

  regeneratePairingCode(credentials: Credentials, reference: string) {
    return request<PairingCodeResponse>(credentials, `/api/v1/admin/licenses/${encodeURIComponent(reference)}/pairing-codes`, {
      method: "POST"
    });
  },

  createUser(credentials: Credentials, payload: { username: string; password: string; roleName: string }) {
    return request<AdminUser>(credentials, "/api/v1/admin/users", {
      method: "POST",
      body: payload
    });
  },

  changePassword(credentials: Credentials, username: string, password: string) {
    return request<void>(credentials, `/api/v1/admin/users/${encodeURIComponent(username)}/password`, {
      method: "PUT",
      body: { password }
    });
  },

  deactivateUser(credentials: Credentials, username: string) {
    return request<void>(credentials, `/api/v1/admin/users/${encodeURIComponent(username)}`, {
      method: "DELETE"
    });
  },

  events(credentials: Credentials, companyId?: string, storeId?: string) {
    return request<SyncEventView[]>(credentials, syncPath("/api/v1/admin/sync/events", companyId, storeId));
  },

  sales(credentials: Credentials, companyId?: string, storeId?: string) {
    return request<SyncEventView[]>(credentials, syncPath("/api/v1/admin/sync/sales", companyId, storeId));
  },

  salesSummary(credentials: Credentials, companyId?: string, storeId?: string) {
    return request<SalesSummary>(credentials, syncPath("/api/v1/admin/sync/sales-summary", companyId, storeId));
  },

  stockMovements(credentials: Credentials, companyId?: string, storeId?: string) {
    return request<SyncEventView[]>(credentials, syncPath("/api/v1/admin/sync/stock-movements", companyId, storeId));
  },

  stockCurrent(credentials: Credentials, companyId?: string, storeId?: string) {
    return request<StockSnapshot[]>(credentials, syncPath("/api/v1/admin/sync/stock-current", companyId, storeId));
  },

  cashClosures(credentials: Credentials, companyId?: string, storeId?: string) {
    return request<SyncEventView[]>(credentials, syncPath("/api/v1/admin/sync/cash-closures", companyId, storeId));
  }
};

function syncPath(path: string, companyId?: string, storeId?: string) {
  const params = new URLSearchParams();
  if (companyId) params.set("companyId", companyId);
  if (storeId) params.set("storeId", storeId);
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}
