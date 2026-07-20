export type AppKind = "venta" | "gestion";

export type Permission =
  | "ADMIN"
  | "APP_GESTION_ACCESS"
  | "VENTA"
  | "GESTION_VENTAS"
  | "GESTION_PRODUCTO"
  | "GESTION_ALMACEN"
  | "GESTION_CLIENTE_PROVEEDOR"
  | "GESTION_USUARIO"
  | "GESTION_CUENTAS"
  | "USERS_MANAGE"
  | "TERMINALS_MANAGE"
  | "CONFIGURACION_TERMINAL"
  | "LICENSES_MANAGE"
  | "BACKUPS_MANAGE"
  | "AUDIT_READ"
  | "CONTROL_ALERTS_READ"
  | "CONTROL_ALERTS_MANAGE"
  | "CONTROL_RULES_MANAGE"
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
  | "TAXES_MANAGE"
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
  | "CASH_READ"
  | "CASH_OPERATE"
  | "CASH_CONFIGURE"
  | "DELIVERY_NOTES_READ"
  | "DELIVERY_NOTES_CONFIRM"
  | "TICKETS_READ"
  | "TICKETS_CANCEL"
  | "INVOICES_READ"
  | "INVOICES_CONFIRM"
  | "INVOICES_PAY"
  | "PAYMENT_TERMINAL_VOID"
  | "PAYMENT_TERMINAL_REFUND"
  | "PAYMENT_TERMINAL_SECRETS";

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
