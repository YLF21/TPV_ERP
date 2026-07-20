import type { Permission, UserSession } from "@tpverp/app-common";

export type GestionModuleKey =
  | "gestion.sales"
  | "gestion.products"
  | "gestion.stock"
  | "gestion.customers"
  | "gestion.suppliers"
  | "gestion.users"
  | "gestion.roles"
  | "gestion.controlAlerts";

const modulePermissions: Record<GestionModuleKey, Permission[]> = {
  "gestion.sales": ["GESTION_VENTAS"],
  "gestion.products": ["GESTION_PRODUCTO"],
  "gestion.stock": ["GESTION_PRODUCTO", "GESTION_ALMACEN", "WAREHOUSES_MANAGE"],
  "gestion.customers": ["GESTION_CLIENTE_PROVEEDOR"],
  "gestion.suppliers": ["GESTION_CLIENTE_PROVEEDOR", "GESTION_ALMACEN"],
  "gestion.users": ["GESTION_USUARIO"],
  "gestion.roles": ["ROLES_MANAGE"],
  "gestion.controlAlerts": ["CONTROL_ALERTS_READ", "CONTROL_ALERTS_MANAGE"]
};

export function canOpenGestionModule(session: UserSession, module: GestionModuleKey): boolean {
  if (session.permissions.includes("ADMIN")) {
    return true;
  }
  return session.permissions.includes("APP_GESTION_ACCESS")
    && modulePermissions[module].some((permission) => session.permissions.includes(permission));
}

export function visibleGestionModules(session: UserSession): GestionModuleKey[] {
  return (Object.keys(modulePermissions) as GestionModuleKey[])
    .filter((module) => canOpenGestionModule(session, module));
}
