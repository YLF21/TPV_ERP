// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { GestionDashboard, type DashboardDataSource } from "./GestionDashboard";
import { defaultDashboardOptions, type DashboardPreference, type SalesOverviewData } from "./dashboardModel";

const t = (key: string) => key;
const session: UserSession = { username: "manager", displayName: "Manager", accessToken: "token", permissions: ["APP_GESTION_ACCESS", "GESTION_VENTAS"] };
const preference: DashboardPreference = {
  widgets: [{ key: "sales.today", width: 4, height: 1 }, { key: "sales.trend", width: 8, height: 2 }, { key: "sales.top-products", width: 8, height: 2 }],
  availableWidgets: ["sales.today", "sales.operations", "sales.average", "sales.trend", "sales.top-products", "control.alerts", "promotions.active"],
  options: { ...defaultDashboardOptions }, businessDate: "2026-09-16", storeTimezone: "Atlantic/Canary"
};
const overview: SalesOverviewData = {
  from: "2026-09-01", to: "2026-09-16", previousFrom: "2026-08-16", previousTo: "2026-08-31", storeTimezone: "Atlantic/Canary", currency: "EUR",
  current: { netSales: 120, operationCount: 3, averageAmount: 40 }, previous: { netSales: 100, operationCount: 4, averageAmount: 25 },
  daily: [{ date: "2026-09-01", netSales: 120, operationCount: 3 }], previousDaily: [{ date: "2026-08-16", netSales: 100, operationCount: 4 }],
  topProducts: [{ productId: "p1", code: "001", name: "Café", netQuantity: 12.5 }]
};
function source(overrides: Partial<DashboardDataSource> = {}): DashboardDataSource {
  return {
    loadPreference: vi.fn().mockResolvedValue(preference),
    savePreference: vi.fn(async (widgets, _token, options) => ({ ...preference, widgets, options: options ?? defaultDashboardOptions })),
    loadSalesOverview: vi.fn().mockResolvedValue(overview),
    loadSalesToday: vi.fn(), loadTopProducts: vi.fn(), loadWarehouses: vi.fn().mockResolvedValue([]),
    loadControlAlertsSummary: vi.fn().mockResolvedValue({ newCount: 0, reviewedCount: 0, recentAlerts: [] }),
    loadActivePromotions: vi.fn().mockResolvedValue([]), ...overrides
  };
}
function props(dataSource: DashboardDataSource, user = session) {
  return { session: user, dataSource, t, onOpenSales: vi.fn(), onOpenStock: vi.fn(), onOpenPromotions: vi.fn(), onOpenControlAlerts: vi.fn() };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}
