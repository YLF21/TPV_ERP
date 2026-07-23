import { Component, lazy, StrictMode, Suspense, useEffect, useState, type ErrorInfo, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { devTerminalContext } from "../../../packages/app-common/src/api/runtime";
import { hasPermission } from "../../../packages/app-common/src/auth/auth";
import { LoginScreen } from "../../../packages/app-common/src/components/LoginScreen";
import { SessionHomeScreen } from "../../../packages/app-common/src/components/SessionHomeScreen";
import { readSaleInterfaceTouchMode } from "../../../packages/app-common/src/components/saleInterfacePreferences";
import "../../../packages/app-common/src/styles/tpv.css";
import type { LocaleCode, TerminalContext, UserSession } from "../../../packages/app-common/src/types";
import { useSaleUserLocalePreference } from "./saleUserLocale";
import { evaluateCompatibility, InvalidCompatibilityContractError, loadBackendCompatibility } from "../../../packages/app-common/src/api/compatibility";
import { ApiConnectionError, ApiError } from "../../../packages/app-common/src/api/client";
import { loadTerminalIdentity } from "../../../packages/app-common/src/terminalIdentity";

type CompatibilityGate = { status: "ready" | "checking" | "blocked"; reason?: string };

type AppLoadingPhase = "application" | "compatibility";

function appLoadingCopy(language: string, phase: AppLoadingPhase) {
  if (language.startsWith("en")) {
    return phase === "compatibility"
      ? { title: "Preparing the point of sale", detail: "Checking the secure connection to the backend" }
      : { title: "Loading APP VENTA", detail: "Preparing your sales workspace" };
  }
  if (language.startsWith("zh")) {
    return phase === "compatibility"
      ? { title: "正在准备销售终端", detail: "正在检查与后端的安全连接" }
      : { title: "正在加载 APP VENTA", detail: "正在准备销售工作区" };
  }
  return phase === "compatibility"
    ? { title: "Preparando el punto de venta", detail: "Comprobando compatibilidad y conexión segura con el backend" }
    : { title: "Cargando APP VENTA", detail: "Preparando tu espacio de venta" };
}

export function AppLoadingFallback({ phase = "application", locale }: { phase?: AppLoadingPhase; locale?: LocaleCode }) {
  const language = locale ?? document.documentElement.lang;
  const copy = appLoadingCopy(language, phase);
  return (
    <main className="app-loading-screen">
      <section className="app-loading-card" role="status" aria-live="polite" aria-busy="true">
        <span className="app-loading-brand">APP VENTA</span>
        <div className="app-loading-copy">
          <h1>{copy.title}</h1>
          <p>{copy.detail}</p>
        </div>
        <progress aria-label={copy.title} />
      </section>
      <footer>TPV ERP</footer>
    </main>
  );
}

class LazyModuleErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("APP VENTA module failed to load", { name: error.name, componentStack: info.componentStack });
  }
  render() {
    if (!this.state.failed) return this.props.children;
    return <main className="settings-screen"><section className="settings-card" role="alert">
      <h1>No se pudo cargar esta parte de APP VENTA</h1>
      <p>La información técnica no se muestra por seguridad.</p>
      <button type="button" onClick={() => window.location.reload()}>Reintentar</button>
      <button type="button" onClick={() => window.history.back()}>Volver</button>
    </section></main>;
  }
}

const SalesReportScreen = lazy(() =>
  import("../../../packages/app-common/src/components/SalesReportScreen").then(({ SalesReportScreen }) => ({
    default: SalesReportScreen
  }))
);
const HardwareSettingsScreen = lazy(() =>
  import("../../../packages/app-common/src/components/HardwareSettingsScreen").then(({ HardwareSettingsScreen }) => ({
    default: HardwareSettingsScreen
  }))
);
const CustomerReceivablesScreen = lazy(() =>
  import("../../../packages/app-common/src/components/CustomerReceivablesScreen").then(({ CustomerReceivablesScreen }) => ({ default: CustomerReceivablesScreen }))
);
const SaleScreen = lazy(() =>
  import("../../../packages/app-common/src/components/SaleScreen").then(({ SaleScreen }) => ({ default: SaleScreen }))
);
const SettingsScreen = lazy(() =>
  import("../../../packages/app-common/src/components/SettingsScreen").then(({ SettingsScreen }) => ({
    default: SettingsScreen
  }))
);
const StockScreen = lazy(() =>
  import("../../../packages/app-common/src/components/StockScreen").then(({ StockScreen }) => ({ default: StockScreen }))
);
const WarehouseScreen = lazy(() =>
  import("../../../packages/app-common/src/components/WarehouseScreen").then(({ WarehouseScreen }) => ({
    default: WarehouseScreen
  }))
);

