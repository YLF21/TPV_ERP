// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { CashOpeningAlertsScreen } from "./CashOpeningAlertsScreen";
import * as activityApi from "./cashActivityApi";
import * as alertsApi from "./cashOpeningAlertsApi";

vi.mock("./cashActivityApi", async original => ({ ...(await original<typeof import("./cashActivityApi")>()), loadCashActivityFilterOptions: vi.fn() }));
vi.mock("./cashOpeningAlertsApi", async original => ({ ...(await original<typeof import("./cashOpeningAlertsApi")>()), loadCashAlerts: vi.fn(), loadCashAlertDetail: vi.fn(), reviewCashAlert: vi.fn() }));

const t = (key: string) => key === "gestion.cashOpeningAlerts.type.closing" ? "Arqueo de cierre" : key === "gestion.cashOpeningAlerts.attempt" ? "Intento {attempt}" : key;
const options: activityApi.CashActivityFilterOptions = {
  businessDate: "2026-10-01", timezone: "Atlantic/Canary", earliestDate: "2026-01-01",
  terminals: [{ id: "terminal-1", name: "CAJA 01", secondaryName: "" }],
  users: [{ id: "user-1", name: "ADMIN", secondaryName: "admin" }]
};
const alert: alertsApi.CashAlert = {
  id: "alert-1", type: "OPENING", sessionId: "alert-1", terminalId: "terminal-1", terminalName: "CAJA 01",
  userId: "user-1", username: "admin", userName: "ADMIN", occurredAt: "2026-10-01T08:00:00Z",
  attemptNumber: null, sessionClosed: null,
  expectedFund: 240, countedFund: 235, difference: -5, status: "PENDING", reviewerId: null,
  reviewerUsername: null, reviewerName: null, reviewedAt: null, comment: null, version: 2
};
function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "admin", displayName: "ADMIN", accessToken: "token", permissions };
}

