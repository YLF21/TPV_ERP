import { lazy, StrictMode, Suspense, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  AppFrame,
  LoginScreen,
  PromotionListScreen,
  createTranslator,
  devTerminalContext,
  type LocaleCode,
  type UserSession
} from "@tpverp/app-common";
import "./gestion.css";
import { visibleGestionModules } from "./gestionAccess";
import { GestionDashboard } from "./GestionDashboard";
import { ControlAlertsScreen } from "./ControlAlertsScreen";

const StockScreen = lazy(() =>
  import("../../../packages/app-common/src/components/StockScreen").then(({ StockScreen }) => ({
    default: StockScreen
  }))
);

const SalesReportScreen = lazy(() =>
  import("../../../packages/app-common/src/components/SalesReportScreen").then(({ SalesReportScreen }) => ({
    default: SalesReportScreen
  }))
);

type GestionModule = "dashboard" | "controlAlerts" | "promotions" | "sales" | "stock";

function App() {
  const [locale, setLocale] = useState<LocaleCode>("es");
  const [session, setSession] = useState<UserSession | null>(null);
  const [module, setModule] = useState<GestionModule>("dashboard");

  if (!session) {
    return (
      <LoginScreen
        app="gestion"
        locale={locale}
        terminalContext={devTerminalContext}
        onLocaleChange={setLocale}
        onLogin={setSession}
      />
    );
  }

  if (module === "promotions") {
    return (
      <PromotionListScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setModule("dashboard")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
      />
    );
  }

  if (module === "stock") {
    return (
      <StockScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setModule("dashboard")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
      />
    );
  }

  if (module === "sales") {
    return (
      <SalesReportScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setModule("dashboard")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
      />
    );
  }

  return (
    <AppFrame titleKey="gestion.title" locale={locale} session={session} onLogout={() => setSession(null)}>
      <GestionScreen
        locale={locale}
        session={session}
        module={module}
        onOpenDashboard={() => setModule("dashboard")}
        onOpenControlAlerts={() => setModule("controlAlerts")}
        onOpenSales={() => setModule("sales")}
        onOpenPromotions={() => setModule("promotions")}
        onOpenStock={() => setModule("stock")}
      />
    </AppFrame>
  );
}

function GestionScreen({
  locale,
  session,
  module,
  onOpenDashboard,
  onOpenControlAlerts,
  onOpenSales,
  onOpenPromotions,
  onOpenStock
}: {
  locale: LocaleCode;
  session: UserSession;
  module: "dashboard" | "controlAlerts";
  onOpenDashboard: () => void;
  onOpenControlAlerts: () => void;
  onOpenSales: () => void;
  onOpenPromotions: () => void;
  onOpenStock: () => void;
}) {
  const t = createTranslator(locale);
  const modules = visibleGestionModules(session);
  const canManageProducts = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_PRODUCTO");

  const navigation = [
    { key: "dashboard", label: t("gestion.dashboard"), onOpen: onOpenDashboard },
    ...(modules.includes("gestion.controlAlerts")
      ? [{ key: "controlAlerts", label: t("gestion.controlAlerts.navigation"), onOpen: onOpenControlAlerts }]
      : []),
    ...(modules.includes("gestion.sales")
      ? [{ key: "sales", label: t("gestion.sales"), onOpen: onOpenSales }]
      : []),
    ...(modules.includes("gestion.stock")
      ? [{ key: "stock", label: t("gestion.stock"), onOpen: onOpenStock }]
      : []),
    ...(canManageProducts
      ? [{ key: "promotions", label: t("promotion.list.heading"), onOpen: onOpenPromotions }]
      : [])
  ];

  if (module === "controlAlerts" && modules.includes("gestion.controlAlerts")) {
    return <ControlAlertsScreen session={session} t={t} navigation={navigation} />;
  }

  return (
    <GestionDashboard
      session={session}
      t={t}
      navigation={navigation}
      onOpenSales={onOpenSales}
      onOpenStock={onOpenStock}
      onOpenPromotions={onOpenPromotions}
      onOpenControlAlerts={onOpenControlAlerts}
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
