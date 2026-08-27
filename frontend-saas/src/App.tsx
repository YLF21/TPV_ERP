import { createContext, FormEvent, useContext, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, extractApiErrorMessage } from "./lib/api";
import type {
  AdminNotification,
  AdminSession,
  AdminUser,
  AdvancedReport,
  AuditLog,
  BillingInvoice,
  BillingSummary,
  CompanyOperations,
  CreateCompanyRequest,
  CustomerHealth,
  Credentials,
  LoginCredentials,
  DashboardData,
  ErpCustomer,
  ErpProduct,
  ErpSupplier,
  ErpWarehouse,
  IntegrationEndpoint,
  InventoryMovement,
  InventoryStock,
  InstallationSummary,
  LicenseSummary,
  OperationalIncident,
  PairingCodeResponse,
  SaasStatus,
  SalesDocument,
  StockSnapshot,
  Subscription,
  SupportTicket,
  SupportTicketComment,
  SyncEventView,
  SyncProjectionStatus,
  TenantPortalData,
  TenantUser,
  TaxRegime,
  TechnicalStatus,
  TaxpayerType,
  CommercialProfile,
  FiscalAddress,
  FiscalProvisioning,
  VerifactuActivationPolicy,
  FiscalStatusAdmin,
  FiscalCompanyStatusAdmin
} from "./lib/types";

