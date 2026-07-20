import type { UserSession } from "../types";

export type StockViewKey =
  | "stock.current"
  | "stock.topSales"
  | "stock.offers"
  | "stock.memberPrice"
  | "stock.promotions"
  | "stock.noDiscount"
  | "stock.bulkEdit";

export const stockViews: StockViewKey[] = [
  "stock.current",
  "stock.topSales",
  "stock.offers",
  "stock.memberPrice",
  "stock.promotions",
  "stock.noDiscount",
  "stock.bulkEdit"
];

export function userCanManageStockProducts(session: Pick<UserSession, "permissions">) {
  return session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_PRODUCTO");
}

export function userHasStockPermission(
  session: Pick<UserSession, "permissions">,
  ...permissions: UserSession["permissions"]
) {
  return session.permissions.includes("ADMIN")
    || permissions.some((permission) => session.permissions.includes(permission));
}

export function userCanReadStock(session: Pick<UserSession, "permissions">) {
  return session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_PRODUCTO")
    || session.permissions.includes("GESTION_ALMACEN")
    || session.permissions.includes("GESTION_VENTAS")
    || session.permissions.includes("STOCK_READ");
}

export function userCanManageWarehouses(session: Pick<UserSession, "permissions">) {
  return session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_ALMACEN")
    || session.permissions.includes("WAREHOUSES_MANAGE");
}

export function visibleStockViewsForSession(session: Pick<UserSession, "permissions">): StockViewKey[] {
  if (!userCanReadStock(session)) return [];
  if (userCanManageStockProducts(session)) return stockViews;
  const warehouseOnly = session.permissions.includes("GESTION_ALMACEN");
  return warehouseOnly
    ? ["stock.current"]
    : stockViews.filter((view) => view !== "stock.bulkEdit");
}
