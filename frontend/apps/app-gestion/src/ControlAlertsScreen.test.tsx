// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, createTranslator, type UserSession } from "@tpverp/app-common";
import { ControlAlertsScreen, controlAlertDueAtToInstant, dateRangeToInstants, validRuleDraft } from "./ControlAlertsScreen";
import * as api from "./controlAlertsApi";

vi.mock("./controlAlertsApi", async (original) => ({
  ...await original<typeof import("./controlAlertsApi")>(),
  loadControlAlertGroups: vi.fn(), loadControlAlerts: vi.fn(), loadControlAlertAssignees: vi.fn(),
  loadControlAlert: vi.fn(), transitionControlAlert: vi.fn(), updateControlAlertWork: vi.fn(), loadRelatedDocument: vi.fn(),
  loadControlRuleCatalog: vi.fn(), loadControlRules: vi.fn(), saveControlRule: vi.fn(), setControlRuleActive: vi.fn(),
  loadControlAlertViewPreference: vi.fn(), saveControlAlertViewPreference: vi.fn()
}));

const initialAlert: api.ControlAlert = {
  id: "alert-1", type: "TICKET_CANCELLED", status: "NEW", priority: "MEDIUM", occurredAt: "2026-09-16T10:30:00Z",
  documentId: "doc-1", documentNumber: "T-100", ruleId: "rule-1", ruleVersion: 1,
  userName: "cashier", terminalId: "terminal-1", data: { terminalCode: "POS-1", reason: "Error" }, version: 0
};
const group: api.ControlRuleAlertGroup = {
  ruleId: "rule-1", type: "TICKET_CANCELLED", ruleName: "Ticket cancelled", active: true, supported: true,
  total: 1, newCount: 1, reviewedCount: 0, closedCount: 0, dismissedCount: 0, parameterKind: "NONE", configuration: {}
};
const rule: api.ControlRule = { id: "rule-1", type: "TICKET_CANCELLED", name: "Ticket cancelled", active: true, configuration: {}, ruleVersion: 1, version: 0 };
const catalog: api.ControlRuleCatalogItem[] = [
  { type: "TICKET_CANCELLED", name: "Ticket cancelled", parameterKind: "NONE", defaultConfiguration: {}, supported: true, configured: true, ruleId: "rule-1" },
  { type: "MANUAL_DISCOUNT_OVER_PERCENT", name: "Manual discount", parameterKind: "PERCENTAGE", defaultConfiguration: { thresholdPercent: 10 }, supported: true, configured: false },
  { type: "MANUAL_PRICE_CHANGED", name: "Manual price change", parameterKind: "NONE", defaultConfiguration: {}, supported: false, configured: false }
];
let currentAlert: api.ControlAlert;
const page = (items: api.ControlAlert[]): api.ControlAlertPage => ({ items, page: 0, size: 25, totalElements: items.length, totalPages: items.length ? 1 : 0 });
const readPermissions: UserSession["permissions"] = ["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"];
const managerPermissions: UserSession["permissions"] = ["APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE", "CONTROL_RULES_MANAGE"];
function session(permissions = readPermissions, username = "manager"): UserSession {
  return { username, displayName: username, accessToken: `${username}-token`, permissions };
}
function renderScreen(permissions = readPermissions, t: (key: string) => string = (key) => key, locale: "es" | "en" | "zh" = "es") {
  return render(<ControlAlertsScreen session={session(permissions)} t={t} locale={locale} />);
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
async function loaded() {
  await screen.findByRole("row", { name: /T-100/ });
}
async function openDetail(t: (key: string) => string = (key) => key) {
  const row = await screen.findByRole("row", { name: /T-100/ });
  fireEvent.doubleClick(row);
  const dialog = await screen.findByRole("dialog", { name: t("gestion.controlAlerts.detail") });
  await within(dialog).findByText(new RegExp(t("gestion.controlAlerts.generated")));
  return dialog;
}

beforeEach(() => {
  currentAlert = { ...initialAlert };
  localStorage.clear();
  vi.mocked(api.loadControlAlertViewPreference).mockResolvedValue({ ...api.defaultControlAlertView, storeTimezone: "Atlantic/Canary", storeLocale: "es-ES" });
  vi.mocked(api.saveControlAlertViewPreference).mockImplementation(async (preference) => preference);
  vi.mocked(api.loadControlAlertGroups).mockResolvedValue([group]);
  vi.mocked(api.loadControlAlerts).mockImplementation(async () => page([currentAlert]));
  vi.mocked(api.loadControlAlertAssignees).mockResolvedValue([]);
  vi.mocked(api.loadControlAlert).mockImplementation(async () => currentAlert);
  vi.mocked(api.transitionControlAlert).mockImplementation(async (_id, action, comment) => {
    const status = action === "REOPEN" ? "NEW" : action === "REVIEW" ? "REVIEWED" : action === "CLOSE" ? "CLOSED" : "DISMISSED";
    currentAlert = { ...currentAlert, status, reviewComment: comment.trim() || currentAlert.reviewComment, version: currentAlert.version + 1,
      history: [...currentAlert.history ?? [], { previousStatus: currentAlert.status, newStatus: status, comment, changedAt: "2026-09-17T10:00:00Z", changedByName: "manager" }] };
    return currentAlert;
  });
  vi.mocked(api.updateControlAlertWork).mockImplementation(async (_alert, work) => {
    currentAlert = { ...currentAlert, ...work, version: currentAlert.version + 1 };
    return currentAlert;
  });
  vi.mocked(api.loadControlRuleCatalog).mockResolvedValue(catalog);
  vi.mocked(api.loadControlRules).mockResolvedValue([rule]);
  vi.mocked(api.saveControlRule).mockResolvedValue({ ...rule, id: "rule-2", type: "MANUAL_DISCOUNT_OVER_PERCENT", active: false, configuration: { thresholdPercent: .29 } });
  vi.mocked(api.setControlRuleActive).mockResolvedValue({ ...rule, active: false, version: 1 });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 404 })));
});
afterEach(() => { cleanup(); vi.resetAllMocks(); vi.unstubAllGlobals(); });