type View = "dashboard" | "licenses" | "sync" | "fiscal" | "users" | "audit" | "support" | "health" | "billing" | "masters" | "operations" | "subscriptions" | "reports";
type Notice = { type: "success" | "error"; text: string } | null;
type LicenseAction = "block" | "unblock" | "pairing";
type SaasAdminRoleName = "ADMIN" | "VIEWER" | "SUPPORT" | "BILLING" | "AUDITOR";
type TenantAssignableRoleName = "MANAGER" | "VIEWER" | "BILLING";
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
    fiscal: "Estado fiscal",
    users: "Usuarios",
    audit: "Auditoria",
    supportCenter: "Soporte",
    customerHealth: "Pulso",
    billing: "Facturacion",
    masters: "Maestros",
    operations: "Operaciones",
    subscriptions: "Suscripciones",
    reports: "Informes",
    logout: "Salir",
    centralPanel: "Panel central",
    sessionContext: "Sesion",
    moduleContext: "Modulo",
    launchPad: "Accesos operativos",
    launchPadSubtitle: "Entrada rapida a las areas principales del SaaS",
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
    commercialProfile: "Perfil comercial",
    storeCode: "Codigo tienda",
    storeName: "Nombre tienda",
    storeTimeZone: "Zona horaria de la tienda",
    companyAddress: "Domicilio fiscal de la empresa",
    storeAddress: "Domicilio de la tienda",
    fiscalProvisioning: "Datos fiscales de aprovisionamiento",
    fiscalProvisioningSubtitle: "Identidad operativa usada al vincular o sustituir una instalación",
    fiscalProvisioningUpdated: "Datos fiscales actualizados.",
    addressLine: "Direccion",
    city: "Ciudad",
    postalCode: "Codigo postal",
    province: "Provincia",
    country: "Pais",
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
    expiredStatus: "Caducada",
    generateCode: "Generar codigo",
    generating: "Generando",
    block: "Bloquear",
    unblock: "Desbloquear",
    noLicenses: "No hay licencias.",
    verifactuPolicy: "Activacion global de VeriFactu",
    verifactuPolicySubtitle: "Fechas distribuidas automaticamente a las licencias activas segun el tipo de contribuyente",
    verifactuPolicyLoading: "Cargando politica fiscal...",
    verifactuPolicyEmpty: "No hay una politica de activacion configurada.",
    verifactuCompany: "Sociedades",
    verifactuSelfEmployed: "Autonomos",
    activationDate: "Fecha de activacion",
    changeReason: "Motivo del cambio",
    changeReasonPlaceholder: "Ej.: ampliacion del plazo publicada por la AEAT",
    policyVersion: "Version",
    updatedBy: "Actualizada por",
    affectedLicenses: "Licencias activas",
    affectedInstallations: "Instalaciones vinculadas",
    currentReason: "Motivo vigente",
    updatePolicy: "Actualizar politica",
    policyUpdated: "Politica de activacion de VeriFactu actualizada.",
    policyReadOnly: "Solo los usuarios con MANAGE_FISCAL_POLICY pueden modificar estas fechas.",
    policyReasonRequired: "Indica un motivo de al menos 3 caracteres.",
    policyConfirm: "Este cambio se distribuira a las licencias activas en su siguiente validacion. ¿Confirmas la nueva fecha?",
    linkedAt: "Vinculada",
    lastValidation: "Ultima validacion",
    pending: "Pendiente",
    noLinkedInstallations: "No hay instalaciones vinculadas.",
    notLinked: "Sin vincular",
    fiscalStatusSubtitle: "Modalidad efectiva comunicada por cada tienda e instalacion",
    fiscalCompany: "Empresa",
    fiscalStore: "Tienda",
    fiscalInstallation: "Instalacion",
    fiscalMode: "Modalidad",
    fiscalActivationState: "Estado activacion",
    fiscalEnvironment: "Entorno",
    fiscalTransport: "Transporte",
    fiscalLastReport: "Ultimo reporte",
    fiscalActivationDate: "Fecha obligatoria",
    fiscalNoData: "Aun no hay estados fiscales recibidos.",
    fiscalPreSif: "PRE-SIF",
    fiscalNoVerifactu: "NO VERI*FACTU",
    fiscalVerifactu: "VERI*FACTU",
    fiscalActive: "Activo",
    fiscalPending: "Pendiente",
    fiscalDueReview: "Requiere activacion local",
    fiscalUnknown: "Sin politica conocida",
    fiscalMixed: "Mixto",
    fiscalStale: "Sin comunicacion reciente",
    fiscalReadOnly: "Consulta de solo lectura: la modalidad se decide y activa en la instalacion local.",
    syncSubtitle: "Eventos enviados desde tiendas",
    consultingEvents: "Consultando eventos",
    events: "Eventos",
    sales: "Ventas",
    stock: "Stock",
    cash: "Caja",
    allCompanies: "Todas las empresas",
    noEventsForFilter: "No hay eventos para el filtro actual.",
    incidents: "Incidencias",
    operationalIncidents: "Incidencias operativas",
    operationalIncidentsSubtitle: "Procesos centrales detenidos que requieren una decision administrativa auditada",
    operationalIncidentCount: "Incidencias activas",
    noOperationalIncidents: "No hay incidencias operativas para el filtro actual.",
    operationalIncidentPermission: "Se requiere VIEW_ADMIN_DATA para consultar incidencias y MANAGE_OPERATIONAL_INCIDENTS para resolverlas.",
    incidentProcess: "Proceso",
    memberCategoryBootstrap: "Bootstrap de categorias de socio",
    incidentProgress: "Tiendas",
    incidentInactivity: "Inactividad",
    incidentInactive: "Inactiva",
    incidentRecent: "Actividad reciente",
    incidentSnapshots: "Snapshots",
    incidentChunks: "Bloques",
    incidentLastActivity: "Ultima actividad",
    incidentConflict: "Conflicto",
    incidentBaseline: "Baseline completado",
    cancelIncident: "Cancelar residual",
    cancelIncidentTitle: "Cancelar bootstrap residual",
    cancelIncidentWarning: "La cancelacion queda auditada y no elimina datos. Confirma que el proceso residual no contiene informacion util.",
    cancelIncidentReason: "Motivo obligatorio",
    cancelIncidentReasonPlaceholder: "Ej.: bootstrap residual vacio revisado por soporte",
    cancelIncidentReasonRequired: "Indica un motivo de al menos 5 caracteres.",
    confirmCancellation: "Confirmar cancelacion",
    cancelling: "Cancelando",
    close: "Cerrar",
    incidentCancelled: "Bootstrap residual cancelado y auditado.",
    incidentConflictReloaded: "La incidencia ha cambiado desde la consulta. Se han recargado los datos.",
    eventProjection: "Proyeccion",
    eventSchemaVersion: "Esquema",
    eventProjectionError: "Error de proyeccion",
    projectionHealth: "Estado de proyeccion central",
    projectionReceived: "Pendientes RECEIVED",
    projectionProjected: "PROJECTED",
    projectionIgnored: "IGNORED",
    projectionErrors: "ERROR",
    projectionOldestReceived: "Pendiente mas antiguo",
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
    companySelectionChanged: "La empresa seleccionada ha cambiado. Revisa los datos y vuelve a intentarlo.",
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
    revoked: "Revocada",
    revokeInstallation: "Revocar instalacion",
    revocationReasonPrompt: "Indica el motivo de revocacion (minimo 5 caracteres). La instalacion dejara de validar y sincronizar:",
    revocationReasonRequired: "El motivo de revocacion debe tener al menos 5 caracteres.",
    installationRevoked: "Instalacion revocada y credencial invalidada.",
    revokedAt: "Revocada el",
    revokedBy: "Revocada por",
    actions: "Acciones",
    phase2Operations: "Gestion SaaS",
    phase2OperationsSubtitle: "Licencia, facturacion y soporte",
    saveChanges: "Guardar cambios",
    saving: "Guardando",
    renewLicense: "Renovar licencia",
    editCompany: "Editar empresa",
    fiscalIdentityLocked: "Tipo de obligado y regimen fiscal bloqueados tras emitir la licencia.",
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
    ,
    realOperations: "Ventas e inventario real",
    realOperationsSubtitle: "Documentos de venta, movimientos y stock calculado",
    salesDocuments: "Documentos de venta",
    documentNumber: "Numero documento",
    customerCode: "Codigo cliente",
    issueSale: "Crear venta",
    inventoryMovements: "Movimientos de inventario",
    movementType: "Tipo movimiento",
    stockCurrent: "Stock actual",
    reason: "Motivo",
    createMovement: "Crear movimiento",
    subscriptionsTitle: "Suscripciones SaaS",
    subscriptionsSubtitle: "Planes, ciclos, renovaciones y estado de cobro",
    billingCycle: "Ciclo",
    nextBillingAt: "Proxima factura",
    startedAt: "Inicio",
    cancelSubscription: "Cancelar suscripcion",
    createSubscription: "Crear suscripcion",
    integrations: "Integraciones",
    integrationsSubtitle: "Conectores, webhooks y claves de intercambio",
    integrationType: "Tipo integracion",
    targetUrl: "URL destino",
    apiKey: "API key",
    apiKeyPreview: "API key",
    lastSyncAt: "Ultima ejecucion",
    markSynced: "Marcar sincronizada",
    createIntegration: "Crear integracion",
    advancedReports: "Informes avanzados",
    advancedReportsSubtitle: "Resumen agregado de SaaS, ventas, cobros e integraciones",
    subscriptionMrr: "MRR suscripciones",
    invoicedTotal: "Facturado",
    paidTotal: "Cobrado",
    salesTotal: "Ventas reales",
    activeIntegrations: "Integraciones activas",
    itemCreated: "Registro creado.",
    itemUpdated: "Registro actualizado.",
    phase11Pending: "Esta fase necesita reiniciar el backend SaaS para aplicar la migracion V11.",
    noPermissionAction: "Tu usuario no tiene permiso para esta accion.",
    invalidAmount: "Introduce un importe numerico valido.",
    invalidUrl: "Introduce una URL valida.",
    duplicateCode: "Ya existe un registro con ese codigo en esta empresa.",
    backendNotUpdated: "El backend SaaS no tiene esta fase activa. Reinicialo para aplicar las migraciones.",
    resourceNotFound: "No se ha encontrado el recurso solicitado.",
    forbiddenAction: "No tienes permiso para realizar esta accion.",
    invalidCredentials: "Credenciales incorrectas o sesion no valida.",
    networkError: "No se pudo conectar con el backend SaaS.",
    pendingInvoices: "Facturas pendientes"
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
    fiscal: "Fiscal status",
    users: "Users",
    audit: "Audit",
    supportCenter: "Support",
    customerHealth: "Health",
    billing: "Billing",
    masters: "Masters",
    logout: "Sign out",
    centralPanel: "Central panel",
    sessionContext: "Session",
    moduleContext: "Module",
    launchPad: "Operational shortcuts",
    launchPadSubtitle: "Quick entry to the main SaaS areas",
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
    commercialProfile: "Commercial profile",
    storeCode: "Store code",
    storeName: "Store name",
    storeTimeZone: "Store time zone",
    companyAddress: "Company fiscal address",
    storeAddress: "Store address",
    fiscalProvisioning: "Fiscal provisioning data",
    fiscalProvisioningSubtitle: "Operational identity used when linking or replacing an installation",
    fiscalProvisioningUpdated: "Fiscal provisioning data updated.",
    addressLine: "Address line",
    city: "City",
    postalCode: "Postal code",
    province: "Province",
    country: "Country",
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
    expiredStatus: "Expired",
    generateCode: "Generate code",
    generating: "Generating",
    block: "Block",
    unblock: "Unblock",
    noLicenses: "No licenses.",
    verifactuPolicy: "Global VeriFactu activation",
    verifactuPolicySubtitle: "Dates automatically distributed to active licenses by taxpayer type",
    verifactuPolicyLoading: "Loading fiscal policy...",
    verifactuPolicyEmpty: "No activation policy is configured.",
    verifactuCompany: "Companies",
    verifactuSelfEmployed: "Self-employed",
    activationDate: "Activation date",
    changeReason: "Reason for change",
    changeReasonPlaceholder: "E.g. deadline extension published by the AEAT",
    policyVersion: "Version",
    updatedBy: "Updated by",
    affectedLicenses: "Active licenses",
    affectedInstallations: "Linked installations",
    currentReason: "Current reason",
    updatePolicy: "Update policy",
    policyUpdated: "VeriFactu activation policy updated.",
    policyReadOnly: "Only users with MANAGE_FISCAL_POLICY can change these dates.",
    policyReasonRequired: "Enter a reason with at least 3 characters.",
    policyConfirm: "This change will be distributed to active licenses during their next validation. Confirm the new date?",
    linkedAt: "Linked at",
    lastValidation: "Last validation",
    pending: "Pending",
    noLinkedInstallations: "No linked installations.",
    notLinked: "Not linked",
    fiscalStatusSubtitle: "Effective modality reported by each store and installation",
    fiscalCompany: "Company",
    fiscalStore: "Store",
    fiscalInstallation: "Installation",
    fiscalMode: "Modality",
    fiscalActivationState: "Activation state",
    fiscalEnvironment: "Environment",
    fiscalTransport: "Transport",
    fiscalLastReport: "Last report",
    fiscalActivationDate: "Mandatory date",
    fiscalNoData: "No fiscal status has been received yet.",
    fiscalPreSif: "PRE-SIF",
    fiscalNoVerifactu: "NO VERI*FACTU",
    fiscalVerifactu: "VERI*FACTU",
    fiscalActive: "Active",
    fiscalPending: "Pending",
    fiscalDueReview: "Local activation required",
    fiscalUnknown: "Policy unknown",
    fiscalMixed: "Mixed",
    fiscalStale: "No recent communication",
    fiscalReadOnly: "Read-only view: modality is selected and activated at the local installation.",
    syncSubtitle: "Events sent from stores",
    consultingEvents: "Checking events",
    events: "Events",
    sales: "Sales",
    stock: "Stock",
    cash: "Cash",
    allCompanies: "All companies",
    noEventsForFilter: "No events for the current filter.",
    incidents: "Incidents",
    operationalIncidents: "Operational incidents",
    operationalIncidentsSubtitle: "Stalled central processes requiring an audited administrative decision",
    operationalIncidentCount: "Active incidents",
    noOperationalIncidents: "No operational incidents for the current filter.",
    operationalIncidentPermission: "VIEW_ADMIN_DATA is required to view incidents and MANAGE_OPERATIONAL_INCIDENTS to resolve them.",
    incidentProcess: "Process",
    memberCategoryBootstrap: "Member category bootstrap",
    incidentProgress: "Stores",
    incidentInactivity: "Inactivity",
    incidentInactive: "Inactive",
    incidentRecent: "Recent activity",
    incidentSnapshots: "Snapshots",
    incidentChunks: "Chunks",
    incidentLastActivity: "Last activity",
    incidentConflict: "Conflict",
    incidentBaseline: "Completed baseline",
    cancelIncident: "Cancel residual",
    cancelIncidentTitle: "Cancel residual bootstrap",
    cancelIncidentWarning: "Cancellation is audited and does not delete data. Confirm that the residual process contains no useful information.",
    cancelIncidentReason: "Required reason",
    cancelIncidentReasonPlaceholder: "E.g. empty residual bootstrap reviewed by support",
    cancelIncidentReasonRequired: "Enter a reason with at least 5 characters.",
    confirmCancellation: "Confirm cancellation",
    cancelling: "Cancelling",
    close: "Close",
    incidentCancelled: "Residual bootstrap cancelled and audited.",
    incidentConflictReloaded: "The incident changed after it was loaded. The data has been refreshed.",
    eventProjection: "Projection",
    eventSchemaVersion: "Schema",
    eventProjectionError: "Projection error",
    projectionHealth: "Central projection status",
    projectionReceived: "Pending RECEIVED",
    projectionProjected: "PROJECTED",
    projectionIgnored: "IGNORED",
    projectionErrors: "ERROR",
    projectionOldestReceived: "Oldest pending",
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
    companySelectionChanged: "The selected company changed. Review the data and try again.",
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
    revoked: "Revoked",
    revokeInstallation: "Revoke installation",
    revocationReasonPrompt: "Enter the revocation reason (at least 5 characters). The installation will no longer validate or sync:",
    revocationReasonRequired: "The revocation reason must contain at least 5 characters.",
    installationRevoked: "Installation revoked and credential invalidated.",
    revokedAt: "Revoked at",
    revokedBy: "Revoked by",
    actions: "Actions",
    phase2Operations: "SaaS management",
    phase2OperationsSubtitle: "License, billing and support",
    saveChanges: "Save changes",
    saving: "Saving",
    renewLicense: "Renew license",
    editCompany: "Edit company",
    fiscalIdentityLocked: "Taxpayer type and tax regime are locked after the license is issued.",
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
    fiscal: "税务状态",
    users: "用户",
    audit: "审计",
    logout: "退出",
    centralPanel: "控制面板",
    sessionContext: "会话",
    moduleContext: "模块",
    launchPad: "快捷入口",
    launchPadSubtitle: "快速进入 SaaS 主要区域",
    refresh: "刷新",
    refreshing: "刷新中",
    loadingSaas: "正在加载 SaaS 数据...",
    noLoadedData: "暂无已加载数据。",
    companySelectionChanged: "所选公司已更改。请检查数据后重试。",
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
    fiscalIdentityLocked: "许可证签发后，纳税人类型和税制将被锁定。",
    company: "公司",
    taxId: "税号",
    type: "类型",
    taxes: "税制",
    commercialProfile: "商业模式",
    storeCode: "门店代码",
    storeName: "门店名称",
    storeTimeZone: "门店时区",
    companyAddress: "公司税务地址",
    storeAddress: "门店地址",
    fiscalProvisioning: "税务配置资料",
    fiscalProvisioningSubtitle: "绑定或更换安装时使用的业务身份资料",
    fiscalProvisioningUpdated: "税务配置资料已更新。",
    addressLine: "地址",
    city: "城市",
    postalCode: "邮政编码",
    province: "省/州",
    country: "国家",
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
    expiredStatus: "已过期",
    generateCode: "生成代码",
    generating: "生成中",
    block: "锁定",
    unblock: "解锁",
    noLicenses: "暂无许可证。",
    verifactuPolicy: "VeriFactu 全局启用策略",
    verifactuPolicySubtitle: "按纳税人类型自动向有效许可证分发启用日期",
    verifactuPolicyLoading: "正在加载税务策略...",
    verifactuPolicyEmpty: "尚未配置启用策略。",
    verifactuCompany: "公司",
    verifactuSelfEmployed: "个体经营者",
    activationDate: "启用日期",
    changeReason: "变更原因",
    changeReasonPlaceholder: "例如：AEAT 公布延长期限",
    policyVersion: "版本",
    updatedBy: "更新人",
    affectedLicenses: "有效许可证",
    affectedInstallations: "已连接安装",
    currentReason: "当前原因",
    updatePolicy: "更新策略",
    policyUpdated: "VeriFactu 启用策略已更新。",
    policyReadOnly: "只有拥有 MANAGE_FISCAL_POLICY 权限的用户才能修改这些日期。",
    policyReasonRequired: "请输入至少 3 个字符的原因。",
    policyConfirm: "此变更将在下次验证时分发到有效许可证。是否确认新日期？",
    linkedAt: "绑定时间",
    lastValidation: "最后验证",
    pending: "待处理",
    noLinkedInstallations: "暂无已绑定安装。",
    notLinked: "未绑定",
    revoked: "已撤销",
    revokeInstallation: "撤销安装",
    revocationReasonPrompt: "请输入撤销原因（至少 5 个字符）。该安装将无法继续验证或同步：",
    revocationReasonRequired: "撤销原因必须至少包含 5 个字符。",
    installationRevoked: "安装已撤销，凭据已失效。",
    revokedAt: "撤销时间",
    revokedBy: "撤销人",
    actions: "操作",
    fiscalStatusSubtitle: "每个门店和安装报告的当前税务模式",
    fiscalCompany: "公司",
    fiscalStore: "门店",
    fiscalInstallation: "安装",
    fiscalMode: "模式",
    fiscalActivationState: "启用状态",
    fiscalEnvironment: "环境",
    fiscalTransport: "传输",
    fiscalLastReport: "最后报告",
    fiscalActivationDate: "强制日期",
    fiscalNoData: "尚未收到税务状态。",
    fiscalPreSif: "PRE-SIF",
    fiscalNoVerifactu: "NO VERI*FACTU",
    fiscalVerifactu: "VERI*FACTU",
    fiscalActive: "已启用",
    fiscalPending: "待处理",
    fiscalDueReview: "需要本地启用",
    fiscalUnknown: "未知策略",
    fiscalMixed: "混合",
    fiscalStale: "近期无通信",
    fiscalReadOnly: "只读视图：模式由本地安装选择和启用。",
    syncSubtitle: "门店发送的事件",
    consultingEvents: "正在查询事件",
    events: "事件",
    sales: "销售",
    stock: "库存",
    cash: "收银",
    allCompanies: "全部公司",
    noEventsForFilter: "当前筛选暂无事件。",
    incidents: "事件异常",
    operationalIncidents: "运行事件异常",
    operationalIncidentsSubtitle: "需要审计管理决策的已停滞中央流程",
    operationalIncidentCount: "活动异常数",
    noOperationalIncidents: "当前筛选没有运行事件异常。",
    operationalIncidentPermission: "查看异常需要 VIEW_ADMIN_DATA 权限，处理异常需要 MANAGE_OPERATIONAL_INCIDENTS 权限。",
    incidentProcess: "流程",
    memberCategoryBootstrap: "会员类别引导流程",
    incidentProgress: "门店",
    incidentInactivity: "活动状态",
    incidentInactive: "已停滞",
    incidentRecent: "近期有活动",
    incidentSnapshots: "快照",
    incidentChunks: "数据块",
    incidentLastActivity: "最后活动",
    incidentConflict: "冲突",
    incidentBaseline: "已完成基线",
    cancelIncident: "取消残留流程",
    cancelIncidentTitle: "取消残留引导流程",
    cancelIncidentWarning: "取消操作会被审计且不会删除数据。请确认残留流程中没有有效信息。",
    cancelIncidentReason: "必填原因",
    cancelIncidentReasonPlaceholder: "例如：支持人员已确认残留引导流程为空",
    cancelIncidentReasonRequired: "请输入至少 5 个字符的原因。",
    confirmCancellation: "确认取消",
    cancelling: "正在取消",
    close: "关闭",
    incidentCancelled: "残留引导流程已取消并记录审计。",
    incidentConflictReloaded: "该异常自加载后已发生变化，数据已刷新。",
    eventProjection: "投影状态",
    eventSchemaVersion: "架构版本",
    eventProjectionError: "投影错误",
    projectionHealth: "中央投影状态",
    projectionReceived: "待处理 RECEIVED",
    projectionProjected: "PROJECTED",
    projectionIgnored: "IGNORED",
    projectionErrors: "ERROR",
    projectionOldestReceived: "最早待处理时间",
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
  commercialProfile: "MAYORISTA",
  companyAddress: { linea1: "", ciudad: "", codigoPostal: "", provincia: "", pais: "ES" },
  storeCode: "001",
  storeName: "",
  storeAddress: { linea1: "", ciudad: "", codigoPostal: "", provincia: "", pais: "ES" },
  timeZoneId: "Atlantic/Canary",
  validUntil: toLocalInput(addYears(new Date(), 1)),
  maxWindows: 1,
  maxPda: 0
};

function emptyFiscalAddress(): FiscalAddress {
  return { linea1: "", ciudad: "", codigoPostal: "", provincia: "", pais: "ES" };
}

