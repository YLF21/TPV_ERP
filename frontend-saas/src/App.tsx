import { createContext, FormEvent, useContext, useEffect, useMemo, useState } from "react";
import { api, ApiError } from "./lib/api";
import type {
  AdminNotification,
  AdminSession,
  AdminUser,
  AuditLog,
  BillingInvoice,
  BillingSummary,
  CompanyOperations,
  CreateCompanyRequest,
  CustomerHealth,
  Credentials,
  DashboardData,
  ErpCustomer,
  ErpProduct,
  ErpSupplier,
  ErpWarehouse,
  InstallationSummary,
  LicenseSummary,
  PairingCodeResponse,
  SaasStatus,
  StockSnapshot,
  SupportTicket,
  SupportTicketComment,
  SyncEventView,
  TenantPortalData,
  TenantUser,
  TaxRegime,
  TechnicalStatus,
  TaxpayerType
} from "./lib/types";

type View = "dashboard" | "licenses" | "sync" | "users" | "audit" | "support" | "health" | "billing" | "masters";
type Notice = { type: "success" | "error"; text: string } | null;
type LicenseAction = "block" | "unblock" | "pairing";
type SaasAdminRoleName = "ADMIN" | "VIEWER" | "SUPPORT" | "BILLING" | "AUDITOR";
type Language = "es" | "en" | "zh";
type AuthMode = "admin" | "tenant";

const LANGUAGE_OPTIONS: Array<{ value: Language; label: string; short: string }> = [
  { value: "es", label: "Español", short: "ES" },
  { value: "en", label: "English", short: "EN" },
  { value: "zh", label: "中文", short: "ZH" }
];

const TRANSLATIONS: Record<Language, Record<string, string>> = {
  es: {
    administration: "Administracion",
    adminAccess: "Acceso administrador",
    mainNavigation: "Navegacion principal",
    language: "Idioma",
    dashboard: "Resumen",
    licensesCompanies: "Licencias y empresas",
    licenses: "Licencias",
    sync: "Sincronizacion",
    users: "Usuarios",
    audit: "Auditoria",
    supportCenter: "Soporte",
    customerHealth: "Pulso",
    billing: "Facturacion",
    masters: "Maestros",
    logout: "Salir",
    centralPanel: "Panel central",
    refresh: "Actualizar",
    refreshing: "Actualizando",
    loadingSaas: "Cargando datos del SaaS...",
    noLoadedData: "No hay datos cargados.",
    username: "Usuario",
    password: "Password",
    enter: "Entrar",
    validLicenses: "Licencias validas",
    blocked: "Bloqueadas",
    installations: "Instalaciones",
    activeUsers: "Usuarios activos",
    syncedSales: "Ventas sincronizadas",
    observedStock: "Stock observado",
    total: "total",
    recentLicenses: "Licencias recientes",
    recentLicensesSubtitle: "Estado operativo de clientes y cupos",
    lastEvent: "Ultimo evento",
    noEvents: "Sin eventos",
    noSyncedEventsYet: "Aun no hay eventos sincronizados.",
    auditRecent: "Acciones administrativas recientes",
    createCompany: "Alta de empresa",
    createCompanySubtitle: "Crea empresa, tienda, licencia y codigo de enlace",
    company: "Empresa",
    taxId: "NIF/CIF",
    type: "Tipo",
    taxes: "Impuestos",
    storeCode: "Codigo tienda",
    storeName: "Nombre tienda",
    validUntil: "Valida hasta",
    creating: "Creando",
    createLicense: "Crear licencia",
    records: "registros",
    linkedInstallations: "Instalaciones vinculadas",
    activePairingCode: "Codigo de enlace activo",
    expires: "expira",
    copy: "Copiar",
    license: "Licencia",
    status: "Estado",
    validity: "Validez",
    quotas: "Cupos",
    valid: "Valida",
    blockedStatus: "Bloqueada",
    generateCode: "Generar codigo",
    generating: "Generando",
    block: "Bloquear",
    unblock: "Desbloquear",
    noLicenses: "No hay licencias.",
    linkedAt: "Vinculada",
    lastValidation: "Ultima validacion",
    pending: "Pendiente",
    noLinkedInstallations: "No hay instalaciones vinculadas.",
    syncSubtitle: "Eventos enviados desde tiendas",
    consultingEvents: "Consultando eventos",
    events: "Eventos",
    sales: "Ventas",
    stock: "Stock",
    cash: "Caja",
    allCompanies: "Todas las empresas",
    demoHint: "Mostrando ejemplos locales porque no hay eventos reales para este filtro.",
    noEventsForFilter: "No hay eventos para el filtro actual.",
    payload: "Payload",
    product: "Producto",
    warehouse: "Almacen",
    quantity: "Cantidad",
    store: "Tienda",
    noStockForFilter: "No hay stock sincronizado para el filtro actual.",
    newUser: "Nuevo usuario",
    availableRoles: "Roles disponibles: ADMIN y VIEWER",
    viewerPermissionHint: "La sesion viewer solo permite consultar datos. Para crear o desactivar usuarios, sal y entra como admin.",
    role: "Rol",
    createUser: "Crear usuario",
    adminUsers: "Usuarios admin",
    accounts: "cuentas",
    created: "Creado",
    active: "Activo",
    inactive: "Inactivo",
    deactivate: "Desactivar",
    adminAudit: "Auditoria administrativa",
    recentActions: "acciones recientes",
    noAuditActions: "No hay acciones de auditoria.",
    globalSearch: "Buscar empresa, licencia, NIF o tienda",
    clearSearch: "Limpiar busqueda",
    alerts: "Alertas",
    alertsSubtitle: "Riesgos operativos que conviene revisar",
    noAlerts: "No hay alertas importantes.",
    expiringSoon: "Licencias proximas a caducar",
    staleInstallations: "Instalaciones sin validacion reciente",
    expiringLicenseAlert: "Licencia proxima a caducar",
    blockedLicenseAlert: "Licencia bloqueada",
    staleInstallationAlert: "Instalacion sin validacion reciente",
    companyDetail: "Ficha de empresa",
    companyDetailSubtitle: "Licencia, instalaciones y actividad sincronizada",
    selectCompany: "Selecciona una empresa para ver el detalle.",
    syncHealth: "Salud de sincronizacion",
    syncHealthSubtitle: "Actividad recibida por tiendas",
    eventsToday: "Eventos hoy",
    salesEvents: "Ventas",
    stockEvents: "Stock",
    cashEvents: "Caja",
    lastSync: "Ultima sincronizacion",
    viewDetail: "Ver ficha",
    selected: "Seleccionada",
    stores: "Tiendas",
    recentActivity: "Actividad reciente",
    noRecentActivity: "Sin actividad reciente.",
    licenseExpires: "Caduca",
    withoutValidation: "Sin validacion",
    stale: "Atrasada",
    phase2Operations: "Gestion SaaS",
    phase2OperationsSubtitle: "Licencia, facturacion y soporte",
    saveChanges: "Guardar cambios",
    saving: "Guardando",
    renewLicense: "Renovar licencia",
    editCompany: "Editar empresa",
    plan: "Plan",
    billingStatus: "Estado de pago",
    renewalDate: "Fecha renovacion",
    monthlyPrice: "Precio mensual",
    supportStatus: "Soporte",
    contactName: "Contacto",
    contactEmail: "Email contacto",
    notes: "Notas",
    deviceDetails: "Detalle tecnico",
    appVersion: "Version TPV",
    operatingSystem: "Sistema",
    terminalName: "Terminal",
    lastIp: "Ultima IP",
    notAvailable: "Pendiente",
    companyUpdated: "Empresa actualizada.",
    licenseRenewed: "Licencia renovada.",
    operationsUpdated: "Datos SaaS actualizados.",
    notifications: "Notificaciones",
    notificationsSubtitle: "Avisos internos calculados desde licencias, instalaciones y facturacion",
    technicalPanel: "Estado tecnico",
    technicalPanelSubtitle: "Pulso operativo del backend SaaS",
    supportTickets: "Tickets de soporte",
    supportTicketsSubtitle: "Incidencias internas por empresa",
    newTicket: "Nuevo ticket",
    title: "Titulo",
    description: "Descripcion",
    priority: "Prioridad",
    openTickets: "Tickets abiertos",
    backendStatus: "Backend SaaS",
    generatedAt: "Generado",
    createTicket: "Crear ticket",
    ticketCreated: "Ticket creado.",
    ticketUpdated: "Ticket actualizado.",
    noNotifications: "No hay notificaciones internas.",
    noTickets: "No hay tickets para esta empresa.",
    allStatuses: "Todos los estados",
    allPriorities: "Todas las prioridades",
    comment: "Comentario",
    addComment: "Añadir comentario",
    markRead: "Marcar leida",
    notificationRead: "Notificacion marcada como leida.",
    commentAdded: "Comentario añadido.",
    noComments: "Sin comentarios.",
    permissions: "Permisos",
    resolve: "Resolver",
    inProgress: "En curso",
    open: "Abierto",
    urgent: "Urgente",
    normal: "Normal",
    high: "Alta",
    technicalOk: "Operativo",
    healthSubtitle: "Riesgo operativo por empresa",
    healthScore: "Puntuacion",
    riskLevel: "Riesgo",
    riskOk: "OK",
    riskWarning: "Atencion",
    riskDanger: "Riesgo alto",
    customersInRisk: "Clientes en riesgo",
    inactiveCustomers: "Sin actividad",
    noHealthData: "No hay datos de pulso.",
    healthSignals: "Senales",
    eventsLast7Days: "Eventos 7 dias",
    urgentTickets: "Tickets urgentes",
    lastEventAt: "Ultimo evento",
    lastValidationAt: "Ultima validacion",
    stableOperation: "Operativa estable",
    billingSubtitle: "Cobros, renovaciones e ingresos estimados",
    paidCompanies: "Al dia",
    pendingBilling: "Pendientes",
    overdueBilling: "Impagadas",
    renewalsNext30Days: "Renovaciones 30 dias",
    monthlyRecurringRevenue: "Ingresos mensuales",
    billingPortfolio: "Cartera de facturacion",
    billingPortfolioSubtitle: "Empresas ordenadas por urgencia de cobro",
    noBillingData: "No hay datos de facturacion.",
    dueSoon: "Renovacion proxima",
    overdue: "Vencido",
    paid: "Pagado",
    clientPortal: "Portal cliente",
    myCompany: "Mi empresa",
    tenantWelcome: "Resumen operativo de tu SaaS",
    myLicenses: "Mis licencias",
    myStores: "Mis tiendas",
    mySupport: "Mi soporte",
    myMasters: "Mis maestros",
    tenantAccess: "Acceso cliente",
    tenantRole: "Rol cliente",
    createSupportRequest: "Crear solicitud",
    supportRequestCreated: "Solicitud creada.",
    noTenantTickets: "No tienes tickets abiertos.",
    tenantInitialAccess: "Acceso cliente inicial",
    tenantInitialAccessHint: "Entrega estas credenciales al cliente para su primer acceso.",
    initialPassword: "Password inicial",
    realBilling: "Facturacion real",
    invoices: "Facturas",
    invoiceNumber: "Numero factura",
    concept: "Concepto",
    amount: "Importe",
    currency: "Moneda",
    issuedAt: "Emitida",
    dueAt: "Vencimiento",
    paidAmount: "Pagado",
    createInvoice: "Crear factura",
    registerPayment: "Registrar pago",
    paymentMethod: "Metodo de pago",
    paymentReference: "Referencia",
    tenantUsers: "Usuarios cliente",
    createTenantUser: "Crear usuario cliente",
    tenantUserCreated: "Usuario cliente creado.",
    tenantUserUpdated: "Usuario cliente actualizado.",
    tenantUserDisabled: "Usuario cliente desactivado.",
    changePassword: "Cambiar password",
    newPassword: "Nuevo password",
    noTenantUsers: "No hay usuarios cliente para esta empresa.",
    erpMasters: "Maestros ERP",
    erpMastersSubtitle: "Clientes, productos, proveedores y almacenes por empresa",
    customers: "Clientes",
    products: "Productos",
    suppliers: "Proveedores",
    warehouses: "Almacenes",
    code: "Codigo",
    name: "Nombre",
    email: "Email",
    phone: "Telefono",
    sku: "SKU",
    category: "Categoria",
    price: "Precio",
    taxRate: "Impuesto",
    minStock: "Stock minimo",
    address: "Direccion",
    createCustomer: "Crear cliente",
    createProduct: "Crear producto",
    createSupplier: "Crear proveedor",
    createWarehouse: "Crear almacen",
    masterCreated: "Maestro creado.",
    masterDisabled: "Maestro desactivado.",
    mastersBackendPending: "Maestros ERP pendiente de activar en el backend SaaS. Reinicia el backend para cargar esta fase.",
    noMasterData: "No hay datos para este maestro."
  },
  en: {
    administration: "Administration",
    adminAccess: "Admin access",
    mainNavigation: "Main navigation",
    language: "Language",
    dashboard: "Dashboard",
    licensesCompanies: "Licenses and companies",
    licenses: "Licenses",
    sync: "Synchronization",
    users: "Users",
    audit: "Audit",
    supportCenter: "Support",
    customerHealth: "Health",
    billing: "Billing",
    masters: "Masters",
    logout: "Sign out",
    centralPanel: "Central panel",
    refresh: "Refresh",
    refreshing: "Refreshing",
    loadingSaas: "Loading SaaS data...",
    noLoadedData: "No data loaded.",
    username: "User",
    password: "Password",
    enter: "Sign in",
    validLicenses: "Valid licenses",
    blocked: "Blocked",
    installations: "Installations",
    activeUsers: "Active users",
    syncedSales: "Synced sales",
    observedStock: "Observed stock",
    total: "total",
    recentLicenses: "Recent licenses",
    recentLicensesSubtitle: "Client status and quotas",
    lastEvent: "Last event",
    noEvents: "No events",
    noSyncedEventsYet: "No synced events yet.",
    auditRecent: "Recent admin actions",
    createCompany: "Create company",
    createCompanySubtitle: "Create company, store, license and pairing code",
    company: "Company",
    taxId: "Tax ID",
    type: "Type",
    taxes: "Taxes",
    storeCode: "Store code",
    storeName: "Store name",
    validUntil: "Valid until",
    creating: "Creating",
    createLicense: "Create license",
    records: "records",
    linkedInstallations: "Linked installations",
    activePairingCode: "Active pairing code",
    expires: "expires",
    copy: "Copy",
    license: "License",
    status: "Status",
    validity: "Validity",
    quotas: "Quotas",
    valid: "Valid",
    blockedStatus: "Blocked",
    generateCode: "Generate code",
    generating: "Generating",
    block: "Block",
    unblock: "Unblock",
    noLicenses: "No licenses.",
    linkedAt: "Linked at",
    lastValidation: "Last validation",
    pending: "Pending",
    noLinkedInstallations: "No linked installations.",
    syncSubtitle: "Events sent from stores",
    consultingEvents: "Checking events",
    events: "Events",
    sales: "Sales",
    stock: "Stock",
    cash: "Cash",
    allCompanies: "All companies",
    demoHint: "Showing local examples because there are no real events for this filter.",
    noEventsForFilter: "No events for the current filter.",
    payload: "Payload",
    product: "Product",
    warehouse: "Warehouse",
    quantity: "Quantity",
    store: "Store",
    noStockForFilter: "No synchronized stock for the current filter.",
    newUser: "New user",
    availableRoles: "Available roles: ADMIN and VIEWER",
    viewerPermissionHint: "The viewer session can only read data. To create or deactivate users, sign out and sign in as admin.",
    role: "Role",
    createUser: "Create user",
    adminUsers: "Admin users",
    accounts: "accounts",
    created: "Created",
    active: "Active",
    inactive: "Inactive",
    deactivate: "Deactivate",
    adminAudit: "Admin audit",
    recentActions: "recent actions",
    noAuditActions: "No audit actions.",
    globalSearch: "Search company, license, tax ID or store",
    clearSearch: "Clear search",
    alerts: "Alerts",
    alertsSubtitle: "Operational risks worth reviewing",
    noAlerts: "No important alerts.",
    expiringSoon: "Licenses expiring soon",
    staleInstallations: "Installations without recent validation",
    expiringLicenseAlert: "License expiring soon",
    blockedLicenseAlert: "Blocked license",
    staleInstallationAlert: "Installation without recent validation",
    companyDetail: "Company profile",
    companyDetailSubtitle: "License, installations and synchronized activity",
    selectCompany: "Select a company to view details.",
    syncHealth: "Synchronization health",
    syncHealthSubtitle: "Activity received from stores",
    eventsToday: "Events today",
    salesEvents: "Sales",
    stockEvents: "Stock",
    cashEvents: "Cash",
    lastSync: "Last sync",
    viewDetail: "View profile",
    selected: "Selected",
    stores: "Stores",
    recentActivity: "Recent activity",
    noRecentActivity: "No recent activity.",
    licenseExpires: "Expires",
    withoutValidation: "Without validation",
    stale: "Delayed",
    phase2Operations: "SaaS management",
    phase2OperationsSubtitle: "License, billing and support",
    saveChanges: "Save changes",
    saving: "Saving",
    renewLicense: "Renew license",
    editCompany: "Edit company",
    plan: "Plan",
    billingStatus: "Billing status",
    renewalDate: "Renewal date",
    monthlyPrice: "Monthly price",
    supportStatus: "Support",
    contactName: "Contact",
    contactEmail: "Contact email",
    notes: "Notes",
    deviceDetails: "Technical detail",
    appVersion: "TPV version",
    operatingSystem: "System",
    terminalName: "Terminal",
    lastIp: "Last IP",
    notAvailable: "Pending",
    companyUpdated: "Company updated.",
    licenseRenewed: "License renewed.",
    operationsUpdated: "SaaS data updated.",
    notifications: "Notifications",
    notificationsSubtitle: "Internal alerts calculated from licenses, installations and billing",
    technicalPanel: "Technical status",
    technicalPanelSubtitle: "Operational pulse of the SaaS backend",
    supportTickets: "Support tickets",
    supportTicketsSubtitle: "Internal issues by company",
    newTicket: "New ticket",
    title: "Title",
    description: "Description",
    priority: "Priority",
    openTickets: "Open tickets",
    backendStatus: "SaaS backend",
    generatedAt: "Generated",
    createTicket: "Create ticket",
    ticketCreated: "Ticket created.",
    ticketUpdated: "Ticket updated.",
    noNotifications: "No internal notifications.",
    noTickets: "No tickets for this company.",
    allStatuses: "All statuses",
    allPriorities: "All priorities",
    comment: "Comment",
    addComment: "Add comment",
    markRead: "Mark read",
    notificationRead: "Notification marked as read.",
    commentAdded: "Comment added.",
    noComments: "No comments.",
    permissions: "Permissions",
    resolve: "Resolve",
    inProgress: "In progress",
    open: "Open",
    urgent: "Urgent",
    normal: "Normal",
    high: "High",
    technicalOk: "Operational",
    healthSubtitle: "Operational risk by company",
    healthScore: "Score",
    riskLevel: "Risk",
    riskOk: "OK",
    riskWarning: "Attention",
    riskDanger: "High risk",
    customersInRisk: "Customers at risk",
    inactiveCustomers: "No activity",
    noHealthData: "No health data.",
    healthSignals: "Signals",
    eventsLast7Days: "Events 7 days",
    urgentTickets: "Urgent tickets",
    lastEventAt: "Last event",
    lastValidationAt: "Last validation",
    stableOperation: "Stable operation",
    billingSubtitle: "Payments, renewals and estimated revenue",
    paidCompanies: "Paid",
    pendingBilling: "Pending",
    overdueBilling: "Overdue",
    renewalsNext30Days: "Renewals 30 days",
    monthlyRecurringRevenue: "Monthly revenue",
    billingPortfolio: "Billing portfolio",
    billingPortfolioSubtitle: "Companies ordered by collection urgency",
    noBillingData: "No billing data.",
    dueSoon: "Renewal soon",
    overdue: "Overdue",
    paid: "Paid"
  },
  zh: {
    administration: "管理",
    adminAccess: "管理员登录",
    mainNavigation: "主导航",
    language: "语言",
    dashboard: "概览",
    licensesCompanies: "许可证和公司",
    licenses: "许可证",
    sync: "同步",
    users: "用户",
    audit: "审计",
    logout: "退出",
    centralPanel: "控制面板",
    refresh: "刷新",
    refreshing: "刷新中",
    loadingSaas: "正在加载 SaaS 数据...",
    noLoadedData: "暂无已加载数据。",
    username: "用户",
    password: "密码",
    enter: "登录",
    validLicenses: "有效许可证",
    blocked: "已锁定",
    installations: "安装",
    activeUsers: "活跃用户",
    syncedSales: "已同步销售",
    observedStock: "库存概览",
    total: "合计",
    recentLicenses: "最近许可证",
    recentLicensesSubtitle: "客户状态和配额",
    lastEvent: "最新事件",
    noEvents: "无事件",
    noSyncedEventsYet: "暂无同步事件。",
    auditRecent: "最近管理操作",
    createCompany: "新增公司",
    createCompanySubtitle: "创建公司、门店、许可证和配对码",
    company: "公司",
    taxId: "税号",
    type: "类型",
    taxes: "税制",
    storeCode: "门店代码",
    storeName: "门店名称",
    validUntil: "有效期至",
    creating: "创建中",
    createLicense: "创建许可证",
    records: "条记录",
    linkedInstallations: "已绑定安装",
    activePairingCode: "当前配对码",
    expires: "过期",
    copy: "复制",
    license: "许可证",
    status: "状态",
    validity: "有效期",
    quotas: "配额",
    valid: "有效",
    blockedStatus: "已锁定",
    generateCode: "生成代码",
    generating: "生成中",
    block: "锁定",
    unblock: "解锁",
    noLicenses: "暂无许可证。",
    linkedAt: "绑定时间",
    lastValidation: "最后验证",
    pending: "待处理",
    noLinkedInstallations: "暂无已绑定安装。",
    syncSubtitle: "门店发送的事件",
    consultingEvents: "正在查询事件",
    events: "事件",
    sales: "销售",
    stock: "库存",
    cash: "收银",
    allCompanies: "全部公司",
    demoHint: "当前筛选没有真实事件，正在显示本地示例。",
    noEventsForFilter: "当前筛选暂无事件。",
    payload: "载荷",
    product: "商品",
    warehouse: "仓库",
    quantity: "数量",
    store: "门店",
    noStockForFilter: "当前筛选暂无同步库存。",
    newUser: "新用户",
    availableRoles: "可用角色：ADMIN 和 VIEWER",
    viewerPermissionHint: "viewer 会话只能查看数据。如需创建或停用用户，请退出并以 admin 登录。",
    role: "角色",
    createUser: "创建用户",
    adminUsers: "管理员用户",
    accounts: "个账户",
    created: "创建时间",
    active: "启用",
    inactive: "停用",
    deactivate: "停用",
    adminAudit: "管理审计",
    recentActions: "最近操作",
    noAuditActions: "暂无审计操作。"
  }
};

