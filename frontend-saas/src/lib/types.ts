export type TaxpayerType = "SOCIEDAD" | "AUTONOMO";
export type TaxRegime = "IVA" | "IGIC";
export type CommercialProfile = "MAYORISTA" | "MINORISTA";
export type FiscalAddress = {
  linea1: string;
  ciudad: string;
  codigoPostal: string;
  provincia: string;
  pais: string;
};
export type LicenseStatus = "VALIDA" | "BLOQUEADA_MANUAL" | "CADUCADA";
export type SyncOperation = "CREAR" | "ACTUALIZAR" | "BORRAR" | "ANULAR" | "CONFIRMAR" | "CERRAR";

export type Credentials = {
  username: string;
  accessToken: string;
  mode: "admin" | "tenant";
};

export type LoginCredentials = {
  username: string;
  password: string;
};

export type LoginResponse = Credentials & {
  expiresAt: string;
  passwordChangeRequired: boolean;
};

export type PlanUsage = {
  companyId: string;
  planName: string;
  usage: Record<string, number>;
  limits: Record<string, number>;
};

export type InvoiceFiscalDetail = {
  invoiceId: string;
  companyId: string;
  number: string;
  series: string;
  fiscalYear: number;
  taxRegime: string;
  taxBase: string;
  taxRate: string;
  taxAmount: string;
  total: string;
  currency: string;
};

export type PaymentReconciliation = {
  id: string;
  companyId: string;
  paymentId: string | null;
  provider: string;
  externalReference: string;
  amount: string;
  currency: string;
  bookedAt: string;
  status: string;
  notes: string | null;
  createdAt: string;
};

export type MasterImportResult = {
  processed: number;
  inserted: number;
  updated: number;
};

export type AdminSession = {
  username: string;
  permissions: string[];
};

export type CreateCompanyRequest = {
  name: string;
  taxId: string;
  taxpayerType: TaxpayerType;
  impuestos: TaxRegime;
  commercialProfile: CommercialProfile;
  companyAddress: FiscalAddress;
  storeCode: string;
  storeName: string;
  storeAddress: FiscalAddress;
  timeZoneId: string;
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
  tenantUsername: string;
  tenantInitialPassword: string;
};

export type LicenseSummary = {
  licenseReference: string;
  companyId: string;
  companyName: string;
  taxId: string;
  taxpayerType: TaxpayerType;
  taxRegime: TaxRegime;
  commercialProfile: CommercialProfile;
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
  appVersion: string | null;
  operatingSystem: string | null;
  terminalName: string | null;
  lastIp: string | null;
  active: boolean;
  revokedAt: string | null;
  revokedBy: string | null;
  revocationReason: string | null;
  version: number;
};

export type FiscalProvisioning = {
  companyId: string;
  companyAddress: FiscalAddress | null;
  stores: Array<{
    storeId: string;
    storeCode: string;
    storeName: string;
    storeAddress: FiscalAddress | null;
    timeZoneId: string;
  }>;
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
  details?: string | null;
  createdAt: string;
};

export type VerifactuActivationPolicy = {
  taxpayerType: TaxpayerType;
  activationDate: string;
  version: number;
  updatedAt: string;
  updatedBy: string;
  reason: string;
  activeLicenses: number;
  linkedInstallations: number;
};

export type FiscalStatusAdmin = {
  companyId: string;
  companyName: string;
  taxId: string;
  storeId: string;
  storeName: string;
  installationId: string | null;
  installationReference: string | null;
  effectiveMode: "PRE_SIF" | "NO_VERIFACTU" | "VERIFACTU" | string;
  activationState: "ACTIVE" | "PENDING" | "DUE_REVIEW" | "UNKNOWN" | string;
  modeVersion: number;
  modeSince: string | null;
  activationDate: string | null;
  policyVersion: number | null;
  runtimeClass: string | null;
  endpointEnvironment: string | null;
  transportMode: string | null;
  reportedAt: string | null;
  receivedAt: string | null;
  stale: boolean;
};

