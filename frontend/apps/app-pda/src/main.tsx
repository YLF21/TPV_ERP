import { Component, lazy, StrictMode, Suspense, useCallback, useEffect, useState, type ErrorInfo, type FormEvent, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { version as applicationVersion } from "../package.json";
import { ApiError, apiRequest, classifyApiFailure } from "../../../packages/app-common/src/api/client";
import { createTranslator } from "../../../packages/app-common/src/i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";
import { PdaHomeDashboard } from "./PdaHomeDashboard";
import {
  applyRotatedPdaCredential, clearPdaIdentity, quarantineDisabledPdaIdentity, readPdaIdentity, writePdaIdentity, type PdaIdentity, type RotatedPdaCredential
} from "./pdaIdentity";
import { usePdaModuleExitWarning, usePdaNavigation } from "./usePdaNavigation";
import { readPdaWorkQueue } from "./PdaWorkQueue";
import "./pda.css";

const GoodsCheckPanel = lazy(async () => ({ default: (await import("../../../packages/app-common/src/components/GoodsCheckPanel")).GoodsCheckPanel }));
const LoginScreen = lazy(async () => ({ default: (await import("../../../packages/app-common/src/components/LoginScreen")).LoginScreen }));
const PdaProductLookup = lazy(async () => ({ default: (await import("./PdaProductLookup")).PdaProductLookup }));
const PdaReplenishment = lazy(async () => ({ default: (await import("./PdaReplenishment")).PdaReplenishment }));
const PdaStockCount = lazy(async () => ({ default: (await import("./PdaStockCount")).PdaStockCount }));
const PdaHistory = lazy(async () => ({ default: (await import("./PdaHistory")).PdaHistory }));
const PdaWorkboard = lazy(async () => ({ default: (await import("./PdaWorkboard")).PdaWorkboard }));

const moduleLoadingCopy: Record<LocaleCode, { eyebrow: string; title: string; detail: string }> = {
  es: { eyebrow: "Preparando módulo", title: "Cargando {module}", detail: "Estamos preparando los datos y las herramientas necesarias." },
  en: { eyebrow: "Preparing module", title: "Loading {module}", detail: "We are preparing the data and tools you need." },
  zh: { eyebrow: "正在准备模块", title: "正在加载{module}", detail: "正在准备所需的数据和工具。" }
};

const moduleErrorCopy: Record<LocaleCode, { title: string; detail: string; retry: string; home: string }> = {
  es: { title: "No se pudo abrir este módulo", detail: "Puedes reintentar la carga o volver al inicio sin perder la sesión.", retry: "Reintentar", home: "Volver al inicio" },
  en: { title: "This module could not be opened", detail: "Retry loading it or return home without losing your session.", retry: "Retry", home: "Back home" },
  zh: { title: "无法打开此模块", detail: "你可以重试加载，或返回首页而无需退出登录。", retry: "重试", home: "返回首页" }
};

class PdaModuleBoundary extends Component<{
  children: ReactNode;
  locale: LocaleCode;
  moduleKey: string;
  onHome: () => void;
}, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() { return { failed: true }; }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("PDA module failed", error, info.componentStack);
  }

  componentDidUpdate(previous: Readonly<{ moduleKey: string }>) {
    if (previous.moduleKey !== this.props.moduleKey && this.state.failed) this.setState({ failed: false });
  }

  render() {
    if (!this.state.failed) return this.props.children;
    const text = moduleErrorCopy[this.props.locale];
    return <section className="pda-module-error" role="alert">
      <h2>{text.title}</h2><p>{text.detail}</p>
      <div><button type="button" onClick={() => window.location.reload()}>{text.retry}</button><button type="button" className="secondary" onClick={this.props.onHome}>{text.home}</button></div>
    </section>;
  }
}

