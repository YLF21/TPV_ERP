export type TaxpayerType = "SOCIEDAD" | "AUTONOMO";
export type TaxRegime = "IVA" | "IGIC";
export type LicenseStatus = "VALIDA" | "BLOQUEADA_MANUAL";
export type SyncOperation = "CREAR" | "ACTUALIZAR" | "BORRAR" | "ANULAR" | "CONFIRMAR" | "CERRAR";

export type Credentials = {
  username: string;
  password: string;
};

export type CreateCompanyRequest = {
  name: string;
  taxId: string;
  taxpayerType: TaxpayerType;
  impuestos: TaxRegime;
  storeCode: string;
  storeName: string;
  validUntil: string;
  maxWindows: number;
  maxPda: number;
};

export type CreateCompanyResponse = {
  companyId: string;
  storeId: string;
  licenseReference: string;
  pairingCode: string;
  validUntil: string;
};

export type LicenseSummary = {
  licenseReference: string;
  companyId: string;
  companyName: string;
  taxId: string;
  status: LicenseStatus;
  validUntil: string;
  maxWindows: number;
  maxPda: number;
};

export type InstallationSummary = {
  installationId: string;
  installationReference: string;
  companyId: string;
  storeId: string;
  licenseReference: string;
  linkedAt: string;
  lastValidatedAt: string | null;
};

export type AdminUser = {
  username: string;
  active: boolean;
  createdAt: string;
};

export type AuditLog = {
  id: string;
  username: string;
  action: string;
  targetType: string;
  targetId: string;
  createdAt: string;
};

export type PairingCodeResponse = {
  licenseReference: string;
  pairingCode: string;
  expiresAt: string;
};

export type AdminLicenseResponse = {
  licenseReference: string;
  status: LicenseStatus;
  validUntil: string;
  maxWindows: number;
  maxPda: number;
};

export type SyncEventView = {
  eventId: string;
  companyId: string;
  storeId: string;
  installationId: string;
  entityType: string;
  entityId: string;
  operation: SyncOperation;
  receivedAt: string;
  payload: Record<string, unknown>;
};

export type SalesSummary = {
  documentCount: number;
  total: string;
};

export type StockSnapshot = {
  companyId: string;
  storeId: string;
  productId: string;
  warehouseId: string;
  quantity: string;
};

export type DashboardData = {
  licenses: LicenseSummary[];
  installations: InstallationSummary[];
  users: AdminUser[];
  audit: AuditLog[];
  salesSummary: SalesSummary;
  stockCurrent: StockSnapshot[];
  events: SyncEventView[];
};
