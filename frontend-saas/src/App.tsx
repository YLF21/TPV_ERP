import { createContext, FormEvent, useContext, useEffect, useMemo, useState } from "react";
import { api, ApiError } from "./lib/api";
import type {
  AdminUser,
  AuditLog,
  CreateCompanyRequest,
  Credentials,
  DashboardData,
  InstallationSummary,
  LicenseSummary,
  PairingCodeResponse,
  StockSnapshot,
  SyncEventView,
  TaxRegime,
  TaxpayerType
} from "./lib/types";

type View = "dashboard" | "licenses" | "sync" | "users" | "audit";
type Notice = { type: "success" | "error"; text: string } | null;
type LicenseAction = "block" | "unblock" | "pairing";
type SaasAdminRoleName = "ADMIN" | "VIEWER";
type Language = "es" | "en" | "zh";

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
    noAuditActions: "No hay acciones de auditoria."
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
    noAuditActions: "No audit actions."
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
  { value: "VIEWER", label: "VIEWER", description: "Solo consulta de datos admin" }
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
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
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

  useEffect(() => {
    if (credentials) {
      void refresh(credentials);
    }
  }, [credentials]);

  async function refresh(activeCredentials = credentials) {
    if (!activeCredentials) return;
    setLoading(true);
    try {
      const dashboard = await api.dashboard(activeCredentials);
      setData(dashboard);
      setNotice(null);
    } catch (error) {
      setNotice({ type: "error", text: errorMessage(error) });
      if (error instanceof ApiError && error.status === 401) {
        setCredentials(null);
        sessionStorage.removeItem("tpv-saas-credentials");
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

        {notice && <div className={`notice ${notice.type}`}>{notice.text}</div>}

        {!data ? (
          <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : (
          <>
            {activeView === "dashboard" && <Dashboard data={data} />}
            {activeView === "licenses" && (
              <LicensesView
                credentials={credentials}
                licenses={data.licenses}
                installations={data.installations}
                onChanged={() => void refresh()}
                onNotice={setNotice}
              />
            )}
            {activeView === "sync" && <SyncView credentials={credentials} licenses={data.licenses} onNotice={setNotice} />}
            {activeView === "users" && (
              <UsersView credentials={credentials} users={data.users} onChanged={() => void refresh()} onNotice={setNotice} />
            )}
            {activeView === "audit" && <AuditView audit={data.audit} />}
          </>
        )}
      </main>
    </div>
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

function Dashboard({ data }: { data: DashboardData }) {
  const { t } = useI18n();
  const activeLicenses = data.licenses.filter((license) => license.status === "VALIDA").length;
  const blockedLicenses = data.licenses.filter((license) => license.status === "BLOQUEADA_MANUAL").length;
  const activeUsers = data.users.filter((user) => user.active).length;
  const lastEvent = data.events[0];

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
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  installations: InstallationSummary[];
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [companyForm, setCompanyForm] = useState<CreateCompanyRequest>(initialCompanyForm);
  const [pairingCode, setPairingCode] = useState<PairingCodeResponse | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const isViewerSession = credentials.username.trim().toLowerCase() === "viewer";

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
      {!isViewerSession && (
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
        <LicenseTable
          licenses={licenses}
          onAction={(reference, action) => void licenseAction(reference, action)}
          busy={busy}
          showPairingAction={!isViewerSession}
          showStatusActions={!isViewerSession}
        />
      </section>

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
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  users: AdminUser[];
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [roleName, setRoleName] = useState<SaasAdminRoleName>("ADMIN");
  const [busy, setBusy] = useState<string | null>(null);
  const isViewerSession = credentials.username.trim().toLowerCase() === "viewer";

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

  return (
    <div className="view-grid">
      <section className="content-section">
        <SectionHeader title={t("newUser")} subtitle={t("availableRoles")} />
        {isViewerSession && (
          <div className="permission-hint">
            {t("viewerPermissionHint")}
          </div>
        )}
        {!isViewerSession && (
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
                {!isViewerSession && <th></th>}
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
                  {!isViewerSession && (
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

function LicenseTable({
  licenses,
  compact = false,
  onAction,
  busy,
  showPairingAction = true,
  showStatusActions = true
}: {
  licenses: LicenseSummary[];
  compact?: boolean;
  onAction?: (reference: string, action: LicenseAction) => void;
  busy?: string | null;
  showPairingAction?: boolean;
  showStatusActions?: boolean;
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
            <tr key={license.licenseReference} className={busy?.endsWith(license.licenseReference) ? "is-busy" : ""}>
              <td>
                <strong>{license.licenseReference}</strong>
                <small>{license.taxId}</small>
              </td>
              <td>{license.companyName}</td>
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
          {pairingCode.licenseReference} · expira {formatDate(pairingCode.expiresAt)}
        </small>
      </div>
      <button className="secondary-button" type="button" onClick={onCopy}>
        {t("copy")}
      </button>
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
              <td>{installation.lastValidatedAt ? formatDate(installation.lastValidatedAt) : t("pending")}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
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
            <strong>{item.action}</strong>
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

function Select({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  return (
    <label>
      {label}
      <select className="control-input" value={value} onChange={(event) => onChange(event.target.value)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
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
    audit: t("audit")
  }[view];
}

function uniqueCompanies(licenses: LicenseSummary[]) {
  return Array.from(new Map(licenses.map((license) => [license.companyId, license])).values()).map((license) => ({
    companyId: license.companyId,
    companyName: license.companyName
  }));
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
  if (error instanceof Error) return error.message;
  return "Operacion no completada";
}

function userManagementErrorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 403) {
    return "No tienes permiso para gestionar usuarios. Entra con un usuario ADMIN.";
  }
  return errorMessage(error);
}
