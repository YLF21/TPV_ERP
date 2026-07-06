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
  | "CAMBIAR_PRECIO";

export type UserSession = {
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
