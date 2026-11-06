import { apiRequest } from "../api/client";

export type ReplenishmentView = {
  id: string; version: number; productId: string; warehouseId: string; warehouseName: string;
  code: string; barcode: string; name: string; quantity: number; reason: string;
  stock: number; purchasePrice: number | null; supplierId: string | null;
  supplierName: string | null; familyId: string | null; familyName: string | null;
  subfamilyId: string | null; subfamilyName: string | null;
  status: "PENDING" | "ORDERED"; orderDate: string | null;
};
export type ReplenishmentFilters = { warehouseId?: string; supplierId?: string; familyId?: string; subfamilyId?: string;
  warehouseIds?: string[]; supplierIds?: string[]; familyIds?: string[]; subfamilyIds?: string[]; search?: string };
const base = "/replenishment-requests";
function query(filters: ReplenishmentFilters, ids?: string[]) {
  const params = new URLSearchParams();
  const { warehouseIds, supplierIds, familyIds, subfamilyIds, ...legacy } = filters;
  Object.entries(legacy).forEach(([key, value]) => { if (value) params.set(key, value); });
  for (const [key, values] of Object.entries({ warehouseId: warehouseIds, supplierId: supplierIds, familyId: familyIds, subfamilyId: subfamilyIds })) {
    if (values?.length) {
      params.delete(key);
      values.forEach(value => params.append(key, value));
    }
  }
  if (ids?.length) params.set("ids", ids.join(","));
  return params.toString();
}
export function listReplenishments(token: string, filters: ReplenishmentFilters, signal?: AbortSignal) {
  const suffix = query(filters);
  return apiRequest<ReplenishmentView[]>(`${base}${suffix ? `?${suffix}` : ""}`, { token, signal });
}
export function createReplenishment(token: string, body: { productId: string; warehouseId: string; quantity: number; reason: string }) {
  return apiRequest<void>(base, { token, body });
}
export function editReplenishment(token: string, item: ReplenishmentView, quantity: number, reason: string) {
  return apiRequest<void>(`${base}/${encodeURIComponent(item.id)}`, { method: "PUT", token, body: { version: item.version, quantity, reason } });
}
export function setReplenishmentsOrdered(token: string, items: ReplenishmentView[], ordered: boolean) {
  return apiRequest<void>(`${base}/status`, { token, body: { items: items.map(({ id, version }) => ({ id, version })), ordered } });
}
export function deleteReplenishments(token: string, items: ReplenishmentView[]) {
  return apiRequest<void>(`${base}/delete`, { token, body: { items: items.map(({ id, version }) => ({ id, version })) } });
}
export function exportReplenishments(token: string, format: "xlsx" | "pdf", locale: string, filters: ReplenishmentFilters, ids?: string[]) {
  return apiRequest<Blob>(`${base}/export`, { token, method: "POST", body: { format, locale, ...filters, ...(ids?.length ? { ids } : {}) }, responseType: "blob" });
}
