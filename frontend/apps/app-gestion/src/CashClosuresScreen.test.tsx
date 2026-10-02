// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { CashClosuresScreen, canReadCashClosures } from "./CashClosuresScreen";
import * as activityApi from "./cashActivityApi";
import * as closuresApi from "./cashClosuresApi";
import * as alertsApi from "./cashOpeningAlertsApi";
import { cashClosureMessages } from "../../../packages/app-common/src/i18n/CashClosureMessages";

vi.mock("./cashActivityApi", async importOriginal => ({
  ...(await importOriginal<typeof import("./cashActivityApi")>()),
  loadCashActivityFilterOptions: vi.fn(),
  loadCashActivity: vi.fn()
}));
vi.mock("./cashClosuresApi", async importOriginal => ({
  ...(await importOriginal<typeof import("./cashClosuresApi")>()),
  loadCashClosure: vi.fn()
}));
vi.mock("./cashOpeningAlertsApi", async importOriginal => ({
  ...(await importOriginal<typeof import("./cashOpeningAlertsApi")>()),
  loadCashAlerts: vi.fn()
}));

const options: activityApi.CashActivityFilterOptions = {
  businessDate: "2026-07-31", earliestDate: "2026-01-01", timezone: "Atlantic/Canary",
  terminals: [{ id: "terminal-1", name: "TPV 1", secondaryName: null }, { id: "terminal-2", name: "TPV 2", secondaryName: null }],
  users: [{ id: "user-1", name: "CAJERO", secondaryName: "cajero" }]
};
const row = (id: string, terminalId: string, action = "ENTRADA"): activityApi.CashActivityRow => ({
  id, occurredAt: "2026-07-31T17:30:00Z", userId: "user-1", username: "cajero", userName: "CAJERO",
  action, concept: "Cambio", amount: 25, balance: 125, reference: "000000123", sessionId: "session-1",
  cashState: "ABIERTA", sourceReference: null, terminalId, terminalCode: null,
  terminalName: terminalId === "terminal-1" ? "TPV 1" : "TPV 2"
});
const page = { items: [row("one", "terminal-1"), row("two", "terminal-2")], nextCursor: null, hasMore: false };
const defaultActivitySort = { column: "reference", direction: "desc" } as const;
function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "manager", displayName: "MANAGER", accessToken: "token", permissions };
}
const t = (key: string) => cashClosureMessages("es")[key] ?? key;

beforeEach(() => {
  vi.mocked(activityApi.loadCashActivityFilterOptions).mockResolvedValue(options);
  vi.mocked(activityApi.loadCashActivity).mockResolvedValue(page);
  vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [], nextCursor: null, hasMore: false, pendingCount: 3 });
  vi.mocked(closuresApi.loadCashClosure).mockResolvedValue({
    id: "session-1", terminalId: "terminal-1", terminalName: "TPV 1", closingUserId: "user-1",
    closingUserName: "CAJERO", closingUsername: "cajero", closedAt: "2026-07-31T17:30:00Z",
    expectedCash: 125, retainedFund: 25, finalWithdrawalAmount: 100,
    retainedFundDenominations: [{ denomination: 5, quantity: 5 }], finalWithdrawalDenominations: [],
    discrepancy: 0, lateClosing: false
  });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 404 })));
});
afterEach(() => { cleanup(); localStorage.clear(); vi.clearAllMocks(); vi.unstubAllGlobals(); });

