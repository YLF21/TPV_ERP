import { createRoot } from "react-dom/client";
import { AppFrame, createTranslator, type UserSession } from "@tpverp/app-common";
import "../../../packages/app-common/src/styles/tpv.css";
import { GestionDashboard, type DashboardDataSource } from "./GestionDashboard";
import { GestionShell, type GestionNavigationItem } from "./GestionShell";
import type { DashboardWidgetLayout } from "./dashboardModel";
import { gestionNavigationGroups } from "./gestionNavigation";
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
  loadControlAlertsSummary: async () => ({ newCount: 0, reviewedCount: 0, recentAlerts: [] }),
  loadWarehouses: async () => [
    { id: "preview-general", name: "Almacén general", active: true },
    { id: "preview-secondary", name: "Almacén secundario", active: true }
  ]
};

const navigation = gestionNavigationGroups.reduce<GestionNavigationItem[]>((items, group) => {
  const destinations = group.destinations.map((destination) => ({
    key: destination.key,
    label: t(destination.labelKey),
    icon: destination.icon,
    lock: destination.lock,
    onOpen: () => undefined
  } satisfies GestionNavigationItem));
  if (group.direct && destinations.length === 1) {
    items.push(destinations[0]);
    return items;
  }
  items.push({
    key: group.key,
    label: t(group.labelKey),
    icon: group.icon,
    lock: group.lock,
    children: destinations
  });
  return items;
}, []);

createRoot(document.getElementById("root")!).render(
  <AppFrame titleKey="gestion.title" locale="es" session={session} onLocaleChange={() => undefined} onLogout={() => undefined}>
    <GestionShell session={session} t={t} activeKey="salesReport.invoices" navigation={navigation}>
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
