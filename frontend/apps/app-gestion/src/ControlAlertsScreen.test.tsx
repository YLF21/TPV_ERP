// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { ControlAlertsScreen } from "./ControlAlertsScreen";
import * as api from "./controlAlertsApi";

vi.mock("./controlAlertsApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./controlAlertsApi")>();
  return {
    ...original,
    loadControlAlertGroups: vi.fn(),
    loadControlAlerts: vi.fn(),
    loadControlAlert: vi.fn(),
    transitionControlAlert: vi.fn(),
    loadRelatedDocument: vi.fn(),
    loadControlRuleCatalog: vi.fn(),
    loadControlRules: vi.fn(),
    saveControlRule: vi.fn(),
    setControlRuleActive: vi.fn()
  };
});

const alert: api.ControlAlert = {
  id: "alert-1",
  type: "TICKET_CANCELLED",
  status: "NEW",
  occurredAt: "2026-07-18T10:30:00Z",
  documentId: "doc-1",
  documentNumber: "T-100",
  ruleId: "rule-1",
  userName: "cashier",
  terminalId: "terminal-1",
  data: { terminalCode: "POS-1", reason: "Error" },
  version: 0
};

const group: api.ControlRuleAlertGroup = {
  ruleId: "rule-1",
  type: "TICKET_CANCELLED",
  ruleName: "Anulación de ticket",
  active: true,
  supported: true,
  total: 8,
  newCount: 3,
  reviewedCount: 2,
  closedCount: 2,
  dismissedCount: 1,
  parameterKind: "NONE",
  configuration: {}
};

const configuredRule: api.ControlRule = {
  id: "rule-1",
  type: "TICKET_CANCELLED",
  name: "Anulación de ticket",
  active: true,
  configuration: {},
  ruleVersion: 1,
  version: 0
};

const catalog: api.ControlRuleCatalogItem[] = [
  { type: "TICKET_CANCELLED", name: "Anulación de ticket", parameterKind: "NONE", defaultConfiguration: {}, supported: true, configured: true, ruleId: "rule-1" },
  { type: "CONSECUTIVE_LINE_DELETIONS", name: "Eliminación de líneas consecutivas", parameterKind: "QUANTITY", defaultConfiguration: { minimumCount: 3 }, supported: true, configured: false, ruleId: null },
  { type: "MANUAL_PRICE_CHANGED", name: "Cambio manual de precio", parameterKind: "NONE", defaultConfiguration: {}, supported: false, configured: false, ruleId: null }
];

function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "manager", displayName: "MANAGER", accessToken: "token", permissions };
}

function renderScreen(
  permissions: UserSession["permissions"],
  t: (key: string) => string = (key) => key
) {
  return render(
    <ControlAlertsScreen
      session={session(permissions)}
      t={t}
    />
  );
}

