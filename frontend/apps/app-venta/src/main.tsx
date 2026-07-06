import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  SalesReportScreen,
  HardwareSettingsScreen,
  LoginScreen,
  SaleScreen,
  SettingsScreen,
  SessionHomeScreen,
  StockScreen,
  devTerminalContext,
  hasPermission,
  type LocaleCode,
  type UserSession
} from "@tpverp/app-common";

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
    <App />
  </StrictMode>
);
