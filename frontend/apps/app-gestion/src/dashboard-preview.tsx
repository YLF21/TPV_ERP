import { createRoot } from "react-dom/client";
import { AppFrame, createTranslator, type UserSession } from "@tpverp/app-common";
import { GestionDashboard, type DashboardDataSource } from "./GestionDashboard";
import { GestionShell, type GestionNavigationItem } from "./GestionShell";
import type { DashboardWidgetLayout } from "./dashboardModel";
import "./gestion.css";

const t = createTranslator("es");
const session: UserSession = {
  username: "ADMIN",
  displayName: "ADMIN",
  accessToken: "design-preview",
  permissions: ["ADMIN"]
};

const widgets: DashboardWidgetLayout[] = [
  { key: "sales.today", width: 4, height: 1 },
  { key: "sales.top-products", width: 8, height: 2 },
  { key: "promotions.active", width: 4, height: 2 },
  { key: "control.alerts", width: 4, height: 2 }
];

const dataSource: DashboardDataSource = {
  loadPreference: async () => ({
    widgets,
    availableWidgets: widgets.map((widget) => widget.key)
  }),
  savePreference: async (nextWidgets) => ({
    widgets: nextWidgets,
    availableWidgets: widgets.map((widget) => widget.key)
  }),
  loadSalesToday: async () => ({
    date: "2026-08-01",
    issuedTotal: 0,
    collectedTotal: 0,
    previousIssuedTotal: 0,
    changePercent: null
  }),
  loadTopProducts: async () => [],
  loadActivePromotions: async () => [],
  loadControlAlertsSummary: async () => ({ newCount: 0, reviewedCount: 0, recentAlerts: [] })
};

const navigation: GestionNavigationItem[] = [
  { key: "dashboard", label: t("gestion.dashboard"), onOpen: () => undefined },
  { key: "verifactu", label: "VERI*FACTU", onOpen: () => undefined },
  { key: "controlAlerts", label: "Alertas de control", onOpen: () => undefined },
  { key: "sales", label: t("gestion.sales"), children: [{ key: "sales.daily", label: "Ventas diarias", onOpen: () => undefined }] },
  { key: "stock", label: t("gestion.stock"), children: [{ key: "stock.current", label: "Stock actual", onOpen: () => undefined }] },
  { key: "warehouse", label: "Almacén", children: [{ key: "warehouse.management", label: "Gestión de almacén", onOpen: () => undefined }] },
  { key: "customers", label: t("gestion.customers"), onOpen: () => undefined },
  { key: "partners", label: "Socios", onOpen: () => undefined },
  { key: "suppliers", label: t("gestion.suppliers"), onOpen: () => undefined },
  { key: "promotions", label: "Promociones", onOpen: () => undefined },
  { key: "security", label: "Seguridad", children: [{ key: "security.users", label: "Usuarios", onOpen: () => undefined }] },
  { key: "settings", label: "Configuración", children: [{ key: "settings.general", label: "General", onOpen: () => undefined }] }
];

createRoot(document.getElementById("root")!).render(
  <AppFrame titleKey="gestion.title" locale="es" session={session} onLocaleChange={() => undefined} onLogout={() => undefined}>
    <GestionShell session={session} t={t} activeKey="dashboard" navigation={navigation}>
      <GestionDashboard
        session={session}
        t={t}
        onOpenSales={() => undefined}
        onOpenStock={() => undefined}
        onOpenPromotions={() => undefined}
        onOpenControlAlerts={() => undefined}
        dataSource={dataSource}
      />
    </GestionShell>
  </AppFrame>
);