export default function App() {
  const [credentials, setCredentials] = useState<Credentials | null>(null);
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
  const permissions = useMemo(() => new Set(session?.permissions ?? []), [session]);

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
        api.session(activeCredentials)
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
      } else {
        setNotice({ type: "error", text: errorMessage(error) });
      }
    } finally {
      setLoading(false);
    }
  }

  async function login(nextCredentials: LoginCredentials) {
    setLoading(true);
    setNotice(null);
    try {
      const authenticated = await api.login(nextCredentials);
      setCredentials({
        username: authenticated.username,
        accessToken: authenticated.accessToken
      });
    } catch (error) {
      setNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setLoading(false);
    }
  }

  function logout() {
    if (credentials) {
      void api.logout(credentials).catch(() => undefined);
    }
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
        <LoginScreen onLogin={login} loading={loading} notice={notice} />
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
          <NavButton active={activeView === "fiscal"} onClick={() => setActiveView("fiscal")} label={i18n.t("fiscal")} />
          <NavButton active={activeView === "users"} onClick={() => setActiveView("users")} label={i18n.t("users")} />
          <NavButton active={activeView === "support"} onClick={() => setActiveView("support")} label={i18n.t("supportCenter")} />
          <NavButton active={activeView === "health"} onClick={() => setActiveView("health")} label={i18n.t("customerHealth")} />
          <NavButton active={activeView === "billing"} onClick={() => setActiveView("billing")} label={i18n.t("billing")} />
          <NavButton active={activeView === "masters"} onClick={() => setActiveView("masters")} label={i18n.t("masters")} />
          <NavButton active={activeView === "operations"} onClick={() => setActiveView("operations")} label={i18n.t("operations")} />
          <NavButton active={activeView === "subscriptions"} onClick={() => setActiveView("subscriptions")} label={i18n.t("subscriptions")} />
          <NavButton active={activeView === "reports"} onClick={() => setActiveView("reports")} label={i18n.t("reports")} />
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
            {activeView === "dashboard" && <Dashboard data={visibleData} onNavigate={setActiveView} />}
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
            {activeView === "sync" && (
              <SyncView
                credentials={credentials}
                licenses={visibleData.licenses}
                permissions={permissions}
                onNotice={setNotice}
              />
            )}
            {activeView === "fiscal" && <FiscalStatusView credentials={credentials} licenses={visibleData.licenses} onNotice={setNotice} />}
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
              <BillingView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "masters" && (
              <MastersView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "operations" && (
              <OperationsView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "subscriptions" && (
              <SubscriptionsView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "reports" && (
              <ReportsView credentials={credentials} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "audit" && <AuditView audit={visibleData.audit} />}
          </>
        )}
      </main>
      <footer className="app-context-footer" aria-label="Contexto SaaS">
        <span>ERP SaaS</span>
        <strong>{i18n.t("sessionContext")}: {session?.username ?? credentials.username}</strong>
        <strong>{i18n.t("moduleContext")}: {viewTitle(activeView, i18n.t)}</strong>
      </footer>
    </div>
    )}
    </I18nContext.Provider>
  );
}

