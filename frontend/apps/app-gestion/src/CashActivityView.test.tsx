// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { CashActivityView } from "./CashActivityView";
import * as api from "./cashActivityApi";

vi.mock("./cashActivityApi", async original => ({ ...(await original<typeof import("./cashActivityApi")>()), loadCashActivityFilterOptions: vi.fn(), loadCashActivity: vi.fn() }));

afterEach(() => { vi.useRealTimers(); cleanup(); vi.clearAllMocks(); });

it("advances through current cash rows until release and leaves other commands alone", async () => {
  vi.mocked(api.loadCashActivityFilterOptions).mockResolvedValue({ businessDate: "2026-10-01", timezone: "Atlantic/Canary", earliestDate: "2026-01-01", terminals: [], users: [] });
  const item: api.CashActivityRow = { id: "row-1", occurredAt: "2026-10-01T08:00:00Z", userId: null, username: "admin", userName: "ADMIN", action: "OPENING", concept: null, amount: 10, balance: 10, reference: "R1", sessionId: null, cashState: "ABIERTA", sourceReference: null, terminalId: "terminal-1", terminalCode: "001", terminalName: "Caja" };
  vi.mocked(api.loadCashActivity).mockResolvedValue({ items: [item, { ...item, id: "row-2", reference: "R2" }, { ...item, id: "row-3", reference: "R3" }, { ...item, id: "row-4", reference: "R4" }], nextCursor: null, hasMore: false });
  const session: UserSession = { username: "admin", displayName: "ADMIN", accessToken: "token", permissions: ["GESTION_CUENTAS"] };
  render(<CashActivityView session={session} t={key => key} locale="es" refreshSignal={0} />);
  const table = await screen.findByRole("table", { name: "gestion.cashClosures.title" });
  await waitFor(() => expect(within(table).getAllByRole("row")).toHaveLength(5));
  const rows = within(table).getAllByRole("row").slice(1);
  expect(document.activeElement).toBe(rows[0]);
  expect(rows[0].getAttribute("aria-selected")).toBe("true");
  rows[0].focus();
  vi.useFakeTimers();
  fireEvent.keyDown(rows[0], { key: "ArrowDown" });
  expect(document.activeElement).toBe(rows[1]);
  await act(async () => { vi.advanceTimersByTime(180); });
  expect(document.activeElement).toBe(rows[2]);
  await act(async () => { vi.advanceTimersByTime(70); });
  expect(document.activeElement).toBe(rows[3]);
  fireEvent.keyUp(rows[3], { key: "ArrowDown" });
  await act(async () => { vi.advanceTimersByTime(300); });
  expect(document.activeElement).toBe(rows[3]);
  fireEvent.keyDown(rows[3], { key: "Enter" });
  expect(rows[3].getAttribute("aria-selected")).toBe("true");
  fireEvent.keyDown(rows[3], { key: "ArrowUp" });
  expect(document.activeElement).toBe(rows[2]);
  screen.getByRole("button", { name: "Hoy" }).focus();
  await act(async () => { vi.advanceTimersByTime(300); });
  expect(document.activeElement).not.toBe(rows[1]);
});