export type FiscalCompanyStatusAdmin = {
  companyId: string;
  companyName: string;
  taxId: string;
  effectiveMode: string;
  activationState: string;
  stores: number;
  installations: number;
  unlinkedStores: number;
  staleInstallations: number;
  lastReportedAt: string | null;
};

export type PairingCodeResponse = {
  licenseReference: string;
  pairingCode: string;
  expiresAt: string | null;
};

export type AdminLicenseResponse = {
  licenseReference: string;
  status: LicenseStatus;
  validUntil: string;
  maxWindows: number;
  maxPda: number;
};

export type CompanyOperations = {
  companyId: string;
  planName: string;
  billingStatus: string;
  renewalDate: string | null;
  monthlyPrice: string | null;
  supportStatus: string;
  contactName: string | null;
  contactEmail: string | null;
  notes: string | null;
};

export type AdminNotification = {
  id: string;
  companyId: string;
  companyName: string;
  severity: "INFO" | "WARNING" | "DANGER" | string;
  title: string;
  detail: string;
  createdAt: string;
  read?: boolean;
};

export type TechnicalStatus = {
  generatedAt: string;
  companies: number;
  licenses: number;
  installations: number;
  eventsToday: number;
  openTickets: number;
  staleInstallations: number;
  lastSyncAt: string | null;
};

export type SaasStatus = {
  generatedAt: string;
  apiVersion: string;
  expectedMigration: string;
  modules: string[];
};

