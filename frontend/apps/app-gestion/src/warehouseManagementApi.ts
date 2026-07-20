import { apiRequest } from "@tpverp/app-common";

export type WarehouseManagementRecord = {
  id: string;
  storeId: string;
  name: string;
  defaultWarehouse: boolean;
  active: boolean;
  version: number;
};

export function loadManagedWarehouses(token: string) {
  return apiRequest<WarehouseManagementRecord[]>("/warehouses", { token });
}

export function createManagedWarehouse(name: string, token: string) {
  return apiRequest<WarehouseManagementRecord>("/warehouses", {
    token,
    body: { name }
  });
}

export function renameManagedWarehouse(id: string, name: string, token: string) {
  return apiRequest<WarehouseManagementRecord>(`/warehouses/${id}`, {
    token,
    method: "PUT",
    body: { name }
  });
}

export function setManagedWarehouseActive(id: string, active: boolean, token: string) {
  return apiRequest<WarehouseManagementRecord>(`/warehouses/${id}/active`, {
    token,
    method: "PATCH",
    body: { active }
  });
}
