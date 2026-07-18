import { apiRequest } from "@tpverp/app-common";

export type DashboardWidgetKey =
  | "sales.today"
  | "sales.top-products"
  | "promotions.active"
  | "control.alerts";

export type DashboardWidgetLayout = {
  key: DashboardWidgetKey;
  width: 3 | 4 | 6 | 8 | 12;
  height: 1 | 2 | 3;
};

export type DashboardPreference = {
  widgets: DashboardWidgetLayout[];
  availableWidgets: DashboardWidgetKey[];
};

export type SalesTodayData = {
  date: string;
  issuedTotal: number;
  collectedTotal: number;
  previousIssuedTotal: number;
  changePercent: number | null;
};

export type TopProductData = {
  productId: string;
  name: string;
  soldQuantity: number;
  netAmount: number;
};

export type ActivePromotionData = {
  id: string;
  name: string;
  type: string;
  endDate: string | null;
};

export type ControlAlertSummaryItem = {
  id: string;
  type: string;
  status: string;
  occurredAt: string;
  documentNumber?: string | null;
  userName?: string | null;
};

export type ControlAlertsSummaryData = {
  newCount: number;
  reviewedCount: number;
  recentAlerts: ControlAlertSummaryItem[];
};

export const dashboardWidgetDefaults: Record<DashboardWidgetKey, DashboardWidgetLayout> = {
  "sales.today": { key: "sales.today", width: 4, height: 1 },
  "sales.top-products": { key: "sales.top-products", width: 8, height: 2 },
  "promotions.active": { key: "promotions.active", width: 4, height: 2 },
  "control.alerts": { key: "control.alerts", width: 4, height: 2 }
};

export const dashboardWidths = [3, 4, 6, 8, 12] as const;

export function loadDashboardPreference(token?: string): Promise<DashboardPreference> {
  return apiRequest<DashboardPreference>("/gestion/dashboard/preference", { token });
}

export function saveDashboardPreference(
  widgets: DashboardWidgetLayout[],
  token?: string
): Promise<DashboardPreference> {
  return apiRequest<DashboardPreference>("/gestion/dashboard/preference", {
    method: "PUT",
    token,
    body: { widgets }
  });
}

export function loadSalesToday(token?: string): Promise<SalesTodayData> {
  return apiRequest<SalesTodayData>("/gestion/dashboard/data/sales-today", { token });
}

export function loadTopProducts(token?: string): Promise<TopProductData[]> {
  return apiRequest<TopProductData[]>("/gestion/dashboard/data/top-products", { token });
}

export function loadActivePromotions(token?: string): Promise<ActivePromotionData[]> {
  return apiRequest<ActivePromotionData[]>("/gestion/dashboard/data/active-promotions", { token });
}

export function loadControlAlertsSummary(token?: string): Promise<ControlAlertsSummaryData> {
  return apiRequest<ControlAlertsSummaryData>("/control/alerts/summary", { token });
}

export function reorderDashboardWidgets(
  widgets: DashboardWidgetLayout[],
  draggedKey: DashboardWidgetKey,
  targetKey: DashboardWidgetKey
): DashboardWidgetLayout[] {
  if (draggedKey === targetKey) return widgets;
  const from = widgets.findIndex((widget) => widget.key === draggedKey);
  const to = widgets.findIndex((widget) => widget.key === targetKey);
  if (from < 0 || to < 0) return widgets;
  const next = [...widgets];
  const [dragged] = next.splice(from, 1);
  next.splice(to, 0, dragged);
  return next;
}

export function moveDashboardWidget(
  widgets: DashboardWidgetLayout[],
  key: DashboardWidgetKey,
  direction: -1 | 1
): DashboardWidgetLayout[] {
  const from = widgets.findIndex((widget) => widget.key === key);
  if (from < 0) return widgets;
  const to = Math.max(0, Math.min(widgets.length - 1, from + direction));
  if (to === from) return widgets;
  const next = [...widgets];
  const [widget] = next.splice(from, 1);
  next.splice(to, 0, widget);
  return next;
}

export function resizeDashboardWidget(
  widgets: DashboardWidgetLayout[],
  key: DashboardWidgetKey,
  direction: -1 | 1
): DashboardWidgetLayout[] {
  return widgets.map((widget) => {
    if (widget.key !== key) return widget;
    const current = dashboardWidths.indexOf(widget.width);
    const next = Math.max(0, Math.min(dashboardWidths.length - 1, current + direction));
    return { ...widget, width: dashboardWidths[next] };
  });
}

export function changeDashboardWidgetHeight(
  widgets: DashboardWidgetLayout[],
  key: DashboardWidgetKey,
  direction: -1 | 1
): DashboardWidgetLayout[] {
  return widgets.map((widget) => widget.key === key
    ? { ...widget, height: Math.max(1, Math.min(3, widget.height + direction)) as 1 | 2 | 3 }
    : widget);
}