const I18nContext = createContext<{
  language: Language;
  setLanguage: (language: Language) => void;
  t: (key: string) => string;
}>({
  language: "es",
  setLanguage: () => undefined,
  t: (key) => TRANSLATIONS.es[key] ?? key
});

function useI18n() {
  return useContext(I18nContext);
}

const SAAS_ADMIN_ROLES: Array<{ value: SaasAdminRoleName; label: string; description: string }> = [
  { value: "ADMIN", label: "ADMIN", description: "Gestion completa del SaaS" },
  { value: "VIEWER", label: "VIEWER", description: "Solo consulta de datos admin" },
  { value: "SUPPORT", label: "SUPPORT", description: "Soporte tecnico y codigos de enlace" },
  { value: "BILLING", label: "BILLING", description: "Licencias, renovaciones y facturacion" },
  { value: "AUDITOR", label: "AUDITOR", description: "Solo auditoria y lectura" }
];

const initialCompanyForm: CreateCompanyRequest = {
  name: "",
  taxId: "",
  taxpayerType: "SOCIEDAD",
  impuestos: "IVA",
  storeCode: "",
  storeName: "",
  validUntil: toLocalInput(addYears(new Date(), 1)),
  maxWindows: 1,
  maxPda: 0
};

const DEMO_COMPANY_ID = "00000000-0000-4000-8000-000000000001";
const DEMO_STORE_ID = "00000000-0000-4000-8000-000000000002";