beforeEach(() => {
  vi.mocked(activityApi.loadCashActivityFilterOptions).mockResolvedValue(options);
  vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [alert], nextCursor: null, hasMore: false, pendingCount: 1 });
  vi.mocked(alertsApi.loadCashAlertDetail).mockRejectedValue(new Error("detail unavailable"));
  vi.mocked(alertsApi.reviewCashAlert).mockResolvedValue({ ...alert, status: "REVIEWED", reviewerName: "ADMIN", reviewerId: "user-1", reviewerUsername: "admin", reviewedAt: "2026-10-01T09:00:00Z", comment: "Verified", version: 3 });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("CashOpeningAlertsScreen", () => {
  it("loads all alerts by default, including an opening from the previous day", async () => {
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [{ ...alert, occurredAt: "2026-09-30T08:00:00Z" }], nextCursor: null, hasMore: false, pendingCount: 1 });
    render(<CashOpeningAlertsScreen session={session(["GESTION_CUENTAS"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    expect(await screen.findByText("CAJA 01")).not.toBeNull();
    expect(alertsApi.loadCashAlerts).toHaveBeenCalledWith(expect.objectContaining({ from: "", to: "", status: "" }), null, "token");
    expect(activityApi.loadCashActivityFilterOptions).toHaveBeenCalledWith("token");
    expect(screen.queryByLabelText("gestion.cashClosures.from")).toBeNull();
    expect(screen.queryByLabelText("gestion.cashClosures.to")).toBeNull();
    expect(screen.getByRole("button", { name: "Hoy" }).getAttribute("aria-pressed")).toBe("false");
  });

  it("shows a pending opening discrepancy before closure and records an audited review", async () => {
    const onReviewed = vi.fn();
    render(<CashOpeningAlertsScreen session={session(["GESTION_CUENTAS"])} t={t} locale="es" refreshSignal={0} onReviewed={onReviewed} />);
    expect(await screen.findByText("CAJA 01")).not.toBeNull();
    expect(screen.getAllByText(/-5,00/)).toHaveLength(2);
    expect(screen.getByText("gestion.cashOpeningAlerts.detail")).not.toBeNull();
    const reviewComment = screen.getByRole("textbox", { name: "gestion.cashOpeningAlerts.reviewComment" });
    fireEvent.change(reviewComment, { target: { value: "Verified" } });
    await waitFor(() => expect((reviewComment as HTMLTextAreaElement).value).toBe("Verified"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashOpeningAlerts.markReviewed" }));
    await waitFor(() => expect(alertsApi.reviewCashAlert).toHaveBeenCalledWith("alert-1", "OPENING", "Verified", 2, "token"));
    expect(onReviewed).toHaveBeenCalledOnce();
    expect(await screen.findByText("Verified")).not.toBeNull();
  });

  it("closes and reopens the selected alert with Enter while preserving filters and dates", async () => {
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await screen.findByText("CAJA 01");

    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.terminal" }));
    fireEvent.click(screen.getByRole("option", { name: "CAJA 01" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.apply" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ terminalId: "terminal-1", status: "" }), null, "token"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashOpeningAlerts.status" }));
    fireEvent.click(screen.getByRole("option", { name: "gestion.cashOpeningAlerts.pending" }));
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-09-30", to: "2026-09-30", terminalId: "terminal-1", status: "" }), null, "token"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.apply" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-09-30", to: "2026-09-30", terminalId: "terminal-1", status: "PENDING" }), null, "token"));

    const callsBeforeClose = vi.mocked(alertsApi.loadCashAlerts).mock.calls.length;
    const selectedRow = screen.getAllByRole("row")[1];
    expect(selectedRow.getAttribute("aria-selected")).toBe("true");
    fireEvent.click(screen.getByRole("button", { name: "common.close" }));

    expect(screen.queryByText("gestion.cashOpeningAlerts.detail")).toBeNull();
    expect(document.activeElement).toBe(selectedRow);
    expect(screen.getByRole("button", { name: "Ayer" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "filters.remove gestion.cashClosures.terminal" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "filters.remove gestion.cashOpeningAlerts.status" })).not.toBeNull();
    expect(alertsApi.loadCashAlerts).toHaveBeenCalledTimes(callsBeforeClose);
    expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-09-30", to: "2026-09-30", terminalId: "terminal-1", status: "PENDING" }), null, "token");

    fireEvent.keyDown(selectedRow, { key: "Enter" });
    expect(screen.getByText("gestion.cashOpeningAlerts.detail")).not.toBeNull();
    expect(selectedRow.getAttribute("aria-selected")).toBe("true");
    expect(alertsApi.loadCashAlerts).toHaveBeenCalledTimes(callsBeforeClose);
  });

  it("keeps opening and closing alerts with the same UUID distinct and reviews the selected closing attempt", async () => {
    const closing: alertsApi.CashAlert = {
      ...alert, type: "CLOSING", id: "alert-1", occurredAt: "2026-10-01T12:00:00Z",
      attemptNumber: 1, sessionClosed: false, expectedFund: 250, countedFund: 245,
      version: 4
    };
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [alert, closing], nextCursor: null, hasMore: false, pendingCount: 2 });
    vi.mocked(alertsApi.reviewCashAlert).mockResolvedValue({ ...closing, status: "REVIEWED", comment: "Checked close", version: 5 });
    render(<CashOpeningAlertsScreen session={session(["GESTION_CUENTAS"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(3));
    const rows = screen.getAllByRole("row");
    expect(rows[1].textContent).toContain("gestion.cashOpeningAlerts.type.opening");
    expect(within(rows[2]).getAllByRole("cell")[2].textContent).toBe("Arqueo de cierre");
    fireEvent.keyDown(rows[2], { key: "Enter" });
    expect(rows[2].getAttribute("aria-selected")).toBe("true");
    expect(rows[1].getAttribute("aria-selected")).toBe("false");
    expect(screen.getByText(/gestion.cashOpeningAlerts.attemptNumber: 1/)).not.toBeNull();
    expect(screen.getByText(/gestion.cashOpeningAlerts.sessionStateAtAttempt: gestion.cashOpeningAlerts.session.open/)).not.toBeNull();
    fireEvent.change(screen.getByRole("textbox", { name: "gestion.cashOpeningAlerts.reviewComment" }), { target: { value: "Checked close" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashOpeningAlerts.markReviewed" }));
    await waitFor(() => expect(alertsApi.reviewCashAlert).toHaveBeenCalledWith("alert-1", "CLOSING", "Checked close", 4, "token"));
    expect(rows[1].textContent).toContain("gestion.cashOpeningAlerts.pending");
    expect(rows[2].textContent).toContain("gestion.cashOpeningAlerts.reviewed");
  });

  it("shows one final closing row and both attempts in its detail, including an incorrect first and zero final count", async () => {
    const closing: alertsApi.CashAlert = { ...alert, type: "CLOSING", id: "attempt-2", attemptNumber: 2, sessionClosed: true, expectedFund: 240, countedFund: 240, difference: 0 };
    const first: alertsApi.CashAlertAttempt = { id: "attempt-1", attemptNumber: 1, occurredAt: closing.occurredAt, userId: closing.userId, username: closing.username, userName: closing.userName, expectedFund: 240, countedFund: 235, difference: -5, sessionClosed: false };
    const second: alertsApi.CashAlertAttempt = { ...first, id: "attempt-2", attemptNumber: 2, countedFund: 240, difference: 0, sessionClosed: true };
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [closing], nextCursor: null, hasMore: false, pendingCount: 1 });
    vi.mocked(alertsApi.loadCashAlertDetail).mockResolvedValue({ alert: closing, attempts: [first, second] });
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(2));
    expect(within(screen.getAllByRole("row")[1]).getAllByRole("cell")[2].textContent).toBe("Arqueo de cierre");
    await waitFor(() => expect(screen.getByText("Intento 1")).not.toBeNull());
    expect(screen.getByText("Intento 2")).not.toBeNull();
    const attempts = document.querySelectorAll(".gestion-cash-alert-attempt");
    expect(attempts).toHaveLength(2);
    expect(attempts[0].textContent).toContain("-5,00");
    expect(attempts[0].textContent).toContain("gestion.cashOpeningAlerts.session.open");
    expect(attempts[1].textContent).toContain("0,00");
    expect(attempts[1].textContent).toContain("gestion.cashOpeningAlerts.session.closed");
    expect(alertsApi.loadCashAlertDetail).toHaveBeenCalledWith("attempt-2", "CLOSING", "token", expect.any(AbortSignal));
  });

  it("keeps all attempt amounts redacted and review controls hidden for a read-only user", async () => {
    const closing: alertsApi.CashAlert = { ...alert, type: "CLOSING", id: "attempt-2", attemptNumber: 2, sessionClosed: true, expectedFund: null, countedFund: null, difference: null };
    const attempt: alertsApi.CashAlertAttempt = { id: "attempt-1", attemptNumber: 1, occurredAt: closing.occurredAt, userId: closing.userId, username: closing.username, userName: closing.userName, expectedFund: null, countedFund: null, difference: null, sessionClosed: false };
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [closing], nextCursor: null, hasMore: false, pendingCount: 1 });
    vi.mocked(alertsApi.loadCashAlertDetail).mockResolvedValue({ alert: closing, attempts: [attempt, { ...attempt, id: closing.id, attemptNumber: 2, sessionClosed: true }] });
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await waitFor(() => expect(document.querySelectorAll(".gestion-cash-alert-attempt")).toHaveLength(2));
    expect(Array.from(document.querySelectorAll(".gestion-cash-alert-attempt .gestion-cash-alert-amounts strong"), cell => cell.textContent)).toEqual(["—", "—", "—", "—", "—", "—"]);
    expect(document.querySelector(".gestion-cash-alert-attempt .shortage")).toBeNull();
    expect(screen.queryByRole("button", { name: "gestion.cashOpeningAlerts.markReviewed" })).toBeNull();
  });

  it("falls back to the final count when detail fails and reviews only the final alert", async () => {
    const closing: alertsApi.CashAlert = { ...alert, type: "CLOSING", id: "attempt-2", attemptNumber: 2, sessionClosed: true, version: 7 };
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [closing], nextCursor: null, hasMore: false, pendingCount: 1 });
    vi.mocked(alertsApi.reviewCashAlert).mockResolvedValue({ ...closing, status: "REVIEWED", version: 8, comment: "Final checked" });
    render(<CashOpeningAlertsScreen session={session(["GESTION_CUENTAS"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    expect(await screen.findByText("gestion.cashOpeningAlerts.detailError")).not.toBeNull();
    expect(screen.getByText(/gestion.cashOpeningAlerts.attemptNumber: 2/)).not.toBeNull();
    fireEvent.change(screen.getByRole("textbox", { name: "gestion.cashOpeningAlerts.reviewComment" }), { target: { value: "Final checked" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashOpeningAlerts.markReviewed" }));
    await waitFor(() => expect(alertsApi.reviewCashAlert).toHaveBeenCalledWith("attempt-2", "CLOSING", "Final checked", 7, "token"));
  });

  it("uses fresh detail status and version when another reviewer changed the final alert", async () => {
    const closing: alertsApi.CashAlert = { ...alert, type: "CLOSING", id: "attempt-2", attemptNumber: 2, sessionClosed: true };
    const reviewed: alertsApi.CashAlert = { ...closing, status: "REVIEWED", version: 3, reviewerName: "MANAGER", reviewedAt: closing.occurredAt, comment: "Already checked" };
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [closing], nextCursor: null, hasMore: false, pendingCount: 1 });
    vi.mocked(alertsApi.loadCashAlertDetail).mockResolvedValue({ alert: reviewed, attempts: [] });
    render(<CashOpeningAlertsScreen session={session(["GESTION_CUENTAS"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    expect(await screen.findByText("Already checked")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "gestion.cashOpeningAlerts.markReviewed" })).toBeNull();
    expect(screen.getAllByRole("row")[1].textContent).toContain("gestion.cashOpeningAlerts.reviewed");
    expect(alertsApi.reviewCashAlert).not.toHaveBeenCalled();
  });

  it("ignores old detail responses after selection and closing, then reloads when reopened", async () => {
    const first: alertsApi.CashAlert = { ...alert, type: "CLOSING", id: "final-a", attemptNumber: 2, sessionClosed: true };
    const second: alertsApi.CashAlert = { ...first, id: "final-b", sessionId: "session-b" };
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [first, second], nextCursor: null, hasMore: false, pendingCount: 2 });
    const pending: Array<(value: alertsApi.CashAlertDetail) => void> = [];
    vi.mocked(alertsApi.loadCashAlertDetail).mockImplementation(() => new Promise(resolve => { pending.push(resolve); }));
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await waitFor(() => expect(pending).toHaveLength(1));
    const rows = screen.getAllByRole("row");
    fireEvent.click(rows[2]);
    await waitFor(() => expect(pending).toHaveLength(2));
    pending[0]({ alert: first, attempts: [{ id: "old", attemptNumber: 1, occurredAt: first.occurredAt, userId: first.userId, username: first.username, userName: "OLD", expectedFund: 100, countedFund: 95, difference: -5, sessionClosed: false }] });
    expect(screen.queryByText("OLD", { exact: false })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "common.close" }));
    expect(document.activeElement).toBe(rows[2]);
    pending[1]({ alert: second, attempts: [] });
    expect(screen.queryByLabelText("gestion.cashOpeningAlerts.detail")).toBeNull();
    fireEvent.keyDown(rows[2], { key: "Enter" });
    await waitFor(() => expect(pending).toHaveLength(3));
    expect(alertsApi.loadCashAlertDetail).toHaveBeenLastCalledWith("final-b", "CLOSING", "token", expect.any(AbortSignal));
  });

  it("keeps review controls hidden from read-only users", async () => {
    vi.mocked(alertsApi.loadCashAlerts).mockResolvedValue({ items: [{ ...alert, expectedFund: null, countedFund: null, difference: null }], nextCursor: null, hasMore: false, pendingCount: 1 });
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await screen.findByText("CAJA 01");
    expect(screen.queryByRole("button", { name: "gestion.cashOpeningAlerts.markReviewed" })).toBeNull();
    const cells = Array.from(document.querySelectorAll(".gestion-cash-alert-row [role='cell']"));
    expect(cells).toHaveLength(9);
    expect(cells.slice(5, 8).map(cell => cell.textContent)).toEqual(["—", "—", "—"]);
    expect(Array.from(document.querySelectorAll(".gestion-cash-alert-amounts strong"), cell => cell.textContent))
      .toEqual(["—", "—", "—"]);
    expect(document.querySelector(".gestion-cash-alert-row .shortage")).toBeNull();
  });

  it("applies terminal and status filters and preserves them when a tag is removed", async () => {
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await screen.findByText("CAJA 01");
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.terminal" }));
    fireEvent.click(screen.getByRole("option", { name: "CAJA 01" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashOpeningAlerts.status" }));
    fireEvent.click(screen.getByRole("option", { name: "gestion.cashOpeningAlerts.reviewed" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.apply" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ terminalId: "terminal-1", status: "REVIEWED" }), null, "token"));
    fireEvent.click(screen.getByRole("button", { name: "filters.remove gestion.cashClosures.terminal" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ terminalId: "", status: "REVIEWED" }), null, "token"));
  });

  it("applies a preset immediately with the applied criteria and synchronizes the manual dates", async () => {
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await screen.findByText("CAJA 01");
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.terminal" }));
    fireEvent.click(screen.getByRole("option", { name: "CAJA 01" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.apply" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ terminalId: "terminal-1" }), null, "token"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashOpeningAlerts.status" }));
    fireEvent.click(screen.getByRole("option", { name: "gestion.cashOpeningAlerts.reviewed" }));
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-09-30", to: "2026-09-30", terminalId: "terminal-1", status: "" }), null, "token"));
    expect(screen.queryByLabelText("gestion.cashClosures.from")).toBeNull();
    expect(screen.queryByLabelText("gestion.cashClosures.to")).toBeNull();
    expect(screen.getByRole("button", { name: "Ayer" }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.apply" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-09-30", to: "2026-09-30", terminalId: "terminal-1", status: "REVIEWED" }), null, "token"));
    expect(screen.getByRole("button", { name: "Ayer" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("applies manual dates from the custom period and clears only the removed criterion", async () => {
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await screen.findByText("CAJA 01");
    expect(screen.queryByLabelText("gestion.cashClosures.from")).toBeNull();
    expect(screen.queryByLabelText("gestion.cashClosures.to")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Periodo personalizado" }));
    fireEvent.change(screen.getByLabelText("Desde"), { target: { value: "2026-09-29" } });
    fireEvent.change(screen.getByLabelText("Hasta"), { target: { value: "2026-10-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar periodo" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-09-29", to: "2026-10-01" }), null, "token"));
    fireEvent.click(screen.getByRole("button", { name: "filters.remove gestion.cashClosures.from / gestion.cashClosures.to" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "", to: "", status: "" }), null, "token"));
    expect(screen.getByRole("button", { name: "Hoy" }).getAttribute("aria-pressed")).toBe("false");
  });

  it("preserves the preset when removing another chip and clears all criteria together", async () => {
    render(<CashOpeningAlertsScreen session={session(["CASH_READ"])} t={t} locale="es" refreshSignal={0} onReviewed={vi.fn()} />);
    await screen.findByText("CAJA 01");
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashOpeningAlerts.status" }));
    fireEvent.click(screen.getByRole("option", { name: "gestion.cashOpeningAlerts.pending" }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.cashClosures.apply" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ status: "PENDING" }), null, "token"));
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    fireEvent.click(screen.getByRole("button", { name: "filters.remove gestion.cashOpeningAlerts.status" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-09-30", to: "2026-09-30", status: "" }), null, "token"));
    expect(screen.getByRole("button", { name: "Ayer" }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(screen.getByRole("button", { name: "filters.clearAll" }));
    await waitFor(() => expect(alertsApi.loadCashAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from: "", to: "", status: "" }), null, "token"));
    expect(screen.getByRole("button", { name: "Ayer" }).getAttribute("aria-pressed")).toBe("false");
    expect(screen.getByRole("button", { name: "Hoy" }).getAttribute("aria-pressed")).toBe("false");
    expect(screen.getByRole("button", { name: "Semana actual" }).getAttribute("aria-pressed")).toBe("false");
  });
});