describe("control alert timeline", () => {
  it.each(["en", "zh"] as const)("formats recorded amounts with the selected %s locale", async (locale) => {
    currentAlert = { ...currentAlert, type: "SALE_SCREEN_CLEARED", data: { lineCount: 4, total: 32.5 } };
    const t = createTranslator(locale);
    renderScreen(readPermissions, t, locale);
    const list = await screen.findByRole("region", { name: t("gestion.controlAlerts.list") });
    expect(await within(list).findByText(/32\.5/)).not.toBeNull();
    expect(within(list).queryByText(/32,5/)).toBeNull();
  });
  it("opens the global chronological list without requiring a rule and loads only permitted data", async () => {
    renderScreen();
    await loaded();
    expect(api.loadControlAlerts).toHaveBeenCalledWith(expect.objectContaining({ sortBy: "occurredAt", sortDirection: "desc", type: "", from: expect.any(String), to: expect.any(String) }), "manager-token", expect.any(AbortSignal));
    expect(api.loadControlRules).not.toHaveBeenCalled();
    expect(api.loadControlRuleCatalog).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "gestion.controlAlerts.configureRules" })).toBeNull();
    expect(screen.getAllByText("T-100").length).toBeGreaterThan(0);
    expect(api.loadControlAlert).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog", { name: "gestion.controlAlerts.detail" })).toBeNull();
  });

  it("opens a selected row with Enter, traps focus and restores focus and scroll on Escape", async () => {
    renderScreen();
    await loaded();
    const row = screen.getByRole("row", { name: /T-100/ });
    const table = screen.getByRole("table", { name: "gestion.controlAlerts.chronologyList" });
    table.scrollTop = 130;
    fireEvent.click(row);
    expect(api.loadControlAlert).not.toHaveBeenCalled();
    row.focus();
    fireEvent.keyDown(row, { key: "Enter" });
    const dialog = await screen.findByRole("dialog", { name: "gestion.controlAlerts.detail" });
    const close = within(dialog).getByRole("button", { name: "common.close" });
    await waitFor(() => expect(document.activeElement).toBe(close));
    fireEvent.keyDown(close, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(close);
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "gestion.controlAlerts.detail" })).toBeNull());
    expect(document.activeElement).toBe(row);
    expect(table.scrollTop).toBe(130);
    expect(row.getAttribute("aria-selected")).toBe("true");
  });

  it("keeps saved column order and widths and adds the review comment last", async () => {
    localStorage.setItem("tpv-erp:gestion:user:manager:table:gestion.controlAlerts.timeline:layout", JSON.stringify([
      { key: "status", width: 145, visible: true }, { key: "operation", width: 350, visible: true },
      { key: "time", width: 95, visible: true }, { key: "documentUser", width: 230, visible: true }
    ]));
    currentAlert = { ...currentAlert, reviewComment: "Reviewed against the original sale" };
    renderScreen();
    await loaded();
    const header = screen.getAllByRole("row")[0];
    expect(header.style.gridTemplateColumns).toBe("145px 350px 95px 230px minmax(270px, 1fr)");
    expect(within(header).getAllByRole("columnheader").at(-1)?.textContent).toContain("gestion.controlAlerts.reviewComment");
    const row = screen.getByRole("row", { name: /T-100/ });
    expect(within(row).getAllByRole("cell").at(-1)?.textContent).toBe("Reviewed against the original sale");
  });

  it("updates the final review comment cell after reviewing without losing row selection or scroll", async () => {
    renderScreen(managerPermissions);
    await loaded();
    const table = screen.getByRole("table", { name: "gestion.controlAlerts.chronologyList" });
    table.scrollTop = 75;
    const detail = await openDetail();
    fireEvent.change(within(detail).getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }), { target: { value: "Approved after checking receipt" } });
    fireEvent.click(within(detail).getByRole("button", { name: "gestion.controlAlerts.action.REVIEW" }));
    await waitFor(() => expect(within(table).getByText("Approved after checking receipt")).not.toBeNull());
    expect(screen.getByRole("row", { name: /T-100/ }).getAttribute("aria-selected")).toBe("true");
    expect(table.scrollTop).toBe(75);
  });

  it.each(["REVIEWED", "CLOSED", "DISMISSED"] as const)("reopens a %s alert and retains the earlier history", async (status) => {
    currentAlert = { ...currentAlert, status, version: 4, reviewComment: "Earlier decision",
      history: [{ newStatus: status, comment: "Earlier decision", changedAt: initialAlert.occurredAt }] };
    renderScreen(managerPermissions);
    const detail = await openDetail();
    fireEvent.change(within(detail).getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }), { target: { value: "Closed by mistake" } });
    fireEvent.click(within(detail).getByRole("button", { name: "gestion.controlAlerts.action.REOPEN" }));
    await waitFor(() => expect(api.transitionControlAlert).toHaveBeenCalledWith("alert-1", "REOPEN", "Closed by mistake", 4, "manager-token"));
    await waitFor(() => expect(within(detail).queryByRole("button", { name: "gestion.controlAlerts.action.REOPEN" })).toBeNull());
    expect(within(detail).getByRole("button", { name: "gestion.controlAlerts.action.REVIEW" }).hasAttribute("disabled")).toBe(false);
    expect(within(detail).getByText("Earlier decision")).not.toBeNull();
    expect(within(detail).getByText("gestion.controlAlerts.reopened")).not.toBeNull();
    expect(within(detail).getByText("Closed by mistake")).not.toBeNull();
    expect(screen.getByRole("row", { name: /T-100/ }).textContent).toContain("gestion.controlAlerts.status.NEW");
  });

  it.each(["REVIEWED", "CLOSED", "DISMISSED"] as const)("does not offer reopening a %s alert to a reader", async (status) => {
    currentAlert = { ...currentAlert, status };
    renderScreen(readPermissions);
    const detail = await openDetail();
    expect(within(detail).queryByRole("button", { name: "gestion.controlAlerts.action.REOPEN" })).toBeNull();
    expect(api.transitionControlAlert).not.toHaveBeenCalled();
  });

  it.each(["es", "en", "zh"] as const)("localizes reopening and its history in %s", async (locale) => {
    currentAlert = { ...currentAlert, status: "CLOSED" };
    const t = createTranslator(locale);
    renderScreen(managerPermissions, t, locale);
    const detail = await openDetail(t);
    fireEvent.click(within(detail).getByRole("button", { name: t("gestion.controlAlerts.action.REOPEN") }));
    expect(await within(detail).findByText(t("gestion.controlAlerts.reopened"))).not.toBeNull();
    expect(detail.textContent).not.toContain("gestion.controlAlerts.");
  });

  it("blocks another reopening while saving and retains the comment if it fails", async () => {
    currentAlert = { ...currentAlert, status: "DISMISSED" };
    const pending = deferred<api.ControlAlert>();
    vi.mocked(api.transitionControlAlert).mockReturnValueOnce(pending.promise);
    renderScreen(managerPermissions);
    const detail = await openDetail();
    const reopen = within(detail).getByRole("button", { name: "gestion.controlAlerts.action.REOPEN" });
    fireEvent.change(within(detail).getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }), { target: { value: "Check again" } });
    fireEvent.click(reopen);
    fireEvent.click(reopen);
    expect(reopen.hasAttribute("disabled")).toBe(true);
    expect(api.transitionControlAlert).toHaveBeenCalledTimes(1);
    await act(async () => pending.reject(new Error("offline")));
    await waitFor(() => expect(reopen.hasAttribute("disabled")).toBe(false));
    expect((within(detail).getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }) as HTMLTextAreaElement).value).toBe("Check again");
    expect(within(detail).getByText("gestion.controlAlerts.detailError")).not.toBeNull();
  });

  it("keeps the alert open when closing the nested document and uses readable identities", async () => {
    currentAlert = { ...currentAlert, terminalName: "Front counter", assigneeId: "internal-assignee", assigneeName: "Marta",
      history: [{ changedAt: initialAlert.occurredAt, changedBy: "internal-actor", changedByName: "Lucía", newStatus: "REVIEWED", comment: "Checked" }],
      workHistory: [{ changedAt: initialAlert.occurredAt, changedBy: "internal-work-actor", changedByName: "Diego", previousPriority: "MEDIUM", newPriority: "HIGH", newAssigneeId: "internal-assignee", newAssigneeName: "Marta" }],
      data: { changedLines: [{ position: 1, productId: "internal-product", name: "Coffee", code: "COF-1", originalPrice: 10, appliedPrice: 12, changePercent: -20 }] }
    };
    vi.mocked(api.loadRelatedDocument).mockResolvedValue({ id: "doc-1", number: "T-100", type: "TICKET", status: "CONFIRMED", date: "2026-09-16", customerId: "internal-customer", customerName: "Client Shop", globalDiscount: 0, baseTotal: 10, taxTotal: 2, total: 12, currency: "EUR", lines: [], payments: [] });
    renderScreen([...managerPermissions, "GESTION_VENTAS"]);
    const detail = await openDetail();
    expect(within(detail).getByText("Front counter")).not.toBeNull();
    expect(within(detail).getByText("Lucía")).not.toBeNull();
    expect(within(detail).getByText("Diego")).not.toBeNull();
    expect(within(detail).getByText(/Coffee.*COF-1/)).not.toBeNull();
    expect(detail.textContent).not.toMatch(/internal-|terminal-1|alert-1|doc-1/);
    const openDocument = within(detail).getByRole("button", { name: "gestion.controlAlerts.openDocument" });
    openDocument.focus();
    fireEvent.click(openDocument);
    const documentDialog = await screen.findByRole("dialog", { name: /T-100/ });
    expect(detail.hasAttribute("inert")).toBe(true);
    expect(within(documentDialog).getByText("Client Shop")).not.toBeNull();
    expect(documentDialog.textContent).not.toContain("internal-customer");
    await waitFor(() => expect(document.activeElement).toBe(within(documentDialog).getByRole("button", { name: "common.close" })));
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /T-100/ })).toBeNull());
    expect(screen.getByRole("dialog", { name: "gestion.controlAlerts.detail" })).toBe(detail);
    expect(detail.hasAttribute("inert")).toBe(false);
    expect(document.activeElement).toBe(openDocument);
  });

  it.each(["es", "en", "zh"] as const)("uses localized unavailable identities instead of UUIDs in %s", async (locale) => {
    currentAlert = { ...currentAlert, documentId: "missing-document-uuid", documentNumber: null, userName: null, terminalId: "missing-terminal-uuid", assigneeId: "missing-assignee-uuid", status: "CLOSED",
      history: [{ changedAt: initialAlert.occurredAt, changedBy: "missing-actor-uuid", newStatus: "CLOSED" }],
      data: { lines: [{ productId: "missing-product-uuid", quantity: 1, total: 10 }] }
    };
    const t = createTranslator(locale);
    renderScreen(managerPermissions, t, locale);
    const row = await screen.findByRole("row", { name: new RegExp(t("gestion.controlAlerts.noDocument")) });
    fireEvent.doubleClick(row);
    const detail = await screen.findByRole("dialog", { name: t("gestion.controlAlerts.detail") });
    await within(detail).findByText(t("gestion.controlAlerts.unknownTerminal"));
    expect(within(detail).getAllByText(t("gestion.controlAlerts.unknownUser")).length).toBeGreaterThanOrEqual(2);
    expect(detail.textContent).not.toContain("-uuid");
    expect(detail.textContent).not.toContain("gestion.controlAlerts.");
  });

  it("uses a type indicator to filter the global list and applies status filters to group counts", async () => {
    renderScreen();
    await loaded();
    const strip = screen.getByRole("region", { name: "gestion.controlAlerts.periodTypes" });
    fireEvent.click(within(strip).getByRole("button", { name: /Ticket cancelled/ }));
    await waitFor(() => expect(api.loadControlAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ type: "TICKET_CANCELLED", page: 0 }), "manager-token", expect.any(AbortSignal)));
    fireEvent.change(screen.getByRole("combobox", { name: "gestion.controlAlerts.filterStatus" }), { target: { value: "NEW" } });
    await waitFor(() => expect(api.loadControlAlertGroups).toHaveBeenLastCalledWith(expect.any(String), expect.any(String), "manager-token", expect.any(AbortSignal), expect.objectContaining({ status: "NEW" })));
    fireEvent.click(within(strip).getByRole("button", { name: /gestion.controlAlerts.all/ }));
    await waitFor(() => expect(api.loadControlAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ type: "" }), "manager-token", expect.any(AbortSignal)));
  });

  it("keeps valid rows visible when a refresh fails", async () => {
    renderScreen();
    await loaded();
    vi.mocked(api.loadControlAlerts).mockRejectedValueOnce(new Error("offline"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.refresh" }));
    expect(await screen.findByText("gestion.controlAlerts.loadError")).not.toBeNull();
    const list = screen.getByRole("region", { name: "gestion.controlAlerts.list" });
    expect(within(list).getByText("T-100")).not.toBeNull();
    fireEvent.click(within(list).getByRole("button", { name: "gestion.controlAlerts.retry" }));
    await waitFor(() => expect(screen.queryByText("gestion.controlAlerts.loadError")).toBeNull());
  });

  it("ignores an older list response after filters change", async () => {
    renderScreen();
    await loaded();
    const older = deferred<api.ControlAlertPage>();
    vi.mocked(api.loadControlAlerts).mockImplementationOnce(() => older.promise).mockResolvedValueOnce(page([{ ...initialAlert, id: "current", documentNumber: "CURRENT", status: "REVIEWED" }]));
    const status = screen.getByRole("combobox", { name: "gestion.controlAlerts.filterStatus" });
    fireEvent.change(status, { target: { value: "NEW" } });
    await waitFor(() => expect(api.loadControlAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ status: "NEW" }), "manager-token", expect.any(AbortSignal)));
    fireEvent.change(status, { target: { value: "REVIEWED" } });
    expect(await screen.findByText("CURRENT")).not.toBeNull();
    await act(async () => older.resolve(page([{ ...initialAlert, documentNumber: "STALE" }])));
    expect(screen.queryByText("STALE")).toBeNull();
    expect(screen.getByText("CURRENT")).not.toBeNull();
  });

  it("refreshes group counts for the latest date range while a previous request is pending", async () => {
    renderScreen();
    await loaded();
    const older = deferred<api.ControlRuleAlertGroup[]>();
    vi.mocked(api.loadControlAlertGroups).mockImplementationOnce(() => older.promise).mockResolvedValueOnce([{ ...group, total: 7 }]);
    const from = screen.getByLabelText("gestion.controlAlerts.from");
    const to = screen.getByLabelText("gestion.controlAlerts.to");
    fireEvent.change(from, { target: { value: "2026-08-01" } });
    fireEvent.change(to, { target: { value: "2026-08-05" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.applyDates" }));
    await waitFor(() => expect(api.loadControlAlertGroups).toHaveBeenLastCalledWith("2026-07-31T23:00:00.000Z", "2026-08-05T23:00:00.000Z", "manager-token", expect.any(AbortSignal), expect.any(Object)));
    fireEvent.change(from, { target: { value: "2026-08-10" } });
    fireEvent.change(to, { target: { value: "2026-08-15" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.applyDates" }));
    const strip = screen.getByRole("region", { name: "gestion.controlAlerts.periodTypes" });
    await waitFor(() => expect(within(strip).getAllByText("7")).toHaveLength(2));
    await act(async () => older.resolve([{ ...group, total: 99 } ]));
    expect(within(strip).queryByText("99")).toBeNull();
  });

  it("preserves selection and reloads the detail when its server version changes", async () => {
    renderScreen(managerPermissions);
    await loaded();
    await openDetail();
    currentAlert = { ...currentAlert, priority: "CRITICAL", version: 2 };
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.refresh" }));
    const detail = screen.getByRole("dialog", { name: "gestion.controlAlerts.detail" });
    await waitFor(() => expect((within(detail).getByRole("combobox", { name: "gestion.controlAlerts.priorityLabel" }) as HTMLSelectElement).value).toBe("CRITICAL"));
    fireEvent.change(screen.getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }), { target: { value: "Checked evidence" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.action.REVIEW" }));
    await waitFor(() => expect(api.transitionControlAlert).toHaveBeenCalledWith("alert-1", "REVIEW", "Checked evidence", 2, "manager-token"));
  });

  it("reloads a conflict without silently retrying the write or dropping the review comment", async () => {
    renderScreen(managerPermissions);
    await openDetail();
    vi.mocked(api.transitionControlAlert).mockImplementationOnce(async () => {
      currentAlert = { ...currentAlert, version: 3 };
      throw new ApiError("stale", 409);
    });
    fireEvent.change(screen.getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }), { target: { value: "Keep this comment" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.action.REVIEW" }));
    expect(await screen.findByText("gestion.controlAlerts.versionConflict")).not.toBeNull();
    await waitFor(() => expect(api.loadControlAlert).toHaveBeenCalledTimes(2));
    expect(api.transitionControlAlert).toHaveBeenCalledTimes(1);
    expect((screen.getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }) as HTMLTextAreaElement).value).toBe("Keep this comment");
    await waitFor(() => expect(screen.getByRole("button", { name: "gestion.controlAlerts.action.REVIEW" }).hasAttribute("disabled")).toBe(false));
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.action.REVIEW" }));
    await waitFor(() => expect(api.transitionControlAlert).toHaveBeenLastCalledWith("alert-1", "REVIEW", "Keep this comment", 3, "manager-token"));
  });

  it("saves operational assignment with the selected alert version", async () => {
    renderScreen(managerPermissions);
    const detail = await openDetail();
    fireEvent.change(within(detail).getByRole("combobox", { name: "gestion.controlAlerts.priorityLabel" }), { target: { value: "CRITICAL" } });
    fireEvent.click(within(detail).getByRole("button", { name: "gestion.controlAlerts.saveWork" }));
    await waitFor(() => expect(api.updateControlAlertWork).toHaveBeenCalledWith(expect.objectContaining({ id: "alert-1", version: 0 }), expect.objectContaining({ priority: "CRITICAL", assigneeId: null, dueAt: null }), "manager-token"));
  });

  it.each(["es", "en", "zh"] as const)("keeps an unavailable historical assignee readable but only allows reassignment or removal in %s", async (locale) => {
    currentAlert = { ...currentAlert, assigneeId: "seller", assigneeName: "VENDEDOR" };
    vi.mocked(api.loadControlAlertAssignees).mockResolvedValue([{ id: "supervisor", name: "SUPERVISOR", userName: "Supervisor" }]);
    const t = createTranslator(locale);
    renderScreen(managerPermissions, t, locale);
    const detail = await openDetail(t);
    expect(await within(detail).findByText(t("gestion.controlAlerts.assigneeUnavailable"))).not.toBeNull();
    const assignee = within(detail).getByRole("combobox", { name: t("gestion.controlAlerts.assigneeLabel") });
    expect(within(assignee).getByRole("option", { name: "VENDEDOR" }).hasAttribute("disabled")).toBe(true);
    fireEvent.change(within(detail).getByRole("combobox", { name: t("gestion.controlAlerts.priorityLabel") }), { target: { value: "HIGH" } });
    expect(within(detail).getByRole("button", { name: t("gestion.controlAlerts.saveWork") }).hasAttribute("disabled")).toBe(true);
    fireEvent.change(assignee, { target: { value: "" } });
    expect(within(detail).queryByText(t("gestion.controlAlerts.assigneeUnavailable"))).toBeNull();
    expect(within(assignee).queryByRole("option", { name: "VENDEDOR" })).toBeNull();
    fireEvent.click(within(detail).getByRole("button", { name: t("gestion.controlAlerts.saveWork") }));
    await waitFor(() => expect(api.updateControlAlertWork).toHaveBeenCalledWith(expect.objectContaining({ assigneeId: "seller" }), expect.objectContaining({ assigneeId: null, priority: "HIGH" }), "manager-token"));
  });

  it("assigns an alert only through the eligible options returned by the API", async () => {
    vi.mocked(api.loadControlAlertAssignees).mockResolvedValue([{ id: "supervisor", name: "SUPERVISOR", userName: "Supervisor" }]);
    renderScreen(managerPermissions);
    const detail = await openDetail();
    const assignee = within(detail).getByRole("combobox", { name: "gestion.controlAlerts.assigneeLabel" });
    expect(within(assignee).getAllByRole("option")).toHaveLength(2);
    fireEvent.change(assignee, { target: { value: "supervisor" } });
    fireEvent.click(within(detail).getByRole("button", { name: "gestion.controlAlerts.saveWork" }));
    await waitFor(() => expect(api.updateControlAlertWork).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ assigneeId: "supervisor" }), "manager-token"));
  });

  it("refreshes assignee eligibility when the user refreshes the alert list", async () => {
    currentAlert = { ...currentAlert, assigneeId: "supervisor", assigneeName: "SUPERVISOR" };
    vi.mocked(api.loadControlAlertAssignees).mockResolvedValue([{ id: "supervisor", name: "SUPERVISOR", userName: "Supervisor" }]);
    renderScreen(managerPermissions);
    const detail = await openDetail();
    expect(within(detail).queryByText("gestion.controlAlerts.assigneeUnavailable")).toBeNull();
    vi.mocked(api.loadControlAlertAssignees).mockResolvedValue([]);
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.refresh" }));
    expect(await within(detail).findByText("gestion.controlAlerts.assigneeUnavailable")).not.toBeNull();
    expect(within(detail).getByRole("option", { name: "SUPERVISOR" }).hasAttribute("disabled")).toBe(true);
  });

  it.each(["es", "en", "zh"] as const)("reports an assignee load failure and retries without mistaking an empty list for failure in %s", async (locale) => {
    const retry = deferred<api.ControlAlertAssignee[]>();
    vi.mocked(api.loadControlAlertAssignees).mockRejectedValueOnce(new Error("offline")).mockImplementationOnce(() => retry.promise);
    const t = createTranslator(locale);
    renderScreen(managerPermissions, t, locale);
    expect(await screen.findByText(t("gestion.controlAlerts.assigneesLoadError"))).not.toBeNull();
    const detail = await openDetail(t);
    expect(within(detail).getByText(t("gestion.controlAlerts.assigneesLoadError"))).not.toBeNull();
    expect(within(detail).queryByText(t("gestion.controlAlerts.assigneeUnavailable"))).toBeNull();
    const assignee = within(detail).getByRole("combobox", { name: t("gestion.controlAlerts.assigneeLabel") });
    expect(assignee.hasAttribute("disabled")).toBe(true);
    fireEvent.change(within(detail).getByRole("combobox", { name: t("gestion.controlAlerts.priorityLabel") }), { target: { value: "HIGH" } });
    const save = within(detail).getByRole("button", { name: t("gestion.controlAlerts.saveWork") });
    expect(save.hasAttribute("disabled")).toBe(true);
    fireEvent.click(save);
    expect(api.updateControlAlertWork).not.toHaveBeenCalled();
    expect(within(detail).getByRole("button", { name: t("gestion.controlAlerts.action.REVIEW") }).hasAttribute("disabled")).toBe(false);
    fireEvent.click(within(detail).getByRole("button", { name: t("gestion.controlAlerts.retry") }));
    await waitFor(() => expect(api.loadControlAlertAssignees).toHaveBeenCalledTimes(2));
    expect(assignee.hasAttribute("disabled")).toBe(true);
    expect(save.hasAttribute("disabled")).toBe(true);
    await act(async () => retry.resolve([]));
    await waitFor(() => expect(assignee.hasAttribute("disabled")).toBe(false));
    expect(within(detail).queryByText(t("gestion.controlAlerts.assigneesLoadError"))).toBeNull();
    expect(within(assignee).getAllByRole("option")).toHaveLength(1);
    expect(api.loadControlAlerts).toHaveBeenCalledTimes(1);
    expect(save.hasAttribute("disabled")).toBe(false);
    fireEvent.click(save);
    await waitFor(() => expect(api.updateControlAlertWork).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ priority: "HIGH", assigneeId: null }), "manager-token"));
  });

  it("retains the assignee and draft after a refresh failure but requires a successful retry before saving", async () => {
    vi.mocked(api.loadControlAlertAssignees).mockResolvedValue([{ id: "supervisor", name: "SUPERVISOR", userName: "Supervisor" }]);
    renderScreen(managerPermissions);
    const detail = await openDetail();
    const assignee = within(detail).getByRole("combobox", { name: "gestion.controlAlerts.assigneeLabel" });
    await waitFor(() => expect(assignee.hasAttribute("disabled")).toBe(false));
    fireEvent.change(assignee, { target: { value: "supervisor" } });
    vi.mocked(api.loadControlAlertAssignees).mockRejectedValueOnce(new Error("offline"));
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.refresh" }));
    expect(await within(detail).findByText("gestion.controlAlerts.assigneesLoadError")).not.toBeNull();
    expect((assignee as HTMLSelectElement).value).toBe("supervisor");
    expect(within(assignee).getByRole("option", { name: "SUPERVISOR (Supervisor)" })).not.toBeNull();
    expect(assignee.hasAttribute("disabled")).toBe(true);
    const save = within(detail).getByRole("button", { name: "gestion.controlAlerts.saveWork" });
    expect(save.hasAttribute("disabled")).toBe(true);
    fireEvent.click(save);
    expect(api.updateControlAlertWork).not.toHaveBeenCalled();
    fireEvent.click(within(detail).getByRole("button", { name: "gestion.controlAlerts.retry" }));
    await waitFor(() => expect(assignee.hasAttribute("disabled")).toBe(false));
    expect((assignee as HTMLSelectElement).value).toBe("supervisor");
    expect(within(detail).queryByText("gestion.controlAlerts.assigneesLoadError")).toBeNull();
    expect(api.loadControlAlertAssignees).toHaveBeenCalledTimes(3);
    fireEvent.click(save);
    await waitFor(() => expect(api.updateControlAlertWork).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ assigneeId: "supervisor" }), "manager-token"));
  });

  it("shows price evidence with positive variation for an increase and keeps actions before history", async () => {
    currentAlert = { ...currentAlert, type: "MANUAL_PRICE_CHANGED", data: { currency: "EUR", changedLines: [{ position: 1, productId: "p-1", originalPrice: 10, appliedPrice: 12, changePercent: -20 }] } };
    renderScreen(managerPermissions, createTranslator("es"));
    expect(await screen.findByText(/10,00.*→.*12,00/)).not.toBeNull();
    const detail = await openDetail(createTranslator("es"));
    expect(await within(detail).findByText(/\+2,00.*\+20 %/)).not.toBeNull();
    const review = within(detail).getByRole("button", { name: "Marcar revisada" });
    const history = within(detail).getByRole("heading", { name: "Historial" });
    expect(review.compareDocumentPosition(history) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it.each(["es", "en", "zh"] as const)("distinguishes the original deletion from delayed receipt in %s", async (locale) => {
    const deletedAt = "2026-09-16T10:30:00Z";
    const receivedAt = "2026-09-17T11:00:00Z";
    currentAlert = { ...currentAlert, type: "SALE_SCREEN_CLEARED", data: { lines: [
      { name: "Weighted product", quantity: -0.125, total: -1.25, deletedAt, receivedAt },
    ] } };
    const t = createTranslator(locale);
    renderScreen(managerPermissions, t, locale);
    await openDetail(t);
    const evidence = await screen.findByRole("region", { name: t("gestion.controlAlerts.evidence") });
    const deletion = within(evidence).getByText(`${t("gestion.controlAlerts.evidence.deletedAt")}:`, { exact: false });
    const receipt = within(evidence).getByText(`${t("gestion.controlAlerts.evidence.receivedAt")}:`, { exact: false });
    expect(deletion.querySelector("time")?.getAttribute("datetime")).toBe(deletedAt);
    expect(receipt.querySelector("time")?.getAttribute("datetime")).toBe(receivedAt);
    expect(deletion.textContent).not.toContain("T10:30");
    expect(receipt.textContent).not.toContain("T11:00");
  });
});

describe("rules and user preferences", () => {
  it("opens existing rule configuration from the modal and accepts a two-decimal threshold", async () => {
    renderScreen(managerPermissions);
    await loaded();
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.configureRules" }));
    const manager = await screen.findByRole("dialog", { name: "gestion.controlAlerts.configureRules" });
    await waitFor(() => expect(api.loadControlRuleCatalog).toHaveBeenCalled());
    const add = within(manager).getAllByRole("button", { name: "gestion.controlRules.add" })[0];
    await waitFor(() => expect(add.hasAttribute("disabled")).toBe(false));
    fireEvent.click(add);
    const editor = await screen.findByRole("dialog", { name: "gestion.controlRules.add" });
    fireEvent.click(within(editor).getByRole("button", { name: /Manual discount/ }));
    fireEvent.change(within(editor).getByRole("spinbutton", { name: "gestion.controlRules.threshold" }), { target: { value: "0.29" } });
    fireEvent.click(within(editor).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(api.saveControlRule).toHaveBeenCalledWith({ type: "MANUAL_DISCOUNT_OVER_PERCENT", active: false, configuration: { thresholdPercent: .29 } }, null, "manager-token"));
  });

  it("makes rule load errors explicit and permits recovery instead of reporting all rules configured", async () => {
    vi.mocked(api.loadControlRules).mockRejectedValueOnce(new Error("offline"));
    renderScreen(managerPermissions);
    await loaded();
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.configureRules" }));
    const manager = await screen.findByRole("dialog", { name: "gestion.controlAlerts.configureRules" });
    expect(await within(manager).findByText("gestion.controlAlerts.rulesLoadError")).not.toBeNull();
    expect(within(manager).queryByText("gestion.controlRules.allConfigured")).toBeNull();
    expect(within(manager).getAllByRole("button", { name: "gestion.controlRules.add" })[0].hasAttribute("disabled")).toBe(true);
    fireEvent.click(within(manager).getByRole("button", { name: "gestion.controlAlerts.retry" }));
    await waitFor(() => expect(within(manager).queryByText("gestion.controlAlerts.rulesLoadError")).toBeNull());
  });

  it("persists a customized view through the user API and only applies it after save", async () => {
    renderScreen();
    await loaded();
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.personalize" }));
    const dialog = screen.getByRole("dialog", { name: "gestion.controlAlerts.personalize" });
    expect(within(dialog).queryByRole("checkbox", { name: "gestion.controlAlerts.preference.showDetail" })).toBeNull();
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "gestion.controlAlerts.preference.groupByDay" }));
    expect(document.querySelector(".gestion-alert-day")).not.toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(api.saveControlAlertViewPreference).toHaveBeenCalledWith(expect.objectContaining({ groupByDay: false, showDetail: true, sortBy: "occurredAt" }), "manager-token"));
    await waitFor(() => expect(document.querySelector(".gestion-alert-day")).toBeNull());
    expect(await openDetail()).not.toBeNull();
  });

  it("does not overwrite existing preferences with defaults when their initial load fails", async () => {
    vi.mocked(api.loadControlAlertViewPreference).mockRejectedValueOnce(new Error("offline"));
    renderScreen();
    expect(await screen.findByText("gestion.controlAlerts.preferenceError")).not.toBeNull();
    expect(screen.getByRole("button", { name: "gestion.controlAlerts.personalize" }).hasAttribute("disabled")).toBe(true);
    expect(api.saveControlAlertViewPreference).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.retry" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "gestion.controlAlerts.personalize" }).hasAttribute("disabled")).toBe(false));
  });

  it("loads another user's preferences without reusing the previous user's view", async () => {
    vi.mocked(api.loadControlAlertViewPreference).mockResolvedValueOnce({ ...api.defaultControlAlertView, showDetail: false, showIndicators: false }).mockResolvedValueOnce({ ...api.defaultControlAlertView, showDetail: true, showIndicators: true });
    const { rerender } = renderScreen();
    await waitFor(() => expect(api.loadControlAlerts).toHaveBeenCalled());
    expect(screen.queryByRole("region", { name: "gestion.controlAlerts.periodTypes" })).toBeNull();
    const detail = await openDetail();
    fireEvent.click(within(detail).getByRole("button", { name: "common.close" }));
    rerender(<ControlAlertsScreen session={session(readPermissions, "second")} t={(key) => key} />);
    await waitFor(() => expect(api.loadControlAlertViewPreference).toHaveBeenLastCalledWith("second-token", expect.any(AbortSignal)));
    expect(await screen.findByRole("region", { name: "gestion.controlAlerts.periodTypes" })).not.toBeNull();
    expect(screen.queryByRole("dialog", { name: "gestion.controlAlerts.detail" })).toBeNull();
  });
});

