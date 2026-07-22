import { lazy, StrictMode, Suspense, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  AppFrame,
  LoginScreen,
  PromotionListScreen,
  createTranslator,
  devTerminalContext,
  userCanManageWarehouses,
  visibleSalesReports,
  visibleStockViewsForSession,
  visibleWarehouseSectionsForSession,
  type LocaleCode,
  type PartyDirectoryKind,
  type StockViewKey,
  type TerminalContext,
  type UserSession,
  type WarehouseSection
} from "@tpverp/app-common";
import "./gestion.css";
import { visibleGestionModules } from "./gestionAccess";
import { GestionDashboard } from "./GestionDashboard";
import { ControlAlertsScreen } from "./ControlAlertsScreen";
import { ServerTerminalSetupScreen } from "./ServerTerminalSetupScreen";
import { resolveGestionTerminalIdentity } from "./terminalIdentity";
import { GestionShell, type GestionNavigationItem } from "./GestionShell";
import { SecurityAdministrationScreen } from "./SecurityAdministrationScreen";

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

const WarehouseScreen = lazy(() =>
  import("../../../packages/app-common/src/components/WarehouseScreen").then(({ WarehouseScreen }) => ({
    default: WarehouseScreen
  }))
);

const WarehouseManagementScreen = lazy(() =>
  import("./WarehouseManagementScreen").then(({ WarehouseManagementScreen }) => ({
    default: WarehouseManagementScreen
  }))
);

const VerifactuManagementScreen = lazy(() =>
  import("./VerifactuManagementScreen").then(({ VerifactuManagementScreen }) => ({
    default: VerifactuManagementScreen
  }))
);

type GestionModule = "dashboard" | "verifactu" | "controlAlerts" | "promotions" | "sales" | "stock" | "users" | "roles";
type StockSelection = {
  key: string;
  view?: StockViewKey;
  partyDirectory?: PartyDirectoryKind;
  settingsMode?: "configuration" | "permissions";
  warehouseSection?: WarehouseSection;
  warehouseManagement?: boolean;
};

function App() {
  const [locale, setLocale] = useState<LocaleCode>("es");
  const [session, setSession] = useState<UserSession | null>(null);
  const [module, setModule] = useState<GestionModule>("dashboard");
  const [salesReport, setSalesReport] = useState("salesReport.dailySales");
  const [stockSelection, setStockSelection] = useState<StockSelection>({ key: "stock.current", view: "stock.current" });
  const [terminalContext, setTerminalContext] = useState<TerminalContext | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    async function loadIdentity() {
      const bridge = window.tpvDesktop?.terminalIdentity;
      if (!bridge) {
        if (!cancelled) setTerminalContext(import.meta.env.DEV ? devTerminalContext : null);
        return;
      }
      const result = await bridge.load();
      if (!cancelled) setTerminalContext(resolveGestionTerminalIdentity(result));
    }
    void loadIdentity();
    return () => { cancelled = true; };
  }, []);

  if (terminalContext === undefined) {
    return null;
  }

  if (terminalContext === null) {
    return <ServerTerminalSetupScreen locale={locale} onProvisioned={setTerminalContext} />;
  }

  if (!session) {
    return (
      <LoginScreen
        app="gestion"
        locale={locale}
        terminalContext={terminalContext}
        onLocaleChange={setLocale}
        onLogin={setSession}
      />
    );
  }

  return (
    <AppFrame titleKey="gestion.title" locale={locale} session={session} onLogout={() => setSession(null)}>
      <GestionScreen
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        module={module}
        salesReport={salesReport}
        stockSelection={stockSelection}
        onOpenDashboard={() => setModule("dashboard")}
        onOpenVerifactu={() => setModule("verifactu")}
        onOpenControlAlerts={() => setModule("controlAlerts")}
        onOpenSales={(report) => {
          setSalesReport(report);
          setModule("sales");
        }}
        onOpenPromotions={() => setModule("promotions")}
        onOpenUsers={() => setModule("users")}
        onOpenRoles={() => setModule("roles")}
        onOpenStock={(selection) => {
          setStockSelection(selection);
          setModule("stock");
        }}
        onLocaleChange={setLocale}
        onLogout={() => setSession(null)}
      />
    </AppFrame>
  );
}