function PdaModuleLoading({ locale, moduleTitle }: { locale: LocaleCode; moduleTitle: string }) {
  const content = moduleLoadingCopy[locale];
  const title = content.title.replace("{module}", moduleTitle);
  return (
    <section className="pda-module-loading" role="status" aria-live="polite" aria-busy="true">
      <div className="pda-module-loading-card">
        <span className="pda-module-loading-icon" aria-hidden="true" />
        <div><span>{content.eyebrow}</span><strong>{title}</strong><p>{content.detail}</p></div>
        <progress aria-label={title} />
      </div>
    </section>
  );
}

type PdaRegistrationResult = {
  terminalId: string;
  terminalCode: string;
  storeName: string;
  terminalCredential: string;
  status: "PENDING" | "APPROVED";
};

type WarehouseOption = { id: string; name?: string | null; nombre?: string | null; defaultWarehouse?: boolean; active?: boolean };
type SupplierOption = {
  id: string;
  supplierId?: string | null;
  legalName?: string | null;
  razonSocial?: string | null;
  tradeName?: string | null;
};

const copy = {
  es: {
    setupTitle: "Registrar este PDA",
    setupHelp: "Asigna un nombre reconocible. Después un administrador deberá aprobar el dispositivo en esPOS Gestión.",
    deviceName: "Nombre del dispositivo",
    devicePlaceholder: "PDA ALMACÉN 1",
    request: "Solicitar acceso",
    registerNew: "Registrar nuevo",
    linkExisting: "Vincular existente",
    pairingCode: "Código temporal",
    pairingPlaceholder: "ABCD-EFGH",
    pairingHelp: "Genera el código desde esPOS Gestión → Seguridad → Terminales y PDA.",
    link: "Vincular PDA",
    linkError: "El código no es válido, ha caducado o ya fue utilizado.",
    pending: "Solicitud enviada. Aprueba el PDA desde esPOS Gestión y después inicia sesión.",
    offlinePending: "Sin conexión. Hay {count} acciones guardadas; se sincronizarán después de iniciar sesión cuando vuelva la conexión.",
    requestError: "No se pudo registrar el PDA",
    reset: "Registrar otro dispositivo",
    loginTitle: "esPOS PDA",
    loginHelp: "Identifícate para trabajar con este dispositivo de almacén.",
    language: "Idioma",
    logout: "Cerrar sesión",
    rotateCredential: "Rotar credencial",
    rotatingCredential: "Rotando…",
    credentialRotated: "Credencial del PDA renovada.",
    credentialRotationError: "No se pudo renovar la credencial. No cierres la sesión y vuelve a intentarlo.",
    title: "Operaciones de almacén",
    device: "Dispositivo"
  },
  en: {
    setupTitle: "Register this PDA",
    setupHelp: "Choose a recognizable name. An administrator must then approve the device in esPOS Management.",
    deviceName: "Device name",
    devicePlaceholder: "WAREHOUSE PDA 1",
    request: "Request access",
    registerNew: "Register new",
    linkExisting: "Link existing",
    pairingCode: "Temporary code",
    pairingPlaceholder: "ABCD-EFGH",
    pairingHelp: "Generate the code in esPOS Management → Security → Terminals and PDA.",
    link: "Link PDA",
    linkError: "The code is invalid, expired, or has already been used.",
    pending: "Request sent. Approve the PDA in esPOS Management and then sign in.",
    offlinePending: "Offline. {count} saved actions will synchronize after sign-in when connectivity returns.",
    requestError: "The PDA could not be registered",
    reset: "Register another device",
    loginTitle: "esPOS PDA",
    loginHelp: "Sign in to work with this warehouse device.",
    language: "Language",
    logout: "Sign out",
    rotateCredential: "Rotate credential",
    rotatingCredential: "Rotating…",
    credentialRotated: "PDA credential renewed.",
    credentialRotationError: "The credential could not be renewed. Keep this session open and retry.",
    title: "Warehouse operations",
    device: "Device"
  },
  zh: {
    setupTitle: "注册此 PDA",
    setupHelp: "请设置一个易识别的名称，然后由管理员在 esPOS 管理应用中批准该设备。",
    deviceName: "设备名称",
    devicePlaceholder: "仓库 PDA 1",
    request: "申请访问",
    registerNew: "注册新设备",
    linkExisting: "绑定已有 PDA",
    pairingCode: "临时绑定码",
    pairingPlaceholder: "ABCD-EFGH",
    pairingHelp: "请在 esPOS 管理应用 → 安全 → 终端和 PDA 中生成绑定码。",
    link: "绑定 PDA",
    linkError: "绑定码无效、已过期或已使用。",
    pending: "申请已发送。请先在 esPOS 管理应用中批准 PDA，然后登录。",
    offlinePending: "当前离线。已有 {count} 个操作安全保存，恢复网络并登录后将自动同步。",
    requestError: "无法注册 PDA",
    reset: "注册其他设备",
    loginTitle: "esPOS PDA",
    loginHelp: "登录以使用此仓库设备。",
    language: "语言",
    logout: "退出登录",
    rotateCredential: "轮换凭据",
    rotatingCredential: "正在轮换…",
    credentialRotated: "PDA 凭据已更新。",
    credentialRotationError: "无法更新凭据。请保持当前会话并重试。",
    title: "仓库作业",
    device: "设备"
  }
} as const;

