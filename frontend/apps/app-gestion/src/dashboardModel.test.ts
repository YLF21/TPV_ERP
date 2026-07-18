import { afterEach, describe, expect, it, vi } from "vitest";
import {
  changeDashboardWidgetHeight,
  dashboardWidgetDefaults,
  loadControlAlertsSummary,
  loadDashboardPreference,
  moveDashboardWidget,
  reorderDashboardWidgets,
  resizeDashboardWidget,
  saveDashboardPreference,
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

    expect(dashboardWidgetDefaults["control.alerts"]).toEqual({ key: "control.alerts", width: 4, height: 2 });
    await expect(loadControlAlertsSummary("token")).resolves.toEqual(response);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/api/v1/control/alerts/summary");
  });
});
