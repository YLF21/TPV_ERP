import type { UserSession } from "../types";

export type WarehouseSection = "input" | "output" | "goodsCheck";

export const warehouseSections: WarehouseSection[] = ["input", "output", "goodsCheck"];

export function userCanManageWarehouse(session: Pick<UserSession, "permissions">) {
  return session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_ALMACEN");
}

export function visibleWarehouseSectionsForSession(
  session: Pick<UserSession, "permissions">
): WarehouseSection[] {
  return userCanManageWarehouse(session) ? warehouseSections : [];
}
