import { api, request } from "./api";
import type { Credentials, TenantPortalData, SupportTicketComment } from "./types";

export type TenantStoreAccess = { storeId: string; code: string; name: string; internalCode: string | null; active: boolean };
export type TenantCompanyAccess = { companyId: string; companyName: string; roleName: string; companyPrivileges: string[]; stores: TenantStoreAccess[] };
export type TenantAccessResponse = { username: string; companies: TenantCompanyAccess[] };
export const DOCUMENT_TYPES = ["TICKET", "ALBARAN_VENTA", "FACTURA_VENTA", "RECTIFICATIVA_VENTA"] as const;
export const DOCUMENT_STATUSES = ["CONFIRMADO", "ANULADO", "PENDIENTE", "PARCIAL", "PAGADO"] as const;
export type DocumentType = typeof DOCUMENT_TYPES[number];
export type DocumentStatus = typeof DOCUMENT_STATUSES[number];
export type TenantDocument = { companyId: string; storeId: string; documentId: string; installationId: string; type: DocumentType; status: DocumentStatus; number: string; date: string; currency: string; total: string | number; customerName: string | null; storeCode: string | null };
export type TenantStock = { companyId: string; storeId: string; productId: string; warehouseId: string; quantity: string };
export type TenantSync = { received: number; projected: number; ignored: number; error: number; oldestReceivedAt: string | null };
export type TenantPage<T> = { items: T[]; nextCursor: string | null; hasMore: boolean };
export type DocumentFilters = { types: readonly DocumentType[]; statuses: readonly DocumentStatus[]; from: string | null; to: string | null; numberContains: string | null };

export const tenantApi = {
  access: (credentials: Credentials) => request<TenantAccessResponse>(credentials, "/api/v1/tenant/access"),
  documents: (credentials: Credentials, storeIds: string[], filters: DocumentFilters, cursor: string | null) =>
    request<TenantPage<TenantDocument>>(credentials, "/api/v1/tenant/documents/page", { method: "POST", body: { storeIds, ...filters, size: 50, cursor } }),
  stock: (credentials: Credentials, storeId: string, cursor: string | null) =>
    request<TenantPage<TenantStock>>(credentials, `/api/v1/tenant/stores/${encodeURIComponent(storeId)}/stock?size=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`),
  sync: (credentials: Credentials, storeId: string) => request<TenantSync>(credentials, `/api/v1/tenant/stores/${encodeURIComponent(storeId)}/sync-status`),
  comments: (credentials: Credentials, ticketId: string) => request<SupportTicketComment[]>(credentials, `/api/v1/tenant/tickets/${encodeURIComponent(ticketId)}/comments`),
  comment: (credentials: Credentials, ticketId: string, message: string) => request<SupportTicketComment>(credentials, `/api/v1/tenant/tickets/${encodeURIComponent(ticketId)}/comments`, { method: "POST", body: { message } }),
};

export async function loadTenantPortal(credentials: Credentials, access: TenantCompanyAccess): Promise<TenantPortalData> {
  const can = (privilege: string) => access.companyPrivileges.includes(privilege);
  const results = await Promise.allSettled([
    api.tenantSession(credentials), api.tenantDashboard(credentials), api.tenantStores(credentials),
    can("READ_COMPANY") ? api.tenantLicenses(credentials) : Promise.resolve([]),
    can("READ_BILLING") ? api.tenantInvoices(credentials) : Promise.resolve([]),
    can("SUPPORT") ? api.tenantTickets(credentials) : Promise.resolve([]),
    can("READ_MASTERS") ? api.tenantErpCustomers(credentials) : Promise.resolve([]),
    can("READ_MASTERS") ? api.tenantErpProducts(credentials) : Promise.resolve([]),
    can("READ_MASTERS") ? api.tenantErpSuppliers(credentials) : Promise.resolve([]),
    can("READ_MASTERS") ? api.tenantErpWarehouses(credentials) : Promise.resolve([]),
  ]);
  const required = <T,>(result: PromiseSettledResult<T>): T => {
    if (result.status === "rejected") throw result.reason;
    return result.value;
  };
  const value = <T,>(index: number, fallback: T): T => results[index].status === "fulfilled" ? (results[index] as PromiseFulfilledResult<T>).value : fallback;
  return {
    session: required(results[0]), dashboard: required(results[1]), stores: required(results[2]),
    licenses: value(3, []), invoices: value(4, []), tickets: value(5, []), customers: value(6, []),
    products: value(7, []), suppliers: value(8, []), warehouses: value(9, []),
    loadErrors: results.slice(3).flatMap((result, index) => result.status === "rejected" ? [["licenses", "invoices", "tickets", "customers", "products", "suppliers", "warehouses"][index]] : []),
  };
}
