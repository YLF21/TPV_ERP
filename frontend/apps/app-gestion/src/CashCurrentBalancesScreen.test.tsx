// @vitest-environment jsdom
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "@tpverp/app-common";
import { CashCurrentBalancesScreen, canReadCashCurrentBalances } from "./CashCurrentBalancesScreen";
import * as api from "./cashCurrentBalancesApi";

vi.mock("./cashCurrentBalancesApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./cashCurrentBalancesApi")>();
  return { ...original, loadCashCurrentBalances: vi.fn() };
});

const snapshot: api.CashCurrentBalances = {
  asOf: "2026-08-01T10:15:00Z",
  timezone: "Atlantic/Canary",
  terminals: [{
    terminalId: "terminal-1",
    terminalName: "TPV 1",
    status: "ABIERTA",
    openingUserId: "user-1",
    openingUserName: "CAJERO",
    openingUsername: "cajero",
    openedAt: "2026-08-01T08:00:00Z",
    expectedCash: 125,
    lastActivityAt: "2026-08-01T10:14:30Z"
  }, {
    terminalId: "terminal-2",
    terminalName: "TPV 2",
    status: "CERRADA",
    openingUserId: null,
    openingUserName: null,
    openingUsername: null,
    openedAt: null,
    expectedCash: 35,
    lastActivityAt: "2026-07-31T20:00:00Z"
  }]
};

function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "manager", displayName: "MANAGER", accessToken: "token", permissions };
}

beforeEach(() => {
  vi.mocked(api.loadCashCurrentBalances).mockResolvedValue(snapshot);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 404 })));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("CashCurrentBalancesScreen", () => {
  it("shows the complete expected cash instead of cash sales alone", async () => {
    render(<CashCurrentBalancesScreen session={session(["APP_GESTION_ACCESS", "CASH_READ"])} t={(key) => key} />);

    expect(await screen.findByText("TPV 1")).not.toBeNull();
    expect(document.querySelector('[role="cell"][data-column-key="expectedCash"]')?.textContent).toContain("125,00");
    expect(screen.getByText("gestion.cashCurrentBalances.status.open")).not.toBeNull();
    expect(screen.getByText("gestion.cashCurrentBalances.status.closed")).not.toBeNull();
    expect(screen.getByText(/160,00/)).not.toBeNull();
  });

  it("refreshes the balances every three seconds", async () => {
    vi.useFakeTimers();
    render(<CashCurrentBalancesScreen session={session(["APP_GESTION_ACCESS", "CASH_READ"])} t={(key) => key} />);
    await act(async () => { await Promise.resolve(); });
    expect(api.loadCashCurrentBalances).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(3_000); });

    expect(api.loadCashCurrentBalances).toHaveBeenCalledTimes(2);
  });

  it("keeps the last snapshot visible when a later refresh fails", async () => {
    vi.useFakeTimers();
    vi.mocked(api.loadCashCurrentBalances)
      .mockResolvedValueOnce(snapshot)
      .mockRejectedValueOnce(new Error("offline"));
    render(<CashCurrentBalancesScreen session={session(["APP_GESTION_ACCESS", "GESTION_CUENTAS"])} t={(key) => key} />);
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await vi.advanceTimersByTimeAsync(3_000); });

    expect(screen.getByRole("alert").textContent).toContain("gestion.cashCurrentBalances.staleWarning");
    expect(screen.getByText("TPV 1")).not.toBeNull();
  });

  it("matches the confirmed financial read permissions", () => {
    expect(canReadCashCurrentBalances(session(["CASH_READ"]))).toBe(true);
    expect(canReadCashCurrentBalances(session(["GESTION_CUENTAS"]))).toBe(true);
    expect(canReadCashCurrentBalances(session(["ADMIN"]))).toBe(true);
    expect(canReadCashCurrentBalances(session(["GESTION_VENTAS"]))).toBe(false);
  });
});
