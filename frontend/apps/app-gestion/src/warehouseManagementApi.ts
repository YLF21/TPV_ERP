import { apiRequest } from "@tpverp/app-common";

export type WarehouseManagementRecord = {
  id: string;
  storeId: string;
  name: string;
  address: string | null;
  notes: string | null;
  defaultWarehouse: boolean;
  active: boolean;
  version: number;
};

export function loadManagedWarehouses(token: string) {
  return apiRequest<WarehouseManagementRecord[]>("/warehouses", { token });
}

export type WarehouseDetailsInput = { name: string; address: string | null; notes: string };

export function createManagedWarehouse(input: WarehouseDetailsInput, token: string) {
  return apiRequest<WarehouseManagementRecord>("/warehouses", {
    token,
    body: input
  });
}

export function renameManagedWarehouse(id: string, input: WarehouseDetailsInput, token: string) {
  return apiRequest<WarehouseManagementRecord>(`/warehouses/${id}`, {
    token,
    method: "PUT",
    body: input
  });
}

export function setManagedWarehouseActive(id: string, active: boolean, token: string) {
  return apiRequest<WarehouseManagementRecord>(`/warehouses/${id}/active`, {
    token,
    method: "PATCH",
    body: { active }
  });
}

export type WarehouseOverview = {
  warehouseId: string;
  productCount: number;
  totalQuantity: number;
  allowNegativeStock: boolean;
  defaultMinimumStock: number;
  alertsEnabled: boolean;
  inheritsStoreSettings: boolean;
};

export function loadWarehouseOverview(token: string) {
  return apiRequest<WarehouseOverview[]>("/warehouses/overview", { token });
}

export function applyWarehouseStockConfigurationToAll(input: Pick<WarehouseStockConfiguration,
  "allowNegativeStock" | "defaultMinimumStock" | "alertsEnabled">, token: string) {
  return apiRequest<GeneralStockConfiguration>("/stock/settings/warehouses/all", {
    token, method: "PUT", body: input
  });
}

export type WarehouseStockConfiguration = {
  warehouseId: string;
  storeId: string;
  allowNegativeStock: boolean;
  defaultMinimumStock: number;
  alertsEnabled: boolean;
  inheritsStoreSettings: boolean;
  version: number;
};

export function loadWarehouseStockConfiguration(id: string, token: string) {
  return apiRequest<WarehouseStockConfiguration>(`/stock/settings/warehouses/${encodeURIComponent(id)}`, { token });
}

export function saveWarehouseStockConfiguration(id: string, input: Pick<WarehouseStockConfiguration,
  "allowNegativeStock" | "defaultMinimumStock" | "alertsEnabled">, token: string) {
  return apiRequest<WarehouseStockConfiguration>(`/stock/settings/warehouses/${encodeURIComponent(id)}`, {
    token, method: "PUT", body: input
  });
}

export function resetWarehouseStockConfiguration(id: string, token: string) {
  return apiRequest<WarehouseStockConfiguration>(`/stock/settings/warehouses/${encodeURIComponent(id)}/override`, {
    token, method: "DELETE"
  });
}

export function deleteManagedWarehouse(id: string, token: string) {
  return apiRequest<void>(`/warehouses/${encodeURIComponent(id)}`, { token, method: "DELETE" });
}

export type GeneralStockConfiguration = {
  defaultWarehouseId: string;
  allowNegativeStock: boolean;
  defaultMinimumStock: number;
  alertsEnabled: boolean;
  allowInactiveProductSales: boolean;
};

export function loadGeneralStockConfiguration(token: string) {
  return apiRequest<GeneralStockConfiguration>("/stock/settings", { token });
}

export function saveGeneralStockConfiguration(warehouseId: string, token: string) {
  return apiRequest<GeneralStockConfiguration>("/stock/settings/default-warehouse", {
    token, method: "PATCH", body: { warehouseId }
  });
}

export function saveInactiveProductSales(allowInactiveProductSales: boolean, token: string) {
  return apiRequest<GeneralStockConfiguration>("/stock/settings/inactive-product-sales", {
    token, method: "PATCH", body: { allowInactiveProductSales }
  });
}
