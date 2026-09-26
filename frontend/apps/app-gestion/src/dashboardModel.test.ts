import { afterEach, describe, expect, it, vi } from "vitest";
import {
  changeDashboardWidgetHeight,
  dashboardWidgetDefaults,
  dashboardPeriodRange,
  dashboardPreset,
  defaultDashboardOptions,
  loadControlAlertsSummary,
  loadDashboardPreference,
  loadSalesToday,
  loadSalesOverview,
  loadTopProducts,
  moveDashboardWidget,
  reorderDashboardWidgets,
  resizeDashboardWidget,
  saveDashboardPreference,
  validDashboardRange,
  type DashboardWidgetLayout
} from "./dashboardModel";

const widgets: DashboardWidgetLayout[] = [
  { key: "sales.today", width: 4, height: 1 },
  { key: "sales.top-products", width: 8, height: 2 },
  { key: "promotions.active", width: 4, height: 2 }
];

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("APP GESTION dashboard model", () => {
  it("reorders widgets without changing their dimensions", () => {
    expect(reorderDashboardWidgets(widgets, "promotions.active", "sales.today"))
      .toEqual([widgets[2], widgets[0], widgets[1]]);
    expect(moveDashboardWidget(widgets, "sales.today", 1))
      .toEqual([widgets[1], widgets[0], widgets[2]]);
  });

  it("uses only the approved discrete grid dimensions", () => {
    expect(resizeDashboardWidget(widgets, "sales.today", 1)[0].width).toBe(6);
    expect(resizeDashboardWidget(widgets, "sales.today", -1)[0].width).toBe(3);
    expect(changeDashboardWidgetHeight(widgets, "sales.today", -1)[0].height).toBe(1);
    expect(changeDashboardWidgetHeight(widgets, "sales.today", 1)[0].height).toBe(2);
  });

  it("loads and saves only the authenticated users server preference", async () => {
    const response = { widgets, availableWidgets: widgets.map((widget) => widget.key) };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => response
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadDashboardPreference("token")).resolves.toEqual(response);
    await expect(saveDashboardPreference(widgets, "token")).resolves.toEqual(response);

    expect(String(fetchMock.mock.calls[0][0])).toContain("/api/v1/gestion/dashboard/preference");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "GET" });
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: "PUT",
      body: JSON.stringify({ widgets })
    });
  });

  it("defines the control-alert widget and loads its real summary endpoint", async () => {
    const response = { newCount: 3, reviewedCount: 2, recentAlerts: [] };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => response
    });
    vi.stubGlobal("fetch", fetchMock);

    expect(dashboardWidgetDefaults["control.alerts"]).toEqual({ key: "control.alerts", width: 3, height: 2 });
    await expect(loadControlAlertsSummary("token")).resolves.toEqual(response);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/api/v1/control/alerts/summary");
  });

  it("scopes dashboard sales data by business date and warehouse", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({})
    });
    vi.stubGlobal("fetch", fetchMock);

    const scope = { date: "2026-08-03", warehouseId: "warehouse-2" };
    await loadSalesToday("token", scope);
    await loadTopProducts("token", scope);

    expect(String(fetchMock.mock.calls[0][0]))
      .toContain("/gestion/dashboard/data/sales-today?date=2026-08-03&warehouseId=warehouse-2");
    expect(String(fetchMock.mock.calls[1][0]))
      .toContain("/gestion/dashboard/data/top-products?date=2026-08-03&warehouseId=warehouse-2");
  });

  it("loads the bounded inclusive overview and sends display options only on explicit save", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();
    await loadSalesOverview("user-token", { from: "2026-03-28", to: "2026-03-30", warehouseId: "warehouse-2" }, controller.signal);
    await saveDashboardPreference(widgets, "user-token", { ...defaultDashboardOptions, trendDisplay: "TABLE" });
    expect(String(fetchMock.mock.calls[0][0])).toContain("sales-overview?from=2026-03-28&to=2026-03-30&warehouseId=warehouse-2");
    expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer user-token");
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ widgets, options: { ...defaultDashboardOptions, trendDisplay: "TABLE" } });
  });

  it.each([
    ["TODAY", "2026-01-01", "2026-01-01"],
    ["LAST_7_DAYS", "2026-01-03", "2025-12-28"],
    ["LAST_7_DAYS", "2026-03-30", "2026-03-24"],
    ["LAST_7_DAYS", "2026-10-26", "2026-10-20"],
    ["LAST_30_DAYS", "2024-03-01", "2024-02-01"],
    ["MONTH", "2026-09-16", "2026-09-01"]
  ] as const)("uses the store business date for %s across year, leap day and DST boundaries", (period, today, from) => {
    expect(dashboardPeriodRange(period, today)).toEqual({ from, to: today });
  });

  it.each([
    ["2026-01-01", "2027-01-01", true], ["2026-01-01", "2027-01-02", false],
    ["2026-02-30", "2026-03-03", false], ["2026-03-30", "2026-03-28", false],
    ["2026-03-28", "2026-03-30", true], ["", "2026-09-16", false]
  ])("validates exact dates and the inclusive 366-day bound", (from, to, valid) => {
    expect(validDashboardRange({ from: String(from), to: String(to) })).toBe(valid);
  });

  it("presets retain authorized alerts/promotions and cannot add unavailable sales widgets", () => {
    expect(dashboardPreset("PRODUCTS", ["promotions.active", "control.alerts"]).map((widget) => widget.key)).toEqual(["promotions.active", "control.alerts"]);
    expect(dashboardPreset("BALANCED", ["sales.today", "sales.operations", "sales.average", "sales.trend"])).toEqual([
      dashboardWidgetDefaults["sales.today"], dashboardWidgetDefaults["sales.operations"], dashboardWidgetDefaults["sales.average"], dashboardWidgetDefaults["sales.trend"]
    ]);
  });
});
