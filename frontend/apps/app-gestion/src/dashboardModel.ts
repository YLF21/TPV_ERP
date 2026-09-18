import { apiRequest } from "@tpverp/app-common";

export type DashboardWidgetKey =
  | "sales.today"
  | "sales.operations"
  | "sales.average"
  | "sales.trend"
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
  options: DashboardOptions;
  businessDate?: string;
  storeTimezone?: string;
};

export type DashboardOptions = {
  defaultPeriod: "TODAY" | "LAST_7_DAYS" | "LAST_30_DAYS" | "MONTH";
  trendDisplay: "LINE" | "BAR" | "TABLE";
  productDisplay: "BAR" | "TABLE";
  density: "COMFORTABLE" | "COMPACT";
  showComparison: boolean;
};
export const defaultDashboardOptions: DashboardOptions = {
  defaultPeriod: "MONTH", trendDisplay: "LINE", productDisplay: "BAR", density: "COMFORTABLE", showComparison: true
};
export type DashboardDateRange = { from: string; to: string };
export type SalesOverviewScope = DashboardDateRange & { warehouseId?: string };
export type SalesPeriodMetrics = { netSales: number; operationCount: number; averageAmount: number };
export type DailySales = { date: string; netSales: number; operationCount: number };
export type SalesOverviewData = DashboardDateRange & {
  previousFrom: string;
  previousTo: string;
  storeTimezone: string;
  currency: string;
  current: SalesPeriodMetrics;
  previous: SalesPeriodMetrics;
  daily: DailySales[];
  previousDaily: DailySales[];
  topProducts: { productId: string; code: string; name: string; netQuantity: number }[];
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

export type DashboardScope = {
  date?: string;
  warehouseId?: string;
};

export type DashboardWarehouse = {
  id: string;
  name: string;
  active: boolean;
};

export const dashboardWidgetDefaults: Record<DashboardWidgetKey, DashboardWidgetLayout> = {
  "sales.today": { key: "sales.today", width: 4, height: 1 },
  "sales.operations": { key: "sales.operations", width: 4, height: 1 },
  "sales.average": { key: "sales.average", width: 4, height: 1 },
  "sales.trend": { key: "sales.trend", width: 12, height: 2 },
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
  token?: string,
  options?: DashboardOptions
): Promise<DashboardPreference> {
  return apiRequest<DashboardPreference>("/gestion/dashboard/preference", {
    method: "PUT",
    token,
    body: options ? { widgets, options } : { widgets }
  });
}

export function loadSalesOverview(token: string | undefined, scope: SalesOverviewScope, signal?: AbortSignal): Promise<SalesOverviewData> {
  const query = new URLSearchParams({ from: scope.from, to: scope.to });
  if (scope.warehouseId) query.set("warehouseId", scope.warehouseId);
  return apiRequest<SalesOverviewData>(`/gestion/dashboard/data/sales-overview?${query}`, { token, signal });
}

export function dashboardPeriodRange(period: DashboardOptions["defaultPeriod"], businessDate: string): DashboardDateRange {
  const date = new Date(`${businessDate}T12:00:00Z`);
  if (period === "MONTH") return { from: `${businessDate.slice(0, 7)}-01`, to: businessDate };
  date.setUTCDate(date.getUTCDate() - (period === "LAST_7_DAYS" ? 6 : period === "LAST_30_DAYS" ? 29 : 0));
  return { from: date.toISOString().slice(0, 10), to: businessDate };
}

export function validDashboardRange(range: DashboardDateRange): boolean {
  const exactDate = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value)
    && !Number.isNaN(Date.parse(`${value}T12:00:00Z`))
    && new Date(`${value}T12:00:00Z`).toISOString().slice(0, 10) === value;
  if (!exactDate(range.from) || !exactDate(range.to)) return false;
  const days = (Date.parse(`${range.to}T12:00:00Z`) - Date.parse(`${range.from}T12:00:00Z`)) / 86_400_000 + 1;
  return days >= 1 && days <= 366;
}

export type DashboardPreset = "BALANCED" | "SALES" | "PRODUCTS";
export function dashboardPreset(preset: DashboardPreset, available: DashboardWidgetKey[]): DashboardWidgetLayout[] {
  const orders: Record<DashboardPreset, DashboardWidgetKey[]> = {
    BALANCED: ["sales.today", "sales.operations", "sales.average", "sales.trend", "sales.top-products", "control.alerts", "promotions.active"],
    SALES: ["sales.today", "sales.operations", "sales.average", "sales.trend", "control.alerts", "sales.top-products", "promotions.active"],
    PRODUCTS: ["sales.today", "sales.operations", "sales.average", "sales.top-products", "promotions.active", "sales.trend", "control.alerts"]
  };
  return orders[preset].filter((key) => available.includes(key)).map((key) => ({
    ...dashboardWidgetDefaults[key],
    ...(preset === "SALES" && key === "sales.top-products" ? { width: 8 as const } : {}),
    ...(preset === "PRODUCTS" && key === "sales.top-products" ? { width: 8 as const, height: 3 as const } : {})
  }));
}

export function loadSalesToday(token?: string, scope: DashboardScope = {}): Promise<SalesTodayData> {
  return apiRequest<SalesTodayData>(dashboardDataPath("sales-today", scope), { token });
}

export function loadTopProducts(token?: string, scope: DashboardScope = {}): Promise<TopProductData[]> {
  return apiRequest<TopProductData[]>(dashboardDataPath("top-products", scope), { token });
}

export function loadActivePromotions(token?: string, scope: DashboardScope = {}): Promise<ActivePromotionData[]> {
  return apiRequest<ActivePromotionData[]>(dashboardDataPath("active-promotions", { date: scope.date }), { token });
}

export function loadDashboardWarehouses(token?: string): Promise<DashboardWarehouse[]> {
  return apiRequest<DashboardWarehouse[]>("/warehouses", { token });
}

export function loadControlAlertsSummary(token?: string): Promise<ControlAlertsSummaryData> {
  return apiRequest<ControlAlertsSummaryData>("/control/alerts/summary", { token });
}

function dashboardDataPath(resource: string, scope: DashboardScope): string {
  const query = new URLSearchParams();
  if (scope.date) query.set("date", scope.date);
  if (scope.warehouseId) query.set("warehouseId", scope.warehouseId);
  const suffix = query.toString();
  return `/gestion/dashboard/data/${resource}${suffix ? `?${suffix}` : ""}`;
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