function App() {
  const [locale, setLocale] = useState<LocaleCode>(() => { const saved=localStorage.getItem("tpverp.pda.locale.v1"); return saved==="en"||saved==="zh"?saved:"es"; });
  const [identity, setIdentity] = useState<PdaIdentity | null>(() => readPdaIdentity(window.localStorage));
  const [session, setSession] = useState<UserSession | null>(null);

  useEffect(() => { document.documentElement.lang = locale === "zh" ? "zh-CN" : locale; try{localStorage.setItem("tpverp.pda.locale.v1",locale)}catch{/* Language persistence is optional. */} }, [locale]);
  useEffect(() => {
    if (!import.meta.env.PROD || !("serviceWorker" in navigator)) return;
    void navigator.serviceWorker.register("/pda-sw.js").catch((error) => console.warn("PDA service worker registration failed", error));
  }, []);

  function forgetDevice() {
    clearPdaIdentity(window.localStorage);
    setSession(null);
    setIdentity(null);
  }

  function handleAuthenticationError(error: unknown) {
    if (classifyApiFailure(error) !== "terminal-disabled") return;
    quarantineDisabledPdaIdentity(window.localStorage);
    setSession(null);
    setIdentity(null);
  }

  if (!identity) {
    return <PdaEnrollment locale={locale} onLocaleChange={setLocale} onRegistered={setIdentity} />;
  }

  if (!session) {
    const text = copy[locale];
    const queued = readPdaWorkQueue().length;
    const notice = identity.pendingApproval ? text.pending : !navigator.onLine && queued > 0 ? text.offlinePending.replace("{count}",String(queued)) : undefined;
    return (
      <div className="pda-classic-login-shell">
        <Suspense fallback={<PdaModuleLoading locale={locale} moduleTitle={text.loginTitle} />}><LoginScreen
          app="pda"
          locale={locale}
          terminalContext={identity}
          onLocaleChange={setLocale}
          onLogin={(value) => {
            const approved = { ...identity, pendingApproval: false };
            writePdaIdentity(window.localStorage, approved);
            setIdentity(approved);
            setSession(value);
          }}
          onAuthenticationError={handleAuthenticationError}
          heading={text.loginTitle}
          notice={notice}
          secondaryActionLabel={text.reset}
          onSecondaryAction={forgetDevice}
        /></Suspense>
      </div>
    );
  }

  return (
    <PdaWorkspace
      identity={identity}
      locale={locale}
      session={session}
      onLocaleChange={setLocale}
      onLogout={() => setSession(null)}
      onIdentityRotated={setIdentity}
    />
  );
}

