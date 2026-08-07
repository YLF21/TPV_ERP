// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { CashClosuresScreen, canReadCashClosures } from "./CashClosuresScreen";
import * as api from "./cashClosuresApi";

vi.mock("./cashClosuresApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./cashClosuresApi")>();
  return {
    ...original,
    loadCashClosureFilterOptions: vi.fn(),
    loadCashClosures: vi.fn()
  };
});

const options: api.CashClosureFilterOptions = {
  businessDate: "2026-07-31",
  timezone: "Atlantic/Canary",
  terminals: [{ id: "terminal-1", name: "TPV 1", secondaryName: "" }],
  users: [{ id: "user-1", name: "CAJERO", secondaryName: "cajero" }]
};

const closure: api.CashClosure = {
  id: "closure-1",
  terminalId: "terminal-1",
  terminalName: "TPV 1",
  closingUserId: "user-1",
  closingUserName: "CAJERO",
  closingUsername: "cajero",
  closedAt: "2026-07-31T17:30:00Z",
  expectedCash: 125,
  retainedFund: 25,
  discrepancy: -2,
  lateClosing: false
};

function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "manager", displayName: "MANAGER", accessToken: "token", permissions };
}

beforeEach(() => {
  vi.mocked(api.loadCashClosureFilterOptions).mockResolvedValue(options);
  vi.mocked(api.loadCashClosures).mockResolvedValue({ items: [closure], nextCursor: null, hasMore: false });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 404 })));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("CashClosuresScreen", () => {
  it("loads the store business day and renders the retained cash and discrepancy", async () => {
    render(<CashClosuresScreen session={session(["APP_GESTION_ACCESS", "CASH_READ"])} t={(key) => key} />);

    expect(await screen.findByText("TPV 1")).not.toBeNull();
    expect(document.querySelector('[role="cell"][data-column-key="retainedFund"]')?.textContent).toContain("25,00");
    expect(screen.getByText(/-2,00/).className).toContain("shortage");
    expect(api.loadCashClosures).toHaveBeenCalledWith(expect.objectContaining({
      from: "2026-07-31",
      to: "2026-07-31",
      terminalId: "",
      userId: ""
    }), null, "token", null);
  });

  it("combines user terminal date range and discrepancy filters", async () => {
    render(<CashClosuresScreen session={session(["APP_GESTION_ACCESS", "GESTION_CUENTAS"])} t={(key) => key} />);
    await screen.findByText("TPV 1");

    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.filter" }));
    fireEvent.change(screen.getByLabelText("gestion.cashClosures.from"), { target: { value: "2026-07-01" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.terminal" }));
    fireEvent.click(screen.getByRole("option", { name: "TPV 1" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.user" }));
    fireEvent.click(screen.getByRole("option", { name: "CAJERO" }));
    fireEvent.click(screen.getByLabelText("gestion.cashClosures.onlyDiscrepancies"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.apply" }));

    await waitFor(() => expect(api.loadCashClosures).toHaveBeenLastCalledWith(expect.objectContaining({
      from: "2026-07-01",
      to: "2026-07-31",
      terminalId: "terminal-1",
      userId: "user-1",
      onlyDiscrepancies: true
    }), null, "token", null));
  });

  it("loads the next cursor when the table approaches the bottom", async () => {
    vi.mocked(api.loadCashClosures)
      .mockResolvedValueOnce({ items: [closure], nextCursor: "page-2", hasMore: true })
      .mockResolvedValueOnce({ items: [{ ...closure, id: "closure-2", closedAt: "2026-07-31T16:00:00Z" }], nextCursor: null, hasMore: false });
    render(<CashClosuresScreen session={session(["APP_GESTION_ACCESS", "CASH_READ"])} t={(key) => key} />);
    await screen.findByText("TPV 1");
    const table = screen.getByRole("table");
    Object.defineProperties(table, {
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 600 },
      scrollTop: { configurable: true, value: 200 }
    });

    fireEvent.scroll(table);

    await waitFor(() => expect(api.loadCashClosures).toHaveBeenLastCalledWith(expect.any(Object), "page-2", "token", null));
    await waitFor(() => expect(screen.getAllByText("TPV 1")).toHaveLength(2));
  });

  it("reloads the first page with the selected backend sort", async () => {
    render(<CashClosuresScreen session={session(["APP_GESTION_ACCESS", "CASH_READ"])} t={(key) => key} />);
    await screen.findByText("TPV 1");

    fireEvent.click(screen.getByRole("button", { name: "party.sortBy gestion.cashClosures.column.retainedFund" }));

    await waitFor(() => expect(api.loadCashClosures).toHaveBeenLastCalledWith(
      expect.any(Object),
      null,
      "token",
      { column: "retainedFund", direction: "asc" }
    ));
  });

  it("matches the confirmed read permissions", () => {
    expect(canReadCashClosures(session(["CASH_READ"]))).toBe(true);
    expect(canReadCashClosures(session(["GESTION_CUENTAS"]))).toBe(true);
    expect(canReadCashClosures(session(["ADMIN"]))).toBe(true);
    expect(canReadCashClosures(session(["GESTION_VENTAS"]))).toBe(false);
  });
});
