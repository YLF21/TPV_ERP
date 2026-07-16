import { lazy, StrictMode, Suspense, useState } from "react";
import { createRoot } from "react-dom/client";
import { devTerminalContext } from "../../../packages/app-common/src/api/runtime";
import { hasPermission } from "../../../packages/app-common/src/auth/auth";
import { LoginScreen } from "../../../packages/app-common/src/components/LoginScreen";
import { SessionHomeScreen } from "../../../packages/app-common/src/components/SessionHomeScreen";
import { readSaleInterfaceTouchMode } from "../../../packages/app-common/src/components/saleInterfacePreferences";
import "../../../packages/app-common/src/styles/tpv.css";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";
import { useSaleUserLocalePreference } from "./saleUserLocale";

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

export function App() {
  const [session, setSession] = useState<UserSession | null>(null);
  const [screen, setScreen] = useState<"home" | "sale" | "stock" | "salesReport" | "customerReceivables" | "settings" | "hardwareSettings">("home");
  const [receivablesCustomerId, setReceivablesCustomerId] = useState<string | undefined>();
  const { locale, applyUserLocale, changeLocale, resetLocale } = useSaleUserLocalePreference();

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

  if (!session) {
    return (
      <LoginScreen
        app="venta"
        locale={locale}
        terminalContext={devTerminalContext}
        onLocaleChange={handleLocaleChange}
        onLogin={handleLogin}
      />
    );
  }

  const canOpenSalesReport =
    hasPermission(session, "GESTION_VENTAS") || hasPermission(session, "GESTION_CUENTAS");
  const canOpenCustomerReceivables = hasPermission(session, "CUSTOMER_RECEIVABLES_READ");

  if (screen === "customerReceivables" && canOpenCustomerReceivables) {
    return <CustomerReceivablesScreen locale={locale} session={session} terminalContext={devTerminalContext} initialCustomerId={receivablesCustomerId} onBack={() => { setReceivablesCustomerId(undefined); setScreen("home"); }} onLogout={handleLogout} onLocaleChange={handleLocaleChange} />;
  }

  if (screen === "salesReport" && canOpenSalesReport) {
    return (
      <SalesReportScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
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
        terminalContext={devTerminalContext}
        touchMode={readSaleInterfaceTouchMode("venta", devTerminalContext)}
        onBack={() => setScreen("home")}
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
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
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
        onOpenCustomerReceivables={(customerId: string) => { setReceivablesCustomerId(customerId); setScreen("customerReceivables"); }}
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
        terminalContext={devTerminalContext}
        onBack={() => setScreen("home")}
        onLogout={handleLogout}
        onLocaleChange={handleLocaleChange}
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
      onLocaleChange={handleLocaleChange}
      onLogout={handleLogout}
      onOpenSales={() => setScreen("sale")}
      onOpenStock={() => setScreen("stock")}
      onOpenSalesReport={() => setScreen("salesReport")}
      onOpenCustomerReceivables={() => { setReceivablesCustomerId(undefined); setScreen("customerReceivables"); }}
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