export function App() {
  const [session, setSession] = useState<UserSession | null>(null);
  const [terminalContext, setTerminalContext] = useState<TerminalContext | null | undefined>(undefined);
  const [screen, setScreen] = useState<"home" | "sale" | "stock" | "warehouse" | "salesReport" | "customerReceivables" | "settings" | "hardwareSettings">("home");
  const [receivablesCustomerId, setReceivablesCustomerId] = useState<string | undefined>();
  const [receivablesReturnScreen, setReceivablesReturnScreen] = useState<"home" | "sale" | "stock">("home");
  const { locale, applyUserLocale, changeLocale, resetLocale } = useSaleUserLocalePreference();
  const [compatibilityGate, setCompatibilityGate] = useState<CompatibilityGate>({ status: "ready" });

  useEffect(() => {
    let cancelled = false;
    async function loadIdentity() {
      const identity = await loadTerminalIdentity(
        window.tpvDesktop?.terminalIdentity,
        import.meta.env.DEV ? devTerminalContext : null
      );
      if (!cancelled) setTerminalContext(identity);
    }
    void loadIdentity();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let active = true;
    if (!session?.accessToken) {
      setCompatibilityGate({ status: "ready" });
      return () => { active = false; };
    }
    setCompatibilityGate({ status: "checking" });
    loadBackendCompatibility(session.accessToken).then(backend => {
      if (!active) return;
      const result = evaluateCompatibility(backend);
      setCompatibilityGate(result.compatible
        ? { status: "ready" }
        : { status: "blocked", reason: result.reason ?? "BACKEND_TOO_OLD" });
    }).catch(error => {
      if (!active) return;
      const reason = error instanceof ApiConnectionError ? "BACKEND_UNREACHABLE"
        : error instanceof ApiError && error.status === 404 ? "BACKEND_TOO_OLD"
          : error instanceof InvalidCompatibilityContractError ? "BACKEND_TOO_OLD"
            : "COMPATIBILITY_CHECK_FAILED";
      setCompatibilityGate({ status: "blocked", reason });
    });
    return () => { active = false; };
  }, [session?.accessToken]);

  const handleLocaleChange = (next: LocaleCode) => changeLocale(session, next);
  const handleLogin = (nextSession: UserSession) => {
    setSession(nextSession);
    applyUserLocale(nextSession);
    setScreen("home");
  };
  const handleLogout = () => {
    setSession(null);
    resetLocale();
  };

  if (terminalContext === undefined) {
    return <AppLoadingFallback locale={locale} />;
  }

  if (terminalContext === null) {
    const copy = locale === "en"
      ? {
          title: "Terminal not configured",
          detail: "APP VENTA has no valid terminal identity. Configure and approve this device before signing in.",
          retry: "Retry"
        }
      : locale === "zh"
        ? {
            title: "终端未配置",
            detail: "APP VENTA 没有有效的终端身份。请先配置并批准此设备，然后再登录。",
            retry: "重试"
          }
        : {
            title: "Terminal no configurado",
            detail: "APP VENTA no dispone de una identidad de terminal válida. Configura y aprueba este equipo antes de iniciar sesión.",
            retry: "Reintentar"
          };
    return (
      <main className="settings-screen">
        <section className="settings-card" role="alert">
          <h1>{copy.title}</h1>
          <p>{copy.detail}</p>
          <button type="button" onClick={() => window.location.reload()}>{copy.retry}</button>
        </section>
      </main>
    );
  }

  if (!session) {
    return (
      <LoginScreen
        app="venta"
        locale={locale}
        terminalContext={terminalContext}
        onLocaleChange={handleLocaleChange}
        onLogin={handleLogin}
      />
    );
  }

  if (compatibilityGate.status === "checking") {
    return <AppLoadingFallback phase="compatibility" locale={locale} />;
  }

  if (compatibilityGate.status === "blocked") {
    const unreachable = compatibilityGate.reason === "BACKEND_UNREACHABLE";
    const message = locale === "en" ? (unreachable ? "The backend is unreachable. Payments remain blocked." : "APP VENTA and the backend are not compatible. Update before taking payments.")
      : locale === "zh" ? (unreachable ? "无法连接后端。收款功能保持锁定。" : "APP VENTA 与后端不兼容。请先更新再进行收款。")
        : unreachable ? "No se puede conectar con el backend. Los cobros permanecen bloqueados." : "APP VENTA y el backend no son compatibles. Actualiza antes de realizar cobros.";
    return <main className="settings-screen"><section className="settings-card" role="alert">
      <h1>{message}</h1><p>{compatibilityGate.reason}</p>
      <button type="button" onClick={handleLogout}>{locale === "en" ? "Log out" : locale === "zh" ? "退出" : "Cerrar sesión"}</button>
    </section></main>;
  }

  const canOpenSalesReport =
    hasPermission(session, "GESTION_VENTAS")
    || hasPermission(session, "GESTION_PRODUCTO")
    || hasPermission(session, "GESTION_ALMACEN")
    || hasPermission(session, "GESTION_CUENTAS");
  const canOpenCustomerReceivables = hasPermission(session, "CUSTOMER_RECEIVABLES_READ");
  const canOpenWarehouse = hasPermission(session, "GESTION_ALMACEN");

  if (screen === "customerReceivables" && canOpenCustomerReceivables) {
    return <CustomerReceivablesScreen locale={locale} session={session} terminalContext={terminalContext} initialCustomerId={receivablesCustomerId} onBack={() => { setReceivablesCustomerId(undefined); setScreen(receivablesReturnScreen); }} onLogout={handleLogout} onLocaleChange={handleLocaleChange} />;
  }

  if (screen === "salesReport" && canOpenSalesReport) {
    return (
      <SalesReportScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={() => setScreen("home")}
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
      />
    );
  }

  if (screen === "sale") {
    return (
      <SaleScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        touchMode={readSaleInterfaceTouchMode("venta", terminalContext)}
        onBack={() => setScreen("home")}
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
        onOpenCustomerReceivables={(customerId?: string) => {
          setReceivablesCustomerId(customerId);
          setReceivablesReturnScreen("sale");
          setScreen("customerReceivables");
        }}
      />
    );
  }

  if (screen === "stock") {
    return (
      <StockScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={() => setScreen("home")}
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
        onOpenCustomerReceivables={(customerId: string) => { setReceivablesCustomerId(customerId); setReceivablesReturnScreen("stock"); setScreen("customerReceivables"); }}
      />
    );
  }

  if (screen === "warehouse" && canOpenWarehouse) {
    return (
      <WarehouseScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={() => setScreen("home")}
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
      />
    );
  }

  if (screen === "hardwareSettings") {
    return (
      <HardwareSettingsScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={() => setScreen("home")}
        onLocaleChange={handleLocaleChange}
        onLogout={handleLogout}
      />
    );
  }

  if (screen === "settings") {
    return (
      <SettingsScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={() => setScreen("home")}
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
        onOpenHardware={() => setScreen("hardwareSettings")}
        onOpenReports={() => setScreen("salesReport")}
      />
    );
  }

  return (
    <SessionHomeScreen
      app="venta"
      locale={locale}
      session={session}
      terminalContext={terminalContext}
      canOpenSalesReport={canOpenSalesReport}
      onLocaleChange={handleLocaleChange}
      onLogout={handleLogout}
      onOpenSales={() => setScreen("sale")}
      onOpenStock={() => setScreen("stock")}
      onOpenWarehouse={() => setScreen("warehouse")}
      onOpenSalesReport={() => setScreen("salesReport")}
      onOpenCustomerReceivables={() => { setReceivablesCustomerId(undefined); setReceivablesReturnScreen("home"); setScreen("customerReceivables"); }}
      onOpenSettings={() => setScreen("settings")}
    />
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <LazyModuleErrorBoundary>
      <Suspense fallback={<AppLoadingFallback />}>
        <App />
      </Suspense>
    </LazyModuleErrorBoundary>
  </StrictMode>
);
