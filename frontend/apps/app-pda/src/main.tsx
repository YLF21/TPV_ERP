import { StrictMode, useEffect, useState, type FormEvent } from "react";
import { createRoot } from "react-dom/client";
import {
  ApiError,
  LoginScreen,
  apiRequest,
  createTranslator,
  type LocaleCode,
  type TerminalContext,
  type UserSession
} from "@tpverp/app-common";
import { GoodsCheckPanel } from "../../../packages/app-common/src/components/GoodsCheckPanel";
import { PdaProductLookup } from "./PdaProductLookup";
import { PdaReplenishment } from "./PdaReplenishment";
import { clearPdaIdentity, readPdaIdentity, writePdaIdentity, type PdaIdentity } from "./pdaIdentity";
import "./pda.css";

type PdaRegistrationResult = {
  terminalId: string;
  terminalCode: string;
  storeName: string;
  terminalCredential: string;
  status: "PENDING";
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

  if (!identity) {
    return <PdaEnrollment locale={locale} onLocaleChange={setLocale} onRegistered={setIdentity} />;
  }

  if (!session) {
    const text = copy[locale];
    return (
      <div className="pda-classic-login-shell">
        <LoginScreen
          app="gestion"
          locale={locale}
          terminalContext={identity}
          onLocaleChange={setLocale}
          onLogin={(value) => {
            const approved = { ...identity, pendingApproval: false };
            writePdaIdentity(window.localStorage, approved);
            setIdentity(approved);
            setSession(value);
          }}
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
  const [name, setName] = useState("");
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

  return (
    <main className="pda-enrollment">
      <section className="pda-enrollment-card">
        <header>
          <span>TPV ERP</span>
          <strong>{text.setupTitle}</strong>
          <p>{text.setupHelp}</p>
        </header>
        <label className="pda-language">
          <span>Idioma / Language / 语言</span>
          <select value={locale} onChange={(event) => onLocaleChange(event.target.value as LocaleCode)}>
            <option value="es">Español</option>
            <option value="en">English</option>
            <option value="zh">中文</option>
          </select>
        </label>
        <form onSubmit={submit}>
          <label>
            <span>{text.deviceName}</span>
            <input autoFocus maxLength={80} value={name} placeholder={text.devicePlaceholder} disabled={busy} onChange={(event) => setName(event.target.value)} />
          </label>
          {error && <strong className="pda-error" role="alert">{error}</strong>}
          <button type="submit" disabled={busy || !name.trim()}>{busy ? "…" : text.request}</button>
        </form>
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
  const [view, setView] = useState<"home" | "check" | "lookup" | "replenishment">("home");
  const t = createTranslator(locale);

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
        <nav className="pda-home-menu" aria-label={t("pda.navigation")}>
          <button type="button" onClick={() => setView("lookup")}><b aria-hidden="true">⌕</b><span>{t("pda.navigation.lookup")}</span><small>{t("pda.home.lookupHelp")}</small></button>
          <button type="button" onClick={() => setView("check")}><b aria-hidden="true">✓</b><span>{t("pda.navigation.check")}</span><small>{t("pda.home.checkHelp")}</small></button>
          <button type="button" onClick={() => setView("replenishment")}><b aria-hidden="true">⇄</b><span>{t("pda.navigation.replenishment")}</span><small>{t("pda.home.replenishmentHelp")}</small></button>
        </nav>
      </section>}
      {view !== "home" && <nav className="pda-module-toolbar" aria-label={t("pda.navigation")}>
        <button type="button" onClick={() => setView("home")}>← {t("pda.navigation.home")}</button>
        <strong>{t(view === "check" ? "pda.navigation.check" : view === "lookup" ? "pda.navigation.lookup" : "pda.navigation.replenishment")}</strong>
      </nav>}
      <div className="pda-module-view" hidden={view !== "check"}>
        <GoodsCheckPanel
          locale={locale}
          token={session.accessToken}
          t={t}
          warehouses={warehouses}
          suppliers={suppliers}
          separateWorkflow
        />
      </div>
      <div className="pda-module-view" hidden={view !== "lookup"}>
        <PdaProductLookup
          token={session.accessToken}
          locale={locale}
          warehouses={warehouses}
          storeName={identity.storeName}
          t={t}
        />
      </div>
      <div className="pda-module-view" hidden={view !== "replenishment"}>
        <PdaReplenishment token={session.accessToken} locale={locale} warehouses={warehouses} t={t} />
      </div>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