function LoginScreen({
  onLogin,
  loading,
  notice
}: {
  onLogin: (credentials: LoginCredentials) => Promise<void>;
  loading: boolean;
  notice: Notice;
}) {
  const { t } = useI18n();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    void onLogin({ username: username.trim(), password });
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
        {notice && <div className={`notice ${notice.type}`}>{notice.text}</div>}
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
          <button className="primary-button" type="submit" disabled={loading}>
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

function Dashboard({ data, onNavigate }: { data: DashboardData; onNavigate: (view: View) => void }) {
  const { t } = useI18n();
  const activeLicenses = data.licenses.filter((license) => license.status === "VALIDA").length;
  const blockedLicenses = data.licenses.filter((license) => license.status === "BLOQUEADA_MANUAL").length;
  const activeUsers = data.users.filter((user) => user.active).length;
  const lastEvent = data.events[0];
  const alerts = operationalAlerts(data, t);
  const report = data.advancedReport;
  const activeSubscriptions = data.subscriptions?.filter((item) => item.status === "ACTIVA").length ?? "-";
  const activeIntegrations = report?.activeIntegrations ?? data.integrations?.filter((item) => item.status === "ACTIVA").length ?? "-";
  const moduleCards: Array<{ view: View; label: string; detail: string; value: string | number }> = [
    { view: "licenses", label: t("licenses"), detail: t("licensesCompanies"), value: data.licenses.length },
    { view: "sync", label: t("sync"), detail: t("syncSubtitle"), value: data.events.length },
    { view: "support", label: t("supportCenter"), detail: t("supportTicketsSubtitle"), value: alerts.length },
    { view: "health", label: t("customerHealth"), detail: t("healthSubtitle"), value: blockedLicenses },
    { view: "billing", label: t("billing"), detail: t("billingSubtitle"), value: report ? formatMoney(report.invoicedTotal) : data.salesSummary.total },
    { view: "masters", label: t("masters"), detail: t("erpMastersSubtitle"), value: data.stockCurrent.length },
    { view: "operations", label: t("operations"), detail: t("realOperationsSubtitle"), value: data.events.length },
    { view: "subscriptions", label: t("subscriptions"), detail: t("subscriptionsSubtitle"), value: activeSubscriptions },
    { view: "reports", label: t("reports"), detail: t("advancedReportsSubtitle"), value: activeIntegrations }
  ];

  return (
    <div className="view-grid">
      <section className="launch-pad" aria-label={t("launchPad")}>
        <SectionHeader title={t("launchPad")} subtitle={t("launchPadSubtitle")} />
        <div className="module-grid">
          {moduleCards.map((card) => (
            <button className="module-card" type="button" key={card.view} onClick={() => onNavigate(card.view)}>
              <span>{card.label}</span>
              <strong>{card.value}</strong>
              <small>{card.detail}</small>
            </button>
          ))}
        </div>
      </section>

      <section className="metric-grid">
        <Metric label={t("validLicenses")} value={activeLicenses} />
        <Metric label={t("blocked")} value={blockedLicenses} tone="warning" />
        <Metric label={t("installations")} value={data.installations.length} />
        <Metric label={t("activeUsers")} value={activeUsers} />
        <Metric label={t("syncedSales")} value={report?.salesDocuments ?? data.salesSummary.documentCount} detail={`${formatMoney(report?.salesTotal ?? data.salesSummary.total)} ${t("total")}`} />
        <Metric label={t("invoices")} value={report?.invoices ?? "-"} detail={report ? `${t("paidTotal")}: ${formatMoney(report.paidTotal)}` : undefined} />
        <Metric label={t("subscriptionMrr")} value={formatMoney(report?.subscriptionMrr ?? "0")} />
        <Metric label={t("activeIntegrations")} value={activeIntegrations} />
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
  const canEditCompany = permissions.has("EDIT_COMPANY_DATA");
  const canRenewLicense = permissions.has("RENEW_LICENSE");
  const canGenerateCode = permissions.has("REGENERATE_PAIRING_CODE");
  const canBlockLicense = permissions.has("BLOCK_LICENSE");
  const canUnblockLicense = permissions.has("UNBLOCK_LICENSE");
  const canRevokeInstallation = permissions.has("REVOKE_INSTALLATION");
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
    const permitted = action === "block"
      ? canBlockLicense
      : action === "unblock"
        ? canUnblockLicense
        : canGenerateCode;
    if (!permitted) return;
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

  async function revokeInstallation(installation: InstallationSummary) {
    if (!canRevokeInstallation || !installation.active) return;
    const reason = window.prompt(t("revocationReasonPrompt"))?.trim();
    if (reason == null) return;
    if (reason.length < 5) {
      onNotice({ type: "error", text: t("revocationReasonRequired") });
      return;
    }
    setBusy(`revoke:${installation.installationId}`);
    try {
      await api.revokeInstallation(credentials, installation.installationId, reason);
      onNotice({ type: "success", text: t("installationRevoked") });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="view-grid">
      <VerifactuPolicySection
        credentials={credentials}
        canManage={permissions.has("MANAGE_FISCAL_POLICY")}
        onChanged={onChanged}
        onNotice={onNotice}
      />

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
            <Select
              label={t("commercialProfile")}
              value={companyForm.commercialProfile}
              options={["MAYORISTA", "MINORISTA"]}
              onChange={(commercialProfile) => setCompanyForm({ ...companyForm, commercialProfile: commercialProfile as CommercialProfile })}
            />
            <AddressFields
              title={t("companyAddress")}
              value={companyForm.companyAddress}
              onChange={(companyAddress) => setCompanyForm({ ...companyForm, companyAddress })}
            />
            <Input
              label={t("storeCode")}
              value={companyForm.storeCode}
              onChange={(storeCode) => setCompanyForm({ ...companyForm, storeCode })}
              required
            />
            <Input label={t("storeName")} value={companyForm.storeName} onChange={(storeName) => setCompanyForm({ ...companyForm, storeName })} />
            <AddressFields
              title={t("storeAddress")}
              value={companyForm.storeAddress}
              onChange={(storeAddress) => setCompanyForm({ ...companyForm, storeAddress })}
            />
            <Select
              label={t("storeTimeZone")}
              value={companyForm.timeZoneId}
              options={["Atlantic/Canary", "Europe/Madrid"]}
              onChange={(timeZoneId) => setCompanyForm({ ...companyForm, timeZoneId })}
            />
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
          showBlockAction={canBlockLicense}
          showUnblockAction={canUnblockLicense}
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
        canEditCompany={canEditCompany}
        canRenewLicense={canRenewLicense}
        canRevokeInstallation={canRevokeInstallation}
        installationBusy={busy}
        onRevokeInstallation={(installation) => void revokeInstallation(installation)}
        onChanged={onChanged}
        onNotice={onNotice}
      />

      <section className="content-section">
        <SectionHeader title={t("linkedInstallations")} subtitle={`${installations.length} ${t("installations").toLowerCase()}`} />
        <InstallationsTable
          installations={installations}
          canRevoke={canRevokeInstallation}
          busy={busy}
          onRevoke={(installation) => void revokeInstallation(installation)}
        />
      </section>
    </div>
  );
}

function VerifactuPolicySection({
  credentials,
  canManage,
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  canManage: boolean;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [policies, setPolicies] = useState<VerifactuActivationPolicy[]>([]);
  const [dates, setDates] = useState<Partial<Record<TaxpayerType, string>>>({});
  const [reasons, setReasons] = useState<Partial<Record<TaxpayerType, string>>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<TaxpayerType | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.verifactuActivationPolicies(credentials)
      .then((values) => {
        if (cancelled) return;
        setPolicies(values);
        setDates(Object.fromEntries(values.map((policy) => [policy.taxpayerType, policy.activationDate])));
      })
      .catch((error) => {
        if (!cancelled) onNotice({ type: "error", text: errorMessage(error) });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [credentials, onNotice]);

  async function updatePolicy(policy: VerifactuActivationPolicy) {
    const activationDate = dates[policy.taxpayerType] ?? "";
    const reason = reasons[policy.taxpayerType]?.trim() ?? "";
    if (!activationDate || reason.length < 3) {
      onNotice({ type: "error", text: t("policyReasonRequired") });
      return;
    }
    if (!window.confirm(`${t("policyConfirm")}\n\n${taxpayerLabel(policy.taxpayerType, t)}: ${activationDate}`)) return;

    setBusy(policy.taxpayerType);
    try {
      const updated = await api.updateVerifactuActivationPolicy(credentials, policy.taxpayerType, { activationDate, reason });
      setPolicies((current) => current.map((item) => item.taxpayerType === updated.taxpayerType ? updated : item));
      setDates((current) => ({ ...current, [updated.taxpayerType]: updated.activationDate }));
      setReasons((current) => ({ ...current, [updated.taxpayerType]: "" }));
      onNotice({ type: "success", text: t("policyUpdated") });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="content-section verifactu-policy-section">
      <SectionHeader title={t("verifactuPolicy")} subtitle={t("verifactuPolicySubtitle")} />
      {!canManage && <div className="permission-hint">{t("policyReadOnly")}</div>}
      {loading ? (
        <EmptyState text={t("verifactuPolicyLoading")} />
      ) : policies.length === 0 ? (
        <EmptyState text={t("verifactuPolicyEmpty")} />
      ) : (
        <div className="verifactu-policy-grid">
          {policies.map((policy) => (
            <article className="verifactu-policy-card" key={policy.taxpayerType}>
              <header>
                <div>
                  <span>{t("type")}</span>
                  <h3>{taxpayerLabel(policy.taxpayerType, t)}</h3>
                </div>
                <StatusPill status={`${t("policyVersion")} ${policy.version}`} tone="muted" />
              </header>

              <div className="verifactu-policy-impact">
                <div><span>{t("affectedLicenses")}</span><strong>{policy.activeLicenses}</strong></div>
                <div><span>{t("affectedInstallations")}</span><strong>{policy.linkedInstallations}</strong></div>
              </div>

              <div className="verifactu-policy-form">
                <Input
                  label={t("activationDate")}
                  type="date"
                  value={dates[policy.taxpayerType] ?? policy.activationDate}
                  onChange={(value) => setDates((current) => ({ ...current, [policy.taxpayerType]: value }))}
                  required
                  disabled={!canManage || busy === policy.taxpayerType}
                />
                <label>
                  {t("changeReason")}
                  <input
                    className="control-input"
                    value={reasons[policy.taxpayerType] ?? ""}
                    maxLength={500}
                    placeholder={t("changeReasonPlaceholder")}
                    onChange={(event) => setReasons((current) => ({ ...current, [policy.taxpayerType]: event.target.value }))}
                    disabled={!canManage || busy === policy.taxpayerType}
                  />
                </label>
              </div>

              <dl className="verifactu-policy-meta">
                <div><dt>{t("updatedBy")}</dt><dd>{policy.updatedBy} · {formatDate(policy.updatedAt)}</dd></div>
                <div><dt>{t("currentReason")}</dt><dd>{policy.reason}</dd></div>
              </dl>

              {canManage && (
                <button
                  className="primary-button"
                  type="button"
                  disabled={busy !== null}
                  onClick={() => void updatePolicy(policy)}
                >
                  {busy === policy.taxpayerType ? t("saving") : t("updatePolicy")}
                </button>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function taxpayerLabel(taxpayerType: TaxpayerType, t: (key: string) => string) {
  return taxpayerType === "SOCIEDAD" ? t("verifactuCompany") : t("verifactuSelfEmployed");
}

function fiscalModeLabel(mode: string, t: (key: string) => string) {
  if (mode === "VERIFACTU") return t("fiscalVerifactu");
  if (mode === "NO_VERIFACTU") return t("fiscalNoVerifactu");
  if (mode === "PRE_SIF") return t("fiscalPreSif");
  if (mode === "MIXED") return t("fiscalMixed");
  return t("fiscalUnknown");
}

function fiscalStateLabel(state: string, t: (key: string) => string) {
  if (state === "ACTIVE") return t("fiscalActive");
  if (state === "PENDING") return t("fiscalPending");
  if (state === "DUE_REVIEW") return t("fiscalDueReview");
  if (state === "MIXED") return t("fiscalMixed");
  return t("fiscalUnknown");
}

function FiscalStatusView({ credentials, licenses, onNotice }: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [rows, setRows] = useState<FiscalStatusAdmin[]>([]);
  const [companyRows, setCompanyRows] = useState<FiscalCompanyStatusAdmin[]>([]);
  const [companyId, setCompanyId] = useState("");
  const [loading, setLoading] = useState(false);
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const visibleRows = rows.filter((row) => !companyId || row.companyId === companyId);

  useEffect(() => { void load(); }, [credentials.username, companyId]);

  async function load() {
    setLoading(true);
    try {
      const [nextRows, nextCompanyRows] = await Promise.all([
        api.fiscalStatus(credentials, companyId || undefined),
        companyId ? Promise.resolve([] as FiscalCompanyStatusAdmin[]) : api.fiscalCompanyStatus(credentials)
      ]);
      setRows(nextRows);
      setCompanyRows(nextCompanyRows);
      onNotice(null);
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader title={t("fiscal")} subtitle={loading ? t("refreshing") : t("fiscalStatusSubtitle")} />
      <div className="permission-hint">{t("fiscalReadOnly")}</div>
      <div className="toolbar">
        <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
          <option value="">{t("allCompanies")}</option>
          {companies.map((company) => <option key={company.companyId} value={company.companyId}>{company.companyName}</option>)}
        </select>
      </div>
      {!companyId && companyRows.length > 0 && <div className="table-wrap">
        <table>
          <thead><tr><th>{t("fiscalCompany")}</th><th>{t("fiscalMode")}</th><th>{t("fiscalActivationState")}</th><th>{t("installations")}</th><th>{t("fiscalLastReport")}</th></tr></thead>
          <tbody>{companyRows.map((row) => <tr key={row.companyId}>
            <td><strong>{row.companyName}</strong><small>{row.taxId}</small></td>
            <td>{fiscalModeLabel(row.effectiveMode, t)}</td>
            <td>{fiscalStateLabel(row.activationState, t)}</td>
            <td>
              {row.installations} / {row.stores}
              {row.unlinkedStores > 0 ? ` (${row.unlinkedStores} ${t("notLinked")})` : ""}
              {row.staleInstallations > 0 ? ` · ${row.staleInstallations} ${t("staleInstallations")}` : ""}
            </td>
            <td>{row.lastReportedAt ? formatDate(row.lastReportedAt) : "-"}</td>
          </tr>)}</tbody>
        </table>
      </div>}
      {visibleRows.length === 0 ? <EmptyState text={t("fiscalNoData")} /> : (
        <div className="table-wrap">
          <table>
            <thead><tr>
              <th>{t("fiscalCompany")}</th><th>{t("fiscalStore")}</th><th>{t("fiscalInstallation")}</th>
              <th>{t("fiscalMode")}</th><th>{t("fiscalActivationState")}</th><th>{t("fiscalActivationDate")}</th>
              <th>{t("fiscalEnvironment")}</th><th>{t("fiscalTransport")}</th><th>{t("fiscalLastReport")}</th>
            </tr></thead>
            <tbody>{visibleRows.map((row) => (
              <tr key={`${row.storeId}:${row.installationId ?? "unlinked"}`}>
                <td><strong>{row.companyName}</strong><small>{row.taxId}</small></td>
                <td>{row.storeName}</td>
                <td><strong>{row.installationReference || t("notLinked")}</strong><small>{row.installationId || "—"}</small></td>
                <td><StatusPill status={fiscalModeLabel(row.effectiveMode, t)} tone={row.effectiveMode === "VERIFACTU" ? "ok" : row.effectiveMode === "NO_VERIFACTU" ? "warning" : "muted"} /></td>
                <td><StatusPill status={row.stale ? t("fiscalStale") : fiscalStateLabel(row.activationState, t)} tone={row.stale || row.activationState === "DUE_REVIEW" ? "warning" : row.activationState === "ACTIVE" ? "ok" : "muted"} /></td>
                <td>{row.activationDate ? formatDate(row.activationDate) : "-"}</td>
                <td>{row.runtimeClass && row.endpointEnvironment ? `${row.runtimeClass} / ${row.endpointEnvironment}` : "-"}</td>
                <td>{row.transportMode || "-"}</td>
                <td>{row.reportedAt ? formatDate(row.reportedAt) : "-"}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function SyncView({
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
  const [mode, setMode] = useState<"events" | "sales" | "stock" | "cash" | "incidents">("events");
  const [companyId, setCompanyId] = useState("");
  const [events, setEvents] = useState<SyncEventView[]>([]);
  const [healthEvents, setHealthEvents] = useState<SyncEventView[]>([]);
  const [stock, setStock] = useState<StockSnapshot[]>([]);
  const [incidents, setIncidents] = useState<OperationalIncident[]>([]);
  const [projectionStatus, setProjectionStatus] = useState<SyncProjectionStatus | null>(null);
  const [cancelTarget, setCancelTarget] = useState<{ incident: OperationalIncident; commandId: string } | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const [loading, setLoading] = useState(false);

  const companyOptions = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const companyNames = useMemo(
    () => new Map(companyOptions.map((company) => [company.companyId, company.companyName])),
    [companyOptions]
  );
  const canViewIncidents = permissions.has("VIEW_ADMIN_DATA");
  const canManageIncidents = permissions.has("MANAGE_OPERATIONAL_INCIDENTS");
  const lastReceivedAt = latestDate(healthEvents.map((event) => event.receivedAt));

  useEffect(() => {
    setCancelTarget(null);
    setCancelReason("");
    void load();
  }, [mode, companyId, canViewIncidents]);

  async function load() {
    setLoading(true);
    try {
      const selectedCompanyId = companyId || undefined;
      const allEventsPromise = api.events(credentials, selectedCompanyId);
      const incidentsPromise = canViewIncidents
        ? api.operationalIncidents(credentials, selectedCompanyId)
        : Promise.resolve([] as OperationalIncident[]);
      const projectionStatusPromise = api.syncProjectionStatus(credentials, selectedCompanyId);
      const selectedDataPromise = mode === "sales"
        ? api.sales(credentials, selectedCompanyId)
        : mode === "stock"
          ? api.stockCurrent(credentials, selectedCompanyId)
          : mode === "cash"
            ? api.cashClosures(credentials, selectedCompanyId)
            : Promise.resolve(null);
      const [nextHealthEvents, nextIncidents, nextProjectionStatus, selectedData] = await Promise.all([
        allEventsPromise,
        incidentsPromise,
        projectionStatusPromise,
        selectedDataPromise
      ]);
      setHealthEvents(nextHealthEvents);
      setIncidents(nextIncidents);
      setProjectionStatus(nextProjectionStatus);
      if (mode === "events") setEvents(nextHealthEvents);
      if (mode === "sales" || mode === "cash") setEvents(selectedData as SyncEventView[]);
      if (mode === "stock") setStock(selectedData as StockSnapshot[]);
      onNotice(null);
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setLoading(false);
    }
  }

  function requestCancellation(incident: OperationalIncident) {
    setCancelTarget({ incident, commandId: crypto.randomUUID() });
    setCancelReason("");
  }

  async function cancelIncident(event: FormEvent) {
    event.preventDefault();
    if (!cancelTarget || !canManageIncidents) return;
    const reason = cancelReason.trim();
    if (reason.length < 5) {
      onNotice({ type: "error", text: t("cancelIncidentReasonRequired") });
      return;
    }
    setCancelling(true);
    try {
      await api.cancelMemberCategoryBootstrapIncident(
        credentials,
        cancelTarget.incident.companyId,
        cancelTarget.incident.targetId,
        {
          commandId: cancelTarget.commandId,
          expectedStatus: cancelTarget.incident.status,
          reason
        }
      );
      setCancelTarget(null);
      setCancelReason("");
      await load();
      onNotice({ type: "success", text: t("incidentCancelled") });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setCancelTarget(null);
        setCancelReason("");
        await load();
        onNotice({ type: "error", text: t("incidentConflictReloaded") });
      } else {
        onNotice({ type: "error", text: errorMessage(error) });
      }
    } finally {
      setCancelling(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader
        title={mode === "incidents" ? t("operationalIncidents") : t("sync")}
        subtitle={loading ? t("consultingEvents") : mode === "incidents" ? t("operationalIncidentsSubtitle") : t("syncSubtitle")}
      />
      <div className="sync-health-grid">
        <Metric label={t("eventsToday")} value={healthEvents.filter((event) => isToday(event.receivedAt)).length} />
        <Metric label={t("salesEvents")} value={healthEvents.filter((event) => event.entityType === "DOCUMENTO").length} />
        <Metric label={t("stockEvents")} value={mode === "stock" ? stock.length : healthEvents.filter((event) => event.entityType === "STOCK_MOVEMENT").length} />
        <Metric label={t("cashEvents")} value={healthEvents.filter((event) => event.entityType === "CIERRE_CAJA").length} />
        <Metric label={t("lastSync")} value={lastReceivedAt ? formatDate(lastReceivedAt) : "-"} />
        <Metric
          label={t("operationalIncidentCount")}
          value={canViewIncidents ? incidents.length : "-"}
          tone={canViewIncidents && incidents.length > 0 ? "warning" : undefined}
        />
      </div>
      <div className="projection-health-panel" aria-label={t("projectionHealth")}>
        <strong>{t("projectionHealth")}</strong>
        <div className="projection-health-grid">
          <ProjectionMetric label={t("projectionReceived")} value={projectionStatus?.received ?? 0} warning={(projectionStatus?.received ?? 0) > 0} />
          <ProjectionMetric label={t("projectionProjected")} value={projectionStatus?.projected ?? 0} />
          <ProjectionMetric label={t("projectionIgnored")} value={projectionStatus?.ignored ?? 0} />
          <ProjectionMetric label={t("projectionErrors")} value={projectionStatus?.error ?? 0} warning={(projectionStatus?.error ?? 0) > 0} />
          <ProjectionMetric
            label={t("projectionOldestReceived")}
            value={projectionStatus?.oldestReceivedAt ? formatDate(projectionStatus.oldestReceivedAt) : "-"}
            warning={Boolean(projectionStatus?.oldestReceivedAt)}
          />
        </div>
      </div>
      <div className="toolbar">
        <Segmented
          value={mode}
          options={[
            ["events", t("events")],
            ["sales", t("sales")],
            ["stock", t("stock")],
            ["cash", t("cash")],
            ["incidents", canViewIncidents ? `${t("incidents")} (${incidents.length})` : t("incidents")]
          ]}
          onChange={(value) => setMode(value as "events" | "sales" | "stock" | "cash" | "incidents")}
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
      {mode === "incidents" && !canViewIncidents && (
        <div className="permission-hint">{t("operationalIncidentPermission")}</div>
      )}
      {mode === "incidents" && cancelTarget && (
        <form className="incident-cancel-panel" onSubmit={cancelIncident}>
          <div>
            <span className="eyebrow">{t("cancelIncidentTitle")}</span>
            <strong>{companyNames.get(cancelTarget.incident.companyId) ?? cancelTarget.incident.companyId}</strong>
            <small>{cancelTarget.incident.targetId}</small>
            <p>{t("cancelIncidentWarning")}</p>
          </div>
          <label>
            <span>{t("cancelIncidentReason")}</span>
            <textarea
              className="control-input"
              value={cancelReason}
              onChange={(event) => setCancelReason(event.target.value)}
              placeholder={t("cancelIncidentReasonPlaceholder")}
              minLength={5}
              maxLength={1000}
              rows={3}
              required
              autoFocus
            />
          </label>
          <div className="row-actions">
            <button
              className="secondary-button"
              type="button"
              onClick={() => {
                setCancelTarget(null);
                setCancelReason("");
              }}
              disabled={cancelling}
            >
              {t("close")}
            </button>
            <button className="danger-button subtle" type="submit" disabled={cancelling || cancelReason.trim().length < 5}>
              {cancelling ? t("cancelling") : t("confirmCancellation")}
            </button>
          </div>
        </form>
      )}
      {mode === "incidents" ? (
        canViewIncidents ? (
          <OperationalIncidentsTable
            rows={incidents}
            companyNames={companyNames}
            canManage={canManageIncidents}
            busyTargetId={cancelling ? cancelTarget?.incident.targetId ?? null : null}
            onCancel={requestCancellation}
          />
        ) : null
      ) : mode === "stock" ? (
        <StockTable rows={stock} />
      ) : (
        <EventsTable events={events} />
      )}
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
  const [tenantUsersCompanyId, setTenantUsersCompanyId] = useState("");
  const [tenantUsername, setTenantUsername] = useState("");
  const [tenantPassword, setTenantPassword] = useState("");
  const [tenantRoleName, setTenantRoleName] = useState<TenantAssignableRoleName>("MANAGER");
  const [tenantPasswordByUser, setTenantPasswordByUser] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const tenantUsersRequestId = useRef(0);
  const tenantUsersMutationId = useRef(0);
  const selectedTenantCompanyIdRef = useRef(tenantCompanyId);
  selectedTenantCompanyIdRef.current = tenantCompanyId;
  const canManageUsers = permissions.has("MANAGE_ADMIN_USERS");
  const canManageTenantUsers = permissions.has("MANAGE_TENANT_USERS");

  useEffect(() => {
    if (!tenantCompanyId && companyOptions[0]) {
      setTenantCompanyId(companyOptions[0].companyId);
    }
  }, [companyOptions, tenantCompanyId]);

  useEffect(() => {
    tenantUsersRequestId.current += 1;
    tenantUsersMutationId.current += 1;
    setTenantPasswordByUser({});
    setBusy((current) => current?.includes("tenant") ? null : current);
    if (!tenantCompanyId) {
      setTenantUsers([]);
      setTenantUsersCompanyId("");
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
    const requestId = ++tenantUsersRequestId.current;
    try {
      const response = await api.tenantUsers(credentials, companyId);
      if (requestId !== tenantUsersRequestId.current
          || selectedTenantCompanyIdRef.current !== companyId) return;
      setTenantUsers(response);
      setTenantUsersCompanyId(companyId);
    } catch (error) {
      if (requestId !== tenantUsersRequestId.current
          || selectedTenantCompanyIdRef.current !== companyId) return;
      setTenantUsers([]);
      setTenantUsersCompanyId(companyId);
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function createTenantUser(event: FormEvent) {
    event.preventDefault();
    if (!tenantCompanyId) return;
    const companyId = tenantCompanyId;
    const mutationId = ++tenantUsersMutationId.current;
    setBusy("create-tenant-user");
    try {
      const created = await api.createTenantUser(credentials, companyId, {
        username: tenantUsername,
        password: tenantPassword,
        roleName: tenantRoleName
      });
      if (!isCurrentTenantMutation(companyId, mutationId) || created.companyId !== companyId) return;
      setTenantUsername("");
      setTenantPassword("");
      onNotice({ type: "success", text: t("tenantUserCreated") });
      if (selectedTenantCompanyIdRef.current === companyId) {
        await loadTenantUsers(companyId);
      }
    } catch (error) {
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      if (isCurrentTenantMutation(companyId, mutationId)) setBusy(null);
    }
  }

  async function changeTenantPassword(user: string) {
    const nextPassword = tenantPasswordByUser[user]?.trim();
    if (!nextPassword) return;
    const companyId = tenantCompanyId;
    if (!ownsSelectedTenantUser(companyId, user)) {
      onNotice({ type: "error", text: t("companySelectionChanged") });
      return;
    }
    const mutationId = ++tenantUsersMutationId.current;
    setBusy(`tenant-password-${user}`);
    try {
      await api.changeTenantPassword(credentials, user, nextPassword);
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      setTenantPasswordByUser((current) => ({ ...current, [user]: "" }));
      onNotice({ type: "success", text: t("tenantUserUpdated") });
    } catch (error) {
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      if (isCurrentTenantMutation(companyId, mutationId)) setBusy(null);
    }
  }

  async function deactivateTenantUser(user: string) {
    const companyId = tenantCompanyId;
    if (!ownsSelectedTenantUser(companyId, user)) {
      onNotice({ type: "error", text: t("companySelectionChanged") });
      return;
    }
    const mutationId = ++tenantUsersMutationId.current;
    setBusy(`tenant-disable-${user}`);
    try {
      await api.deactivateTenantUser(credentials, user);
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "success", text: t("tenantUserDisabled") });
      if (selectedTenantCompanyIdRef.current === companyId) {
        await loadTenantUsers(companyId);
      }
    } catch (error) {
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      if (isCurrentTenantMutation(companyId, mutationId)) setBusy(null);
    }
  }

  function ownsSelectedTenantUser(companyId: string, username: string) {
    return companyId.length > 0
      && tenantUsersCompanyId === companyId
      && tenantUsers.some((user) => user.companyId === companyId && user.username === username);
  }

  function isCurrentTenantMutation(companyId: string, mutationId: number) {
    return mutationId === tenantUsersMutationId.current
      && selectedTenantCompanyIdRef.current === companyId;
  }

  const visibleTenantUsers = tenantUsersCompanyId === tenantCompanyId ? tenantUsers : [];

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
                onChange={(event) => setTenantRoleName(event.target.value as TenantAssignableRoleName)}
                disabled={!tenantCompanyId}
              >
                <option value="MANAGER">MANAGER</option>
                <option value="VIEWER">VIEWER</option>
                <option value="BILLING">BILLING</option>
              </select>
            </label>
            <div className="form-actions">
              <button className="primary-button" type="submit" disabled={!tenantCompanyId || busy === "create-tenant-user"}>
                {t("createTenantUser")}
              </button>
            </div>
          </form>
        )}
        {visibleTenantUsers.length === 0 ? (
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
                {visibleTenantUsers.map((user) => (
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
      setHealth([]);
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
  permissions,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
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
  const canManage = permissions.has("MANAGE_BILLING");
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
      setSummary(null);
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function loadInvoices(companyId: string) {
    try {
      setInvoices(await api.billingInvoices(credentials, companyId));
      onNotice(null);
    } catch (error) {
      setInvoices([]);
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function createInvoice(event: FormEvent) {
    event.preventDefault();
    if (!selectedCompanyId) return;
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(invoiceForm.amount)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
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
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(paymentForm.amount)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
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
        {canManage && (
          <>
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
          </>
        )}
        <InvoiceTable invoices={invoices} />
      </section>
    </div>
  );
}

type MasterMode = "customers" | "products" | "suppliers" | "warehouses";

function OperationsView({
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
  const [sales, setSales] = useState<SalesDocument[]>([]);
  const [movements, setMovements] = useState<InventoryMovement[]>([]);
  const [stock, setStock] = useState<InventoryStock[]>([]);
  const [salesStatusFilter, setSalesStatusFilter] = useState("");
  const [inventoryFilter, setInventoryFilter] = useState("");
  const [saleForm, setSaleForm] = useState({
    storeId: "",
    documentNumber: "",
    customerCode: "",
    total: "0.00",
    currency: "EUR",
    status: "CONFIRMADA",
    issuedAt: toLocalInput(new Date())
  });
  const [movementForm, setMovementForm] = useState({
    warehouseCode: "",
    productSku: "",
    movementType: "ENTRADA",
    quantity: "1.00",
    reason: "",
    movedAt: toLocalInput(new Date())
  });
  const [busy, setBusy] = useState(false);
  const canManage = permissions.has("MANAGE_OPERATIONS");
  const filteredSales = sales.filter((item) => !salesStatusFilter || item.status === salesStatusFilter);
  const filteredMovements = movements.filter((item) =>
    [item.warehouseCode, item.productSku, item.movementType, item.reason ?? ""].some((value) => normalizeSearch(value).includes(normalizeSearch(inventoryFilter)))
  );
  const filteredStock = stock.filter((item) =>
    [item.warehouseCode, item.productSku].some((value) => normalizeSearch(value).includes(normalizeSearch(inventoryFilter)))
  );

  useEffect(() => {
    if (!companyId && companies[0]) setCompanyId(companies[0].companyId);
  }, [companies, companyId]);

  useEffect(() => {
    if (companyId) void loadOperations(companyId);
  }, [companyId]);

  async function loadOperations(nextCompanyId: string) {
    try {
      const [nextSales, nextMovements, nextStock] = await Promise.all([
        api.salesDocuments(credentials, nextCompanyId),
        api.inventoryMovements(credentials, nextCompanyId),
        api.inventoryStock(credentials, nextCompanyId)
      ]);
      setSales(nextSales);
      setMovements(nextMovements);
      setStock(nextStock);
      onNotice(null);
    } catch (error) {
      if (isRecoverableBackendDataError(error)) {
        setSales([]);
        setMovements([]);
        setStock([]);
        onNotice({ type: "error", text: t("phase11Pending") });
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function createSale(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(saleForm.total)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
    if (sales.some((item) => item.documentNumber.toLowerCase() === saleForm.documentNumber.trim().toLowerCase())) {
      onNotice({ type: "error", text: t("duplicateCode") });
      return;
    }
    setBusy(true);
    try {
      await api.createSalesDocument(credentials, companyId, {
        ...saleForm,
        storeId: saleForm.storeId || null,
        issuedAt: new Date(saleForm.issuedAt).toISOString()
      });
      setSaleForm({ storeId: "", documentNumber: "", customerCode: "", total: "0.00", currency: "EUR", status: "CONFIRMADA", issuedAt: toLocalInput(new Date()) });
      await loadOperations(companyId);
      onNotice({ type: "success", text: t("itemCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  async function createMovement(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(movementForm.quantity)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
    setBusy(true);
    try {
      await api.createInventoryMovement(credentials, companyId, {
        ...movementForm,
        movedAt: new Date(movementForm.movedAt).toISOString()
      });
      setMovementForm({ warehouseCode: "", productSku: "", movementType: "ENTRADA", quantity: "1.00", reason: "", movedAt: toLocalInput(new Date()) });
      await loadOperations(companyId);
      onNotice({ type: "success", text: t("itemCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="view-grid">
      <section className="content-section">
        <SectionHeader title={t("realOperations")} subtitle={t("realOperationsSubtitle")} />
        <div className="toolbar">
          <label className="toolbar-field">
            {t("company")}
            <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
              {companies.map((company) => (
                <option key={company.companyId} value={company.companyId}>{company.companyName}</option>
              ))}
            </select>
          </label>
          <label className="toolbar-field">
            {t("status")}
            <select className="control-input" value={salesStatusFilter} onChange={(event) => setSalesStatusFilter(event.target.value)}>
              <option value="">{t("allStatuses")}</option>
              {uniqueStrings(sales.map((item) => item.status)).map((status) => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
          </label>
        </div>
        {canManage && (
          <form className="compact-form-grid" onSubmit={createSale}>
            <Input label={t("documentNumber")} value={saleForm.documentNumber} onChange={(documentNumber) => setSaleForm({ ...saleForm, documentNumber })} required />
            <Input label={t("customerCode")} value={saleForm.customerCode} onChange={(customerCode) => setSaleForm({ ...saleForm, customerCode })} />
            <Input label={t("amount")} value={saleForm.total} onChange={(total) => setSaleForm({ ...saleForm, total })} required />
            <Input label={t("currency")} value={saleForm.currency} onChange={(currency) => setSaleForm({ ...saleForm, currency })} required />
            <Input label={t("issuedAt")} type="datetime-local" value={saleForm.issuedAt} onChange={(issuedAt) => setSaleForm({ ...saleForm, issuedAt })} required />
            <button className="primary-button" type="submit" disabled={busy}>{t("issueSale")}</button>
          </form>
        )}
        <SimpleSalesTable sales={filteredSales} />
      </section>

      <section className="content-section">
        <SectionHeader title={t("inventoryMovements")} subtitle={t("stockCurrent")} />
        <div className="toolbar">
          <Input label={t("globalSearch")} value={inventoryFilter} onChange={setInventoryFilter} />
        </div>
        {canManage && (
          <form className="compact-form-grid" onSubmit={createMovement}>
            <Input label={t("warehouse")} value={movementForm.warehouseCode} onChange={(warehouseCode) => setMovementForm({ ...movementForm, warehouseCode })} required />
            <Input label={t("sku")} value={movementForm.productSku} onChange={(productSku) => setMovementForm({ ...movementForm, productSku })} required />
            <Input label={t("movementType")} value={movementForm.movementType} onChange={(movementType) => setMovementForm({ ...movementForm, movementType })} required />
            <Input label={t("quantity")} value={movementForm.quantity} onChange={(quantity) => setMovementForm({ ...movementForm, quantity })} required />
            <Input label={t("reason")} value={movementForm.reason} onChange={(reason) => setMovementForm({ ...movementForm, reason })} />
            <Input label={t("created")} type="datetime-local" value={movementForm.movedAt} onChange={(movedAt) => setMovementForm({ ...movementForm, movedAt })} required />
            <button className="primary-button" type="submit" disabled={busy}>{t("createMovement")}</button>
          </form>
        )}
        <SimpleStockTable stock={filteredStock} movements={filteredMovements} />
      </section>
    </div>
  );
}

function SubscriptionsView({
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
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [statusFilter, setStatusFilter] = useState("");
  const [form, setForm] = useState({
    planName: "STANDARD",
    status: "ACTIVA",
    billingCycle: "MENSUAL",
    amount: "0.00",
    currency: "EUR",
    startedAt: toLocalInput(new Date()),
    nextBillingAt: toLocalInput(addDays(new Date(), 30))
  });
  const [busy, setBusy] = useState(false);
  const canManage = permissions.has("MANAGE_SUBSCRIPTIONS");
  const filteredSubscriptions = subscriptions.filter((item) => !statusFilter || item.status === statusFilter);

  useEffect(() => {
    if (!companyId && companies[0]) setCompanyId(companies[0].companyId);
  }, [companies, companyId]);

  useEffect(() => {
    void loadSubscriptions();
  }, [credentials.username]);

  async function loadSubscriptions() {
    try {
      setSubscriptions(await api.subscriptions(credentials));
      onNotice(null);
    } catch (error) {
      setSubscriptions([]);
      onNotice({ type: "error", text: isRecoverableBackendDataError(error) ? t("phase11Pending") : errorMessage(error) });
    }
  }

  async function createSubscription(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(form.amount)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
    setBusy(true);
    try {
      await api.createSubscription(credentials, companyId, {
        ...form,
        startedAt: new Date(form.startedAt).toISOString(),
        nextBillingAt: form.nextBillingAt ? new Date(form.nextBillingAt).toISOString() : null
      });
      await loadSubscriptions();
      onNotice({ type: "success", text: t("itemCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  async function cancel(id: string) {
    setBusy(true);
    try {
      await api.cancelSubscription(credentials, id);
      await loadSubscriptions();
      onNotice({ type: "success", text: t("itemUpdated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader title={t("subscriptionsTitle")} subtitle={t("subscriptionsSubtitle")} />
      <div className="toolbar">
        <label className="toolbar-field">
          {t("status")}
          <select className="control-input" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="">{t("allStatuses")}</option>
            {uniqueStrings(subscriptions.map((item) => item.status)).map((status) => (
              <option key={status} value={status}>{status}</option>
            ))}
          </select>
        </label>
      </div>
      {canManage && (
        <form className="compact-form-grid" onSubmit={createSubscription}>
          <label>
            {t("company")}
            <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
              {companies.map((company) => (
                <option key={company.companyId} value={company.companyId}>{company.companyName}</option>
              ))}
            </select>
          </label>
          <Input label={t("plan")} value={form.planName} onChange={(planName) => setForm({ ...form, planName })} required />
          <Input label={t("billingCycle")} value={form.billingCycle} onChange={(billingCycle) => setForm({ ...form, billingCycle })} required />
          <Input label={t("amount")} value={form.amount} onChange={(amount) => setForm({ ...form, amount })} required />
          <Input label={t("currency")} value={form.currency} onChange={(currency) => setForm({ ...form, currency })} required />
          <Input label={t("nextBillingAt")} type="datetime-local" value={form.nextBillingAt} onChange={(nextBillingAt) => setForm({ ...form, nextBillingAt })} />
          <button className="primary-button" type="submit" disabled={busy}>{t("createSubscription")}</button>
        </form>
      )}
      <SubscriptionsTable subscriptions={filteredSubscriptions} canManage={canManage} onCancel={(id) => void cancel(id)} />
    </section>
  );
}

function ReportsView({
  credentials,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [report, setReport] = useState<AdvancedReport | null>(null);
  const [integrations, setIntegrations] = useState<IntegrationEndpoint[]>([]);
  const [integrationFilter, setIntegrationFilter] = useState("");
  const [form, setForm] = useState({ companyId: "", name: "", integrationType: "WEBHOOK", status: "ACTIVA", targetUrl: "", apiKey: "" });
  const [busy, setBusy] = useState(false);
  const canManage = permissions.has("MANAGE_INTEGRATIONS");
  const filteredIntegrations = integrations.filter((item) =>
    [item.name, item.companyName ?? "", item.integrationType, item.status, item.targetUrl ?? ""].some((value) =>
      normalizeSearch(value).includes(normalizeSearch(integrationFilter))
    )
  );

  useEffect(() => {
    void loadReports();
  }, [credentials.username]);

  async function loadReports() {
    try {
      const [nextReport, nextIntegrations] = await Promise.all([api.advancedReports(credentials), api.integrations(credentials)]);
      setReport(nextReport);
      setIntegrations(nextIntegrations);
      onNotice(null);
    } catch (error) {
      setReport(null);
      setIntegrations([]);
      onNotice({ type: "error", text: isRecoverableBackendDataError(error) ? t("phase11Pending") : errorMessage(error) });
    }
  }

  async function createIntegration(event: FormEvent) {
    event.preventDefault();
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (form.targetUrl && !isValidUrl(form.targetUrl)) {
      onNotice({ type: "error", text: t("invalidUrl") });
      return;
    }
    setBusy(true);
    try {
      await api.createIntegration(credentials, { ...form, companyId: form.companyId || null });
      setForm({ companyId: "", name: "", integrationType: "WEBHOOK", status: "ACTIVA", targetUrl: "", apiKey: "" });
      await loadReports();
      onNotice({ type: "success", text: t("itemCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  async function markSynced(id: string) {
    try {
      await api.markIntegrationSynced(credentials, id);
      await loadReports();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  return (
    <div className="view-grid">
      <section className="metric-grid">
        <Metric label={t("subscriptionMrr")} value={formatMoney(report?.subscriptionMrr ?? "0")} />
        <Metric label={t("invoicedTotal")} value={formatMoney(report?.invoicedTotal ?? "0")} />
        <Metric label={t("paidTotal")} value={formatMoney(report?.paidTotal ?? "0")} />
        <Metric label={t("salesTotal")} value={formatMoney(report?.salesTotal ?? "0")} />
        <Metric label={t("inventoryMovements")} value={report?.inventoryMovements ?? "-"} />
        <Metric label={t("activeIntegrations")} value={report?.activeIntegrations ?? "-"} />
      </section>
      <section className="content-section">
        <SectionHeader title={t("advancedReports")} subtitle={t("advancedReportsSubtitle")} />
        <div className="table-wrap">
          <table>
            <tbody>
              <tr><td>{t("company")}</td><td>{report?.companies ?? 0}</td></tr>
              <tr><td>{t("subscriptions")}</td><td>{report?.subscriptions ?? 0}</td></tr>
              <tr><td>{t("invoices")}</td><td>{report?.invoices ?? 0}</td></tr>
              <tr><td>{t("salesDocuments")}</td><td>{report?.salesDocuments ?? 0}</td></tr>
              <tr><td>{t("integrations")}</td><td>{report?.integrations ?? 0}</td></tr>
            </tbody>
          </table>
        </div>
      </section>
      <section className="content-section">
        <SectionHeader title={t("integrations")} subtitle={t("integrationsSubtitle")} />
        <div className="toolbar">
          <Input label={t("globalSearch")} value={integrationFilter} onChange={setIntegrationFilter} />
        </div>
        {canManage && (
          <form className="compact-form-grid" onSubmit={createIntegration}>
            <Input label={t("name")} value={form.name} onChange={(name) => setForm({ ...form, name })} required />
            <Input label={t("integrationType")} value={form.integrationType} onChange={(integrationType) => setForm({ ...form, integrationType })} required />
            <Input label={t("targetUrl")} value={form.targetUrl} onChange={(targetUrl) => setForm({ ...form, targetUrl })} />
            <Input label={t("apiKey")} value={form.apiKey} onChange={(apiKey) => setForm({ ...form, apiKey })} />
            <button className="primary-button" type="submit" disabled={busy}>{t("createIntegration")}</button>
          </form>
        )}
        <IntegrationsTable integrations={filteredIntegrations} canManage={canManage} onSync={(id) => void markSynced(id)} />
      </section>
    </div>
  );
}

function SimpleSalesTable({ sales }: { sales: SalesDocument[] }) {
  const { t } = useI18n();
  if (sales.length === 0) return <EmptyState text={t("noBillingData")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("documentNumber")}</th>
            <th>{t("customerCode")}</th>
            <th>{t("amount")}</th>
            <th>{t("status")}</th>
            <th>{t("issuedAt")}</th>
          </tr>
        </thead>
        <tbody>
          {sales.map((item) => (
            <tr key={item.id}>
              <td><strong>{item.documentNumber}</strong></td>
              <td>{item.customerCode || "-"}</td>
              <td>{formatMoney(item.total)} {item.currency}</td>
              <td><StatusPill status={item.status} tone={item.status === "ANULADA" ? "warning" : "ok"} /></td>
              <td>{formatDate(item.issuedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SimpleStockTable({ stock, movements }: { stock: InventoryStock[]; movements: InventoryMovement[] }) {
  const { t } = useI18n();
  return (
    <div className="tenant-master-grid">
      <div>
        <h3>{t("stockCurrent")}</h3>
        {stock.length === 0 ? (
          <EmptyState text={t("noStockForFilter")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("warehouse")}</th>
                  <th>{t("sku")}</th>
                  <th>{t("quantity")}</th>
                </tr>
              </thead>
              <tbody>
                {stock.map((item) => (
                  <tr key={`${item.warehouseCode}-${item.productSku}`}>
                    <td>{item.warehouseCode}</td>
                    <td>{item.productSku}</td>
                    <td>{formatMoney(item.quantity)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <div>
        <h3>{t("inventoryMovements")}</h3>
        {movements.length === 0 ? (
          <EmptyState text={t("noEventsForFilter")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("warehouse")}</th>
                  <th>{t("sku")}</th>
                  <th>{t("movementType")}</th>
                  <th>{t("quantity")}</th>
                  <th>{t("created")}</th>
                </tr>
              </thead>
              <tbody>
                {movements.map((item) => (
                  <tr key={item.id}>
                    <td>{item.warehouseCode}</td>
                    <td>{item.productSku}</td>
                    <td>{item.movementType}</td>
                    <td>{formatMoney(item.quantity)}</td>
                    <td>{formatDate(item.movedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function SubscriptionsTable({
  subscriptions,
  canManage,
  onCancel
}: {
  subscriptions: Subscription[];
  canManage: boolean;
  onCancel: (id: string) => void;
}) {
  const { t } = useI18n();
  if (subscriptions.length === 0) return <EmptyState text={t("noBillingData")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("company")}</th>
            <th>{t("plan")}</th>
            <th>{t("billingCycle")}</th>
            <th>{t("amount")}</th>
            <th>{t("status")}</th>
            <th>{t("nextBillingAt")}</th>
            {canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {subscriptions.map((item) => (
            <tr key={item.id}>
              <td>{item.companyName}</td>
              <td>{item.planName}</td>
              <td>{item.billingCycle}</td>
              <td>{formatMoney(item.amount)} {item.currency}</td>
              <td><StatusPill status={item.status} tone={item.status === "ACTIVA" ? "ok" : "muted"} /></td>
              <td>{item.nextBillingAt ? formatDate(item.nextBillingAt) : "-"}</td>
              {canManage && (
                <td className="table-actions">
                  {item.status !== "CANCELADA" && (
                    <button className="danger-button subtle" type="button" onClick={() => onCancel(item.id)}>{t("cancelSubscription")}</button>
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

function IntegrationsTable({
  integrations,
  canManage,
  onSync
}: {
  integrations: IntegrationEndpoint[];
  canManage: boolean;
  onSync: (id: string) => void;
}) {
  const { t } = useI18n();
  if (integrations.length === 0) return <EmptyState text={t("noEventsForFilter")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("name")}</th>
            <th>{t("company")}</th>
            <th>{t("integrationType")}</th>
            <th>{t("status")}</th>
            <th>{t("apiKeyPreview")}</th>
            <th>{t("lastSyncAt")}</th>
            {canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {integrations.map((item) => (
            <tr key={item.id}>
              <td><strong>{item.name}</strong><small>{item.targetUrl || "-"}</small></td>
              <td>{item.companyName || t("allCompanies")}</td>
              <td>{item.integrationType}</td>
              <td><StatusPill status={item.status} tone={item.status === "ACTIVA" ? "ok" : "muted"} /></td>
              <td>{item.apiKeyPreview || "-"}</td>
              <td>{item.lastSyncAt ? formatDate(item.lastSyncAt) : "-"}</td>
              {canManage && (
                <td className="table-actions">
                  <button className="secondary-button" type="button" onClick={() => onSync(item.id)}>{t("markSynced")}</button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

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
    } else {
      setTechnicalStatus(null);
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

function licenseStatusPresentation(
  status: LicenseSummary["status"],
  t: (key: string) => string
): { label: string; tone: "ok" | "warning" } {
  if (status === "CADUCADA") return { label: t("expiredStatus"), tone: "warning" };
  if (status === "BLOQUEADA_MANUAL") return { label: t("blockedStatus"), tone: "warning" };
  return { label: t("valid"), tone: "ok" };
}

function LicenseTable({
  licenses,
  compact = false,
  onAction,
  busy,
  showPairingAction = true,
  showBlockAction = true,
  showUnblockAction = true,
  selectedCompanyId,
  onSelectCompany
}: {
  licenses: LicenseSummary[];
  compact?: boolean;
  onAction?: (reference: string, action: LicenseAction) => void;
  busy?: string | null;
  showPairingAction?: boolean;
  showBlockAction?: boolean;
  showUnblockAction?: boolean;
  selectedCompanyId?: string;
  onSelectCompany?: (companyId: string) => void;
}) {
  const { t } = useI18n();
  const showActionColumn = Boolean(onAction
    && (showPairingAction || showBlockAction || showUnblockAction));
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
                  status={licenseStatusPresentation(license.status, t).label}
                  tone={licenseStatusPresentation(license.status, t).tone}
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
                  {license.status === "BLOQUEADA_MANUAL" ? (
                    showUnblockAction && (
                      <button className="small-button" type="button" onClick={() => onAction?.(license.licenseReference, "unblock")} disabled={busy === `unblock:${license.licenseReference}`}>
                        {t("unblock")}
                      </button>
                    )
                  ) : (
                    showBlockAction && (
                      <button className="small-button danger" type="button" onClick={() => onAction?.(license.licenseReference, "block")} disabled={busy === `block:${license.licenseReference}`}>
                        {t("block")}
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

function InstallationsTable({
  installations,
  canRevoke = false,
  busy = null,
  onRevoke
}: {
  installations: InstallationSummary[];
  canRevoke?: boolean;
  busy?: string | null;
  onRevoke?: (installation: InstallationSummary) => void;
}) {
  const { t } = useI18n();
  if (installations.length === 0) return <EmptyState text={t("noLinkedInstallations")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("installations")}</th>
            <th>{t("status")}</th>
            <th>{t("license")}</th>
            <th>{t("linkedAt")}</th>
            <th>{t("lastValidation")}</th>
            <th>{t("deviceDetails")}</th>
            {canRevoke && <th>{t("actions")}</th>}
          </tr>
        </thead>
        <tbody>
          {installations.map((installation) => (
            <tr key={installation.installationId}>
              <td>
                <strong>{installation.installationReference}</strong>
                <small>{installation.installationId}</small>
              </td>
              <td>
                <StatusPill
                  status={installation.active ? t("active") : t("revoked")}
                  tone={installation.active ? "ok" : "warning"}
                />
                {!installation.active && installation.revokedAt && (
                  <small>{t("revokedAt")}: {formatDate(installation.revokedAt)}</small>
                )}
                {!installation.active && installation.revokedBy && (
                  <small>{t("revokedBy")}: {installation.revokedBy}</small>
                )}
                {!installation.active && installation.revocationReason && (
                  <small>{installation.revocationReason}</small>
                )}
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
              {canRevoke && (
                <td>
                  {installation.active && (
                    <button
                      className="danger-button subtle"
                      type="button"
                      disabled={busy === `revoke:${installation.installationId}`}
                      onClick={() => onRevoke?.(installation)}
                    >
                      {t("revokeInstallation")}
                    </button>
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

function CompanyDetail({
  credentials,
  license,
  installations,
  events,
  canEditCompany,
  canRenewLicense,
  canRevokeInstallation,
  installationBusy,
  onRevokeInstallation,
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  license: LicenseSummary | null;
  installations: InstallationSummary[];
  events: SyncEventView[];
  canEditCompany: boolean;
  canRenewLicense: boolean;
  canRevokeInstallation: boolean;
  installationBusy: string | null;
  onRevokeInstallation: (installation: InstallationSummary) => void;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [companyName, setCompanyName] = useState("");
  const [taxpayerType, setTaxpayerType] = useState<TaxpayerType>("SOCIEDAD");
  const [taxRegime, setTaxRegime] = useState<TaxRegime>("IVA");
  const [commercialProfile, setCommercialProfile] = useState<CommercialProfile>("MAYORISTA");
  const [validUntil, setValidUntil] = useState("");
  const [maxWindows, setMaxWindows] = useState("1");
  const [maxPda, setMaxPda] = useState("0");
  const [operations, setOperations] = useState<CompanyOperations | null>(null);
  const [fiscalProvisioning, setFiscalProvisioning] = useState<FiscalProvisioning | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const companyDetailRequestId = useRef(0);
  const companyDetailMutationId = useRef(0);
  const selectedCompanyIdRef = useRef<string | null>(license?.companyId ?? null);
  selectedCompanyIdRef.current = license?.companyId ?? null;

  useEffect(() => {
    if (!license) return;
    setCompanyName(license.companyName);
    setTaxpayerType(license.taxpayerType);
    setTaxRegime(license.taxRegime);
    setCommercialProfile(license.commercialProfile);
    setValidUntil(toLocalInput(new Date(license.validUntil)));
    setMaxWindows(String(license.maxWindows));
    setMaxPda(String(license.maxPda));
  }, [
    license?.companyId,
    license?.companyName,
    license?.taxpayerType,
    license?.taxRegime,
    license?.commercialProfile,
    license?.validUntil,
    license?.maxWindows,
    license?.maxPda,
  ]);

  useEffect(() => {
    const companyId = license?.companyId ?? null;
    const requestId = ++companyDetailRequestId.current;
    companyDetailMutationId.current += 1;
    setOperations(null);
    setFiscalProvisioning(null);
    setBusy(null);
    if (!companyId) return;
    void loadOperations(companyId, requestId);
    void loadFiscalProvisioning(companyId, requestId);
  }, [license?.companyId]);

  async function loadOperations(companyId: string, requestId: number) {
    try {
      const loaded = await api.companyOperations(credentials, companyId);
      if (!isCurrentCompanyRequest(companyId, requestId) || loaded.companyId !== companyId) return;
      setOperations(loaded);
    } catch {
      if (!isCurrentCompanyRequest(companyId, requestId)) return;
      setOperations(defaultCompanyOperations(companyId));
    }
  }

  async function loadFiscalProvisioning(companyId: string, requestId: number) {
    try {
      const loaded = await api.fiscalProvisioning(credentials, companyId);
      if (!isCurrentCompanyRequest(companyId, requestId) || loaded.companyId !== companyId) return;
      setFiscalProvisioning({
        ...loaded,
        companyAddress: loaded.companyAddress ?? emptyFiscalAddress(),
        stores: loaded.stores.map((store) => ({
          ...store,
          storeAddress: store.storeAddress ?? emptyFiscalAddress(),
        })),
      });
    } catch (error) {
      if (!isCurrentCompanyRequest(companyId, requestId)) return;
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  function isCurrentCompanyRequest(companyId: string, requestId: number) {
    return requestId === companyDetailRequestId.current
      && selectedCompanyIdRef.current === companyId;
  }

  function isCurrentCompanyMutation(companyId: string, mutationId: number) {
    return mutationId === companyDetailMutationId.current
      && selectedCompanyIdRef.current === companyId;
  }

  async function saveCompany(event: FormEvent) {
    event.preventDefault();
    if (!license || !canEditCompany) return;
    setBusy("company");
    try {
      await api.editCompany(credentials, license.companyId, {
        name: companyName,
        taxpayerType,
        impuestos: taxRegime,
        commercialProfile,
      });
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
    if (!license || !canRenewLicense) return;
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

  async function saveFiscalProvisioning(event: FormEvent) {
    event.preventDefault();
    if (!license || !canEditCompany || !fiscalProvisioning?.companyAddress) return;
    const companyId = license.companyId;
    if (fiscalProvisioning.companyId !== companyId) {
      onNotice({ type: "error", text: t("companySelectionChanged") });
      return;
    }
    const mutationId = ++companyDetailMutationId.current;
    setBusy("fiscal-provisioning");
    try {
      const saved = await api.updateFiscalProvisioning(credentials, companyId, {
        companyAddress: fiscalProvisioning.companyAddress,
        stores: fiscalProvisioning.stores.map((store) => ({
          storeId: store.storeId,
          storeAddress: store.storeAddress ?? emptyFiscalAddress(),
          timeZoneId: store.timeZoneId,
        })),
      });
      if (!isCurrentCompanyMutation(companyId, mutationId) || saved.companyId !== companyId) return;
      setFiscalProvisioning(saved);
      onNotice({ type: "success", text: t("fiscalProvisioningUpdated") });
      onChanged();
    } catch (error) {
      if (!isCurrentCompanyMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (isCurrentCompanyMutation(companyId, mutationId)) setBusy(null);
    }
  }

  async function saveOperations(event: FormEvent) {
    event.preventDefault();
    if (!license || !operations || !canEditCompany) return;
    const companyId = license.companyId;
    if (operations.companyId !== companyId) {
      onNotice({ type: "error", text: t("companySelectionChanged") });
      return;
    }
    const mutationId = ++companyDetailMutationId.current;
    setBusy("operations");
    try {
      const saved = await api.updateCompanyOperations(credentials, companyId, {
        planName: operations.planName,
        billingStatus: operations.billingStatus,
        renewalDate: operations.renewalDate ? new Date(operations.renewalDate).toISOString() : null,
        monthlyPrice: operations.monthlyPrice,
        supportStatus: operations.supportStatus,
        contactName: operations.contactName,
        contactEmail: operations.contactEmail,
        notes: operations.notes
      });
      if (!isCurrentCompanyMutation(companyId, mutationId) || saved.companyId !== companyId) return;
      setOperations(saved);
      onNotice({ type: "success", text: t("operationsUpdated") });
      onChanged();
    } catch (error) {
      if (!isCurrentCompanyMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (isCurrentCompanyMutation(companyId, mutationId)) setBusy(null);
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

  const activeInstallations = installations.filter((installation) => installation.active);
  const stores = new Set(activeInstallations.map((installation) => installation.storeId)).size;
  const recentEvents = events.slice(0, 4);
  const operationsForm = operations?.companyId === license.companyId
    ? operations
    : defaultCompanyOperations(license.companyId);
  const fiscalProvisioningForm = fiscalProvisioning?.companyId === license.companyId
    ? fiscalProvisioning
    : null;
  const statusPresentation = licenseStatusPresentation(license.status, t);

  return (
    <section className="content-section company-detail">
      <SectionHeader title={t("companyDetail")} subtitle={t("companyDetailSubtitle")} />
      <div className="company-detail-grid">
        <div className="company-card-main">
          <strong>{license.companyName}</strong>
          <span>{license.taxId}</span>
          <StatusPill status={statusPresentation.label} tone={statusPresentation.tone} />
        </div>
        <Metric label={t("installations")} value={activeInstallations.length} />
        <Metric label={t("stores")} value={stores} />
        <Metric label={t("quotas")} value={`${license.maxWindows} W / ${license.maxPda} PDA`} detail={`${t("licenseExpires")} ${formatDate(license.validUntil)}`} />
      </div>
      <div className="detail-columns">
        <div>
          <SectionHeader title={t("linkedInstallations")} subtitle={`${installations.length} ${t("installations").toLowerCase()}`} />
          <InstallationsTable
            installations={installations}
            canRevoke={canRevokeInstallation}
            busy={installationBusy}
            onRevoke={onRevokeInstallation}
          />
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
            <Input label={t("company")} value={companyName} onChange={setCompanyName} disabled={!canEditCompany} required />
            <Select label={t("type")} value={taxpayerType} options={["SOCIEDAD", "AUTONOMO"]} onChange={(value) => setTaxpayerType(value as TaxpayerType)} disabled />
            <Select label={t("taxes")} value={taxRegime} options={["IVA", "IGIC"]} onChange={(value) => setTaxRegime(value as TaxRegime)} disabled />
            <Select label={t("commercialProfile")} value={commercialProfile} options={["MAYORISTA", "MINORISTA"]} onChange={(value) => setCommercialProfile(value as CommercialProfile)} disabled />
            <small>{t("fiscalIdentityLocked")}</small>
            {canEditCompany && (
              <button className="secondary-button" type="submit" disabled={busy === "company"}>
                {busy === "company" ? t("saving") : t("saveChanges")}
              </button>
            )}
          </form>
          <form className="stack-form phase2-form" onSubmit={renewSelectedLicense}>
            <h3>{t("renewLicense")}</h3>
            <Input label={t("validUntil")} type="datetime-local" value={validUntil} onChange={setValidUntil} disabled={!canRenewLicense} required />
            <Input label="Windows" type="number" value={maxWindows} min={1} onChange={setMaxWindows} disabled={!canRenewLicense} required />
            <Input label="PDA" type="number" value={maxPda} min={0} onChange={setMaxPda} disabled={!canRenewLicense} required />
            {canRenewLicense && (
              <button className="secondary-button" type="submit" disabled={busy === "license"}>
                {busy === "license" ? t("saving") : t("saveChanges")}
              </button>
            )}
          </form>
          {fiscalProvisioningForm?.companyAddress && (
            <form className="stack-form phase2-form wide" onSubmit={saveFiscalProvisioning}>
              <h3>{t("fiscalProvisioning")}</h3>
              <small>{t("fiscalProvisioningSubtitle")}</small>
              <AddressFields
                title={t("companyAddress")}
                value={fiscalProvisioningForm.companyAddress}
                onChange={(companyAddress) => setFiscalProvisioning({ ...fiscalProvisioningForm, companyAddress })}
                disabled={!canEditCompany}
              />
              {fiscalProvisioningForm.stores.map((store) => (
                <div className="fiscal-store-provisioning" key={store.storeId}>
                  <AddressFields
                    title={`${t("storeAddress")}: ${store.storeName} (${store.storeCode})`}
                    value={store.storeAddress ?? emptyFiscalAddress()}
                    onChange={(storeAddress) => setFiscalProvisioning({
                      ...fiscalProvisioningForm,
                      stores: fiscalProvisioningForm.stores.map((candidate) => candidate.storeId === store.storeId
                        ? { ...candidate, storeAddress }
                        : candidate),
                    })}
                    disabled={!canEditCompany}
                  />
                  <Input
                    label={t("timezone")}
                    value={store.timeZoneId}
                    onChange={(timeZoneId) => setFiscalProvisioning({
                      ...fiscalProvisioningForm,
                      stores: fiscalProvisioningForm.stores.map((candidate) => candidate.storeId === store.storeId
                        ? { ...candidate, timeZoneId }
                        : candidate),
                    })}
                    disabled={!canEditCompany}
                    required
                  />
                </div>
              ))}
              {canEditCompany && (
                <button className="secondary-button" type="submit" disabled={busy === "fiscal-provisioning"}>
                  {busy === "fiscal-provisioning" ? t("saving") : t("saveChanges")}
                </button>
              )}
            </form>
          )}
          <form className="stack-form phase2-form wide" onSubmit={saveOperations}>
            <h3>{t("billingStatus")} / {t("supportStatus")}</h3>
            <div className="compact-form-grid">
              <Input label={t("plan")} value={operationsForm.planName} onChange={(planName) => setOperations({ ...operationsForm, planName })} disabled={!canEditCompany} />
              <Input label={t("billingStatus")} value={operationsForm.billingStatus} onChange={(billingStatus) => setOperations({ ...operationsForm, billingStatus })} disabled={!canEditCompany} />
              <Input
                label={t("renewalDate")}
                type="datetime-local"
                value={operationsForm.renewalDate ? toLocalInput(new Date(operationsForm.renewalDate)) : ""}
                onChange={(renewalDate) => setOperations({ ...operationsForm, renewalDate })}
                disabled={!canEditCompany}
              />
              <Input label={t("monthlyPrice")} value={operationsForm.monthlyPrice ?? ""} onChange={(monthlyPrice) => setOperations({ ...operationsForm, monthlyPrice })} disabled={!canEditCompany} />
              <Input label={t("supportStatus")} value={operationsForm.supportStatus} onChange={(supportStatus) => setOperations({ ...operationsForm, supportStatus })} disabled={!canEditCompany} />
              <Input label={t("contactName")} value={operationsForm.contactName ?? ""} onChange={(contactName) => setOperations({ ...operationsForm, contactName })} disabled={!canEditCompany} />
              <Input label={t("contactEmail")} value={operationsForm.contactEmail ?? ""} onChange={(contactEmail) => setOperations({ ...operationsForm, contactEmail })} disabled={!canEditCompany} />
            </div>
            <label>
              {t("notes")}
              <textarea
                className="control-input text-area"
                value={operationsForm.notes ?? ""}
                onChange={(event) => setOperations({ ...operationsForm, notes: event.target.value })}
                disabled={!canEditCompany}
              />
            </label>
            {canEditCompany && (
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
  if (!installation.active) {
    return null;
  }
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
  const projectionTone = event.projectionStatus === "PROJECTED"
    ? "ok"
    : event.projectionStatus === "RECEIVED" || event.projectionStatus === "ERROR"
      ? "warning"
      : "muted";
  return (
    <article className="event-row">
      <div>
        <div className="event-row-heading">
          <strong>{event.entityType}</strong>
          <StatusPill status={event.projectionStatus} tone={projectionTone} />
        </div>
        <span>{event.operation} · {formatDate(event.receivedAt)}</span>
        <div className="event-summary">{eventSummary(event)}</div>
        <div className="event-projection-meta">
          <span>{t("eventSchemaVersion")}: {event.schemaVersion}</span>
          {event.projectedAt && <span>{t("eventProjection")}: {formatDate(event.projectedAt)}</span>}
        </div>
        {event.projectionError && (
          <div className="event-projection-error">
            <strong>{t("eventProjectionError")}</strong>
            <span>{event.projectionError}</span>
          </div>
        )}
      </div>
      <code>{event.entityId}</code>
      <details>
        <summary>{t("payload")}</summary>
        <pre>{JSON.stringify(event.payload, null, 2)}</pre>
      </details>
    </article>
  );
}

function OperationalIncidentsTable({
  rows,
  companyNames,
  canManage,
  busyTargetId,
  onCancel
}: {
  rows: OperationalIncident[];
  companyNames: Map<string, string>;
  canManage: boolean;
  busyTargetId: string | null;
  onCancel: (incident: OperationalIncident) => void;
}) {
  const { t } = useI18n();
  if (rows.length === 0) return <EmptyState text={t("noOperationalIncidents")} />;
  return (
    <div className="table-wrap operational-incident-table">
      <table>
        <thead>
          <tr>
            <th>{t("company")}</th>
            <th>{t("incidentProcess")}</th>
            <th>{t("status")}</th>
            <th>{t("incidentInactivity")}</th>
            <th>{t("incidentProgress")}</th>
            <th>{t("incidentSnapshots")}</th>
            <th>{t("incidentChunks")}</th>
            <th>{t("incidentLastActivity")}</th>
            <th aria-label={t("operations")} />
          </tr>
        </thead>
        <tbody>
          {rows.map((incident) => (
            <tr key={`${incident.incidentType}-${incident.targetId}`} className={busyTargetId === incident.targetId ? "is-busy" : undefined}>
              <td>
                <strong>{companyNames.get(incident.companyId) ?? incident.companyId}</strong>
                <small>{incident.companyId}</small>
              </td>
              <td>
                <strong>{incident.incidentType === "MEMBER_CATEGORY_BOOTSTRAP_STALLED" ? t("memberCategoryBootstrap") : incident.incidentType}</strong>
                <small>{incident.targetId}</small>
                {incident.completedBaselineId && <small>{t("incidentBaseline")}: {incident.completedBaselineId}</small>}
              </td>
              <td>
                <StatusPill status={incident.status} tone={incident.status === "CONFLICT" ? "warning" : "muted"} />
                {incident.conflictSummary && <small title={incident.conflictSummary}>{t("incidentConflict")}: {incident.conflictSummary}</small>}
              </td>
              <td>
                <StatusPill
                  status={incident.inactive ? t("incidentInactive") : t("incidentRecent")}
                  tone={incident.inactive ? "warning" : "ok"}
                />
              </td>
              <td>{incident.completedStoreCount} / {incident.expectedStoreCount}</td>
              <td>{incident.snapshotCount}</td>
              <td>{incident.chunkCount}</td>
              <td>
                <strong>{formatDate(incident.lastActivityAt)}</strong>
                <small>{t("created")}: {formatDate(incident.createdAt)}</small>
              </td>
              <td className="table-actions">
                {incident.cancellable && canManage ? (
                  <button
                    className="small-button danger"
                    type="button"
                    onClick={() => onCancel(incident)}
                    disabled={busyTargetId !== null}
                  >
                    {t("cancelIncident")}
                  </button>
                ) : "-"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
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
            <div className="audit-detail">
              <code>{item.targetType}:{item.targetId}</code>
              {item.details && <p>{item.details}</p>}
            </div>
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

function ProjectionMetric({ label, value, warning = false }: { label: string; value: number | string; warning?: boolean }) {
  return (
    <div className={warning ? "projection-metric warning" : "projection-metric"}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function AddressFields({
  title,
  value,
  onChange,
  disabled = false
}: {
  title: string;
  value: FiscalAddress;
  onChange: (value: FiscalAddress) => void;
  disabled?: boolean;
}) {
  const { t } = useI18n();
  const update = (field: keyof FiscalAddress, next: string) => onChange({ ...value, [field]: next });
  return (
    <fieldset className="address-fields">
      <legend>{title}</legend>
      <Input label={t("addressLine")} value={value.linea1} onChange={(next) => update("linea1", next)} disabled={disabled} required />
      <Input label={t("city")} value={value.ciudad} onChange={(next) => update("ciudad", next)} disabled={disabled} required />
      <Input label={t("postalCode")} value={value.codigoPostal} onChange={(next) => update("codigoPostal", next)} disabled={disabled} required />
      <Input label={t("province")} value={value.provincia} onChange={(next) => update("provincia", next)} disabled={disabled} required />
      <Input label={t("country")} value={value.pais} onChange={(next) => update("pais", next)} disabled={disabled} required />
    </fieldset>
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

function readLanguage(): Language {
  const value = localStorage.getItem("tpv-saas-language");
  return value === "en" || value === "zh" || value === "es" ? value : "es";
}

function viewTitle(view: View, t: (key: string) => string) {
  return {
    dashboard: t("dashboard"),
    licenses: t("licensesCompanies"),
    sync: t("sync"),
    fiscal: t("fiscal"),
    users: t("adminUsers"),
    support: t("supportCenter"),
    health: t("customerHealth"),
    billing: t("billing"),
    masters: t("masters"),
    operations: t("operations"),
    subscriptions: t("subscriptions"),
    reports: t("reports"),
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

function isPositiveAmount(value: string | null | undefined) {
  return parseAmount(value) > 0;
}

function isValidUrl(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
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
  const stale = data.installations.filter((installation) => installation.active
    && (!installation.lastValidatedAt || hoursSince(installation.lastValidatedAt) > 48));

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
    UPDATE_SUPPORT_TICKET: "Ticket de soporte actualizado",
    UPDATE_VERIFACTU_ACTIVATION_POLICY: "Politica de activacion de VeriFactu actualizada"
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

function uniqueStrings(values: string[]) {
  return Array.from(new Set(values.filter(Boolean))).sort((left, right) => left.localeCompare(right));
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
    const actionableMessage = extractApiErrorMessage(error.message);
    if (actionableMessage) return actionableMessage;
    if (error.status === 401) return TRANSLATIONS.es.invalidCredentials;
    if (error.status === 403) return TRANSLATIONS.es.forbiddenAction;
    if (error.status === 404) return TRANSLATIONS.es.resourceNotFound;
    if (error.status >= 500) return TRANSLATIONS.es.backendNotUpdated;
    if (error.status === 400) return "La solicitud no es válida.";
    if (error.status === 409) return "La operación entra en conflicto con el estado actual.";
    if (error.status === 429) return "Demasiadas solicitudes. Espera un momento y vuelve a intentarlo.";
    return cleanTechnicalText(error.message);
  }
  if (error instanceof TypeError) return TRANSLATIONS.es.networkError;
  if (error instanceof Error) return cleanTechnicalText(error.message);
  return "Operacion no completada";
}

function cleanTechnicalText(value: string) {
  const text = value.trim();
  if (!text) return "Operacion no completada";
  if (text.startsWith("{") || text.includes("\"timestamp\"")) return TRANSLATIONS.es.backendNotUpdated;
  if (text.includes("Failed to fetch") || text.includes("NetworkError")) return TRANSLATIONS.es.networkError;
  return text;
}

function isMissingPhase3Endpoint(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

function isRecoverableBackendDataError(error: unknown) {
  return error instanceof ApiError && error.status >= 500;
}

function userManagementErrorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 403) {
    return "No tienes permiso para gestionar usuarios. Entra con un usuario ADMIN.";
  }
  return errorMessage(error);
}
