import type {
  AdminNotification,
  AdminLicenseResponse,
  AdminSession,
  AdminUser,
  AuditLog,
  BillingInvoice,
  BillingPayment,
  BillingSummary,
  CompanyOperations,
  CreateCompanyRequest,
  CreateCompanyResponse,
  Credentials,
  CustomerHealth,
  DashboardData,
  ErpCustomer,
  ErpProduct,
  ErpSupplier,
  ErpWarehouse,
  InstallationSummary,
  LicenseSummary,
  PairingCodeResponse,
  SaasStatus,
  SalesSummary,
  StockSnapshot,
  SupportTicket,
  SupportTicketComment,
  SyncEventView,
  TenantDashboard,
  TenantPortalData,
  TenantSession,
  TenantStore,
  TenantUser,
  TaxRegime,
  TechnicalStatus,
  TaxpayerType,
  VerifactuActivationPolicy
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

  verifactuActivationPolicies(credentials: Credentials) {
    return request<VerifactuActivationPolicy[]>(credentials, "/api/v1/admin/verifactu-activation-policies");
  },

  updateVerifactuActivationPolicy(
    credentials: Credentials,
    taxpayerType: TaxpayerType,
    payload: { activationDate: string; reason: string }
  ) {
    return request<VerifactuActivationPolicy>(
      credentials,
      `/api/v1/admin/verifactu-activation-policies/${encodeURIComponent(taxpayerType)}`,
      { method: "PUT", body: payload }
    );
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

  session(credentials: Credentials) {
    return request<AdminSession>(credentials, "/api/v1/admin/me");
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

  companyOperations(credentials: Credentials, companyId: string) {
    return request<CompanyOperations>(credentials, `/api/v1/admin/companies/${companyId}/operations`);
  },

  updateCompanyOperations(credentials: Credentials, companyId: string, payload: Omit<CompanyOperations, "companyId">) {
    return request<CompanyOperations>(credentials, `/api/v1/admin/companies/${companyId}/operations`, {
      method: "PUT",
      body: payload
    });
  },

  notifications(credentials: Credentials) {
    return request<AdminNotification[]>(credentials, "/api/v1/admin/notifications");
  },

  markNotificationRead(credentials: Credentials, notificationId: string) {
    return request<void>(credentials, `/api/v1/admin/notifications/${encodeURIComponent(notificationId)}/read`, {
      method: "PUT"
    });
  },

  technicalStatus(credentials: Credentials) {
    return request<TechnicalStatus>(credentials, "/api/v1/admin/technical-status");
  },

  saasStatus(credentials: Credentials) {
    return request<SaasStatus>(credentials, "/api/v1/admin/status");
  },

  billingSummary(credentials: Credentials) {
    return request<BillingSummary>(credentials, "/api/v1/admin/billing-summary");
  },

  billingInvoices(credentials: Credentials, companyId: string) {
    return request<BillingInvoice[]>(credentials, `/api/v1/admin/companies/${companyId}/invoices`);
  },

  createBillingInvoice(
    credentials: Credentials,
    companyId: string,
    payload: { number: string; concept: string; amount: string; currency: string; issuedAt: string; dueAt: string }
  ) {
    return request<BillingInvoice>(credentials, `/api/v1/admin/companies/${companyId}/invoices`, {
      method: "POST",
      body: payload
    });
  },

  createBillingPayment(
    credentials: Credentials,
    invoiceId: string,
    payload: { amount: string; method: string; paidAt: string; reference: string }
  ) {
    return request<BillingPayment>(credentials, `/api/v1/admin/invoices/${invoiceId}/payments`, {
      method: "POST",
      body: payload
    });
  },

  tenantUsers(credentials: Credentials, companyId: string) {
    return request<TenantUser[]>(credentials, `/api/v1/admin/companies/${companyId}/tenant-users`);
  },

  createTenantUser(credentials: Credentials, companyId: string, payload: { username: string; password: string; roleName: string }) {
    return request<TenantUser>(credentials, `/api/v1/admin/companies/${companyId}/tenant-users`, {
      method: "POST",
      body: payload
    });
  },

  changeTenantPassword(credentials: Credentials, username: string, password: string) {
    return request<void>(credentials, `/api/v1/admin/tenant-users/${encodeURIComponent(username)}/password`, {
      method: "PUT",
      body: { password }
    });
  },

  deactivateTenantUser(credentials: Credentials, username: string) {
    return request<void>(credentials, `/api/v1/admin/tenant-users/${encodeURIComponent(username)}`, {
      method: "DELETE"
    });
  },

  erpCustomers(credentials: Credentials, companyId: string) {
    return request<ErpCustomer[]>(credentials, `/api/v1/admin/companies/${companyId}/erp/customers`);
  },

  createErpCustomer(
    credentials: Credentials,
    companyId: string,
    payload: { code: string; name: string; taxId: string; email: string; phone: string }
  ) {
    return request<ErpCustomer>(credentials, `/api/v1/admin/companies/${companyId}/erp/customers`, {
      method: "POST",
      body: payload
    });
  },

  deactivateErpCustomer(credentials: Credentials, companyId: string, id: string) {
    return request<ErpCustomer>(credentials, `/api/v1/admin/companies/${companyId}/erp/customers/${id}`, {
      method: "DELETE"
    });
  },

  erpProducts(credentials: Credentials, companyId: string) {
    return request<ErpProduct[]>(credentials, `/api/v1/admin/companies/${companyId}/erp/products`);
  },

  createErpProduct(
    credentials: Credentials,
    companyId: string,
    payload: { sku: string; name: string; category: string; price: string; taxRate: string; minStock: string }
  ) {
    return request<ErpProduct>(credentials, `/api/v1/admin/companies/${companyId}/erp/products`, {
      method: "POST",
      body: payload
    });
  },

  deactivateErpProduct(credentials: Credentials, companyId: string, id: string) {
    return request<ErpProduct>(credentials, `/api/v1/admin/companies/${companyId}/erp/products/${id}`, {
      method: "DELETE"
    });
  },

  erpSuppliers(credentials: Credentials, companyId: string) {
    return request<ErpSupplier[]>(credentials, `/api/v1/admin/companies/${companyId}/erp/suppliers`);
  },

  createErpSupplier(
    credentials: Credentials,
    companyId: string,
    payload: { code: string; name: string; taxId: string; email: string; phone: string }
  ) {
    return request<ErpSupplier>(credentials, `/api/v1/admin/companies/${companyId}/erp/suppliers`, {
      method: "POST",
      body: payload
    });
  },

  deactivateErpSupplier(credentials: Credentials, companyId: string, id: string) {
    return request<ErpSupplier>(credentials, `/api/v1/admin/companies/${companyId}/erp/suppliers/${id}`, {
      method: "DELETE"
    });
  },

  erpWarehouses(credentials: Credentials, companyId: string) {
    return request<ErpWarehouse[]>(credentials, `/api/v1/admin/companies/${companyId}/erp/warehouses`);
  },

  createErpWarehouse(credentials: Credentials, companyId: string, payload: { code: string; name: string; address: string }) {
    return request<ErpWarehouse>(credentials, `/api/v1/admin/companies/${companyId}/erp/warehouses`, {
      method: "POST",
      body: payload
    });
  },

  deactivateErpWarehouse(credentials: Credentials, companyId: string, id: string) {
    return request<ErpWarehouse>(credentials, `/api/v1/admin/companies/${companyId}/erp/warehouses/${id}`, {
      method: "DELETE"
    });
  },

  customerHealth(credentials: Credentials) {
    return request<CustomerHealth[]>(credentials, "/api/v1/admin/health");
  },

  companyHealth(credentials: Credentials, companyId: string) {
    return request<CustomerHealth>(credentials, `/api/v1/admin/companies/${companyId}/health`);
  },

  supportTickets(credentials: Credentials, companyId: string) {
    return request<SupportTicket[]>(credentials, `/api/v1/admin/companies/${companyId}/tickets`);
  },

  createSupportTicket(credentials: Credentials, companyId: string, payload: { title: string; description: string; priority: string }) {
    return request<SupportTicket>(credentials, `/api/v1/admin/companies/${companyId}/tickets`, {
      method: "POST",
      body: payload
    });
  },

  updateSupportTicket(credentials: Credentials, ticketId: string, payload: { status: string; priority: string }) {
    return request<SupportTicket>(credentials, `/api/v1/admin/tickets/${ticketId}`, {
      method: "PUT",
      body: payload
    });
  },

  supportTicketComments(credentials: Credentials, ticketId: string) {
    return request<SupportTicketComment[]>(credentials, `/api/v1/admin/tickets/${ticketId}/comments`);
  },

  createSupportTicketComment(credentials: Credentials, ticketId: string, message: string) {
    return request<SupportTicketComment>(credentials, `/api/v1/admin/tickets/${ticketId}/comments`, {
      method: "POST",
      body: { message }
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
  },

  tenantPortal(credentials: Credentials): Promise<TenantPortalData> {
    return Promise.all([
      this.tenantSession(credentials),
      this.tenantDashboard(credentials),
      this.tenantLicenses(credentials),
      this.tenantStores(credentials),
      this.tenantTickets(credentials),
      this.tenantInvoices(credentials),
      this.tenantErpCustomers(credentials),
      this.tenantErpProducts(credentials),
      this.tenantErpSuppliers(credentials),
      this.tenantErpWarehouses(credentials)
    ]).then(([session, dashboard, licenses, stores, tickets, invoices, customers, products, suppliers, warehouses]) => ({
      session,
      dashboard,
      licenses,
      stores,
      tickets,
      invoices,
      customers,
      products,
      suppliers,
      warehouses
    }));
  },

  tenantSession(credentials: Credentials) {
    return request<TenantSession>(credentials, "/api/v1/tenant/me");
  },

  tenantDashboard(credentials: Credentials) {
    return request<TenantDashboard>(credentials, "/api/v1/tenant/dashboard");
  },

  tenantLicenses(credentials: Credentials) {
    return request<LicenseSummary[]>(credentials, "/api/v1/tenant/licenses");
  },

  tenantStores(credentials: Credentials) {
    return request<TenantStore[]>(credentials, "/api/v1/tenant/stores");
  },

  tenantTickets(credentials: Credentials) {
    return request<SupportTicket[]>(credentials, "/api/v1/tenant/tickets");
  },

  tenantInvoices(credentials: Credentials) {
    return request<BillingInvoice[]>(credentials, "/api/v1/tenant/invoices");
  },

  tenantErpCustomers(credentials: Credentials) {
    return request<ErpCustomer[]>(credentials, "/api/v1/tenant/erp/customers");
  },

  createTenantErpCustomer(credentials: Credentials, payload: { code: string; name: string; taxId: string; email: string; phone: string }) {
    return request<ErpCustomer>(credentials, "/api/v1/tenant/erp/customers", {
      method: "POST",
      body: payload
    });
  },

  tenantErpProducts(credentials: Credentials) {
    return request<ErpProduct[]>(credentials, "/api/v1/tenant/erp/products");
  },

  createTenantErpProduct(
    credentials: Credentials,
    payload: { sku: string; name: string; category: string; price: string; taxRate: string; minStock: string }
  ) {
    return request<ErpProduct>(credentials, "/api/v1/tenant/erp/products", {
      method: "POST",
      body: payload
    });
  },

  tenantErpSuppliers(credentials: Credentials) {
    return request<ErpSupplier[]>(credentials, "/api/v1/tenant/erp/suppliers");
  },

  createTenantErpSupplier(credentials: Credentials, payload: { code: string; name: string; taxId: string; email: string; phone: string }) {
    return request<ErpSupplier>(credentials, "/api/v1/tenant/erp/suppliers", {
      method: "POST",
      body: payload
    });
  },

  tenantErpWarehouses(credentials: Credentials) {
    return request<ErpWarehouse[]>(credentials, "/api/v1/tenant/erp/warehouses");
  },

  createTenantErpWarehouse(credentials: Credentials, payload: { code: string; name: string; address: string }) {
    return request<ErpWarehouse>(credentials, "/api/v1/tenant/erp/warehouses", {
      method: "POST",
      body: payload
    });
  },

  createTenantTicket(credentials: Credentials, payload: { title: string; description: string; priority: string }) {
    return request<SupportTicket>(credentials, "/api/v1/tenant/tickets", {
      method: "POST",
      body: payload
    });
  }
};

function syncPath(path: string, companyId?: string, storeId?: string) {
  const params = new URLSearchParams();
  if (companyId) params.set("companyId", companyId);
  if (storeId) params.set("storeId", storeId);
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}