beforeEach(() => {
  vi.mocked(api.loadControlAlertGroups).mockResolvedValue([group]);
  vi.mocked(api.loadControlAlerts).mockResolvedValue({ items: [alert], page: 0, size: 25, totalElements: 1, totalPages: 1 });
  vi.mocked(api.loadControlAlert).mockResolvedValue(alert);
  vi.mocked(api.transitionControlAlert).mockResolvedValue({ ...alert, status: "REVIEWED", version: 1 });
  vi.mocked(api.loadControlRuleCatalog).mockResolvedValue(catalog);
  vi.mocked(api.loadControlRules).mockResolvedValue([configuredRule]);
  vi.mocked(api.saveControlRule).mockResolvedValue({ id: "rule-2", type: "CONSECUTIVE_LINE_DELETIONS", name: "Eliminación de líneas consecutivas", active: false, configuration: { minimumCount: 4 }, ruleVersion: 1, version: 0 });
  vi.mocked(api.setControlRuleActive).mockResolvedValue({ ...configuredRule, active: false, version: 1 });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 404 })));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("ControlAlertsScreen", () => {
  it("loads only readable group data for CONTROL_ALERTS_READ and opens through the visible button", async () => {
    renderScreen(["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"]);

    expect(await screen.findByText("Anulación de ticket")).not.toBeNull();
    expect(screen.getByText("8")).not.toBeNull();
    expect(screen.getByText("3")).not.toBeNull();
    expect(api.loadControlRuleCatalog).not.toHaveBeenCalled();
    expect(api.loadControlRules).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "gestion.controlAlerts.openList" }));
    await waitFor(() => expect(api.loadControlAlerts).toHaveBeenCalledWith(expect.objectContaining({ ruleId: "rule-1", from: expect.any(String), to: expect.any(String) }), "token"));
    expect(await screen.findByText("cashier")).not.toBeNull();
  });

  it.each([
    ["double click", (element: HTMLElement) => fireEvent.doubleClick(element)],
    ["Enter", (element: HTMLElement) => fireEvent.keyDown(element, { key: "Enter" })]
  ])("opens a rule list with %s", async (_label, activate) => {
    renderScreen(["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"]);
    const card = (await screen.findByText("Anulación de ticket")).closest("article");
    expect(card).not.toBeNull();
    activate(card as HTMLElement);
    await waitFor(() => expect(api.loadControlAlerts).toHaveBeenCalledWith(expect.objectContaining({ ruleId: "rule-1" }), "token"));
  });

  it("shows only missing catalog types and keeps unsupported rules disabled", async () => {
    renderScreen(["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ", "CONTROL_RULES_MANAGE"]);
    await waitFor(() => expect(api.loadControlRuleCatalog).toHaveBeenCalledWith("token"));

    fireEvent.click(screen.getAllByRole("button", { name: "gestion.controlRules.add" })[0]);
    const dialog = await screen.findByRole("dialog", { name: "gestion.controlRules.add" });
    expect(dialog.querySelector("input[type='text']")).toBeNull();
    expect(screen.queryByRole("button", { name: /Anulación de ticket/ })).toBeNull();
    expect(screen.getByRole("button", { name: /Cambio manual de precio/ }).hasAttribute("disabled")).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: /Eliminación de líneas consecutivas/ }));
    const minimum = screen.getByRole("spinbutton", { name: "gestion.controlRules.minimumCount" });
    fireEvent.change(minimum, { target: { value: "4" } });
    fireEvent.click(screen.getByRole("button", { name: "common.save" }));

    await waitFor(() => expect(api.saveControlRule).toHaveBeenCalledWith({ type: "CONSECUTIVE_LINE_DELETIONS", active: false, configuration: { minimumCount: 4 } }, null, "token"));
  });

  it("keeps alert workflow actions but never offers deletion", async () => {
    renderScreen(["APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE"]);
    fireEvent.click(await screen.findByRole("button", { name: "gestion.controlAlerts.openList" }));
    const review = await screen.findByRole("button", { name: "gestion.controlAlerts.action.REVIEW" });
    fireEvent.change(screen.getByRole("textbox", { name: "gestion.controlAlerts.actionComment" }), { target: { value: "camera checked" } });
    fireEvent.click(review);
    await waitFor(() => expect(api.transitionControlAlert).toHaveBeenCalledWith("alert-1", "REVIEW", "camera checked", 0, "token"));
    expect(screen.queryByText(/delete|eliminar/i)).toBeNull();
  });

  it("counts the manual product discounts returned by the backend evidence", async () => {
    vi.mocked(api.loadControlAlert).mockResolvedValue({
      ...alert,
      type: "PRODUCT_DISCOUNT_APPLIED",
      data: { discountedLines: [{ position: 1 }, { position: 2 }] }
    });
    renderScreen(
      ["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"],
      (key) => key === "gestion.controlAlerts.summaryProductDiscount" ? "{count} descuentos" : key
    );

    fireEvent.click(await screen.findByRole("button", { name: "gestion.controlAlerts.openList" }));

    expect(await screen.findByText("2 descuentos")).not.toBeNull();
  });
});
