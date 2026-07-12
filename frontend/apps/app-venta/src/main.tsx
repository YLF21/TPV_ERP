import { lazy, StrictMode, Suspense, useState } from "react";
import { createRoot } from "react-dom/client";
import { devTerminalContext } from "../../../packages/app-common/src/api/runtime";
import { hasPermission } from "../../../packages/app-common/src/auth/auth";
import { LoginScreen } from "../../../packages/app-common/src/components/LoginScreen";
import { SessionHomeScreen } from "../../../packages/app-common/src/components/SessionHomeScreen";
import "../../../packages/app-common/src/styles/tpv.css";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";

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

function App() {
  const [locale, setLocale] = useState<LocaleCode>("es");
  const [session, setSession] = useState<UserSession | null>(null);
  const [screen, setScreen] = useState<"home" | "sale" | "stock" | "salesReport" | "settings" | "hardwareSettings">("home");

  if (!session) {
    return (
      <LoginScreen
        app="venta"
        locale={locale}
        terminalContext={devTerminalContext}
        onLocaleChange={setLocale}
        onLogin={(nextSession) => {
          setSession(nextSession);
          setScreen("home");
        }}
      />
    );
  }

  const canOpenSalesReport =
    hasPermission(session, "GESTION_VENTAS") || hasPermission(session, "GESTION_CUENTAS");

  if (screen === "salesReport" && canOpenSalesReport) {
    return (
      <SalesReportScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setScreen("home")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
      />
    );
  }

  if (screen === "sale") {
    return (
      <SaleScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setScreen("home")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
      />
    );
  }

  if (screen === "stock") {
    return (
      <StockScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setScreen("home")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
      />
    );
  }

  if (screen === "hardwareSettings") {
    return (
      <HardwareSettingsScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setScreen("home")}
        onLocaleChange={setLocale}
        onLogout={() => setSession(null)}
      />
    );
  }

  if (screen === "settings") {
    return (
      <SettingsScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setScreen("home")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
        onOpenHardware={() => setScreen("hardwareSettings")}
      />
    );
  }

  return (
    <SessionHomeScreen
      app="venta"
      locale={locale}
      session={session}
      terminalContext={devTerminalContext}
      canOpenSalesReport={canOpenSalesReport}
      onLocaleChange={setLocale}
      onLogout={() => setSession(null)}
      onOpenSales={() => setScreen("sale")}
      onOpenStock={() => setScreen("stock")}
      onOpenSalesReport={() => setScreen("salesReport")}
      onOpenSettings={() => setScreen("settings")}
    />
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Suspense fallback={null}>
      <App />
    </Suspense>
  </StrictMode>
);
