export type AppKind = "venta" | "gestion";

export type Permission =
  | "ADMIN"
  | "VENTA"
  | "GESTION_VENTAS"
  | "GESTION_PRODUCTO"
  | "GESTION_CUENTAS"
  | "TICKETS_CREATE"
  | "INVOICES_WRITE"
  | "DELIVERY_NOTES_WRITE"
  | "APLICAR_DESCUENTO"
  | "CAMBIAR_PRECIO"
  | "PRODUCTS_READ"
  | "PRODUCTS_WRITE"
  | "PRODUCTS_DELETE"
  | "STOCK_READ"
  | "STOCK_ADJUST"
  | "STOCK_TRANSFER"
  | "WAREHOUSES_MANAGE"
  | "WAREHOUSE_INPUTS_READ"
  | "WAREHOUSE_INPUTS_WRITE"
  | "WAREHOUSE_INPUTS_DELETE"
  | "WAREHOUSE_INPUTS_CONFIRM"
  | "WAREHOUSE_OUTPUTS_READ"
  | "WAREHOUSE_OUTPUTS_EDIT"
  | "WAREHOUSE_OUTPUTS_DELETE"
  | "WAREHOUSE_OUTPUTS_CONFIRM"
  | "CUSTOMERS_READ"
  | "CUSTOMERS_WRITE"
  | "CUSTOMERS_DELETE"
  | "SUPPLIERS_READ"
  | "SUPPLIERS_WRITE"
  | "SUPPLIERS_DELETE"
  | "ROLES_MANAGE"
  | "PAYMENT_TERMINAL_VOID"
  | "PAYMENT_TERMINAL_REFUND";

export type UserSession = {
  userId?: string;
  username: string;
  displayName: string;
  role?: string;
  accessToken?: string;
  permissions: Permission[];
};

export type LocaleCode = "es" | "en" | "zh";

export type TerminalContext = {
  storeName: string;
  terminalCode: string;
  terminalId?: string;
  terminalCredential?: string;
};