export default function App() {
  const [credentials, setCredentials] = useState<Credentials | null>(() => readCredentials());
  const [language, setLanguageState] = useState<Language>(() => readLanguage());
  const [activeView, setActiveView] = useState<View>("dashboard");
  const [data, setData] = useState<DashboardData | null>(null);
  const [tenantData, setTenantData] = useState<TenantPortalData | null>(null);
  const [session, setSession] = useState<AdminSession | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const i18n = useMemo(
    () => ({
      language,
      setLanguage: (nextLanguage: Language) => {
        localStorage.setItem("tpv-saas-language", nextLanguage);
        setLanguageState(nextLanguage);
      },
      t: (key: string) => TRANSLATIONS[language][key] ?? TRANSLATIONS.es[key] ?? key
    }),
    [language]
  );
  const visibleData = useMemo(() => (data ? filterDashboardData(data, searchQuery) : null), [data, searchQuery]);
  const permissions = useMemo(() => new Set(session?.permissions ?? fallbackPermissions(credentials?.username)), [session, credentials]);

  useEffect(() => {
    if (credentials) {
      void refresh(credentials);
    }
  }, [credentials]);

  async function refresh(activeCredentials = credentials) {
    if (!activeCredentials) return;
    setLoading(true);
    try {
      const [dashboard, nextSession] = await Promise.all([
        api.dashboard(activeCredentials),
        api.session(activeCredentials).catch((error) => {
          if (isMissingPhase3Endpoint(error)) return fallbackSession(activeCredentials.username);
          throw error;
        })
      ]);
      setData(dashboard);
      setTenantData(null);
      setSession(nextSession);
      setAuthMode("admin");
      setNotice(null);
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        try {
          const nextTenantData = await api.tenantPortal(activeCredentials);
          setTenantData(nextTenantData);
          setData(null);
          setSession(null);
          setAuthMode("tenant");
          setNotice(null);
          return;
        } catch (tenantError) {
          setNotice({ type: "error", text: errorMessage(tenantError) });
        }
        setCredentials(null);
        sessionStorage.removeItem("tpv-saas-credentials");
      } else {
        setNotice({ type: "error", text: errorMessage(error) });
      }
    } finally {
      setLoading(false);
    }
  }

  function login(nextCredentials: Credentials) {
    sessionStorage.setItem("tpv-saas-credentials", JSON.stringify(nextCredentials));
    setCredentials(nextCredentials);
  }

  function logout() {
    sessionStorage.removeItem("tpv-saas-credentials");
    setCredentials(null);
    setData(null);
    setTenantData(null);
    setSession(null);
    setAuthMode(null);
    setNotice(null);
  }

  if (!credentials) {
    return (
      <I18nContext.Provider value={i18n}>
        <LoginScreen onLogin={login} />
      </I18nContext.Provider>
    );
  }

  return (
    <I18nContext.Provider value={i18n}>
    {authMode === "tenant" ? (
      <TenantPortal
        credentials={credentials}
        data={tenantData}
        loading={loading}
        notice={notice}
        onRefresh={() => void refresh()}
        onLogout={logout}
        onNotice={setNotice}
      />
    ) : (
    <div className="app-shell">
      <header className="app-header" aria-label={i18n.t("mainNavigation")}>
        <div className="brand">
          <span className="brand-mark">TPV</span>
          <div>
            <strong>ERP SaaS</strong>
            <span>{i18n.t("administration")}</span>
          </div>
        </div>
        <nav className="nav-list top-nav-list">
          <NavButton active={activeView === "dashboard"} onClick={() => setActiveView("dashboard")} label={i18n.t("dashboard")} />
          <NavButton active={activeView === "licenses"} onClick={() => setActiveView("licenses")} label={i18n.t("licenses")} />
          <NavButton active={activeView === "sync"} onClick={() => setActiveView("sync")} label={i18n.t("sync")} />
          <NavButton active={activeView === "users"} onClick={() => setActiveView("users")} label={i18n.t("users")} />
          <NavButton active={activeView === "support"} onClick={() => setActiveView("support")} label={i18n.t("supportCenter")} />
          <NavButton active={activeView === "health"} onClick={() => setActiveView("health")} label={i18n.t("customerHealth")} />
          <NavButton active={activeView === "billing"} onClick={() => setActiveView("billing")} label={i18n.t("billing")} />
          <NavButton active={activeView === "masters"} onClick={() => setActiveView("masters")} label={i18n.t("masters")} />
          <NavButton active={activeView === "audit"} onClick={() => setActiveView("audit")} label={i18n.t("audit")} />
        </nav>
        <div className="app-actions" aria-label="Panel actions">
          <LanguageSelector variant="floating" />
          <button className="login-round-action" type="button" aria-label={i18n.t("logout")} onClick={logout}>
            <svg className="power-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M12 3v8" />
              <path d="M7.05 7.05a7 7 0 1 0 9.9 0" />
            </svg>
          </button>
        </div>
      </header>

      <main className="main-panel">
        <header className="topbar">
          <div>
            <p className="eyebrow">{i18n.t("centralPanel")}</p>
            <h1>{viewTitle(activeView, i18n.t)}</h1>
          </div>
          <button className="secondary-button" type="button" onClick={() => void refresh()} disabled={loading}>
            {loading ? i18n.t("refreshing") : i18n.t("refresh")}
          </button>
        </header>

        {data && (
          <div className="global-search" role="search">
            <input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder={i18n.t("globalSearch")}
              aria-label={i18n.t("globalSearch")}
            />
            {searchQuery && (
              <button className="small-button" type="button" onClick={() => setSearchQuery("")}>
                {i18n.t("clearSearch")}
              </button>
            )}
          </div>
        )}

        {notice && <div className={`notice ${notice.type}`}>{notice.text}</div>}

        {!visibleData ? (
          <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : (
          <>
            {activeView === "dashboard" && <Dashboard data={visibleData} />}
            {activeView === "licenses" && (
              <LicensesView
                credentials={credentials}
                licenses={visibleData.licenses}
                installations={visibleData.installations}
                events={visibleData.events}
                permissions={permissions}
                onChanged={() => void refresh()}
                onNotice={setNotice}
              />
            )}
            {activeView === "sync" && <SyncView credentials={credentials} licenses={visibleData.licenses} onNotice={setNotice} />}
            {activeView === "users" && (
              <UsersView
                credentials={credentials}
                users={visibleData.users}
                licenses={visibleData.licenses}
                permissions={permissions}
                onChanged={() => void refresh()}
                onNotice={setNotice}
              />
            )}
            {activeView === "support" && (
              <SupportView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "health" && (
              <CustomerHealthView credentials={credentials} licenses={visibleData.licenses} onNotice={setNotice} />
            )}
            {activeView === "billing" && (
              <BillingView credentials={credentials} licenses={visibleData.licenses} onNotice={setNotice} />
            )}
            {activeView === "masters" && (
              <MastersView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "audit" && <AuditView audit={visibleData.audit} />}
          </>
        )}
      </main>
    </div>
    )}
    </I18nContext.Provider>
  );
}

function LoginScreen({ onLogin }: { onLogin: (credentials: Credentials) => void }) {
  const { t } = useI18n();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onLogin({ username: username.trim(), password });
  }

  return (
    <main className="login-page">
      <div className="login-actions" aria-label="Login actions">
        <LanguageSelector variant="floating" />
        <button className="login-round-action" type="button" aria-label={t("logout")} onClick={() => {
          setUsername("");
          setPassword("");
        }}>
          <svg className="power-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M12 3v8" />
            <path d="M7.05 7.05a7 7 0 1 0 9.9 0" />
          </svg>
        </button>
      </div>

      <header className="login-heading">
        <h1>Tienda Principal</h1>
        <p>Terminal: 01</p>
      </header>

      <section className="login-panel" aria-label={t("adminAccess")}>
        <form className="stack-form" onSubmit={submit}>
          <label>
            {t("username")}
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              placeholder={t("username")}
              required
            />
          </label>
          <label>
            {t("password")}
            <input
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              type="password"
              autoComplete="current-password"
              placeholder={t("password")}
              required
            />
          </label>
          <button className="primary-button" type="submit">
            {t("enter")}
          </button>
        </form>
      </section>
    </main>
  );
}

function TenantPortal({
  credentials,
  data,
  loading,
  notice,
  onRefresh,
  onLogout,
  onNotice
}: {
  credentials: Credentials;
  data: TenantPortalData | null;
  loading: boolean;
  notice: Notice;
  onRefresh: () => void;
  onLogout: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState("NORMAL");
  const [busy, setBusy] = useState(false);

  async function submitTicket(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await api.createTenantTicket(credentials, { title, description, priority });
      setTitle("");
      setDescription("");
      setPriority("NORMAL");
      onNotice({ type: "success", text: t("supportRequestCreated") });
      onRefresh();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell tenant-shell">
      <header className="app-header" aria-label={t("clientPortal")}>
        <div className="brand">
          <span className="brand-mark">TPV</span>
          <div>
            <strong>{data?.session.companyName ?? "ERP SaaS"}</strong>
            <span>{t("clientPortal")}</span>
          </div>
        </div>
        <nav className="nav-list top-nav-list tenant-top-nav">
          <span>{t("myCompany")}</span>
          <span>{t("myLicenses")}</span>
          <span>{t("myMasters")}</span>
          <span>{t("mySupport")}</span>
        </nav>
        <div className="app-actions" aria-label="Tenant actions">
          <LanguageSelector variant="floating" />
          <button className="login-round-action" type="button" aria-label={t("logout")} onClick={onLogout}>
            <svg className="power-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M12 3v8" />
              <path d="M7.05 7.05a7 7 0 1 0 9.9 0" />
            </svg>
          </button>
        </div>
      </header>

      <main className="main-panel tenant-main">
        <header className="tenant-hero">
          <p className="eyebrow">{t("clientPortal")}</p>
          <h1>{data?.session.companyName ?? t("myCompany")}</h1>
          <p>{t("tenantWelcome")}</p>
          <button className="secondary-button" type="button" onClick={onRefresh} disabled={loading}>
            {loading ? t("refreshing") : t("refresh")}
          </button>
        </header>

        {notice && <div className={`notice ${notice.type}`}>{notice.text}</div>}

        {!data ? (
          <EmptyState text={loading ? t("loadingSaas") : t("noLoadedData")} />
        ) : (
          <div className="view-grid tenant-view">
            <section className="metric-grid tenant-metrics">
              <Metric label={t("licenses")} value={data.dashboard.licenses} />
              <Metric label={t("stores")} value={data.dashboard.stores} />
              <Metric label={t("installations")} value={data.dashboard.installations} />
              <Metric label={t("openTickets")} value={data.dashboard.openTickets} />
              <Metric label={t("billingStatus")} value={data.dashboard.billingStatus} />
              <Metric label={t("monthlyPrice")} value={data.dashboard.monthlyPrice ?? "-"} detail={data.dashboard.renewalDate ? `${t("renewalDate")}: ${formatDate(data.dashboard.renewalDate)}` : undefined} />
            </section>

            <section className="content-section">
              <SectionHeader title={t("myLicenses")} subtitle={`${data.licenses.length} ${t("records")}`} />
              <LicenseTable licenses={data.licenses} compact />
            </section>

            <section className="content-section">
              <SectionHeader title={t("invoices")} subtitle={`${data.invoices.length} ${t("records")}`} />
              <InvoiceTable invoices={data.invoices} />
            </section>

            <section className="content-section">
              <SectionHeader title={t("myMasters")} subtitle={t("erpMastersSubtitle")} />
              <div className="tenant-master-grid">
                <div>
                  <h3>{t("customers")}</h3>
                  <MasterTable mode="customers" customers={data.customers} products={[]} suppliers={[]} warehouses={[]} />
                </div>
                <div>
                  <h3>{t("products")}</h3>
                  <MasterTable mode="products" customers={[]} products={data.products} suppliers={[]} warehouses={[]} />
                </div>
                <div>
                  <h3>{t("suppliers")}</h3>
                  <MasterTable mode="suppliers" customers={[]} products={[]} suppliers={data.suppliers} warehouses={[]} />
                </div>
                <div>
                  <h3>{t("warehouses")}</h3>
                  <MasterTable mode="warehouses" customers={[]} products={[]} suppliers={[]} warehouses={data.warehouses} />
                </div>
              </div>
            </section>

            <section className="content-section two-column tenant-two-column">
              <div>
                <SectionHeader title={t("myStores")} subtitle={`${data.stores.length} ${t("records")}`} />
                <div className="tenant-store-list">
                  {data.stores.map((store) => (
                    <div className="tenant-store" key={store.storeId}>
                      <strong>{store.name}</strong>
                      <span>{store.code}</span>
                      <small>{formatDate(store.createdAt)}</small>
                    </div>
                  ))}
                  {data.stores.length === 0 && <EmptyState text={t("noLoadedData")} />}
                </div>
              </div>
              <div>
                <SectionHeader title={t("mySupport")} subtitle={`${data.tickets.length} ${t("records")}`} />
                <form className="ticket-form tenant-ticket-form" onSubmit={submitTicket}>
                  <label>
                    {t("title")}
                    <input value={title} onChange={(event) => setTitle(event.target.value)} required />
                  </label>
                  <label>
                    {t("priority")}
                    <select value={priority} onChange={(event) => setPriority(event.target.value)}>
                      <option value="NORMAL">{t("normal")}</option>
                      <option value="ALTA">{t("high")}</option>
                      <option value="URGENTE">{t("urgent")}</option>
                    </select>
                  </label>
                  <label className="wide-field">
                    {t("description")}
                    <textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={4} />
                  </label>
                  <button className="primary-button" type="submit" disabled={busy}>
                    {busy ? t("saving") : t("createSupportRequest")}
                  </button>
                </form>
                <TenantTicketList tickets={data.tickets.slice(0, 5)} />
              </div>
            </section>
          </div>
        )}
      </main>
    </div>
  );
}

function TenantTicketList({ tickets }: { tickets: SupportTicket[] }) {
  const { t } = useI18n();
  if (tickets.length === 0) return <EmptyState text={t("noTenantTickets")} />;
  return (
    <div className="ticket-list tenant-ticket-list">
      {tickets.map((ticket) => (
        <article className="ticket-card" key={ticket.id}>
          <div className="ticket-main">
            <div>
              <strong>{ticket.title}</strong>
              <span>{formatDate(ticket.createdAt)}</span>
            </div>
            <div className="ticket-badges">
              <StatusPill status={ticketStatusLabel(ticket.status, t)} tone={ticket.status === "RESUELTO" ? "ok" : "warning"} />
              <StatusPill status={ticketPriorityLabel(ticket.priority, t)} tone={ticket.priority === "URGENTE" ? "warning" : "muted"} />
            </div>
          </div>
          {ticket.description && <p>{ticket.description}</p>}
        </article>
      ))}
    </div>
  );
}

function InvoiceTable({ invoices }: { invoices: BillingInvoice[] }) {
  const { t } = useI18n();
  if (invoices.length === 0) return <EmptyState text={t("noBillingData")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("invoiceNumber")}</th>
            <th>{t("concept")}</th>
            <th>{t("amount")}</th>
            <th>{t("paidAmount")}</th>
            <th>{t("status")}</th>
            <th>{t("dueAt")}</th>
          </tr>
        </thead>
        <tbody>
          {invoices.map((invoice) => (
            <tr key={invoice.id}>
              <td>
                <strong>{invoice.number}</strong>
                <small>{formatDate(invoice.issuedAt)}</small>
              </td>
              <td>{invoice.concept}</td>
              <td>{formatMoney(invoice.amount)} {invoice.currency}</td>
              <td>{formatMoney(invoice.paidAmount)} {invoice.currency}</td>
              <td>
                <StatusPill status={billingStatusLabel(invoice.status, t)} tone={invoice.status === "PAGADA" ? "ok" : "warning"} />
              </td>
              <td>{formatDate(invoice.dueAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Dashboard({ data }: { data: DashboardData }) {
  const { t } = useI18n();
  const activeLicenses = data.licenses.filter((license) => license.status === "VALIDA").length;
  const blockedLicenses = data.licenses.filter((license) => license.status === "BLOQUEADA_MANUAL").length;
  const activeUsers = data.users.filter((user) => user.active).length;
  const lastEvent = data.events[0];
  const alerts = operationalAlerts(data, t);

  return (
    <div className="view-grid">
      <section className="metric-grid">
        <Metric label={t("validLicenses")} value={activeLicenses} />
        <Metric label={t("blocked")} value={blockedLicenses} tone="warning" />
        <Metric label={t("installations")} value={data.installations.length} />
        <Metric label={t("activeUsers")} value={activeUsers} />
        <Metric label={t("syncedSales")} value={data.salesSummary.documentCount} detail={`${data.salesSummary.total} ${t("total")}`} />
        <Metric label={t("observedStock")} value={data.stockCurrent.length} />
      </section>

      <section className="content-section">
        <SectionHeader title={t("alerts")} subtitle={t("alertsSubtitle")} />
        <AlertList alerts={alerts} />
      </section>

      <section className="content-section">
        <SectionHeader title={t("recentLicenses")} subtitle={t("recentLicensesSubtitle")} />
        <LicenseTable licenses={data.licenses.slice(0, 8)} compact />
      </section>

      <section className="content-section two-column">
        <div>
          <SectionHeader title={t("lastEvent")} subtitle={lastEvent ? formatDate(lastEvent.receivedAt) : t("noEvents")} />
          {lastEvent ? <EventLine event={lastEvent} /> : <EmptyState text={t("noSyncedEventsYet")} />}
        </div>
        <div>
          <SectionHeader title={t("audit")} subtitle={t("auditRecent")} />
          <AuditList audit={data.audit.slice(0, 5)} />
        </div>
      </section>
    </div>
  );
}

function LicensesView({
  credentials,
  licenses,
  installations,
  events,
  permissions,
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  installations: InstallationSummary[];
  events: SyncEventView[];
  permissions: Set<string>;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [companyForm, setCompanyForm] = useState<CreateCompanyRequest>(initialCompanyForm);
  const [pairingCode, setPairingCode] = useState<PairingCodeResponse | null>(null);
  const [tenantAccess, setTenantAccess] = useState<{ username: string; password: string } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [selectedCompanyId, setSelectedCompanyId] = useState<string>(() => licenses[0]?.companyId ?? "");
  const canCreateCompany = permissions.has("ADD_COMPANY");
  const canManageCompany = permissions.has("EDIT_COMPANY_DATA") || permissions.has("RENEW_LICENSE");
  const canGenerateCode = permissions.has("REGENERATE_PAIRING_CODE");
  const canChangeLicenseStatus = permissions.has("BLOCK_LICENSE") || permissions.has("UNBLOCK_LICENSE");
  const selectedCompany = licenses.find((license) => license.companyId === selectedCompanyId) ?? licenses[0] ?? null;

  useEffect(() => {
    if (licenses.length > 0 && !licenses.some((license) => license.companyId === selectedCompanyId)) {
      setSelectedCompanyId(licenses[0].companyId);
    }
  }, [licenses, selectedCompanyId]);

  async function createCompany(event: FormEvent) {
    event.preventDefault();
    setBusy("create");
    try {
      const response = await api.createCompany(credentials, {
        ...companyForm,
        validUntil: new Date(companyForm.validUntil).toISOString()
      });
      setPairingCode({
        licenseReference: response.licenseReference,
        pairingCode: response.pairingCode,
        expiresAt: addDays(new Date(), 7).toISOString()
      });
      setTenantAccess({ username: response.tenantUsername, password: response.tenantInitialPassword });
      setCompanyForm(initialCompanyForm);
      onNotice({ type: "success", text: `Licencia ${response.licenseReference} creada.` });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function licenseAction(reference: string, action: LicenseAction) {
    setBusy(`${action}:${reference}`);
    try {
      if (action === "block") {
        await api.blockLicense(credentials, reference);
        onNotice({ type: "success", text: `Licencia ${reference} bloqueada.` });
      }
      if (action === "unblock") {
        await api.unblockLicense(credentials, reference);
        onNotice({ type: "success", text: `Licencia ${reference} desbloqueada.` });
      }
      if (action === "pairing") {
        const code = await api.regeneratePairingCode(credentials, reference);
        setPairingCode(code);
        onNotice({ type: "success", text: `Codigo ${code.pairingCode} generado para ${reference}.` });
      }
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function copyPairingCode() {
    if (!pairingCode) return;
    try {
      await copyText(pairingCode.pairingCode);
      onNotice({ type: "success", text: `Codigo ${pairingCode.pairingCode} copiado.` });
    } catch {
      onNotice({ type: "error", text: "No se pudo copiar automaticamente. Selecciona el codigo manualmente." });
    }
  }

  return (
    <div className="view-grid">
      {canCreateCompany && (
        <section className="content-section">
          <SectionHeader title={t("createCompany")} subtitle={t("createCompanySubtitle")} />
          <form className="form-grid" onSubmit={createCompany}>
            <Input label={t("company")} value={companyForm.name} onChange={(name) => setCompanyForm({ ...companyForm, name })} required />
            <Input label={t("taxId")} value={companyForm.taxId} onChange={(taxId) => setCompanyForm({ ...companyForm, taxId })} required />
            <Select
              label={t("type")}
              value={companyForm.taxpayerType}
              options={["SOCIEDAD", "AUTONOMO"]}
              onChange={(taxpayerType) => setCompanyForm({ ...companyForm, taxpayerType: taxpayerType as TaxpayerType })}
            />
            <Select
              label={t("taxes")}
              value={companyForm.impuestos}
              options={["IVA", "IGIC"]}
              onChange={(impuestos) => setCompanyForm({ ...companyForm, impuestos: impuestos as TaxRegime })}
            />
            <Input
              label={t("storeCode")}
              value={companyForm.storeCode}
              onChange={(storeCode) => setCompanyForm({ ...companyForm, storeCode })}
              required
            />
            <Input label={t("storeName")} value={companyForm.storeName} onChange={(storeName) => setCompanyForm({ ...companyForm, storeName })} />
            <Input
              label={t("validUntil")}
              type="datetime-local"
              value={companyForm.validUntil}
              onChange={(validUntil) => setCompanyForm({ ...companyForm, validUntil })}
              required
            />
            <Input
              label="Windows"
              type="number"
              value={String(companyForm.maxWindows)}
              min={1}
              onChange={(value) => setCompanyForm({ ...companyForm, maxWindows: Number(value) })}
              required
            />
            <Input
              label="PDA"
              type="number"
              value={String(companyForm.maxPda)}
              min={0}
              onChange={(value) => setCompanyForm({ ...companyForm, maxPda: Number(value) })}
              required
            />
            <div className="form-actions">
              <button className="primary-button" type="submit" disabled={busy === "create"}>
                {busy === "create" ? t("creating") : t("createLicense")}
              </button>
            </div>
          </form>
        </section>
      )}

      <section className="content-section">
        <SectionHeader title={t("licenses")} subtitle={`${licenses.length} ${t("records")}`} />
        {pairingCode && <PairingCodePanel pairingCode={pairingCode} onCopy={() => void copyPairingCode()} />}
        {tenantAccess && <TenantAccessPanel access={tenantAccess} />}
        <LicenseTable
          licenses={licenses}
          onAction={(reference, action) => void licenseAction(reference, action)}
          busy={busy}
          showStatusActions={canChangeLicenseStatus}
          showPairingAction={canGenerateCode}
          selectedCompanyId={selectedCompany?.companyId}
          onSelectCompany={setSelectedCompanyId}
        />
      </section>

      <CompanyDetail
        credentials={credentials}
        license={selectedCompany}
        installations={installations.filter((installation) => installation.companyId === selectedCompany?.companyId)}
        events={events.filter((event) => event.companyId === selectedCompany?.companyId)}
        canManage={canManageCompany}
        onChanged={onChanged}
        onNotice={onNotice}
      />

      <section className="content-section">
        <SectionHeader title={t("linkedInstallations")} subtitle={`${installations.length} ${t("installations").toLowerCase()}`} />
        <InstallationsTable installations={installations} />
      </section>
    </div>
  );
}

function SyncView({ credentials, licenses, onNotice }: { credentials: Credentials; licenses: LicenseSummary[]; onNotice: (notice: Notice) => void }) {
  const { t } = useI18n();
  const [mode, setMode] = useState<"events" | "sales" | "stock" | "cash">("events");
  const [companyId, setCompanyId] = useState("");
  const [events, setEvents] = useState<SyncEventView[]>([]);
  const [stock, setStock] = useState<StockSnapshot[]>([]);
  const [loading, setLoading] = useState(false);

  const companyOptions = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const sampleCompanies = companyOptions.length > 0 ? companyOptions : [{ companyId: DEMO_COMPANY_ID, companyName: "Empresa demo" }];
  const displayEvents = events.length > 0 ? events : sampleSyncEvents(mode, sampleCompanies, companyId);
  const displayStock = stock.length > 0 ? stock : sampleStock(sampleCompanies, companyId);
  const showingSamples = mode === "stock" ? stock.length === 0 : events.length === 0;
  const healthEvents = mode === "stock" ? sampleSyncEvents("events", sampleCompanies, companyId) : displayEvents;
  const lastReceivedAt = latestDate(healthEvents.map((event) => event.receivedAt));

  useEffect(() => {
    void load();
  }, [mode, companyId]);

  async function load() {
    setLoading(true);
    try {
      if (mode === "events") setEvents(await api.events(credentials, companyId || undefined));
      if (mode === "sales") setEvents(await api.sales(credentials, companyId || undefined));
      if (mode === "stock") setStock(await api.stockCurrent(credentials, companyId || undefined));
      if (mode === "cash") setEvents(await api.cashClosures(credentials, companyId || undefined));
      onNotice(null);
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader title={t("sync")} subtitle={loading ? t("consultingEvents") : t("syncSubtitle")} />
      <div className="sync-health-grid">
        <Metric label={t("eventsToday")} value={healthEvents.filter((event) => isToday(event.receivedAt)).length} />
        <Metric label={t("salesEvents")} value={healthEvents.filter((event) => event.entityType === "DOCUMENTO").length} />
        <Metric label={t("stockEvents")} value={mode === "stock" ? displayStock.length : healthEvents.filter((event) => event.entityType === "STOCK_MOVEMENT").length} />
        <Metric label={t("cashEvents")} value={healthEvents.filter((event) => event.entityType === "CIERRE_CAJA").length} />
        <Metric label={t("lastSync")} value={lastReceivedAt ? formatDate(lastReceivedAt) : "-"} />
      </div>
      <div className="toolbar">
        <Segmented
          value={mode}
          options={[
            ["events", t("events")],
            ["sales", t("sales")],
            ["stock", t("stock")],
            ["cash", t("cash")]
          ]}
          onChange={(value) => setMode(value as "events" | "sales" | "stock" | "cash")}
        />
        <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
          <option value="">{t("allCompanies")}</option>
          {companyOptions.map((company) => (
            <option key={company.companyId} value={company.companyId}>
              {company.companyName}
            </option>
          ))}
        </select>
      </div>
      {showingSamples && <div className="demo-hint">{t("demoHint")}</div>}
      {mode === "stock" ? <StockTable rows={displayStock} /> : <EventsTable events={displayEvents} />}
    </section>
  );
}

function UsersView({
  credentials,
  users,
  licenses,
  permissions,
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  users: AdminUser[];
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const companyOptions = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [roleName, setRoleName] = useState<SaasAdminRoleName>("ADMIN");
  const [tenantCompanyId, setTenantCompanyId] = useState("");
  const [tenantUsers, setTenantUsers] = useState<TenantUser[]>([]);
  const [tenantUsername, setTenantUsername] = useState("");
  const [tenantPassword, setTenantPassword] = useState("");
  const [tenantRoleName, setTenantRoleName] = useState("MANAGER");
  const [tenantPasswordByUser, setTenantPasswordByUser] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const canManageUsers = permissions.has("MANAGE_ADMIN_USERS");
  const canManageTenantUsers = permissions.has("MANAGE_TENANT_USERS");

  useEffect(() => {
    if (!tenantCompanyId && companyOptions[0]) {
      setTenantCompanyId(companyOptions[0].companyId);
    }
  }, [companyOptions, tenantCompanyId]);

  useEffect(() => {
    if (!tenantCompanyId) {
      setTenantUsers([]);
      return;
    }
    void loadTenantUsers(tenantCompanyId);
  }, [tenantCompanyId]);

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy("create-user");
    try {
      await api.createUser(credentials, { username, password, roleName });
      setUsername("");
      setPassword("");
      onNotice({ type: "success", text: `Usuario ${username} creado.` });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function deactivate(user: string) {
    setBusy(user);
    try {
      await api.deactivateUser(credentials, user);
      onNotice({ type: "success", text: `Usuario ${user} desactivado.` });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function loadTenantUsers(companyId: string) {
    try {
      const response = await api.tenantUsers(credentials, companyId);
      setTenantUsers(response);
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function createTenantUser(event: FormEvent) {
    event.preventDefault();
    if (!tenantCompanyId) return;
    setBusy("create-tenant-user");
    try {
      await api.createTenantUser(credentials, tenantCompanyId, {
        username: tenantUsername,
        password: tenantPassword,
        roleName: tenantRoleName
      });
      setTenantUsername("");
      setTenantPassword("");
      onNotice({ type: "success", text: t("tenantUserCreated") });
      await loadTenantUsers(tenantCompanyId);
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function changeTenantPassword(user: string) {
    const nextPassword = tenantPasswordByUser[user]?.trim();
    if (!nextPassword) return;
    setBusy(`tenant-password-${user}`);
    try {
      await api.changeTenantPassword(credentials, user, nextPassword);
      setTenantPasswordByUser((current) => ({ ...current, [user]: "" }));
      onNotice({ type: "success", text: t("tenantUserUpdated") });
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function deactivateTenantUser(user: string) {
    setBusy(`tenant-disable-${user}`);
    try {
      await api.deactivateTenantUser(credentials, user);
      onNotice({ type: "success", text: t("tenantUserDisabled") });
      if (tenantCompanyId) {
        await loadTenantUsers(tenantCompanyId);
      }
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="view-grid">
      <section className="content-section">
        <SectionHeader title={t("newUser")} subtitle={t("availableRoles")} />
        {!canManageUsers && (
          <div className="permission-hint">
            {t("viewerPermissionHint")}
          </div>
        )}
        {canManageUsers && (
          <form className="form-grid three" onSubmit={create}>
            <Input label={t("username")} value={username} onChange={setUsername} required />
            <Input label={t("password")} type="password" value={password} onChange={setPassword} required />
            <label>
              {t("role")}
              <select
                className="control-input"
                value={roleName}
                onChange={(event) => setRoleName(event.target.value as SaasAdminRoleName)}
              >
                {SAAS_ADMIN_ROLES.map((role) => (
                  <option key={role.value} value={role.value}>
                    {role.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="form-actions">
              <button className="primary-button" type="submit" disabled={busy === "create-user"}>
                {t("createUser")}
              </button>
            </div>
          </form>
        )}
      </section>
      <section className="content-section">
        <SectionHeader title={t("adminUsers")} subtitle={`${users.length} ${t("accounts")}`} />
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("username")}</th>
                <th>{t("status")}</th>
                <th>{t("created")}</th>
                {canManageUsers && <th></th>}
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.username}>
                  <td>{user.username}</td>
                  <td>
                    <StatusPill status={user.active ? t("active") : t("inactive")} tone={user.active ? "ok" : "muted"} />
                  </td>
                  <td>{formatDate(user.createdAt)}</td>
                  {canManageUsers && (
                    <td className="row-actions">
                      <button
                        className="small-button"
                        type="button"
                        disabled={!user.active || busy === user.username}
                        onClick={() => void deactivate(user.username)}
                      >
                        {t("deactivate")}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="content-section">
        <SectionHeader title={t("tenantUsers")} subtitle={t("tenantAccess")} />
        <div className="toolbar">
          <label className="toolbar-field">
            {t("company")}
            <select className="control-input" value={tenantCompanyId} onChange={(event) => setTenantCompanyId(event.target.value)}>
              {companyOptions.map((company) => (
                <option key={company.companyId} value={company.companyId}>
                  {company.companyName}
                </option>
              ))}
            </select>
          </label>
        </div>
        {canManageTenantUsers && (
          <form className="form-grid four compact-form" onSubmit={createTenantUser}>
            <Input label={t("username")} value={tenantUsername} onChange={setTenantUsername} required disabled={!tenantCompanyId} />
            <Input label={t("password")} type="password" value={tenantPassword} onChange={setTenantPassword} required disabled={!tenantCompanyId} />
            <label>
              {t("role")}
              <select
                className="control-input"
                value={tenantRoleName}
                onChange={(event) => setTenantRoleName(event.target.value)}
                disabled={!tenantCompanyId}
              >
                <option value="OWNER">OWNER</option>
                <option value="MANAGER">MANAGER</option>
                <option value="VIEWER">VIEWER</option>
              </select>
            </label>
            <div className="form-actions">
              <button className="primary-button" type="submit" disabled={!tenantCompanyId || busy === "create-tenant-user"}>
                {t("createTenantUser")}
              </button>
            </div>
          </form>
        )}
        {tenantUsers.length === 0 ? (
          <EmptyState text={t("noTenantUsers")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("username")}</th>
                  <th>{t("role")}</th>
                  <th>{t("status")}</th>
                  <th>{t("created")}</th>
                  {canManageTenantUsers && <th></th>}
                </tr>
              </thead>
              <tbody>
                {tenantUsers.map((user) => (
                  <tr key={user.username}>
                    <td>{user.username}</td>
                    <td>{user.roleName}</td>
                    <td>
                      <StatusPill status={user.active ? t("active") : t("inactive")} tone={user.active ? "ok" : "muted"} />
                    </td>
                    <td>{formatDate(user.createdAt)}</td>
                    {canManageTenantUsers && (
                      <td className="row-actions tenant-user-actions">
                        <input
                          className="control-input inline-password"
                          type="password"
                          value={tenantPasswordByUser[user.username] ?? ""}
                          placeholder={t("newPassword")}
                          disabled={!user.active}
                          onChange={(event) => setTenantPasswordByUser((current) => ({ ...current, [user.username]: event.target.value }))}
                        />
                        <button
                          className="small-button"
                          type="button"
                          disabled={!user.active || !tenantPasswordByUser[user.username]?.trim() || busy === `tenant-password-${user.username}`}
                          onClick={() => void changeTenantPassword(user.username)}
                        >
                          {t("changePassword")}
                        </button>
                        <button
                          className="small-button danger"
                          type="button"
                          disabled={!user.active || busy === `tenant-disable-${user.username}`}
                          onClick={() => void deactivateTenantUser(user.username)}
                        >
                          {t("deactivate")}
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function AuditView({ audit }: { audit: AuditLog[] }) {
  const { t } = useI18n();
  return (
    <section className="content-section">
      <SectionHeader title={t("adminAudit")} subtitle={`${audit.length} ${t("recentActions")}`} />
      <AuditList audit={audit} expanded />
    </section>
  );
}

function CustomerHealthView({
  credentials,
  licenses,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const visibleCompanyIds = useMemo(() => new Set(licenses.map((license) => license.companyId)), [licenses]);
  const [health, setHealth] = useState<CustomerHealth[]>([]);
  const [selectedCompanyId, setSelectedCompanyId] = useState("");
  const visibleHealth = health.filter((item) => visibleCompanyIds.size === 0 || visibleCompanyIds.has(item.companyId));
  const selected = visibleHealth.find((item) => item.companyId === selectedCompanyId) ?? visibleHealth[0] ?? null;
  const riskCount = visibleHealth.filter((item) => item.riskLevel === "DANGER").length;
  const warningCount = visibleHealth.filter((item) => item.riskLevel === "WARNING").length;
  const inactiveCount = visibleHealth.filter((item) => item.eventsLast7Days === 0).length;

  useEffect(() => {
    void loadHealth();
  }, [credentials.username]);

  useEffect(() => {
    if (visibleHealth.length > 0 && !visibleHealth.some((item) => item.companyId === selectedCompanyId)) {
      setSelectedCompanyId(visibleHealth[0].companyId);
    }
  }, [visibleHealth, selectedCompanyId]);

  async function loadHealth() {
    try {
      setHealth(await api.customerHealth(credentials));
      onNotice(null);
    } catch (error) {
      if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) {
        setHealth(fallbackHealth(licenses));
        onNotice(null);
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  return (
    <div className="view-grid">
      <section className="metric-grid">
        <Metric label={t("customersInRisk")} value={riskCount} tone={riskCount > 0 ? "warning" : undefined} />
        <Metric label={t("riskWarning")} value={warningCount} tone={warningCount > 0 ? "warning" : undefined} />
        <Metric label={t("inactiveCustomers")} value={inactiveCount} tone={inactiveCount > 0 ? "warning" : undefined} />
        <Metric label={t("company")} value={visibleHealth.length} />
      </section>

      <section className="content-section health-board">
        <SectionHeader title={t("customerHealth")} subtitle={t("healthSubtitle")} />
        {visibleHealth.length === 0 ? (
          <EmptyState text={t("noHealthData")} />
        ) : (
          <div className="health-layout">
            <div className="health-list">
              {visibleHealth
                .slice()
                .sort((left, right) => left.score - right.score)
                .map((item) => (
                  <button
                    className={`health-card ${item.riskLevel.toLowerCase()} ${selected?.companyId === item.companyId ? "active" : ""}`}
                    type="button"
                    key={item.companyId}
                    onClick={() => setSelectedCompanyId(item.companyId)}
                  >
                    <span>{item.companyName}</span>
                    <strong>{item.score}</strong>
                    <small>{riskLabel(item.riskLevel, t)} - {item.billingStatus}</small>
                  </button>
                ))}
            </div>

            {selected && (
              <article className={`health-detail ${selected.riskLevel.toLowerCase()}`}>
                <div className="health-detail-header">
                  <div>
                    <span>{selected.taxId}</span>
                    <h2>{selected.companyName}</h2>
                  </div>
                  <StatusPill status={riskLabel(selected.riskLevel, t)} tone={selected.riskLevel === "OK" ? "ok" : "warning"} />
                </div>

                <div className="health-score">
                  <strong>{selected.score}</strong>
                  <span>{t("healthScore")}</span>
                </div>

                <div className="health-facts">
                  <Metric label={t("plan")} value={selected.planName} detail={selected.billingStatus} />
                  <Metric label={t("license")} value={selected.licenseStatus} detail={selected.validUntil ? formatDate(selected.validUntil) : t("pending")} />
                  <Metric label={t("eventsLast7Days")} value={selected.eventsLast7Days} detail={selected.lastEventAt ? `${t("lastEventAt")}: ${formatDate(selected.lastEventAt)}` : t("noEvents")} />
                  <Metric label={t("installations")} value={selected.installations} detail={`${t("staleInstallations")}: ${selected.staleInstallations}`} />
                  <Metric label={t("openTickets")} value={selected.openTickets} detail={`${t("urgentTickets")}: ${selected.urgentTickets}`} />
                  <Metric label={t("lastValidationAt")} value={selected.lastValidationAt ? formatDate(selected.lastValidationAt) : t("pending")} />
                </div>

                <div className="health-signals">
                  <strong>{t("healthSignals")}</strong>
                  <div>
                    {selected.signals.map((signal) => (
                      <span key={signal}>{signal}</span>
                    ))}
                  </div>
                </div>
              </article>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

function BillingView({
  credentials,
  licenses,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const visibleCompanyIds = useMemo(() => new Set(licenses.map((license) => license.companyId)), [licenses]);
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [selectedCompanyId, setSelectedCompanyId] = useState("");
  const [invoices, setInvoices] = useState<BillingInvoice[]>([]);
  const [invoiceForm, setInvoiceForm] = useState({
    number: "",
    concept: "",
    amount: "",
    currency: "EUR",
    issuedAt: toLocalInput(new Date()),
    dueAt: toLocalInput(addDays(new Date(), 30))
  });
  const [paymentForm, setPaymentForm] = useState({ invoiceId: "", amount: "", method: "TRANSFERENCIA", reference: "" });
  const [busy, setBusy] = useState<string | null>(null);
  const visibleCompanies = (summary?.companies ?? []).filter((company) => visibleCompanyIds.size === 0 || visibleCompanyIds.has(company.companyId));
  const orderedCompanies = visibleCompanies.slice().sort((left, right) => Number(right.overdue) - Number(left.overdue) || Number(right.renewalDueSoon) - Number(left.renewalDueSoon) || left.companyName.localeCompare(right.companyName));
  const localSummary = summary
    ? {
        ...summary,
        totalCompanies: visibleCompanies.length,
        paidCompanies: visibleCompanies.filter((company) => company.billingStatus === "PAGADO").length,
        pendingCompanies: visibleCompanies.filter((company) => ["PENDIENTE", "VENCIDO", "IMPAGADO"].includes(company.billingStatus)).length,
        overdueCompanies: visibleCompanies.filter((company) => company.overdue).length,
        renewalsNext30Days: visibleCompanies.filter((company) => company.renewalDueSoon).length,
        monthlyRecurringRevenue: visibleCompanies.reduce((total, company) => total + parseAmount(company.monthlyPrice), 0).toFixed(2)
      }
    : null;

  useEffect(() => {
    void loadBilling();
  }, [credentials.username]);

  useEffect(() => {
    if (!selectedCompanyId && orderedCompanies[0]) {
      setSelectedCompanyId(orderedCompanies[0].companyId);
    }
  }, [orderedCompanies, selectedCompanyId]);

  useEffect(() => {
    if (selectedCompanyId) {
      void loadInvoices(selectedCompanyId);
    }
  }, [selectedCompanyId]);

  async function loadBilling() {
    try {
      setSummary(await api.billingSummary(credentials));
    } catch (error) {
      if (isMissingPhase3Endpoint(error)) {
        setSummary(fallbackBilling(licenses));
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function loadInvoices(companyId: string) {
    try {
      setInvoices(await api.billingInvoices(credentials, companyId));
      onNotice(null);
    } catch (error) {
      if (error instanceof ApiError && error.status >= 500) {
        setInvoices([]);
        onNotice(null);
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function createInvoice(event: FormEvent) {
    event.preventDefault();
    if (!selectedCompanyId) return;
    setBusy("invoice");
    try {
      await api.createBillingInvoice(credentials, selectedCompanyId, {
        ...invoiceForm,
        issuedAt: new Date(invoiceForm.issuedAt).toISOString(),
        dueAt: new Date(invoiceForm.dueAt).toISOString()
      });
      setInvoiceForm({
        number: "",
        concept: "",
        amount: "",
        currency: "EUR",
        issuedAt: toLocalInput(new Date()),
        dueAt: toLocalInput(addDays(new Date(), 30))
      });
      await loadInvoices(selectedCompanyId);
      await loadBilling();
      onNotice({ type: "success", text: t("createInvoice") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function registerPayment(event: FormEvent) {
    event.preventDefault();
    if (!paymentForm.invoiceId) return;
    setBusy("payment");
    try {
      await api.createBillingPayment(credentials, paymentForm.invoiceId, {
        amount: paymentForm.amount,
        method: paymentForm.method,
        paidAt: new Date().toISOString(),
        reference: paymentForm.reference
      });
      setPaymentForm({ invoiceId: "", amount: "", method: "TRANSFERENCIA", reference: "" });
      if (selectedCompanyId) await loadInvoices(selectedCompanyId);
      await loadBilling();
      onNotice({ type: "success", text: t("registerPayment") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="view-grid">
      <section className="metric-grid">
        <Metric label={t("monthlyRecurringRevenue")} value={formatMoney(localSummary?.monthlyRecurringRevenue ?? "0")} />
        <Metric label={t("paidCompanies")} value={localSummary?.paidCompanies ?? "-"} />
        <Metric label={t("pendingBilling")} value={localSummary?.pendingCompanies ?? "-"} tone={(localSummary?.pendingCompanies ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("overdueBilling")} value={localSummary?.overdueCompanies ?? "-"} tone={(localSummary?.overdueCompanies ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("renewalsNext30Days")} value={localSummary?.renewalsNext30Days ?? "-"} tone={(localSummary?.renewalsNext30Days ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("company")} value={localSummary?.totalCompanies ?? "-"} />
      </section>

      <section className="content-section billing-board">
        <SectionHeader title={t("billingPortfolio")} subtitle={t("billingPortfolioSubtitle")} />
        {orderedCompanies.length === 0 ? (
          <EmptyState text={t("noBillingData")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("company")}</th>
                  <th>{t("plan")}</th>
                  <th>{t("billingStatus")}</th>
                  <th>{t("renewalDate")}</th>
                  <th>{t("monthlyPrice")}</th>
                  <th>{t("license")}</th>
                </tr>
              </thead>
              <tbody>
                {orderedCompanies.map((company) => (
                  <tr key={company.companyId} className={company.overdue ? "billing-overdue" : company.renewalDueSoon ? "billing-due" : ""}>
                    <td>
                      <strong>{company.companyName}</strong>
                      <small>{company.taxId}</small>
                    </td>
                    <td>{company.planName}</td>
                    <td>
                      <StatusPill
                        status={billingStatusLabel(company.billingStatus, t)}
                        tone={company.overdue || company.renewalDueSoon ? "warning" : "ok"}
                      />
                      {company.renewalDueSoon && <small>{t("dueSoon")}</small>}
                    </td>
                    <td>{company.renewalDate ? formatDate(company.renewalDate) : t("pending")}</td>
                    <td>{formatMoney(company.monthlyPrice ?? "0")}</td>
                    <td>
                      <strong>{company.licenseReference ?? t("notAvailable")}</strong>
                      <small>{company.validUntil ? formatDate(company.validUntil) : t("pending")}</small>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="content-section">
        <SectionHeader title={t("realBilling")} subtitle={t("invoices")} />
        <div className="toolbar">
          <select value={selectedCompanyId} onChange={(event) => setSelectedCompanyId(event.target.value)}>
            {orderedCompanies.map((company) => (
              <option value={company.companyId} key={company.companyId}>{company.companyName}</option>
            ))}
          </select>
        </div>
        <form className="compact-form-grid" onSubmit={createInvoice}>
          <Input label={t("invoiceNumber")} value={invoiceForm.number} onChange={(number) => setInvoiceForm({ ...invoiceForm, number })} required />
          <Input label={t("concept")} value={invoiceForm.concept} onChange={(concept) => setInvoiceForm({ ...invoiceForm, concept })} required />
          <Input label={t("amount")} value={invoiceForm.amount} onChange={(amount) => setInvoiceForm({ ...invoiceForm, amount })} required />
          <Input label={t("currency")} value={invoiceForm.currency} onChange={(currency) => setInvoiceForm({ ...invoiceForm, currency })} required />
          <Input label={t("issuedAt")} type="datetime-local" value={invoiceForm.issuedAt} onChange={(issuedAt) => setInvoiceForm({ ...invoiceForm, issuedAt })} required />
          <Input label={t("dueAt")} type="datetime-local" value={invoiceForm.dueAt} onChange={(dueAt) => setInvoiceForm({ ...invoiceForm, dueAt })} required />
          <button className="primary-button" type="submit" disabled={busy === "invoice"}>{t("createInvoice")}</button>
        </form>
        <form className="compact-form-grid" onSubmit={registerPayment}>
          <label>
            {t("invoices")}
            <select
              className="control-input"
              value={paymentForm.invoiceId}
              onChange={(event) => {
                const invoiceId = event.target.value;
                const invoice = invoices.find((value) => value.id === invoiceId);
                setPaymentForm({ ...paymentForm, invoiceId, amount: invoice ? invoice.amount : paymentForm.amount });
              }}
            >
              <option value="">{t("pending")}</option>
              {invoices.map((invoice) => (
                <option value={invoice.id} key={invoice.id}>
                  {invoice.number} - {formatMoney(invoice.amount)} {invoice.currency}
                </option>
              ))}
            </select>
          </label>
          <Input label={t("amount")} value={paymentForm.amount} onChange={(amount) => setPaymentForm({ ...paymentForm, amount })} required />
          <Input label={t("paymentMethod")} value={paymentForm.method} onChange={(method) => setPaymentForm({ ...paymentForm, method })} required />
          <Input label={t("paymentReference")} value={paymentForm.reference} onChange={(reference) => setPaymentForm({ ...paymentForm, reference })} />
          <button className="primary-button" type="submit" disabled={busy === "payment" || !paymentForm.invoiceId}>{t("registerPayment")}</button>
        </form>
        <InvoiceTable invoices={invoices} />
      </section>
    </div>
  );
}

type MasterMode = "customers" | "products" | "suppliers" | "warehouses";

function MastersView({
  credentials,
  licenses,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const [companyId, setCompanyId] = useState("");
  const [mode, setMode] = useState<MasterMode>("customers");
  const [customers, setCustomers] = useState<ErpCustomer[]>([]);
  const [products, setProducts] = useState<ErpProduct[]>([]);
  const [suppliers, setSuppliers] = useState<ErpSupplier[]>([]);
  const [warehouses, setWarehouses] = useState<ErpWarehouse[]>([]);
  const [partyForm, setPartyForm] = useState({ code: "", name: "", taxId: "", email: "", phone: "" });
  const [productForm, setProductForm] = useState({ sku: "", name: "", category: "", price: "0.00", taxRate: "21.00", minStock: "0.00" });
  const [warehouseForm, setWarehouseForm] = useState({ code: "", name: "", address: "" });
  const [busy, setBusy] = useState(false);
  const canManage = permissions.has("MANAGE_ERP_MASTERS");

  useEffect(() => {
    if (!companyId && companies[0]) {
      setCompanyId(companies[0].companyId);
    }
  }, [companies, companyId]);

  useEffect(() => {
    if (!companyId) return;
    void loadMasters(companyId);
  }, [companyId]);

  async function loadMasters(nextCompanyId: string) {
    const [nextCustomers, nextProducts, nextSuppliers, nextWarehouses] = await Promise.all([
      loadMasterList(() => api.erpCustomers(credentials, nextCompanyId)),
      loadMasterList(() => api.erpProducts(credentials, nextCompanyId)),
      loadMasterList(() => api.erpSuppliers(credentials, nextCompanyId)),
      loadMasterList(() => api.erpWarehouses(credentials, nextCompanyId))
    ]);
    setCustomers(nextCustomers);
    setProducts(nextProducts);
    setSuppliers(nextSuppliers);
    setWarehouses(nextWarehouses);
    onNotice(null);
  }

  async function loadMasterList<T>(loader: () => Promise<T[]>): Promise<T[]> {
    try {
      return await loader();
    } catch (error) {
      if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) {
        return [];
      }
      onNotice({ type: "error", text: errorMessage(error) });
      return [];
    }
  }

  async function createMaster(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    setBusy(true);
    try {
      if (mode === "customers") {
        await api.createErpCustomer(credentials, companyId, partyForm);
        setPartyForm({ code: "", name: "", taxId: "", email: "", phone: "" });
      } else if (mode === "products") {
        await api.createErpProduct(credentials, companyId, productForm);
        setProductForm({ sku: "", name: "", category: "", price: "0.00", taxRate: "21.00", minStock: "0.00" });
      } else if (mode === "suppliers") {
        await api.createErpSupplier(credentials, companyId, partyForm);
        setPartyForm({ code: "", name: "", taxId: "", email: "", phone: "" });
      } else {
        await api.createErpWarehouse(credentials, companyId, warehouseForm);
        setWarehouseForm({ code: "", name: "", address: "" });
      }
      onNotice({ type: "success", text: t("masterCreated") });
      await loadMasters(companyId);
    } catch (error) {
      if (isMissingPhase3Endpoint(error)) {
        onNotice({ type: "error", text: t("mastersBackendPending") });
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  async function deactivateMaster(id: string) {
    if (!companyId) return;
    setBusy(true);
    try {
      if (mode === "customers") {
        await api.deactivateErpCustomer(credentials, companyId, id);
      } else if (mode === "products") {
        await api.deactivateErpProduct(credentials, companyId, id);
      } else if (mode === "suppliers") {
        await api.deactivateErpSupplier(credentials, companyId, id);
      } else {
        await api.deactivateErpWarehouse(credentials, companyId, id);
      }
      await loadMasters(companyId);
      onNotice({ type: "success", text: t("masterDisabled") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader title={t("erpMasters")} subtitle={t("erpMastersSubtitle")} />
      <div className="toolbar">
        <Segmented
          value={mode}
          options={[
            ["customers", t("customers")],
            ["products", t("products")],
            ["suppliers", t("suppliers")],
            ["warehouses", t("warehouses")]
          ]}
          onChange={(value) => setMode(value as MasterMode)}
        />
        <label className="toolbar-field">
          {t("company")}
          <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
            {companies.map((company) => (
              <option key={company.companyId} value={company.companyId}>
                {company.companyName}
              </option>
            ))}
          </select>
        </label>
      </div>
      {canManage && (
        <form className="compact-form-grid masters-form" onSubmit={createMaster}>
          {mode === "products" ? (
            <>
              <Input label={t("sku")} value={productForm.sku} onChange={(sku) => setProductForm({ ...productForm, sku })} required />
              <Input label={t("name")} value={productForm.name} onChange={(name) => setProductForm({ ...productForm, name })} required />
              <Input label={t("category")} value={productForm.category} onChange={(category) => setProductForm({ ...productForm, category })} />
              <Input label={t("price")} value={productForm.price} onChange={(price) => setProductForm({ ...productForm, price })} required />
              <Input label={t("taxRate")} value={productForm.taxRate} onChange={(taxRate) => setProductForm({ ...productForm, taxRate })} required />
              <Input label={t("minStock")} value={productForm.minStock} onChange={(minStock) => setProductForm({ ...productForm, minStock })} required />
            </>
          ) : mode === "warehouses" ? (
            <>
              <Input label={t("code")} value={warehouseForm.code} onChange={(code) => setWarehouseForm({ ...warehouseForm, code })} required />
              <Input label={t("name")} value={warehouseForm.name} onChange={(name) => setWarehouseForm({ ...warehouseForm, name })} required />
              <Input label={t("address")} value={warehouseForm.address} onChange={(address) => setWarehouseForm({ ...warehouseForm, address })} />
            </>
          ) : (
            <>
              <Input label={t("code")} value={partyForm.code} onChange={(code) => setPartyForm({ ...partyForm, code })} required />
              <Input label={t("name")} value={partyForm.name} onChange={(name) => setPartyForm({ ...partyForm, name })} required />
              <Input label={t("taxId")} value={partyForm.taxId} onChange={(taxId) => setPartyForm({ ...partyForm, taxId })} />
              <Input label={t("email")} value={partyForm.email} onChange={(email) => setPartyForm({ ...partyForm, email })} />
              <Input label={t("phone")} value={partyForm.phone} onChange={(phone) => setPartyForm({ ...partyForm, phone })} />
            </>
          )}
          <div className="form-actions">
            <button className="primary-button" type="submit" disabled={busy || !companyId}>
              {mode === "customers" && t("createCustomer")}
              {mode === "products" && t("createProduct")}
              {mode === "suppliers" && t("createSupplier")}
              {mode === "warehouses" && t("createWarehouse")}
            </button>
          </div>
        </form>
      )}
      <MasterTable
        mode={mode}
        customers={customers}
        products={products}
        suppliers={suppliers}
        warehouses={warehouses}
        canManage={canManage}
        onDeactivate={(id) => void deactivateMaster(id)}
      />
    </section>
  );
}

function MasterTable({
  mode,
  customers,
  products,
  suppliers,
  warehouses,
  canManage = false,
  onDeactivate = () => undefined
}: {
  mode: MasterMode;
  customers: ErpCustomer[];
  products: ErpProduct[];
  suppliers: ErpSupplier[];
  warehouses: ErpWarehouse[];
  canManage?: boolean;
  onDeactivate?: (id: string) => void;
}) {
  const { t } = useI18n();
  if (mode === "products") {
    if (products.length === 0) return <EmptyState text={t("noMasterData")} />;
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t("sku")}</th>
              <th>{t("name")}</th>
              <th>{t("category")}</th>
              <th>{t("price")}</th>
              <th>{t("taxRate")}</th>
              <th>{t("minStock")}</th>
              {canManage && <th></th>}
            </tr>
          </thead>
          <tbody>
            {products.map((item) => (
              <tr key={item.id}>
                <td>{item.sku}</td>
                <td>{item.name}</td>
                <td>{item.category || "-"}</td>
                <td>{formatMoney(item.price)}</td>
                <td>{formatMoney(item.taxRate)}%</td>
                <td>{formatMoney(item.minStock)}</td>
                {canManage && (
                  <td className="table-actions">
                    {item.active && <button className="danger-button subtle" type="button" onClick={() => onDeactivate(item.id)}>{t("deactivate")}</button>}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  if (mode === "warehouses") {
    if (warehouses.length === 0) return <EmptyState text={t("noMasterData")} />;
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t("code")}</th>
              <th>{t("name")}</th>
              <th>{t("address")}</th>
              <th>{t("status")}</th>
              {canManage && <th></th>}
            </tr>
          </thead>
          <tbody>
            {warehouses.map((item) => (
              <tr key={item.id}>
                <td>{item.code}</td>
                <td>{item.name}</td>
                <td>{item.address || "-"}</td>
                <td><StatusPill status={item.active ? t("active") : t("inactive")} tone={item.active ? "ok" : "muted"} /></td>
                {canManage && (
                  <td className="table-actions">
                    {item.active && <button className="danger-button subtle" type="button" onClick={() => onDeactivate(item.id)}>{t("deactivate")}</button>}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  const rows = mode === "customers" ? customers : suppliers;
  if (rows.length === 0) return <EmptyState text={t("noMasterData")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("code")}</th>
            <th>{t("name")}</th>
            <th>{t("taxId")}</th>
            <th>{t("email")}</th>
            <th>{t("phone")}</th>
            <th>{t("status")}</th>
            {canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((item) => (
            <tr key={item.id}>
              <td>{item.code}</td>
              <td>{item.name}</td>
              <td>{item.taxId || "-"}</td>
              <td>{item.email || "-"}</td>
              <td>{item.phone || "-"}</td>
              <td><StatusPill status={item.active ? t("active") : t("inactive")} tone={item.active ? "ok" : "muted"} /></td>
              {canManage && (
                <td className="table-actions">
                  {item.active && <button className="danger-button subtle" type="button" onClick={() => onDeactivate(item.id)}>{t("deactivate")}</button>}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SupportView({
  credentials,
  licenses,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const [companyId, setCompanyId] = useState("");
  const [notifications, setNotifications] = useState<AdminNotification[]>([]);
  const [technicalStatus, setTechnicalStatus] = useState<TechnicalStatus | null>(null);
  const [saasStatus, setSaasStatus] = useState<SaasStatus | null>(null);
  const [tickets, setTickets] = useState<SupportTicket[]>([]);
  const [commentsByTicket, setCommentsByTicket] = useState<Record<string, SupportTicketComment[]>>({});
  const [commentDrafts, setCommentDrafts] = useState<Record<string, string>>({});
  const [statusFilter, setStatusFilter] = useState("");
  const [priorityFilter, setPriorityFilter] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState("NORMAL");
  const [busy, setBusy] = useState<string | null>(null);
  const canManage = permissions.has("MANAGE_SUPPORT_TICKETS");
  const filteredTickets = tickets.filter((ticket) =>
    (!statusFilter || ticket.status === statusFilter) &&
    (!priorityFilter || ticket.priority === priorityFilter)
  );

  useEffect(() => {
    if (!companyId && companies[0]) {
      setCompanyId(companies[0].companyId);
    }
  }, [companies, companyId]);

  useEffect(() => {
    void loadOverview();
  }, [credentials.username]);

  useEffect(() => {
    if (companyId) {
      void loadTickets(companyId);
    }
  }, [companyId]);

  async function loadOverview() {
    const [nextNotifications, nextTechnicalStatus, nextSaasStatus] = await Promise.allSettled([
      api.notifications(credentials),
      api.technicalStatus(credentials),
      api.saasStatus(credentials)
    ]);

    if (nextNotifications.status === "fulfilled") {
      setNotifications(nextNotifications.value);
    } else if (isMissingPhase3Endpoint(nextNotifications.reason) || isRecoverableBackendDataError(nextNotifications.reason)) {
      setNotifications([]);
    } else {
      onNotice({ type: "error", text: errorMessage(nextNotifications.reason) });
    }

    if (nextTechnicalStatus.status === "fulfilled") {
      setTechnicalStatus(nextTechnicalStatus.value);
    } else if (isMissingPhase3Endpoint(nextTechnicalStatus.reason) || isRecoverableBackendDataError(nextTechnicalStatus.reason)) {
      setTechnicalStatus(fallbackTechnicalStatus(licenses));
      onNotice(null);
    } else {
      onNotice({ type: "error", text: errorMessage(nextTechnicalStatus.reason) });
    }

    if (nextSaasStatus.status === "fulfilled") {
      setSaasStatus(nextSaasStatus.value);
    } else if (isMissingPhase3Endpoint(nextSaasStatus.reason) || isRecoverableBackendDataError(nextSaasStatus.reason)) {
      setSaasStatus(null);
    } else {
      onNotice({ type: "error", text: errorMessage(nextSaasStatus.reason) });
    }
  }

  async function loadTickets(nextCompanyId: string) {
    try {
      const nextTickets = await api.supportTickets(credentials, nextCompanyId);
      setTickets(nextTickets);
      await loadTicketComments(nextTickets);
    } catch (error) {
      if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) {
        setTickets([]);
        setCommentsByTicket({});
        onNotice(null);
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function loadTicketComments(nextTickets: SupportTicket[]) {
    const entries = await Promise.all(
      nextTickets.map(async (ticket) => {
        try {
          return [ticket.id, await api.supportTicketComments(credentials, ticket.id)] as const;
        } catch (error) {
          if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) return [ticket.id, []] as const;
          throw error;
        }
      })
    );
    setCommentsByTicket(Object.fromEntries(entries));
  }

  async function createTicket(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    setBusy("create");
    try {
      await api.createSupportTicket(credentials, companyId, { title, description, priority });
      setTitle("");
      setDescription("");
      setPriority("NORMAL");
      await Promise.all([loadTickets(companyId), loadOverview()]);
      onNotice({ type: "success", text: t("ticketCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function updateTicket(ticket: SupportTicket, status: string) {
    setBusy(ticket.id);
    try {
      await api.updateSupportTicket(credentials, ticket.id, { status, priority: ticket.priority });
      await Promise.all([loadTickets(ticket.companyId), loadOverview()]);
      onNotice({ type: "success", text: t("ticketUpdated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function addComment(ticket: SupportTicket) {
    const message = (commentDrafts[ticket.id] ?? "").trim();
    if (!message) return;
    setBusy(`comment:${ticket.id}`);
    try {
      await api.createSupportTicketComment(credentials, ticket.id, message);
      setCommentDrafts((current) => ({ ...current, [ticket.id]: "" }));
      await loadTicketComments(tickets);
      onNotice({ type: "success", text: t("commentAdded") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function markNotificationRead(notificationId: string) {
    try {
      setNotifications((current) => current.filter((notification) => notification.id !== notificationId));
      await api.markNotificationRead(credentials, notificationId);
      onNotice({ type: "success", text: t("notificationRead") });
    } catch (error) {
      if (!isMissingPhase3Endpoint(error) && !isRecoverableBackendDataError(error)) {
        onNotice({ type: "error", text: errorMessage(error) });
      }
    }
  }

  return (
    <div className="view-grid">
      <section className="metric-grid support-metrics">
        <Metric label={t("backendStatus")} value={t("technicalOk")} detail={saasStatus ? `${saasStatus.apiVersion} · ${saasStatus.expectedMigration}` : technicalStatus ? `${t("generatedAt")} ${formatDate(technicalStatus.generatedAt)}` : t("loadingSaas")} />
        <Metric label={t("company")} value={technicalStatus?.companies ?? "-"} />
        <Metric label={t("licenses")} value={technicalStatus?.licenses ?? "-"} />
        <Metric label={t("eventsToday")} value={technicalStatus?.eventsToday ?? "-"} />
        <Metric label={t("openTickets")} value={technicalStatus?.openTickets ?? "-"} tone={(technicalStatus?.openTickets ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("lastSync")} value={technicalStatus?.lastSyncAt ? formatDate(technicalStatus.lastSyncAt) : t("pending")} />
      </section>

      <section className="content-section two-column support-layout">
        <div>
          <SectionHeader title={t("notifications")} subtitle={t("notificationsSubtitle")} />
          <NotificationList notifications={notifications} onMarkRead={(notificationId) => void markNotificationRead(notificationId)} />
        </div>
        <div>
          <SectionHeader title={t("technicalPanel")} subtitle={t("technicalPanelSubtitle")} />
          <div className="technical-card">
            <Metric label={t("installations")} value={technicalStatus?.installations ?? "-"} />
            <Metric label={t("staleInstallations")} value={technicalStatus?.staleInstallations ?? "-"} tone={(technicalStatus?.staleInstallations ?? 0) > 0 ? "warning" : undefined} />
          </div>
        </div>
      </section>

      <section className="content-section support-tickets-panel">
        <SectionHeader title={t("supportTickets")} subtitle={t("supportTicketsSubtitle")} />
        {companies.length === 0 ? (
          <EmptyState text={t("selectCompany")} />
        ) : (
          <>
            <label className="company-ticket-selector">
              {t("company")}
              <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
                {companies.map((company) => (
                  <option key={company.companyId} value={company.companyId}>
                    {company.companyName}
                  </option>
                ))}
              </select>
            </label>
            {canManage && (
              <form className="support-ticket-form" onSubmit={createTicket}>
                <div className="support-ticket-form-top">
                  <Input label={t("title")} value={title} onChange={setTitle} required />
                  <Select label={t("priority")} value={priority} options={["NORMAL", "ALTA", "URGENTE"]} onChange={setPriority} />
                  <div className="form-actions">
                    <button className="primary-button" type="submit" disabled={busy === "create"}>
                      {busy === "create" ? t("saving") : t("createTicket")}
                    </button>
                  </div>
                </div>
                <label className="support-ticket-description">
                  {t("description")}
                  <textarea
                    className="control-input text-area"
                    value={description}
                    onChange={(event) => setDescription(event.target.value)}
                  />
                </label>
              </form>
            )}
            <div className="support-ticket-filters">
              <Select label={t("status")} value={statusFilter} options={["", "ABIERTO", "EN_CURSO", "RESUELTO"]} onChange={setStatusFilter} emptyLabel={t("allStatuses")} />
              <Select label={t("priority")} value={priorityFilter} options={["", "NORMAL", "ALTA", "URGENTE"]} onChange={setPriorityFilter} emptyLabel={t("allPriorities")} />
            </div>
            <TicketList
              tickets={filteredTickets}
              commentsByTicket={commentsByTicket}
              commentDrafts={commentDrafts}
              canManage={canManage}
              busy={busy}
              onUpdate={updateTicket}
              onCommentDraftChange={(ticketId, message) => setCommentDrafts((current) => ({ ...current, [ticketId]: message }))}
              onAddComment={(ticket) => void addComment(ticket)}
            />
          </>
        )}
      </section>
    </div>
  );
}

function NotificationList({ notifications, onMarkRead }: { notifications: AdminNotification[]; onMarkRead: (notificationId: string) => void }) {
  const { t } = useI18n();
  if (notifications.length === 0) return <EmptyState text={t("noNotifications")} />;
  return (
    <div className="notification-list">
      {notifications.map((notification) => (
        <article className={`notification-card ${notification.severity.toLowerCase()}`} key={notification.id}>
          <div>
            <strong>{notification.title}</strong>
            <span>{notification.companyName}</span>
          </div>
          <p>{notification.detail}</p>
          <button className="small-button" type="button" onClick={() => onMarkRead(notification.id)}>
            {t("markRead")}
          </button>
        </article>
      ))}
    </div>
  );
}

function TicketList({
  tickets,
  commentsByTicket,
  commentDrafts,
  canManage,
  busy,
  onUpdate,
  onCommentDraftChange,
  onAddComment
}: {
  tickets: SupportTicket[];
  commentsByTicket: Record<string, SupportTicketComment[]>;
  commentDrafts: Record<string, string>;
  canManage: boolean;
  busy: string | null;
  onUpdate: (ticket: SupportTicket, status: string) => void;
  onCommentDraftChange: (ticketId: string, message: string) => void;
  onAddComment: (ticket: SupportTicket) => void;
}) {
  const { t } = useI18n();
  if (tickets.length === 0) return <EmptyState text={t("noTickets")} />;
  return (
    <div className="ticket-list">
      {tickets.map((ticket) => (
        <article className="ticket-card" key={ticket.id}>
          <div className="ticket-main">
            <div>
              <strong>{ticket.title}</strong>
              <span>{ticket.companyName} - {ticket.createdBy} - {formatDate(ticket.createdAt)}</span>
            </div>
            <div className="ticket-badges">
              <StatusPill status={ticketStatusLabel(ticket.status, t)} tone={ticket.status === "RESUELTO" ? "ok" : "warning"} />
              <StatusPill status={ticketPriorityLabel(ticket.priority, t)} tone={ticket.priority === "URGENTE" ? "warning" : "muted"} />
            </div>
          </div>
          {ticket.description && <p>{ticket.description}</p>}
          <div className="ticket-comments">
            {(commentsByTicket[ticket.id] ?? []).length === 0 ? (
              <span>{t("noComments")}</span>
            ) : (
              (commentsByTicket[ticket.id] ?? []).map((comment) => (
                <div className="ticket-comment" key={comment.id}>
                  <strong>{comment.author}</strong>
                  <span>{formatDate(comment.createdAt)}</span>
                  <p>{comment.message}</p>
                </div>
              ))
            )}
          </div>
          {canManage && ticket.status !== "RESUELTO" && (
            <div className="ticket-actions">
              {ticket.status !== "EN_CURSO" && (
                <button className="small-button" type="button" disabled={busy === ticket.id} onClick={() => onUpdate(ticket, "EN_CURSO")}>
                  {t("inProgress")}
                </button>
              )}
              <button className="small-button" type="button" disabled={busy === ticket.id} onClick={() => onUpdate(ticket, "RESUELTO")}>
                {t("resolve")}
              </button>
            </div>
          )}
          {canManage && (
            <div className="ticket-comment-form">
              <input
                className="control-input"
                value={commentDrafts[ticket.id] ?? ""}
                onChange={(event) => onCommentDraftChange(ticket.id, event.target.value)}
                placeholder={t("comment")}
              />
              <button className="small-button" type="button" disabled={busy === `comment:${ticket.id}`} onClick={() => onAddComment(ticket)}>
                {t("addComment")}
              </button>
            </div>
          )}
        </article>
      ))}
    </div>
  );
}

function LicenseTable({
  licenses,
  compact = false,
  onAction,
  busy,
  showPairingAction = true,
  showStatusActions = true,
  selectedCompanyId,
  onSelectCompany
}: {
  licenses: LicenseSummary[];
  compact?: boolean;
  onAction?: (reference: string, action: LicenseAction) => void;
  busy?: string | null;
  showPairingAction?: boolean;
  showStatusActions?: boolean;
  selectedCompanyId?: string;
  onSelectCompany?: (companyId: string) => void;
}) {
  const { t } = useI18n();
  const showActionColumn = Boolean(onAction && (showPairingAction || showStatusActions));
  if (licenses.length === 0) return <EmptyState text={t("noLicenses")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("license")}</th>
            <th>{t("company")}</th>
            <th>{t("status")}</th>
            <th>{t("validity")}</th>
            {!compact && <th>{t("quotas")}</th>}
            {!compact && showActionColumn && <th></th>}
          </tr>
        </thead>
        <tbody>
          {licenses.map((license) => (
            <tr
              key={license.licenseReference}
              className={[
                busy?.endsWith(license.licenseReference) ? "is-busy" : "",
                selectedCompanyId === license.companyId ? "is-selected" : ""
              ].filter(Boolean).join(" ")}
            >
              <td>
                <strong>{license.licenseReference}</strong>
                <small>{license.taxId}</small>
              </td>
              <td>
                <button className="link-button" type="button" onClick={() => onSelectCompany?.(license.companyId)}>
                  {license.companyName}
                </button>
                {!compact && selectedCompanyId === license.companyId && <small>{t("selected")}</small>}
              </td>
              <td>
                <StatusPill
                  status={license.status === "VALIDA" ? t("valid") : t("blockedStatus")}
                  tone={license.status === "VALIDA" ? "ok" : "warning"}
                />
              </td>
              <td>{formatDate(license.validUntil)}</td>
              {!compact && <td>{license.maxWindows} Windows · {license.maxPda} PDA</td>}
              {!compact && showActionColumn && (
                <td className="row-actions">
                  {showPairingAction && (
                    <button
                      className="small-button code"
                      type="button"
                      onClick={() => onAction?.(license.licenseReference, "pairing")}
                      disabled={busy === `pairing:${license.licenseReference}`}
                      aria-label={`${t("generateCode")} ${license.licenseReference}`}
                    >
                      {busy === `pairing:${license.licenseReference}` ? t("generating") : t("generateCode")}
                    </button>
                  )}
                  {showStatusActions && (
                    license.status === "VALIDA" ? (
                      <button className="small-button danger" type="button" onClick={() => onAction?.(license.licenseReference, "block")} disabled={busy === `block:${license.licenseReference}`}>
                        {t("block")}
                      </button>
                    ) : (
                      <button className="small-button" type="button" onClick={() => onAction?.(license.licenseReference, "unblock")} disabled={busy === `unblock:${license.licenseReference}`}>
                        {t("unblock")}
                      </button>
                    )
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PairingCodePanel({ pairingCode, onCopy }: { pairingCode: PairingCodeResponse; onCopy: () => void }) {
  const { t } = useI18n();
  return (
    <div className="pairing-panel" role="status" aria-live="polite">
      <div>
        <span>{t("activePairingCode")}</span>
        <strong>{pairingCode.pairingCode}</strong>
        <small>
          {pairingCode.licenseReference} - {t("expires")} {formatDate(pairingCode.expiresAt)}
        </small>
      </div>
      <button className="secondary-button" type="button" onClick={onCopy}>
        {t("copy")}
      </button>
    </div>
  );
}

function TenantAccessPanel({ access }: { access: { username: string; password: string } }) {
  const { t } = useI18n();
  return (
    <div className="pairing-panel tenant-access-panel" role="status" aria-live="polite">
      <div>
        <span>{t("tenantInitialAccess")}</span>
        <strong>{access.username}</strong>
        <small>{t("initialPassword")}: {access.password}</small>
        <small>{t("tenantInitialAccessHint")}</small>
      </div>
    </div>
  );
}

function InstallationsTable({ installations }: { installations: InstallationSummary[] }) {
  const { t } = useI18n();
  if (installations.length === 0) return <EmptyState text={t("noLinkedInstallations")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("installations")}</th>
            <th>{t("license")}</th>
            <th>{t("linkedAt")}</th>
            <th>{t("lastValidation")}</th>
            <th>{t("deviceDetails")}</th>
          </tr>
        </thead>
        <tbody>
          {installations.map((installation) => (
            <tr key={installation.installationId}>
              <td>
                <strong>{installation.installationReference}</strong>
                <small>{installation.installationId}</small>
              </td>
              <td>{installation.licenseReference}</td>
              <td>{formatDate(installation.linkedAt)}</td>
              <td>
                {installation.lastValidatedAt ? formatDate(installation.lastValidatedAt) : t("pending")}
                <InstallationHealth installation={installation} />
              </td>
              <td>
                <strong>{installation.terminalName || t("notAvailable")}</strong>
                <small>{t("appVersion")}: {installation.appVersion || t("notAvailable")}</small>
                <small>{t("operatingSystem")}: {installation.operatingSystem || t("notAvailable")}</small>
                <small>{t("lastIp")}: {installation.lastIp || t("notAvailable")}</small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function CompanyDetail({
  credentials,
  license,
  installations,
  events,
  canManage,
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  license: LicenseSummary | null;
  installations: InstallationSummary[];
  events: SyncEventView[];
  canManage: boolean;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [companyName, setCompanyName] = useState("");
  const [taxpayerType, setTaxpayerType] = useState<TaxpayerType>("SOCIEDAD");
  const [taxRegime, setTaxRegime] = useState<TaxRegime>("IVA");
  const [validUntil, setValidUntil] = useState("");
  const [maxWindows, setMaxWindows] = useState("1");
  const [maxPda, setMaxPda] = useState("0");
  const [operations, setOperations] = useState<CompanyOperations | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    if (!license) return;
    setCompanyName(license.companyName);
    setValidUntil(toLocalInput(new Date(license.validUntil)));
    setMaxWindows(String(license.maxWindows));
    setMaxPda(String(license.maxPda));
    setOperations(null);
    void loadOperations(license.companyId);
  }, [license?.companyId]);

  async function loadOperations(companyId: string) {
    try {
      setOperations(await api.companyOperations(credentials, companyId));
    } catch {
      setOperations(defaultCompanyOperations(companyId));
    }
  }

  async function saveCompany(event: FormEvent) {
    event.preventDefault();
    if (!license) return;
    setBusy("company");
    try {
      await api.editCompany(credentials, license.companyId, { name: companyName, taxpayerType, impuestos: taxRegime });
      onNotice({ type: "success", text: t("companyUpdated") });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function renewSelectedLicense(event: FormEvent) {
    event.preventDefault();
    if (!license) return;
    setBusy("license");
    try {
      await api.renewLicense(credentials, license.licenseReference, {
        validUntil: new Date(validUntil).toISOString(),
        maxWindows: Number(maxWindows),
        maxPda: Number(maxPda)
      });
      onNotice({ type: "success", text: t("licenseRenewed") });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function saveOperations(event: FormEvent) {
    event.preventDefault();
    if (!license || !operations) return;
    setBusy("operations");
    try {
      const saved = await api.updateCompanyOperations(credentials, license.companyId, {
        planName: operations.planName,
        billingStatus: operations.billingStatus,
        renewalDate: operations.renewalDate ? new Date(operations.renewalDate).toISOString() : null,
        monthlyPrice: operations.monthlyPrice,
        supportStatus: operations.supportStatus,
        contactName: operations.contactName,
        contactEmail: operations.contactEmail,
        notes: operations.notes
      });
      setOperations(saved);
      onNotice({ type: "success", text: t("operationsUpdated") });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  if (!license) {
    return (
      <section className="content-section">
        <SectionHeader title={t("companyDetail")} subtitle={t("companyDetailSubtitle")} />
        <EmptyState text={t("selectCompany")} />
      </section>
    );
  }

  const stores = new Set(installations.map((installation) => installation.storeId)).size;
  const recentEvents = events.slice(0, 4);
  const operationsForm = operations ?? defaultCompanyOperations(license.companyId);

  return (
    <section className="content-section company-detail">
      <SectionHeader title={t("companyDetail")} subtitle={t("companyDetailSubtitle")} />
      <div className="company-detail-grid">
        <div className="company-card-main">
          <strong>{license.companyName}</strong>
          <span>{license.taxId}</span>
          <StatusPill status={license.status === "VALIDA" ? t("valid") : t("blockedStatus")} tone={license.status === "VALIDA" ? "ok" : "warning"} />
        </div>
        <Metric label={t("installations")} value={installations.length} />
        <Metric label={t("stores")} value={stores} />
        <Metric label={t("quotas")} value={`${license.maxWindows} W / ${license.maxPda} PDA`} detail={`${t("licenseExpires")} ${formatDate(license.validUntil)}`} />
      </div>
      <div className="detail-columns">
        <div>
          <SectionHeader title={t("linkedInstallations")} subtitle={`${installations.length} ${t("installations").toLowerCase()}`} />
          <InstallationsTable installations={installations} />
        </div>
        <div>
          <SectionHeader title={t("recentActivity")} subtitle={events[0] ? formatDate(events[0].receivedAt) : t("noEvents")} />
          {recentEvents.length > 0 ? <EventsTable events={recentEvents} /> : <EmptyState text={t("noRecentActivity")} />}
        </div>
      </div>
      <div className="phase2-panel">
        <SectionHeader title={t("phase2Operations")} subtitle={t("phase2OperationsSubtitle")} />
        <div className="phase2-grid">
          <form className="stack-form phase2-form" onSubmit={saveCompany}>
            <h3>{t("editCompany")}</h3>
            <Input label={t("company")} value={companyName} onChange={setCompanyName} disabled={!canManage} required />
            <Select label={t("type")} value={taxpayerType} options={["SOCIEDAD", "AUTONOMO"]} onChange={(value) => setTaxpayerType(value as TaxpayerType)} disabled={!canManage} />
            <Select label={t("taxes")} value={taxRegime} options={["IVA", "IGIC"]} onChange={(value) => setTaxRegime(value as TaxRegime)} disabled={!canManage} />
            {canManage && (
              <button className="secondary-button" type="submit" disabled={busy === "company"}>
                {busy === "company" ? t("saving") : t("saveChanges")}
              </button>
            )}
          </form>
          <form className="stack-form phase2-form" onSubmit={renewSelectedLicense}>
            <h3>{t("renewLicense")}</h3>
            <Input label={t("validUntil")} type="datetime-local" value={validUntil} onChange={setValidUntil} disabled={!canManage} required />
            <Input label="Windows" type="number" value={maxWindows} min={1} onChange={setMaxWindows} disabled={!canManage} required />
            <Input label="PDA" type="number" value={maxPda} min={0} onChange={setMaxPda} disabled={!canManage} required />
            {canManage && (
              <button className="secondary-button" type="submit" disabled={busy === "license"}>
                {busy === "license" ? t("saving") : t("saveChanges")}
              </button>
            )}
          </form>
          <form className="stack-form phase2-form wide" onSubmit={saveOperations}>
            <h3>{t("billingStatus")} / {t("supportStatus")}</h3>
            <div className="compact-form-grid">
              <Input label={t("plan")} value={operationsForm.planName} onChange={(planName) => setOperations({ ...operationsForm, planName })} disabled={!canManage} />
              <Input label={t("billingStatus")} value={operationsForm.billingStatus} onChange={(billingStatus) => setOperations({ ...operationsForm, billingStatus })} disabled={!canManage} />
              <Input
                label={t("renewalDate")}
                type="datetime-local"
                value={operationsForm.renewalDate ? toLocalInput(new Date(operationsForm.renewalDate)) : ""}
                onChange={(renewalDate) => setOperations({ ...operationsForm, renewalDate })}
                disabled={!canManage}
              />
              <Input label={t("monthlyPrice")} value={operationsForm.monthlyPrice ?? ""} onChange={(monthlyPrice) => setOperations({ ...operationsForm, monthlyPrice })} disabled={!canManage} />
              <Input label={t("supportStatus")} value={operationsForm.supportStatus} onChange={(supportStatus) => setOperations({ ...operationsForm, supportStatus })} disabled={!canManage} />
              <Input label={t("contactName")} value={operationsForm.contactName ?? ""} onChange={(contactName) => setOperations({ ...operationsForm, contactName })} disabled={!canManage} />
              <Input label={t("contactEmail")} value={operationsForm.contactEmail ?? ""} onChange={(contactEmail) => setOperations({ ...operationsForm, contactEmail })} disabled={!canManage} />
            </div>
            <label>
              {t("notes")}
              <textarea
                className="control-input text-area"
                value={operationsForm.notes ?? ""}
                onChange={(event) => setOperations({ ...operationsForm, notes: event.target.value })}
                disabled={!canManage}
              />
            </label>
            {canManage && (
              <button className="secondary-button" type="submit" disabled={busy === "operations"}>
                {busy === "operations" ? t("saving") : t("saveChanges")}
              </button>
            )}
          </form>
        </div>
      </div>
    </section>
  );
}

function AlertList({ alerts }: { alerts: Array<{ tone: "warning" | "danger"; title: string; detail: string }> }) {
  const { t } = useI18n();
  if (alerts.length === 0) return <EmptyState text={t("noAlerts")} />;
  return (
    <div className="alert-list">
      {alerts.map((alert) => (
        <article className={`alert-card ${alert.tone}`} key={`${alert.title}-${alert.detail}`}>
          <strong>{alert.title}</strong>
          <span>{alert.detail}</span>
        </article>
      ))}
    </div>
  );
}

function InstallationHealth({ installation }: { installation: InstallationSummary }) {
  const { t } = useI18n();
  if (!installation.lastValidatedAt) {
    return <StatusPill status={t("withoutValidation")} tone="muted" />;
  }
  if (hoursSince(installation.lastValidatedAt) > 48) {
    return <StatusPill status={t("stale")} tone="warning" />;
  }
  return null;
}

function EventsTable({ events }: { events: SyncEventView[] }) {
  const { t } = useI18n();
  if (events.length === 0) return <EmptyState text={t("noEventsForFilter")} />;
  return (
    <div className="event-list">
      {events.map((event) => (
        <EventLine key={event.eventId} event={event} />
      ))}
    </div>
  );
}

function EventLine({ event }: { event: SyncEventView }) {
  const { t } = useI18n();
  return (
    <article className="event-row">
      <div>
        <strong>{event.entityType}</strong>
        <span>{event.operation} · {formatDate(event.receivedAt)}</span>
        <div className="event-summary">{eventSummary(event)}</div>
      </div>
      <code>{event.entityId}</code>
      <details>
        <summary>{t("payload")}</summary>
        <pre>{JSON.stringify(event.payload, null, 2)}</pre>
      </details>
    </article>
  );
}

function StockTable({ rows }: { rows: StockSnapshot[] }) {
  const { t } = useI18n();
  if (rows.length === 0) return <EmptyState text={t("noStockForFilter")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("product")}</th>
            <th>{t("warehouse")}</th>
            <th>{t("quantity")}</th>
            <th>{t("store")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.companyId}-${row.storeId}-${row.productId}-${row.warehouseId}`}>
              <td>{row.productId}</td>
              <td>{row.warehouseId}</td>
              <td>{row.quantity}</td>
              <td><small>{row.storeId}</small></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AuditList({ audit, expanded = false }: { audit: AuditLog[]; expanded?: boolean }) {
  const { t } = useI18n();
  if (audit.length === 0) return <EmptyState text={t("noAuditActions")} />;
  return (
    <div className="audit-list">
      {audit.map((item) => (
        <article className="audit-row" key={item.id}>
          <div>
            <strong>{auditActionLabel(item.action)}</strong>
            <span>{item.username} · {formatDate(item.createdAt)}</span>
          </div>
          {expanded && (
            <code>
              {item.targetType}:{item.targetId}
            </code>
          )}
        </article>
      ))}
    </div>
  );
}

function NavButton({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button className={active ? "nav-button active" : "nav-button"} type="button" onClick={onClick}>
      {label}
    </button>
  );
}

function Metric({ label, value, detail, tone }: { label: string; value: number | string; detail?: string; tone?: "warning" }) {
  return (
    <article className={tone === "warning" ? "metric warning" : "metric"}>
      <span>{label}</span>
      <strong>{value}</strong>
      {detail && <small>{detail}</small>}
    </article>
  );
}

function SectionHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div className="section-header">
      <div>
        <h2>{title}</h2>
        <p>{subtitle}</p>
      </div>
    </div>
  );
}

function EmptyState({ text }: { text: string }) {
  return <div className="empty-state">{text}</div>;
}

function StatusPill({ status, tone }: { status: string; tone: "ok" | "warning" | "muted" }) {
  return <span className={`status-pill ${tone}`}>{status}</span>;
}

function Input({
  label,
  value,
  onChange,
  type = "text",
  required,
  min,
  disabled
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  required?: boolean;
  min?: number;
  disabled?: boolean;
}) {
  return (
    <label>
      {label}
      <input
        className="control-input"
        type={type}
        value={value}
        min={min}
        onChange={(event) => onChange(event.target.value)}
        required={required}
        disabled={disabled}
      />
    </label>
  );
}

function Select({
  label,
  value,
  options,
  onChange,
  disabled,
  emptyLabel
}: {
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
  disabled?: boolean;
  emptyLabel?: string;
}) {
  return (
    <label>
      {label}
      <select className="control-input" value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option || emptyLabel || option}
          </option>
        ))}
      </select>
    </label>
  );
}

function Segmented({ value, options, onChange }: { value: string; options: [string, string][]; onChange: (value: string) => void }) {
  return (
    <div className="segmented">
      {options.map(([optionValue, label]) => (
        <button key={optionValue} className={value === optionValue ? "active" : ""} type="button" onClick={() => onChange(optionValue)}>
          {label}
        </button>
      ))}
    </div>
  );
}

function LanguageSelector({ variant = "sidebar" }: { variant?: "sidebar" | "floating" }) {
  const { language, setLanguage, t } = useI18n();
  const [open, setOpen] = useState(false);
  const current = LANGUAGE_OPTIONS.find((option) => option.value === language) ?? LANGUAGE_OPTIONS[0];
  const isFloating = variant === "floating";

  function choose(nextLanguage: Language) {
    setLanguage(nextLanguage);
    setOpen(false);
  }

  return (
    <div className={`language-selector language-selector-${variant}`}>
      {!isFloating && <span>{t("language")}</span>}
      <button className="language-trigger" type="button" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        {isFloating ? (
          <svg className="language-globe" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="12" cy="12" r="9" />
            <path d="M3 12h18" />
            <path d="M12 3a13 13 0 0 1 0 18" />
            <path d="M12 3a13 13 0 0 0 0 18" />
          </svg>
        ) : (
          <>
            {current.label}
            <span aria-hidden="true">⌄</span>
          </>
        )}
      </button>
      {open && (
        <div className="language-menu" role="listbox">
          {LANGUAGE_OPTIONS.map((option) => (
            <button
              key={option.value}
              className={option.value === language ? "active" : ""}
              type="button"
              role="option"
              aria-selected={option.value === language}
              onClick={() => choose(option.value)}
            >
              <span>{option.label}</span>
              <small>{option.short}</small>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function readCredentials() {
  const raw = sessionStorage.getItem("tpv-saas-credentials");
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Credentials;
  } catch {
    return null;
  }
}

function readLanguage(): Language {
  const value = localStorage.getItem("tpv-saas-language");
  return value === "en" || value === "zh" || value === "es" ? value : "es";
}

function viewTitle(view: View, t: (key: string) => string) {
  return {
    dashboard: t("dashboard"),
    licenses: t("licensesCompanies"),
    sync: t("sync"),
    users: t("adminUsers"),
    support: t("supportCenter"),
    health: t("customerHealth"),
    billing: t("billing"),
    masters: t("masters"),
    audit: t("audit")
  }[view];
}

function uniqueCompanies(licenses: LicenseSummary[]) {
  return Array.from(new Map(licenses.map((license) => [license.companyId, license])).values()).map((license) => ({
    companyId: license.companyId,
    companyName: license.companyName
  }));
}

function defaultCompanyOperations(companyId: string): CompanyOperations {
  return {
    companyId,
    planName: "STANDARD",
    billingStatus: "PENDIENTE",
    renewalDate: null,
    monthlyPrice: "",
    supportStatus: "NORMAL",
    contactName: "",
    contactEmail: "",
    notes: ""
  };
}

function fallbackHealth(licenses: LicenseSummary[]): CustomerHealth[] {
  return uniqueCompanies(licenses).map((company) => {
    const license = licenses.find((item) => item.companyId === company.companyId);
    const validUntil = license?.validUntil ?? null;
    const isBlocked = license?.status !== "VALIDA";
    const expiresSoon = validUntil ? new Date(validUntil).getTime() < Date.now() + 30 * 24 * 60 * 60 * 1000 : true;
    const score = isBlocked ? 45 : expiresSoon ? 70 : 92;
    return {
      companyId: company.companyId,
      companyName: company.companyName,
      taxId: license?.taxId ?? "",
      planName: "STANDARD",
      billingStatus: "PENDIENTE",
      licenseStatus: license?.status ?? "SIN_LICENCIA",
      validUntil,
      installations: 0,
      staleInstallations: 0,
      lastValidationAt: null,
      eventsLast7Days: 0,
      lastEventAt: null,
      openTickets: 0,
      urgentTickets: 0,
      score,
      riskLevel: score < 50 ? "DANGER" : score < 75 ? "WARNING" : "OK",
      signals: [isBlocked ? "Licencia no valida" : expiresSoon ? "Licencia proxima a caducar" : "Operativa estable"]
    };
  });
}

function fallbackTechnicalStatus(licenses: LicenseSummary[]): TechnicalStatus {
  return {
    generatedAt: new Date().toISOString(),
    companies: uniqueCompanies(licenses).length,
    licenses: licenses.length,
    installations: 0,
    eventsToday: 0,
    openTickets: 0,
    staleInstallations: 0,
    lastSyncAt: null
  };
}

function fallbackBilling(licenses: LicenseSummary[]): BillingSummary {
  const companies = uniqueCompanies(licenses).map((company) => {
    const license = licenses.find((item) => item.companyId === company.companyId);
    const validUntil = license?.validUntil ?? null;
    const renewalDueSoon = validUntil ? daysUntil(validUntil) <= 30 : false;
    return {
      companyId: company.companyId,
      companyName: company.companyName,
      taxId: license?.taxId ?? "",
      planName: "STANDARD",
      billingStatus: "PENDIENTE",
      renewalDate: validUntil,
      monthlyPrice: "",
      licenseReference: license?.licenseReference ?? null,
      validUntil,
      renewalDueSoon,
      overdue: false
    };
  });
  return {
    totalCompanies: companies.length,
    paidCompanies: 0,
    pendingCompanies: companies.length,
    overdueCompanies: 0,
    renewalsNext30Days: companies.filter((company) => company.renewalDueSoon).length,
    monthlyRecurringRevenue: "0",
    companies
  };
}

function riskLabel(riskLevel: string, t: (key: string) => string) {
  if (riskLevel === "DANGER") return t("riskDanger");
  if (riskLevel === "WARNING") return t("riskWarning");
  return t("riskOk");
}

function billingStatusLabel(status: string, t: (key: string) => string) {
  const normalized = status.toUpperCase();
  if (normalized === "PAGADO") return t("paid");
  if (normalized === "IMPAGADO" || normalized === "VENCIDO") return t("overdue");
  return status;
}

function parseAmount(value: string | null | undefined) {
  if (!value) return 0;
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatMoney(value: string | number) {
  const amount = typeof value === "number" ? value : parseAmount(value);
  return new Intl.NumberFormat("es-ES", { style: "currency", currency: "EUR" }).format(amount);
}

function filterDashboardData(data: DashboardData, query: string): DashboardData {
  const normalized = normalizeSearch(query);
  if (!normalized) return data;

  const matchingLicenses = data.licenses.filter((license) =>
    [
      license.licenseReference,
      license.companyName,
      license.taxId,
      license.companyId
    ].some((value) => normalizeSearch(value).includes(normalized))
  );
  const companyIds = new Set(matchingLicenses.map((license) => license.companyId));
  const licenseReferences = new Set(matchingLicenses.map((license) => license.licenseReference));
  const matchingInstallations = data.installations.filter((installation) =>
    companyIds.has(installation.companyId) ||
    licenseReferences.has(installation.licenseReference) ||
    [
      installation.installationReference,
      installation.installationId,
      installation.storeId
    ].some((value) => normalizeSearch(value).includes(normalized))
  );
  matchingInstallations.forEach((installation) => {
    companyIds.add(installation.companyId);
    licenseReferences.add(installation.licenseReference);
  });

  return {
    ...data,
    licenses: data.licenses.filter((license) => companyIds.has(license.companyId) || licenseReferences.has(license.licenseReference)),
    installations: data.installations.filter((installation) => companyIds.has(installation.companyId) || licenseReferences.has(installation.licenseReference)),
    events: data.events.filter((event) => companyIds.has(event.companyId) || normalizeSearch(event.storeId).includes(normalized)),
    stockCurrent: data.stockCurrent.filter((row) => companyIds.has(row.companyId) || normalizeSearch(row.storeId).includes(normalized)),
    audit: data.audit.filter((item) =>
      [item.username, item.action, item.targetType, item.targetId].some((value) => normalizeSearch(value).includes(normalized))
    )
  };
}

function operationalAlerts(data: DashboardData, t: (key: string) => string) {
  const alerts: Array<{ tone: "warning" | "danger"; title: string; detail: string }> = [];
  const soon = data.licenses.filter((license) => license.status === "VALIDA" && daysUntil(license.validUntil) <= 30);
  const blocked = data.licenses.filter((license) => license.status === "BLOQUEADA_MANUAL");
  const stale = data.installations.filter((installation) => !installation.lastValidatedAt || hoursSince(installation.lastValidatedAt) > 48);

  soon.slice(0, 3).forEach((license) => {
    alerts.push({
      tone: "warning",
      title: t("expiringLicenseAlert"),
      detail: `${license.companyName} - ${license.licenseReference} - ${formatDate(license.validUntil)}`
    });
  });
  blocked.slice(0, 3).forEach((license) => {
    alerts.push({
      tone: "danger",
      title: t("blockedLicenseAlert"),
      detail: `${license.companyName} - ${license.licenseReference}`
    });
  });
  stale.slice(0, 3).forEach((installation) => {
    alerts.push({
      tone: "warning",
      title: t("staleInstallationAlert"),
      detail: `${installation.installationReference} - ${installation.lastValidatedAt ? formatDate(installation.lastValidatedAt) : "pendiente"}`
    });
  });

  return alerts;
}

function auditActionLabel(action: string) {
  const labels: Record<string, string> = {
    CREATE_COMPANY: "Empresa creada",
    UPDATE_COMPANY: "Empresa actualizada",
    CREATE_LICENSE: "Licencia creada",
    RENEW_LICENSE: "Licencia renovada",
    BLOCK_LICENSE: "Licencia bloqueada",
    UNBLOCK_LICENSE: "Licencia desbloqueada",
    REGENERATE_PAIRING_CODE: "Codigo de enlace regenerado",
    CREATE_ADMIN_USER: "Usuario admin creado",
    UPDATE_ADMIN_PASSWORD: "Password admin actualizada",
    CHANGE_ADMIN_PASSWORD: "Password admin actualizada",
    DELETE_ADMIN_USER: "Usuario admin desactivado",
    DEACTIVATE_ADMIN_USER: "Usuario admin desactivado",
    UPDATE_COMPANY_OPERATIONS: "Datos SaaS de empresa actualizados",
    CREATE_SUPPORT_TICKET: "Ticket de soporte creado",
    UPDATE_SUPPORT_TICKET: "Ticket de soporte actualizado"
  };
  return labels[action] ?? action.replaceAll("_", " ").toLowerCase().replace(/^\w/, (value) => value.toUpperCase());
}

function ticketStatusLabel(status: string, t: (key: string) => string) {
  const labels: Record<string, string> = {
    ABIERTO: t("open"),
    EN_CURSO: t("inProgress"),
    RESUELTO: t("resolve")
  };
  return labels[status] ?? status;
}

function ticketPriorityLabel(priority: string, t: (key: string) => string) {
  const labels: Record<string, string> = {
    NORMAL: t("normal"),
    ALTA: t("high"),
    URGENTE: t("urgent")
  };
  return labels[priority] ?? priority;
}

function normalizeSearch(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

function daysUntil(value: string) {
  return Math.ceil((new Date(value).getTime() - Date.now()) / 86_400_000);
}

function hoursSince(value: string) {
  return (Date.now() - new Date(value).getTime()) / 3_600_000;
}

function isToday(value: string) {
  const date = new Date(value);
  const now = new Date();
  return date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate();
}

function latestDate(values: string[]) {
  return values.reduce<string | null>((latest, value) => {
    if (!latest || new Date(value).getTime() > new Date(latest).getTime()) return value;
    return latest;
  }, null);
}

function sampleSyncEvents(
  mode: "events" | "sales" | "stock" | "cash",
  companies: Array<{ companyId: string; companyName: string }>,
  selectedCompanyId: string
): SyncEventView[] {
  const now = new Date();
  const profiles = demoProfiles();
  const selected = companies
    .map((company, index) => ({ company, index }))
    .filter((entry) => !selectedCompanyId || entry.company.companyId === selectedCompanyId);
  const sales = selected.flatMap(({ company, index }) => [
    sampleEvent({
      eventId: sampleUuid("1", index, 1),
      companyId: company.companyId,
      storeId: sampleStoreId(index),
      entityType: "DOCUMENTO",
      entityId: sampleUuid("2", index, 1),
      operation: "CONFIRMAR",
      receivedAt: minutesAgo(now, 7 + index * 11),
      payload: {
        ...profiles[index % profiles.length],
        empresa: company.companyName,
        tipo: "TICKET",
        numero: `T-2026-${String(145 + index).padStart(6, "0")}`,
        cliente: profiles[index % profiles.length].clienteTicket,
        total: profiles[index % profiles.length].totalTicket,
        impuestos: "IVA",
        lineas: [
          {
            productoId: profiles[index % profiles.length].productoVenta,
            descripcion: profiles[index % profiles.length].descripcionVenta,
            cantidad: profiles[index % profiles.length].cantidadVenta,
            total: profiles[index % profiles.length].totalTicket
          }
        ],
        pagos: [{ metodo: profiles[index % profiles.length].metodoPago, importe: profiles[index % profiles.length].totalTicket }]
      }
    }),
    sampleEvent({
      eventId: sampleUuid("1", index, 2),
      companyId: company.companyId,
      storeId: sampleStoreId(index),
      entityType: "DOCUMENTO",
      entityId: sampleUuid("2", index, 2),
      operation: "CONFIRMAR",
      receivedAt: minutesAgo(now, 28 + index * 13),
      payload: {
        ...profiles[index % profiles.length],
        empresa: company.companyName,
        tipo: "FACTURA_VENTA",
        numero: `FV-2026-${String(32 + index).padStart(6, "0")}`,
        cliente: profiles[index % profiles.length].clienteFactura,
        total: profiles[index % profiles.length].totalFactura,
        impuestos: "IVA",
        pagos: [{ metodo: "TRANSFERENCIA", importe: profiles[index % profiles.length].totalFactura }]
      }
    })
  ]);
  const stockEvents = selected.flatMap(({ company, index }) => [
    sampleEvent({
      eventId: sampleUuid("1", index, 3),
      companyId: company.companyId,
      storeId: sampleStoreId(index),
      entityType: "STOCK_MOVEMENT",
      entityId: sampleUuid("2", index, 3),
      operation: "CREAR",
      receivedAt: minutesAgo(now, 15 + index * 17),
      payload: {
        ...profiles[index % profiles.length],
        empresa: company.companyName,
        productoId: profiles[index % profiles.length].productoVenta,
        almacenId: "ALMACEN-PRINCIPAL",
        cantidad: profiles[index % profiles.length].cantidadStockSalida,
        motivo: "Salida por venta"
      }
    }),
    sampleEvent({
      eventId: sampleUuid("1", index, 4),
      companyId: company.companyId,
      storeId: sampleStoreId(index),
      entityType: "STOCK_MOVEMENT",
      entityId: sampleUuid("2", index, 4),
      operation: "CREAR",
      receivedAt: minutesAgo(now, 42 + index * 19),
      payload: {
        ...profiles[index % profiles.length],
        empresa: company.companyName,
        productoId: profiles[index % profiles.length].productoEntrada,
        almacenId: "ALMACEN-PRINCIPAL",
        cantidad: profiles[index % profiles.length].cantidadStockEntrada,
        motivo: "Entrada de proveedor"
      }
    })
  ]);
  const cash = selected.map(({ company, index }) =>
    sampleEvent({
      eventId: sampleUuid("1", index, 5),
      companyId: company.companyId,
      storeId: sampleStoreId(index),
      entityType: "CIERRE_CAJA",
      entityId: sampleUuid("2", index, 5),
      operation: "CERRAR",
      receivedAt: minutesAgo(now, 65 + index * 23),
      payload: {
        ...profiles[index % profiles.length],
        empresa: company.companyName,
        sesion: `CAJA-2026-07-03-T${index + 1}`,
        terminal: `TPV-0${index + 1}`,
        apertura: profiles[index % profiles.length].apertura,
        efectivoDeclarado: profiles[index % profiles.length].efectivo,
        tarjeta: profiles[index % profiles.length].tarjeta,
        totalCobrado: profiles[index % profiles.length].totalCaja,
        descuadre: profiles[index % profiles.length].descuadre
      }
    })
  );

  if (mode === "sales") return sales;
  if (mode === "stock") return stockEvents;
  if (mode === "cash") return cash;
  return [...sales, ...stockEvents, ...cash];
}

function sampleStock(companies: Array<{ companyId: string; companyName: string }>, selectedCompanyId: string): StockSnapshot[] {
  const profiles = demoProfiles();
  const selected = companies
    .map((company, index) => ({ company, index }))
    .filter((entry) => !selectedCompanyId || entry.company.companyId === selectedCompanyId);
  return selected.flatMap(({ company, index }) => [
    {
      companyId: company.companyId,
      storeId: sampleStoreId(index),
      productId: profiles[index % profiles.length].productoVenta,
      warehouseId: "ALMACEN-PRINCIPAL",
      quantity: profiles[index % profiles.length].stockActual
    },
    {
      companyId: company.companyId,
      storeId: sampleStoreId(index),
      productId: profiles[index % profiles.length].productoEntrada,
      warehouseId: "ALMACEN-PRINCIPAL",
      quantity: profiles[index % profiles.length].stockEntradaActual
    }
  ]);
}

function demoProfiles() {
  return [
    {
      clienteTicket: "Cliente mostrador",
      clienteFactura: "Gimnasio Centro SL",
      productoVenta: "CAMISETA-BASIC-BLANCA",
      descripcionVenta: "Camiseta Basic blanca",
      productoEntrada: "ZAPATILLA-RUNNER-42",
      cantidadVenta: "2",
      totalTicket: "48.75",
      totalFactura: "186.40",
      metodoPago: "TARJETA",
      cantidadStockSalida: "-2",
      cantidadStockEntrada: "6",
      stockActual: "34",
      stockEntradaActual: "6",
      apertura: "120.00",
      efectivo: "336.20",
      tarjeta: "482.15",
      totalCaja: "818.35",
      descuadre: "0.00"
    },
    {
      clienteTicket: "Restaurante Norte",
      clienteFactura: "Hosteleria Plaza SL",
      productoVenta: "CAFETERA-INDUSTRIAL",
      descripcionVenta: "Cafetera industrial acero",
      productoEntrada: "VASO-TAKEAWAY-200",
      cantidadVenta: "1",
      totalTicket: "92.30",
      totalFactura: "240.00",
      metodoPago: "EFECTIVO",
      cantidadStockSalida: "-1",
      cantidadStockEntrada: "100",
      stockActual: "8",
      stockEntradaActual: "100",
      apertura: "90.00",
      efectivo: "188.50",
      tarjeta: "241.80",
      totalCaja: "430.30",
      descuadre: "-1.50"
    },
    {
      clienteTicket: "Compra rapida tienda",
      clienteFactura: "Papeleria Sur CB",
      productoVenta: "BOLSA-PAPEL-MED",
      descripcionVenta: "Bolsa papel mediana",
      productoEntrada: "ETIQUETA-PRECIO",
      cantidadVenta: "12",
      totalTicket: "31.60",
      totalFactura: "74.95",
      metodoPago: "TARJETA",
      cantidadStockSalida: "-12",
      cantidadStockEntrada: "250",
      stockActual: "420",
      stockEntradaActual: "250",
      apertura: "150.00",
      efectivo: "420.00",
      tarjeta: "96.55",
      totalCaja: "516.55",
      descuadre: "0.00"
    }
  ];
}

function sampleEvent(value: Omit<SyncEventView, "installationId">): SyncEventView {
  return {
    ...value,
    installationId: "00000000-0000-4000-8000-000000000003"
  };
}

function minutesAgo(date: Date, minutes: number) {
  return new Date(date.getTime() - minutes * 60_000).toISOString();
}

function sampleStoreId(index: number) {
  return index === 0 ? DEMO_STORE_ID : `00000000-0000-4000-8000-${String(index + 2).padStart(12, "0")}`;
}

function sampleUuid(group: string, companyIndex: number, itemIndex: number) {
  return `${group}0000000-0000-4000-8000-${String(companyIndex * 10 + itemIndex).padStart(12, "0")}`;
}

function eventSummary(event: SyncEventView) {
  const payload = event.payload;
  const company = stringPayload(payload.empresa);
  if (event.entityType === "DOCUMENTO") {
    return `${company} - ${stringPayload(payload.numero)} - ${stringPayload(payload.cliente)} - ${stringPayload(payload.total)} EUR`;
  }
  if (event.entityType === "STOCK_MOVEMENT") {
    return `${company} - ${stringPayload(payload.productoId)} - ${stringPayload(payload.cantidad)} uds - ${stringPayload(payload.motivo)}`;
  }
  if (event.entityType === "CIERRE_CAJA") {
    return `${company} - ${stringPayload(payload.terminal)} - total ${stringPayload(payload.totalCobrado)} EUR - descuadre ${stringPayload(payload.descuadre)}`;
  }
  return company || "Evento sincronizado";
}

function stringPayload(value: unknown) {
  return value == null ? "" : String(value);
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("es-ES", {
    dateStyle: "short",
    timeStyle: "short"
  }).format(new Date(value));
}

function toLocalInput(date: Date) {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function addYears(date: Date, years: number) {
  const next = new Date(date);
  next.setFullYear(next.getFullYear() + years);
  return next;
}

function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

async function copyText(text: string) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Fall back to a temporary selection for browsers that block Clipboard API.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "true");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  textarea.style.top = "0";
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand("copy");
  document.body.removeChild(textarea);
  if (!copied) {
    throw new Error("Copy command rejected");
  }
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    try {
      const body = JSON.parse(error.message) as { error?: string; message?: string; status?: number };
      if (body.message) return body.message;
      if (body.error === "Internal Server Error") return "Error interno del backend SaaS.";
      if (body.error) return body.error;
      if (body.status) return `Error ${body.status}`;
    } catch {
      return error.message;
    }
  }
  if (error instanceof Error) return error.message;
  return "Operacion no completada";
}

function isMissingPhase3Endpoint(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

function isRecoverableBackendDataError(error: unknown) {
  return error instanceof ApiError && error.status >= 500;
}

function fallbackSession(username: string): AdminSession {
  return {
    username,
    permissions: fallbackPermissions(username)
  };
}

function fallbackPermissions(username?: string) {
  const normalized = username?.trim().toLowerCase();
  if (normalized === "viewer" || normalized === "auditor") {
    return ["VIEW_ADMIN_DATA"];
  }
  if (normalized === "support") {
    return ["VIEW_ADMIN_DATA", "REGENERATE_PAIRING_CODE", "MANAGE_SUPPORT_TICKETS"];
  }
  if (normalized === "billing") {
    return ["VIEW_ADMIN_DATA", "RENEW_LICENSE", "EDIT_COMPANY_DATA"];
  }
  return [
    "ADD_COMPANY",
    "RENEW_LICENSE",
    "BLOCK_LICENSE",
    "UNBLOCK_LICENSE",
    "EDIT_COMPANY_DATA",
    "VIEW_ADMIN_DATA",
    "REGENERATE_PAIRING_CODE",
    "MANAGE_ADMIN_USERS",
    "MANAGE_SUPPORT_TICKETS",
    "MANAGE_BILLING",
    "MANAGE_TENANT_USERS",
    "MANAGE_ERP_MASTERS"
  ];
}

function userManagementErrorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 403) {
    return "No tienes permiso para gestionar usuarios. Entra con un usuario ADMIN.";
  }
  return errorMessage(error);
}