describe("rule decimals and store dates", () => {
  it("interprets due dates in the store timezone and rejects nonexistent DST times", () => {
    expect(controlAlertDueAtToInstant("2026-09-16T10:30", "Asia/Shanghai")).toBe("2026-09-16T02:30:00.000Z");
    expect(() => controlAlertDueAtToInstant("2026-03-29T01:30", "Atlantic/Canary")).toThrow(RangeError);
  });
  it.each([0, .07, .29, 8.03, 99.99, 100])("accepts the exact two-decimal percentage %s", (thresholdPercent) => {
    expect(validRuleDraft(catalog[1], { type: "MANUAL_DISCOUNT_OVER_PERCENT", active: true, configuration: { thresholdPercent } })).toBe(true);
  });
  it.each([-.01, 100.01, .001, 12.345, 1e-12, NaN, Infinity])("rejects invalid percentage %s", (thresholdPercent) => {
    expect(validRuleDraft(catalog[1], { type: "MANUAL_DISCOUNT_OVER_PERCENT", active: true, configuration: { thresholdPercent } })).toBe(false);
  });
  it("uses the store timezone rather than the browser timezone", () => {
    expect(dateRangeToInstants({ from: "2026-09-16", to: "2026-09-16" }, "Asia/Shanghai")).toEqual({ from: "2026-09-15T16:00:00.000Z", to: "2026-09-16T16:00:00.000Z" });
  });
  it("keeps inclusive/exclusive day boundaries across the spring DST change", () => {
    expect(dateRangeToInstants({ from: "2026-03-29", to: "2026-03-29" }, "Atlantic/Canary")).toEqual({ from: "2026-03-29T00:00:00.000Z", to: "2026-03-29T23:00:00.000Z" });
  });
  it("keeps the full 25-hour day across the autumn DST change", () => {
    expect(dateRangeToInstants({ from: "2026-10-25", to: "2026-10-25" }, "Atlantic/Canary")).toEqual({ from: "2026-10-24T23:00:00.000Z", to: "2026-10-26T00:00:00.000Z" });
  });
});