function GestionScreen({
  locale,
  session,
  terminalContext,
  module,
  salesReport,
  stockSelection,
  onOpenDashboard,
  onOpenVerifactu,
  onOpenControlAlerts,
  onOpenSales,
  onOpenPromotions,
  onOpenUsers,
  onOpenRoles,
  onOpenStock,
  onLocaleChange,
  onLogout
}: {
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  module: GestionModule;
  salesReport: string;
  stockSelection: StockSelection;
  onOpenDashboard: () => void;
  onOpenVerifactu: () => void;
  onOpenControlAlerts: () => void;
  onOpenSales: (report: string) => void;
  onOpenPromotions: () => void;
  onOpenUsers: () => void;
  onOpenRoles: () => void;
  onOpenStock: (selection: StockSelection) => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout: () => void;
}) {
  const t = createTranslator(locale);
  const modules = visibleGestionModules(session);
  const verifactuAllowed = modules.includes("gestion.verifactu");
  const effectiveModule = module === "verifactu" && !verifactuAllowed ? "dashboard" : module;
  const canManageProducts = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_PRODUCTO");
  const reports = visibleSalesReports(session).all;
  const stockViews = visibleStockViewsForSession(session);
  const warehouseSections = visibleWarehouseSectionsForSession(session);
  const canReadCustomers = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_CLIENTE_PROVEEDOR")
    || session.permissions.includes("CUSTOMERS_READ");
  const canReadSuppliers = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_CLIENTE_PROVEEDOR")
    || session.permissions.includes("GESTION_ALMACEN")
    || session.permissions.includes("SUPPLIERS_READ");
  const stockChildren: GestionNavigationItem[] = [
    ...stockViews.map((view) => ({
      key: view,
      label: t(view),
      onOpen: () => onOpenStock({ key: view, view })
    })),
    ...(userCanManageWarehouses(session) ? [{
      key: "stock.settings.configuration",
      label: t("stock.settings.configuration"),
      onOpen: () => onOpenStock({ key: "stock.settings.configuration", settingsMode: "configuration" as const })
    }] : []),
    ...(session.permissions.includes("ADMIN") ? [{
      key: "stock.settings.permissions",
      label: t("stock.settings.permissions"),
      onOpen: () => onOpenStock({ key: "stock.settings.permissions", settingsMode: "permissions" as const })
    }] : [])
  ];
  const warehouseChildren: GestionNavigationItem[] = [
    ...(userCanManageWarehouses(session) ? [{
      key: "stock.warehouse.management",
      label: t("warehouse.management.navigation"),
      onOpen: () => onOpenStock({
        key: "stock.warehouse.management",
        warehouseManagement: true
      })
    }] : []),
    ...warehouseSections.map((warehouseSection) => ({
      key: `stock.warehouse.${warehouseSection}`,
      label: t(warehouseSection === "input"
        ? "stock.nav.inputWarehouse"
        : warehouseSection === "output"
          ? "stock.nav.outputWarehouse"
          : "warehouseScreen.goodsCheck"),
      onOpen: () => onOpenStock({
        key: `stock.warehouse.${warehouseSection}`,
        warehouseSection
      })
    }))
  ];
  const partyItems: GestionNavigationItem[] = [
    ...(canReadCustomers ? (["customers", "members"] as PartyDirectoryKind[]).map((partyDirectory) => ({
      key: `stock.party.${partyDirectory}`,
      label: t(`party.${partyDirectory}.title`),
      onOpen: () => onOpenStock({ key: `stock.party.${partyDirectory}`, partyDirectory })
    })) : []),
    ...(canReadSuppliers ? [{
      key: "stock.party.suppliers",
      label: t("party.suppliers.title"),
      onOpen: () => onOpenStock({ key: "stock.party.suppliers", partyDirectory: "suppliers" })
    }] : [])
  ];
  const stockContentItems = [...stockChildren, ...warehouseChildren, ...partyItems];
  const securityChildren: GestionNavigationItem[] = [
    ...(modules.includes("gestion.users")
      ? [{ key: "users", label: t("gestion.users.navigation"), onOpen: onOpenUsers }]
      : []),
    ...(modules.includes("gestion.roles")
      ? [{ key: "roles", label: t("gestion.roles.navigation"), onOpen: onOpenRoles }]
      : [])
  ];

  const navigation: GestionNavigationItem[] = [
    { key: "dashboard", label: t("gestion.dashboard"), onOpen: onOpenDashboard },
    ...(verifactuAllowed
      ? [{ key: "verifactu", label: t("verifactu.management.navigation"), onOpen: onOpenVerifactu }]
      : []),
    ...(modules.includes("gestion.controlAlerts")
      ? [{ key: "controlAlerts", label: t("gestion.controlAlerts.navigation"), onOpen: onOpenControlAlerts }]
      : []),
    ...(modules.includes("gestion.sales")
      ? [{
          key: "sales",
          label: t("gestion.sales"),
          children: reports.map((report) => ({ key: report, label: t(report), onOpen: () => onOpenSales(report) }))
        }]
      : []),
    ...(modules.includes("gestion.stock")
      && stockChildren.length > 0
      ? [{ key: "stock", label: t("gestion.stock"), children: stockChildren }]
      : []),
    ...(warehouseChildren.length > 0
      ? [{ key: "warehouse", label: t("stock.warehouse"), children: warehouseChildren }]
      : []),
    ...partyItems,
    ...(canManageProducts
      ? [{ key: "promotions", label: t("promotion.list.heading"), onOpen: onOpenPromotions }]
      : []),
    ...(securityChildren.length > 0
      ? [{ key: "security", label: t("gestion.security.navigation"), children: securityChildren }]
      : [])
  ];

  const activeKey = effectiveModule === "sales"
    ? salesReport
    : effectiveModule === "stock"
      ? stockSelection.key
      : effectiveModule;

  let content;
  if (effectiveModule === "verifactu" && verifactuAllowed) {
    content = <VerifactuManagementScreen locale={locale} session={session} t={t} />;
  } else if (effectiveModule === "controlAlerts" && modules.includes("gestion.controlAlerts")) {
    content = <ControlAlertsScreen session={session} t={t} />;
  } else if (effectiveModule === "sales" && reports.includes(salesReport)) {
    content = (
      <SalesReportScreen
        key={salesReport}
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialReport={salesReport}
      />
    );
  } else if (effectiveModule === "stock" && stockContentItems.some((item) => item.key === stockSelection.key)) {
    content = stockSelection.warehouseManagement ? (
      <WarehouseManagementScreen session={session} t={t} />
    ) : stockSelection.warehouseSection ? (
      <WarehouseScreen
        key={stockSelection.key}
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialSection={stockSelection.warehouseSection}
      />
    ) : (
      <StockScreen
        key={stockSelection.key}
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialView={stockSelection.view}
        initialPartyDirectory={stockSelection.partyDirectory}
        initialSettingsMode={stockSelection.settingsMode}
      />
    );
  } else if (effectiveModule === "promotions" && canManageProducts) {
    content = (
      <PromotionListScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
      />
    );
  } else if (effectiveModule === "users" && modules.includes("gestion.users")) {
    content = <SecurityAdministrationScreen mode="users" session={session} t={t} />;
  } else if (effectiveModule === "roles" && modules.includes("gestion.roles")) {
    content = <SecurityAdministrationScreen mode="roles" session={session} t={t} />;
  } else {
    content = (
      <GestionDashboard
        session={session}
        t={t}
        onOpenSales={() => onOpenSales(reports[0] ?? "salesReport.dailySales")}
        onOpenStock={() => onOpenStock({ key: stockViews[0] ?? "stock.current", view: stockViews[0] ?? "stock.current" })}
        onOpenPromotions={onOpenPromotions}
        onOpenControlAlerts={onOpenControlAlerts}
      />
    );
  }

  return (
    <GestionShell session={session} t={t} activeKey={activeKey} navigation={navigation}>
      <section className="gestion-module-stage">
        <Suspense fallback={<div className="gestion-module-loading">{t("common.loading")}</div>}>
          {content}
        </Suspense>
      </section>
    </GestionShell>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Suspense fallback={null}>
      <App />
    </Suspense>
  </StrictMode>
);