beforeEach(() => vi.setSystemTime(new Date("2026-09-16T12:00:00Z")));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe("dashboard explicit configuration and isolated requests", () => {
  it("removes applied period and warehouse tags independently without persisting widget configuration", async () => {
    const dataSource = source({ loadWarehouses: vi.fn().mockResolvedValue([{ id: "reserve", name: "RESERVA", active: true }]) });
    render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("Café");
    expect(screen.queryByRole("group", { name: "filters.applied" })).toBeNull();
    fireEvent.change(screen.getByLabelText("gestion.dashboard.from"), { target: { value: "2026-09-03" } });
    expect(screen.queryByRole("group", { name: "filters.applied" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.apply" }));
    fireEvent.change(screen.getByRole("combobox", { name: "gestion.dashboard.warehouse" }), { target: { value: "reserve" } });
    await waitFor(() => expect(dataSource.loadSalesOverview).toHaveBeenLastCalledWith("token",
      { from: "2026-09-03", to: "2026-09-16", warehouseId: "reserve" }, expect.any(AbortSignal)));
    expect(screen.getByRole("group", { name: "filters.applied" }).textContent).toContain("RESERVA");
    fireEvent.click(screen.getByRole("button", { name: "filters.remove gestion.dashboard.period" }));
    await waitFor(() => expect(dataSource.loadSalesOverview).toHaveBeenLastCalledWith("token",
      { from: "2026-09-01", to: "2026-09-16", warehouseId: "reserve" }, expect.any(AbortSignal)));
    expect(screen.queryByRole("button", { name: "filters.remove gestion.dashboard.period" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "filters.remove gestion.dashboard.warehouse" }));
    await waitFor(() => expect(dataSource.loadSalesOverview).toHaveBeenLastCalledWith("token",
      { from: "2026-09-01", to: "2026-09-16", warehouseId: undefined }, expect.any(AbortSignal)));
    expect(screen.queryByRole("group", { name: "filters.applied" })).toBeNull();
    expect(dataSource.savePreference).not.toHaveBeenCalled();
  });

  it("renders stored order and dimensions in normal view without adding new widgets", async () => {
    const dataSource = source();
    const view = render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("Café");
    const widgets = [...view.container.querySelectorAll("[data-widget-key]")];
    expect(widgets.map((widget) => widget.getAttribute("data-widget-key"))).toEqual(preference.widgets.map((widget) => widget.key));
    expect((widgets[1] as HTMLElement).style.getPropertyValue("--widget-width")).toBe("8");
    expect((widgets[1] as HTMLElement).style.getPropertyValue("--widget-height")).toBe("2");
    expect(dataSource.loadSalesOverview).toHaveBeenCalledWith("token", { from: "2026-09-01", to: "2026-09-16", warehouseId: undefined }, expect.any(AbortSignal));
    expect(dataSource.loadSalesToday).not.toHaveBeenCalled();
  });

  it("keeps edits as a draft and Cancel restores the saved view without a request", async () => {
    const dataSource = source();
    const view = render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("Café");
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.customize" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.moveUp" }));
    fireEvent.click(screen.getByRole("button", { name: "1/2" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.display.TABLE" }));
    expect(dataSource.savePreference).not.toHaveBeenCalled();
    expect(view.container.querySelector("[data-widget-key]")?.getAttribute("data-widget-key")).toBe("sales.trend");
    fireEvent.click(screen.getByRole("button", { name: "common.cancel" }));
    expect(view.container.querySelector("[data-widget-key]")?.getAttribute("data-widget-key")).toBe("sales.today");
    expect(view.container.querySelector("[data-widget-key='sales.trend']")?.getAttribute("style")).toContain("--widget-width: 8");
    expect(view.container.querySelector("svg.gd-chart")).not.toBeNull();
    expect(dataSource.savePreference).not.toHaveBeenCalled();
  });

  it("saves layout and options once, blocks edits during save, and applies the returned normal view", async () => {
    const pending = deferred<DashboardPreference>();
    const savePreference = vi.fn().mockReturnValue(pending.promise);
    const dataSource = source({ savePreference });
    const view = render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("Café");
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.customize" }));
    fireEvent.click(screen.getByRole("button", { name: "1/2" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.display.BAR" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.saveConfiguration" }));
    expect((screen.getByRole("button", { name: "common.cancel" }) as HTMLButtonElement).disabled).toBe(true);
    expect(savePreference).toHaveBeenCalledTimes(1);
    expect(savePreference.mock.calls[0][2]).toMatchObject({ trendDisplay: "BAR" });
    await act(async () => pending.resolve({ ...preference, widgets: savePreference.mock.calls[0][0], options: savePreference.mock.calls[0][2] }));
    expect(screen.queryByRole("complementary")).toBeNull();
    expect(view.container.querySelector("[data-widget-key='sales.trend']")?.getAttribute("style")).toContain("--widget-width: 6");
    expect(view.container.querySelector(".gd-chart-bar.current")).not.toBeNull();
  });

  it("keeps unsaved edits after a failed save and retries only when explicitly requested", async () => {
    const savePreference = vi.fn().mockRejectedValueOnce(new Error("offline")).mockImplementation(async (widgets, _token, options) => ({ ...preference, widgets, options }));
    render(<GestionDashboard {...props(source({ savePreference }))} />);
    await screen.findByText("Café");
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.customize" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.display.TABLE" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.saveConfiguration" }));
    await screen.findByText("gestion.dashboard.saveError");
    expect(screen.getByRole("button", { name: "gestion.dashboard.display.TABLE" }).getAttribute("aria-pressed")).toBe("true");
    expect(savePreference).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.saveConfiguration" }));
    await screen.findByText("gestion.dashboard.saved");
    expect(savePreference).toHaveBeenCalledTimes(2);
  });

  it("blocks customization after preference load failure instead of saving defaults", async () => {
    const loadPreference = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(preference);
    const dataSource = source({ loadPreference });
    render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("gestion.dashboard.loadError");
    expect((screen.getByRole("button", { name: "gestion.dashboard.customize" }) as HTMLButtonElement).disabled).toBe(true);
    expect(dataSource.savePreference).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.retry" }));
    await screen.findByText("Café");
    expect((screen.getByRole("button", { name: "gestion.dashboard.customize" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("never shows the first user's saved widgets while loading another user's preference", async () => {
    const other = deferred<DashboardPreference>();
    const loadPreference = vi.fn().mockResolvedValueOnce(preference).mockReturnValueOnce(other.promise);
    const dataSource = source({ loadPreference });
    const view = render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("Café");
    view.rerender(<GestionDashboard {...props(dataSource, { ...session, username: "other", accessToken: "other-token" })} />);
    expect(screen.queryByText("Café")).toBeNull();
    await act(async () => other.resolve({ ...preference, widgets: [], availableWidgets: ["control.alerts"] }));
    await screen.findByText("gestion.dashboard.empty");
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.customize" }));
    expect(screen.queryByRole("checkbox", { name: "gestion.widget.sales.today" })).toBeNull();
    expect(loadPreference).toHaveBeenLastCalledWith("other-token");
  });

  it("discards a late range response and aborts the superseded request", async () => {
    const first = deferred<SalesOverviewData>();
    const second = deferred<SalesOverviewData>();
    const loadSalesOverview = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    render(<GestionDashboard {...props(source({ loadSalesOverview }))} />);
    await waitFor(() => expect(loadSalesOverview).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByRole("combobox", { name: "gestion.dashboard.period" }), { target: { value: "TODAY" } });
    await waitFor(() => expect(loadSalesOverview).toHaveBeenCalledTimes(2));
    expect(loadSalesOverview.mock.calls[0][2].aborted).toBe(true);
    await act(async () => second.resolve({ ...overview, topProducts: [{ ...overview.topProducts[0], name: "Latest" }] }));
    await screen.findByText("Latest");
    await act(async () => first.resolve(overview));
    expect(screen.queryByText("Café")).toBeNull();
    expect(screen.getByText("Latest")).not.toBeNull();
  });

  it("preserves valid data after refresh failure and allows retry", async () => {
    const loadSalesOverview = vi.fn().mockResolvedValueOnce(overview).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(overview);
    render(<GestionDashboard {...props(source({ loadSalesOverview }))} />);
    await screen.findByText("Café");
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.refresh" }));
    await screen.findByText("gestion.dashboard.dataError");
    expect(screen.getByText("Café")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.retry" }));
    await waitFor(() => expect(screen.queryByText("gestion.dashboard.dataError")).toBeNull());
    expect(loadSalesOverview).toHaveBeenCalledTimes(3);
  });

  it.each(["TODAY", "MONTH"] as const)("refreshes %s after the store crosses midnight and a month boundary", async (defaultPeriod) => {
    vi.setSystemTime(new Date("2026-09-30T22:55:00Z"));
    const dataSource = source({ loadPreference: vi.fn().mockResolvedValue({
      ...preference, businessDate: "2026-09-30", options: { ...defaultDashboardOptions, defaultPeriod }
    }) });
    render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("Café");
    vi.setSystemTime(new Date("2026-09-30T23:05:00Z")); // 1 October in Atlantic/Canary.
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.refresh" }));
    await waitFor(() => expect(dataSource.loadSalesOverview).toHaveBeenLastCalledWith("token",
      { from: "2026-10-01", to: "2026-10-01", warehouseId: undefined }, expect.any(AbortSignal)));
    fireEvent.change(screen.getByRole("combobox", { name: "gestion.dashboard.period" }), { target: { value: "LAST_7_DAYS" } });
    await waitFor(() => expect(dataSource.loadSalesOverview).toHaveBeenLastCalledWith("token",
      { from: "2026-09-25", to: "2026-10-01", warehouseId: undefined }, expect.any(AbortSignal)));
  });

  it("preserves an explicit date range when refreshing after midnight", async () => {
    const dataSource = source();
    render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("Café");
    fireEvent.change(screen.getByLabelText("gestion.dashboard.from"), { target: { value: "2026-09-03" } });
    fireEvent.change(screen.getByLabelText("gestion.dashboard.to"), { target: { value: "2026-09-05" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.apply" }));
    await waitFor(() => expect(dataSource.loadSalesOverview).toHaveBeenCalledTimes(2));
    vi.setSystemTime(new Date("2026-10-01T12:00:00Z"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.dashboard.refresh" }));
    await waitFor(() => expect(dataSource.loadSalesOverview).toHaveBeenCalledTimes(3));
    expect(dataSource.loadSalesOverview).toHaveBeenLastCalledWith("token",
      { from: "2026-09-03", to: "2026-09-05", warehouseId: undefined }, expect.any(AbortSignal));
  });

  it.each(["es", "en", "zh"] as const)("renders translated controls and locale-aware ranking numbers in %s", async (locale) => {
    const translator = createTranslator(locale);
    const view = render(<GestionDashboard {...props(source())} t={translator} locale={locale} />);
    await screen.findByText("Café");
    fireEvent.click(screen.getByRole("button", { name: translator("gestion.dashboard.customize") }));
    expect(view.container.textContent).not.toMatch(/gestion\.(dashboard|widget)\./);
    expect(screen.getByText(locale === "es" ? "12,5" : "12.5")).not.toBeNull();
  });

  it("shows no average or percentage without transactions", async () => {
    const averagePreference = { ...preference, widgets: [{ key: "sales.average" as const, width: 4 as const, height: 1 as const }] };
    const dataSource = source({ loadPreference: vi.fn().mockResolvedValue(averagePreference), loadSalesOverview: vi.fn().mockResolvedValue({ ...overview, current: { netSales: 0, operationCount: 0, averageAmount: 0 } }) });
    const view = render(<GestionDashboard {...props(dataSource)} />);
    await screen.findByText("gestion.widget.noComparison");
    expect(view.container.querySelector(".gd-metric-value")?.textContent).toBe("—");
    expect(view.container.querySelector(".gd-change")?.textContent).toBe("—");
  });
});