export type SupportTicket = {
  id: string;
  companyId: string;
  companyName: string;
  title: string;
  description: string | null;
  status: string;
  priority: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

export type SupportTicketComment = {
  id: string;
  ticketId: string;
  author: string;
  message: string;
  createdAt: string;
};

export type CustomerHealth = {
  companyId: string;
  companyName: string;
  taxId: string;
  planName: string;
  billingStatus: string;
  licenseStatus: string;
  validUntil: string | null;
  installations: number;
  staleInstallations: number;
  lastValidationAt: string | null;
  eventsLast7Days: number;
  lastEventAt: string | null;
  openTickets: number;
  urgentTickets: number;
  score: number;
  riskLevel: "OK" | "WARNING" | "DANGER" | string;
  signals: string[];
};

export type BillingCompany = {
  companyId: string;
  companyName: string;
  taxId: string;
  planName: string;
  billingStatus: string;
  renewalDate: string | null;
  monthlyPrice: string | null;
  licenseReference: string | null;
  validUntil: string | null;
  renewalDueSoon: boolean;
  overdue: boolean;
};

export type BillingSummary = {
  totalCompanies: number;
  paidCompanies: number;
  pendingCompanies: number;
  overdueCompanies: number;
  renewalsNext30Days: number;
  monthlyRecurringRevenue: string;
  companies: BillingCompany[];
};

export type BillingInvoice = {
  id: string;
  companyId: string;
  companyName: string;
  number: string;
  concept: string;
  amount: string;
  paidAmount: string;
  currency: string;
  status: string;
  issuedAt: string;
  dueAt: string;
  createdAt: string;
};

export type BillingPayment = {
  id: string;
  invoiceId: string;
  amount: string;
  method: string;
  reference: string | null;
  paidAt: string;
  createdAt: string;
};

export type TenantUser = {
  id: string;
  companyId: string;
  username: string;
  roleName: string;
  active: boolean;
  createdAt: string;
};

export type ErpCustomer = {
  id: string;
  companyId: string;
  code: string;
  name: string;
  taxId: string | null;
  email: string | null;
  phone: string | null;
  active: boolean;
  createdAt: string;
};

export type ErpProduct = {
  id: string;
  companyId: string;
  sku: string;
  name: string;
  category: string | null;
  price: string;
  taxRate: string;
  minStock: string;
  active: boolean;
  createdAt: string;
};

export type ErpSupplier = {
  id: string;
  companyId: string;
  code: string;
  name: string;
  taxId: string | null;
  email: string | null;
  phone: string | null;
  active: boolean;
  createdAt: string;
};

export type ErpWarehouse = {
  id: string;
  companyId: string;
  code: string;
  name: string;
  address: string | null;
  active: boolean;
  createdAt: string;
};

export type TenantSession = {
  username: string;
  companyId: string;
  companyName: string;
  roleName: string;
};

export type TenantDashboard = {
  companyId: string;
  companyName: string;
  licenses: number;
  stores: number;
  installations: number;
  openTickets: number;
  billingStatus: string;
  renewalDate: string | null;
  monthlyPrice: string | null;
};

export type TenantStore = {
  storeId: string;
  code: string;
  name: string;
  createdAt: string;
};

export type TenantPortalData = {
  session: TenantSession;
  dashboard: TenantDashboard;
  licenses: LicenseSummary[];
  stores: TenantStore[];
  tickets: SupportTicket[];
  invoices: BillingInvoice[];
  customers: ErpCustomer[];
  products: ErpProduct[];
  suppliers: ErpSupplier[];
  warehouses: ErpWarehouse[];
  loadErrors: string[];
};

export type SyncEventView = {
  eventId: string;
  companyId: string;
  storeId: string;
  installationId: string;
  entityType: string;
  entityId: string;
  operation: SyncOperation;
  projectionStatus: "RECEIVED" | "PROJECTED" | "IGNORED" | "ERROR" | string;
  projectedAt: string | null;
  projectionError: string | null;
  schemaVersion: number;
  receivedAt: string;
  payload: Record<string, unknown>;
};

export type OperationalIncident = {
  incidentType: string;
  companyId: string;
  targetId: string;
  status: string;
  expectedStoreCount: number;
  completedStoreCount: number;
  snapshotCount: number;
  chunkCount: number;
  conflictSummary: string | null;
  createdAt: string;
  lastActivityAt: string;
  inactive: boolean;
  cancellable: boolean;
  completedBaselineId: string | null;
};

export type OperationalIncidentCancellation = {
  commandId: string;
  companyId: string;
  bootstrapId: string;
  previousStatus: string;
  status: string;
  cancelledAt: string;
  idempotentReplay: boolean;
};

export type SyncProjectionStatus = {
  received: number;
  projected: number;
  ignored: number;
  error: number;
  oldestReceivedAt: string | null;
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

export type SalesDocument = {
  id: string;
  companyId: string;
  storeId: string | null;
  documentNumber: string;
  customerCode: string | null;
  total: string;
  currency: string;
  status: string;
  issuedAt: string;
  createdAt: string;
};

export type InventoryMovement = {
  id: string;
  companyId: string;
  warehouseCode: string;
  productSku: string;
  movementType: string;
  quantity: string;
  reason: string | null;
  movedAt: string;
  createdAt: string;
};

export type InventoryStock = {
  warehouseCode: string;
  productSku: string;
  quantity: string;
};

export type Subscription = {
  id: string;
  companyId: string;
  companyName: string;
  planName: string;
  status: string;
  billingCycle: string;
  amount: string;
  currency: string;
  startedAt: string;
  nextBillingAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
};

export type IntegrationEndpoint = {
  id: string;
  companyId: string | null;
  companyName: string | null;
  name: string;
  integrationType: string;
  status: string;
  targetUrl: string | null;
  apiKeyPreview: string | null;
  lastSyncAt: string | null;
  createdAt: string;
};

export type AdvancedReport = {
  companies: number;
  subscriptions: number;
  subscriptionMrr: string;
  invoices: number;
  invoicedTotal: string;
  paidTotal: string;
  salesDocuments: number;
  salesTotal: string;
  inventoryMovements: number;
  integrations: number;
  activeIntegrations: number;
};

export type DashboardData = {
  licenses: LicenseSummary[];
  installations: InstallationSummary[];
  users: AdminUser[];
  audit: AuditLog[];
  salesSummary: SalesSummary;
  stockCurrent: StockSnapshot[];
  events: SyncEventView[];
  advancedReport?: AdvancedReport | null;
  subscriptions?: Subscription[];
  integrations?: IntegrationEndpoint[];
};
