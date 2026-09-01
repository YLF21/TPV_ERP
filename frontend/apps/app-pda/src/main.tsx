import { lazy, StrictMode, Suspense, useEffect, useState, type FormEvent } from "react";
import { createRoot } from "react-dom/client";
import { SpinnerGap } from "@phosphor-icons/react";
import {
  ApiError,
  LoginScreen,
  apiRequest,
  classifyApiFailure,
  createTranslator,
  type LocaleCode,
  type TerminalContext,
  type UserSession
} from "@tpverp/app-common";
import { PdaHomeDashboard } from "./PdaHomeDashboard";
import {
  clearPdaIdentity, quarantineDisabledPdaIdentity, readPdaIdentity, writePdaIdentity, type PdaIdentity
} from "./pdaIdentity";
import { usePdaModuleExitWarning, usePdaNavigation } from "./usePdaNavigation";
import "./pda.css";

const GoodsCheckPanel = lazy(async () => ({ default: (await import("../../../packages/app-common/src/components/GoodsCheckPanel")).GoodsCheckPanel }));
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

function PdaModuleLoading({ locale, moduleTitle }: { locale: LocaleCode; moduleTitle: string }) {
  const content = moduleLoadingCopy[locale];
  const title = content.title.replace("{module}", moduleTitle);
  return (
    <section className="pda-module-loading" role="status" aria-live="polite" aria-busy="true">
      <div className="pda-module-loading-card">
        <SpinnerGap className="pda-module-loading-icon" size={44} weight="bold" aria-hidden="true" />
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
    setupHelp: "Asigna un nombre reconocible. Después un administrador deberá aprobar el dispositivo en APP GESTIÓN.",
    deviceName: "Nombre del dispositivo",
    devicePlaceholder: "PDA ALMACÉN 1",
    request: "Solicitar acceso",
    registerNew: "Registrar nuevo",
    linkExisting: "Vincular existente",
    pairingCode: "Código temporal",
    pairingPlaceholder: "ABCD-EFGH",
    pairingHelp: "Genera el código desde APP GESTIÓN → Seguridad → Terminales y PDA.",
    link: "Vincular PDA",
    linkError: "El código no es válido, ha caducado o ya fue utilizado.",
    pending: "Solicitud enviada. Aprueba el PDA desde APP GESTIÓN y después inicia sesión.",
    requestError: "No se pudo registrar el PDA",
    reset: "Registrar otro dispositivo",
    loginTitle: "APP PDA",
    loginHelp: "Identifícate para trabajar con este dispositivo de almacén.",
    language: "Idioma",
    logout: "Cerrar sesión",
    title: "Operaciones de almacén",
    device: "Dispositivo"
  },
  en: {
    setupTitle: "Register this PDA",
    setupHelp: "Choose a recognizable name. An administrator must then approve the device in APP MANAGEMENT.",
    deviceName: "Device name",
    devicePlaceholder: "WAREHOUSE PDA 1",
    request: "Request access",
    registerNew: "Register new",
    linkExisting: "Link existing",
    pairingCode: "Temporary code",
    pairingPlaceholder: "ABCD-EFGH",
    pairingHelp: "Generate the code in APP MANAGEMENT → Security → Terminals and PDA.",
    link: "Link PDA",
    linkError: "The code is invalid, expired, or has already been used.",
    pending: "Request sent. Approve the PDA in APP MANAGEMENT and then sign in.",
    requestError: "The PDA could not be registered",
    reset: "Register another device",
    loginTitle: "APP PDA",
    loginHelp: "Sign in to work with this warehouse device.",
    language: "Language",
    logout: "Sign out",
    title: "Warehouse operations",
    device: "Device"
  },
  zh: {
    setupTitle: "注册此 PDA",
    setupHelp: "请设置一个易识别的名称，然后由管理员在管理应用中批准该设备。",
    deviceName: "设备名称",
    devicePlaceholder: "仓库 PDA 1",
    request: "申请访问",
    registerNew: "注册新设备",
    linkExisting: "绑定已有 PDA",
    pairingCode: "临时绑定码",
    pairingPlaceholder: "ABCD-EFGH",
    pairingHelp: "请在管理应用 → 安全 → 终端和 PDA 中生成绑定码。",
    link: "绑定 PDA",
    linkError: "绑定码无效、已过期或已使用。",
    pending: "申请已发送。请先在管理应用中批准 PDA，然后登录。",
    requestError: "无法注册 PDA",
    reset: "注册其他设备",
    loginTitle: "APP PDA",
    loginHelp: "登录以使用此仓库设备。",
    language: "语言",
    logout: "退出登录",
    title: "仓库作业",
    device: "设备"
  }
} as const;

function App() {
  const [locale, setLocale] = useState<LocaleCode>("es");
  const [identity, setIdentity] = useState<PdaIdentity | null>(() => readPdaIdentity(window.localStorage));
  const [session, setSession] = useState<UserSession | null>(null);

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
    return (
      <div className="pda-classic-login-shell">
        <LoginScreen
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
          notice={identity.pendingApproval ? text.pending : undefined}
          secondaryActionLabel={text.reset}
          onSecondaryAction={forgetDevice}
        />
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
          <span>TPV ERP</span>
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
  onLogout
}: {
  identity: TerminalContext;
  locale: LocaleCode;
  session: UserSession;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout: () => void;
}) {
  const [warehouses, setWarehouses] = useState<WarehouseOption[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierOption[]>([]);
  const { view, openView, goHome } = usePdaNavigation();
  const t = createTranslator(locale);
  const moduleTitle = t(view === "check" ? "pda.navigation.check" : view === "lookup" ? "pda.navigation.lookup" : view === "count" ? "pda.navigation.count" : view === "history" ? "pda.navigation.history" : view === "work" ? "pda.navigation.work" : "pda.navigation.replenishment");

  usePdaModuleExitWarning(view !== "home");

  useEffect(() => {
    let cancelled = false;
    const token = session.accessToken;
    if (!token) return;
    void Promise.all([
      apiRequest<WarehouseOption[]>("/warehouses", { token }),
      apiRequest<SupplierOption[]>("/suppliers", { token })
    ]).then(([warehouseValues, supplierValues]) => {
      if (!cancelled) {
        setWarehouses(warehouseValues);
        setSuppliers(supplierValues);
      }
    }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [session.accessToken]);

  return (
    <main className="pda-app-shell">
      <header className="pda-app-header">
        <div><span>TPV ERP · PDA</span><strong>{copy[locale].title}</strong></div>
        <div className="pda-header-actions">
          <small>{copy[locale].device}: {identity.terminalCode}</small>
          <select aria-label="Idioma" value={locale} onChange={(event) => onLocaleChange(event.target.value as LocaleCode)}>
            <option value="es">ES</option><option value="en">EN</option><option value="zh">中文</option>
          </select>
          <button type="button" onClick={onLogout}>{copy[locale].logout}</button>
        </div>
      </header>
      {view === "home" && <section className="pda-home">
        <header><span>{t("pda.home.eyebrow")}</span><h1>{t("pda.home.title")}</h1><p>{t("pda.home.subtitle")}</p></header>
        <PdaHomeDashboard token={session.accessToken} locale={locale} warehouses={warehouses} onOpen={openView} />
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
      <Suspense fallback={<PdaModuleLoading locale={locale} moduleTitle={moduleTitle} />}>
      {view === "check" && <div className="pda-module-view">
        <GoodsCheckPanel
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
        <PdaWorkboard token={session.accessToken} locale={locale} warehouses={warehouses} />
      </div>}
      </Suspense>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