describe("CashClosuresScreen activity", () => {
  it("loads movements from both terminals and keeps the date dock below the scrollable table", async () => {
    localStorage.setItem("tpv-erp:gestion:user:manager:table:gestion.cash.activity:sort", JSON.stringify({ column: "quantity", direction: "desc" }));
    render(<CashClosuresScreen session={session(["CASH_READ"])} t={t} />);
    expect(await screen.findByRole("row", { name: /000000123.*TPV 1/ })).not.toBeNull();
    expect(screen.getByText("TPV 2")).not.toBeNull();
    expect(activityApi.loadCashActivity).toHaveBeenCalledWith(expect.objectContaining({
      from: "2026-07-31", to: "2026-07-31", terminalId: ""
    }), null, "token", defaultActivitySort, expect.any(AbortSignal));
    const view = document.querySelector(".gestion-cash-activity-view")!;
    expect(view.querySelector(".gestion-cash-activity-table-wrap")!.compareDocumentPosition(view.querySelector(".report-date-range-filter")!)
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(document.querySelectorAll('[role="cell"][data-column-key="quantity"]')).toHaveLength(2);
    expect(document.querySelector('[role="cell"][data-column-key="quantity"]')?.textContent).toBe("—");
  });

  it("removes independent filter tags without resetting the date range", async () => {
    render(<CashClosuresScreen session={session(["GESTION_CUENTAS"])} t={t} />);
    await screen.findByText("TPV 2");
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    await waitFor(() => expect(activityApi.loadCashActivity).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: "2026-07-30", to: "2026-07-30" }), null, "token", defaultActivitySort, expect.any(AbortSignal)));
    fireEvent.click(screen.getByRole("button", { name: "Terminal" }));
    fireEvent.click(screen.getByRole("option", { name: "TPV 1" }));
    await waitFor(() => expect(activityApi.loadCashActivity).toHaveBeenLastCalledWith(
      expect.objectContaining({ terminalId: "terminal-1" }), null, "token", defaultActivitySort, expect.any(AbortSignal)));
    const chip = document.querySelector(".erp-filter-applied")!;
    expect(chip.textContent).toContain("TPV 1");
    fireEvent.click(chip.querySelector("button")!);
    await waitFor(() => expect(activityApi.loadCashActivity).toHaveBeenLastCalledWith(
      expect.objectContaining({ terminalId: "", from: "2026-07-30" }), null, "token", defaultActivitySort, expect.any(AbortSignal)));
  });

  it("keeps cash alerts, admin settings, and closing detail", async () => {
    vi.mocked(activityApi.loadCashActivity).mockResolvedValue({ items: [row("closing", "terminal-1", "CLOSING")], nextCursor: null, hasMore: false });
    render(<CashClosuresScreen session={session(["ADMIN"])} t={t} />);
    fireEvent.click(await screen.findByRole("row", { name: /000000123/ }));
    expect(await screen.findByText("Fondo conservado por denominaciones")).not.toBeNull();
    expect(closuresApi.loadCashClosure).toHaveBeenCalledWith("session-1", "token");
    expect(screen.getByRole("tab", { name: /Alertas de caja \(3\)/ })).not.toBeNull();
    expect(alertsApi.loadCashAlerts).toHaveBeenCalledWith(expect.objectContaining({ from: "", to: "", status: "PENDING" }), null, "token", 1);
    expect(screen.getByRole("tab", { name: "Configuración de caja" })).not.toBeNull();
  });

  it("refreshes the pending badge when entering alerts after another app records discrepancies", async () => {
    vi.mocked(alertsApi.loadCashAlerts)
      .mockResolvedValueOnce({ items: [], nextCursor: null, hasMore: false, pendingCount: 0 })
      .mockResolvedValue({ items: [], nextCursor: null, hasMore: false, pendingCount: 2 });
    render(<CashClosuresScreen session={session(["CASH_READ"])} t={t} />);
    const pendingRequests = () => vi.mocked(alertsApi.loadCashAlerts).mock.calls.filter(([filters]) => filters.status === "PENDING");
    await waitFor(() => expect(pendingRequests()).toHaveLength(1));
    const tab = screen.getByRole("tab", { name: "Alertas de caja (0)" });
    fireEvent.click(tab);
    await waitFor(() => expect(screen.getByRole("tab", { name: "Alertas de caja (2)" })).not.toBeNull());
    expect(pendingRequests()).toHaveLength(2);
    fireEvent.click(screen.getByRole("tab", { name: "Alertas de caja (2)" }));
    expect(pendingRequests()).toHaveLength(2);
  });

  it("appends cursor pages and ignores an obsolete response after a filter change", async () => {
    let releaseFirst!: (value: activityApi.CashActivityPage) => void;
    vi.mocked(activityApi.loadCashActivity)
      .mockImplementationOnce(() => new Promise(resolve => { releaseFirst = resolve; }))
      .mockResolvedValueOnce({ items: [row("filtered", "terminal-1")], nextCursor: "page-2", hasMore: true })
      .mockResolvedValueOnce({ items: [row("second", "terminal-2")], nextCursor: null, hasMore: false });
    render(<CashClosuresScreen session={session(["GESTION_CUENTAS"])} t={t} />);
    await waitFor(() => expect(activityApi.loadCashActivity).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "Terminal" }));
    fireEvent.click(screen.getByRole("option", { name: "TPV 1" }));
    expect(await screen.findByRole("row", { name: /000000123.*TPV 1/ })).not.toBeNull();
    releaseFirst(page);
    await waitFor(() => expect(screen.queryByText("TPV 2")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Cargar más" }));
    await waitFor(() => expect(activityApi.loadCashActivity).toHaveBeenLastCalledWith(
      expect.objectContaining({ terminalId: "terminal-1" }), "page-2", "token", defaultActivitySort, expect.any(AbortSignal)));
    expect(await screen.findByRole("row", { name: /000000123.*TPV 2/ })).not.toBeNull();
  });
});

describe("cash permissions", () => {
  it("preserves the existing access boundary", () => {
    expect(canReadCashClosures(session(["CASH_READ"]))).toBe(true);
    expect(canReadCashClosures(session(["GESTION_CUENTAS"]))).toBe(true);
    expect(canReadCashClosures(session([]))).toBe(false);
  });
});
