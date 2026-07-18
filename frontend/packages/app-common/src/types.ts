export type AppKind = "venta" | "gestion";

export type Permission =
  | "ADMIN"
  | "VENTA"
  | "GESTION_VENTAS"
  | "GESTION_PRODUCTO"
  | "GESTION_ALMACEN"
  | "GESTION_CUENTAS"
  | "TICKETS_CREATE"
  | "INVOICES_WRITE"
  | "DELIVERY_NOTES_WRITE"
  | "CUSTOMER_RECEIVABLES_READ"
  | "CUSTOMER_RECEIVABLES_CREATE"
  | "CUSTOMER_RECEIVABLES_PAY"
  | "APLICAR_DESCUENTO"
  | "CAMBIAR_PRECIO"
  | "PRODUCTS_READ"
  | "PRODUCTS_WRITE"
  | "PRODUCTS_DELETE"
  | "STOCK_READ"
  | "STOCK_ADJUST"
  | "STOCK_TRANSFER"
  | "WAREHOUSES_MANAGE"
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