function PdaEnrollment({
  locale,
  onLocaleChange,
  onRegistered
}: {
  locale: LocaleCode;
  onLocaleChange: (locale: LocaleCode) => void;
  onRegistered: (identity: PdaIdentity) => void;
}) {
  const [mode, setMode] = useState<"register" | "link">("register");
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const text = copy[locale];

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setError("");
    try {
      const result = await apiRequest<PdaRegistrationResult>("/terminals/pda/request", {
        method: "POST",
        body: { name: name.trim() }
      });
      const identity: PdaIdentity = {
        storeName: result.storeName,
        terminalCode: result.terminalCode,
        terminalId: result.terminalId,
        terminalCredential: result.terminalCredential,
        pendingApproval: true
      };
      writePdaIdentity(window.localStorage, identity);
      onRegistered(identity);
    } catch (caught) {
      setError(caught instanceof ApiError && caught.status === 409 ? caught.message : text.requestError);
    } finally {
      setBusy(false);
    }
  }

  async function linkExisting(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (code.replace(/[^A-Za-z0-9]/g, "").length !== 8) return;
    setBusy(true);
    setError("");
    try {
      const result = await apiRequest<PdaRegistrationResult>("/terminals/pda/link", {
        method: "POST",
        body: { code: code.trim() }
      });
      const identity: PdaIdentity = {
        storeName: result.storeName,
        terminalCode: result.terminalCode,
        terminalId: result.terminalId,
        terminalCredential: result.terminalCredential,
        pendingApproval: false
      };
      writePdaIdentity(window.localStorage, identity);
      onRegistered(identity);
    } catch {
      setError(text.linkError);
    } finally {
      setBusy(false);
    }
  }

  function changeMode(next: "register" | "link") {
    setMode(next);
    setError("");
  }

  return (
    <main className="pda-enrollment">
      <section className="pda-enrollment-card">
        <header>
          <span>esPOS · v{applicationVersion}</span>
          <strong>{text.setupTitle}</strong>
          <p>{mode === "register" ? text.setupHelp : text.pairingHelp}</p>
        </header>
        <label className="pda-language">
          <span>Idioma / Language / 语言</span>
          <select value={locale} onChange={(event) => onLocaleChange(event.target.value as LocaleCode)}>
            <option value="es">Español</option>
            <option value="en">English</option>
            <option value="zh">中文</option>
          </select>
        </label>
        <nav className="pda-enrollment-modes" aria-label={text.setupTitle}>
          <button type="button" className={mode === "register" ? "active" : ""} onClick={() => changeMode("register")}>{text.registerNew}</button>
          <button type="button" className={mode === "link" ? "active" : ""} onClick={() => changeMode("link")}>{text.linkExisting}</button>
        </nav>
        {mode === "register" ? <form onSubmit={submit}>
          <label>
            <span>{text.deviceName}</span>
            <input autoFocus maxLength={80} value={name} placeholder={text.devicePlaceholder} disabled={busy} onChange={(event) => setName(event.target.value)} />
          </label>
          {error && <strong className="pda-error" role="alert">{error}</strong>}
          <button type="submit" disabled={busy || !name.trim()}>{busy ? "…" : text.request}</button>
        </form> : <form onSubmit={linkExisting}>
          <label>
            <span>{text.pairingCode}</span>
            <input autoFocus inputMode="text" autoCapitalize="characters" maxLength={9} value={code} placeholder={text.pairingPlaceholder} disabled={busy}
              onChange={(event) => setCode(event.target.value.toUpperCase().replace(/[^A-Z0-9-]/g, ""))} />
          </label>
          {error && <strong className="pda-error" role="alert">{error}</strong>}
          <button type="submit" disabled={busy || code.replace(/[^A-Za-z0-9]/g, "").length !== 8}>{busy ? "…" : text.link}</button>
        </form>}
      </section>
    </main>
  );
}
function PdaWorkspace({
  identity,
  locale,
  session,
  onLocaleChange,
  onLogout,
  onIdentityRotated
}: {
  identity: PdaIdentity;
  locale: LocaleCode;
  session: UserSession;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout: () => void;
  onIdentityRotated: (identity: PdaIdentity) => void;
}) {
  const [warehouses, setWarehouses] = useState<WarehouseOption[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierOption[]>([]);
  const [catalogErrors, setCatalogErrors] = useState<Array<"warehouses" | "suppliers">>([]);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [credentialRotation, setCredentialRotation] = useState<"idle"|"busy"|"success"|"error">("idle");
  const { view, openView, goHome } = usePdaNavigation();
  const t = createTranslator(locale);
  const moduleTitle = t(view === "check" ? "pda.navigation.check" : view === "lookup" ? "pda.navigation.lookup" : view === "count" ? "pda.navigation.count" : view === "history" ? "pda.navigation.history" : view === "work" ? "pda.navigation.work" : "pda.navigation.replenishment");

  usePdaModuleExitWarning(view !== "home");

  const loadCatalogs = useCallback(async () => {
    const token = session.accessToken;
    if (!token) return;
    setCatalogLoading(true);
    const [warehouseResult, supplierResult] = await Promise.allSettled([
      apiRequest<WarehouseOption[]>("/warehouses", { token }),
      apiRequest<SupplierOption[]>("/suppliers", { token })
    ]);
    const failures: Array<"warehouses" | "suppliers"> = [];
    if (warehouseResult.status === "fulfilled") setWarehouses(warehouseResult.value);
    else failures.push("warehouses");
    if (supplierResult.status === "fulfilled") setSuppliers(supplierResult.value);
    else failures.push("suppliers");
    setCatalogErrors(failures);
    setCatalogLoading(false);
  }, [session.accessToken]);

  useEffect(() => { void loadCatalogs(); }, [loadCatalogs]);

  async function rotateCredential() {
    if (credentialRotation === "busy") return;
    setCredentialRotation("busy");
    try {
      // Preflight the browser store before invalidating the server-side secret.
      writePdaIdentity(window.localStorage, identity);
      const result = await apiRequest<RotatedPdaCredential>("/terminals/current/credential/rotate", { method: "POST", token: session.accessToken });
      const rotated = applyRotatedPdaCredential(window.localStorage, identity, result);
      onIdentityRotated(rotated);
      setCredentialRotation("success");
    } catch {
      setCredentialRotation("error");
    }
  }

  const catalogText = locale === "es"
    ? { loading: "Actualizando almacenes y proveedores…", warehouses: "No se pudieron cargar los almacenes.", suppliers: "No se pudieron cargar los proveedores.", retry: "Reintentar" }
    : locale === "en"
      ? { loading: "Updating warehouses and suppliers…", warehouses: "Warehouses could not be loaded.", suppliers: "Suppliers could not be loaded.", retry: "Retry" }
      : { loading: "正在更新仓库和供应商…", warehouses: "无法加载仓库。", suppliers: "无法加载供应商。", retry: "重试" };

  return (
    <main className="pda-app-shell">
      <header className="pda-app-header">
        <div><span>esPOS · PDA · v{applicationVersion}</span><strong>{copy[locale].title}</strong></div>
        <div className="pda-header-actions">
          <small>{copy[locale].device}: {identity.terminalCode}</small>
          <select aria-label={copy[locale].language} value={locale} onChange={(event) => onLocaleChange(event.target.value as LocaleCode)}>
            <option value="es">ES</option><option value="en">EN</option><option value="zh">中文</option>
          </select>
          <button type="button" className="pda-rotate-credential" aria-label={copy[locale].rotateCredential} disabled={credentialRotation==="busy"} onClick={()=>void rotateCredential()}>{credentialRotation==="busy"?copy[locale].rotatingCredential:copy[locale].rotateCredential}</button>
          <button type="button" onClick={onLogout}>{copy[locale].logout}</button>
        </div>
      </header>
      {credentialRotation==="success"&&<p className="pda-count-status" role="status">{copy[locale].credentialRotated}</p>}
      {credentialRotation==="error"&&<p className="pda-count-error" role="alert">{copy[locale].credentialRotationError}</p>}
      {(catalogLoading || catalogErrors.length > 0) && <div className={`pda-catalog-status${catalogErrors.length ? " is-error" : ""}`} role={catalogErrors.length ? "alert" : "status"} aria-live="polite">
        <span>{catalogLoading ? catalogText.loading : catalogErrors.map((key) => catalogText[key]).join(" ")}</span>
        {catalogErrors.length > 0 && <button type="button" onClick={() => void loadCatalogs()} disabled={catalogLoading}>{catalogText.retry}</button>}
      </div>}
      {view === "home" && <section className="pda-home">
        <header><span>{t("pda.home.eyebrow")}</span><h1>{t("pda.home.title")}</h1><p>{t("pda.home.subtitle")}</p></header>
        <PdaHomeDashboard token={session.accessToken} userId={session.userId} locale={locale} warehouses={warehouses} onOpen={openView} />
        <nav className="pda-home-menu" aria-label={t("pda.navigation")}>
          <button type="button" onClick={() => openView("lookup")}><b aria-hidden="true">⌕</b><span>{t("pda.navigation.lookup")}</span><small>{t("pda.home.lookupHelp")}</small></button>
          <button type="button" onClick={() => openView("check")}><b aria-hidden="true">✓</b><span>{t("pda.navigation.check")}</span><small>{t("pda.home.checkHelp")}</small></button>
          <button type="button" onClick={() => openView("replenishment")}><b aria-hidden="true">⇄</b><span>{t("pda.navigation.replenishment")}</span><small>{t("pda.home.replenishmentHelp")}</small></button>
          <button type="button" onClick={() => openView("count")}><b aria-hidden="true">≣</b><span>{t("pda.navigation.count")}</span><small>{t("pda.home.countHelp")}</small></button>
          <button type="button" onClick={() => openView("history")}><b aria-hidden="true">↺</b><span>{t("pda.navigation.history")}</span><small>{t("pda.home.historyHelp")}</small></button>
          <button type="button" onClick={() => openView("work")}><b aria-hidden="true">▦</b><span>{t("pda.navigation.work")}</span><small>{t("pda.home.workHelp")}</small></button>
        </nav>
      </section>}
      {view !== "home" && <nav className="pda-module-toolbar" aria-label={t("pda.navigation")}>
        <button type="button" onClick={goHome}>← {t("pda.navigation.home")}</button>
        <strong>{moduleTitle}</strong>
      </nav>}
      <PdaModuleBoundary locale={locale} moduleKey={view} onHome={goHome}>
      <Suspense fallback={<PdaModuleLoading locale={locale} moduleTitle={moduleTitle} />}>
      {view === "check" && <div className="pda-module-view">
        <GoodsCheckPanel
          app="pda"
          locale={locale}
          token={session.accessToken}
          t={t}
          warehouses={warehouses}
          suppliers={suppliers}
          separateWorkflow
        />
      </div>}
      {view === "lookup" && <div className="pda-module-view">
        <PdaProductLookup
          token={session.accessToken}
          locale={locale}
          warehouses={warehouses}
          storeName={identity.storeName}
          t={t}
        />
      </div>}
      {view === "replenishment" && <div className="pda-module-view">
        <PdaReplenishment token={session.accessToken} locale={locale} warehouses={warehouses} t={t} />
      </div>}
      {view === "count" && <div className="pda-module-view">
        <PdaStockCount token={session.accessToken} locale={locale} warehouses={warehouses} t={t} />
      </div>}
      {view === "history" && <div className="pda-module-view">
        <PdaHistory token={session.accessToken} locale={locale} warehouses={warehouses} t={t} />
      </div>}
      {view === "work" && <div className="pda-module-view">
        <PdaWorkboard token={session.accessToken} locale={locale} warehouses={warehouses} userId={session.userId} />
      </div>}
      </Suspense>
      </PdaModuleBoundary>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
