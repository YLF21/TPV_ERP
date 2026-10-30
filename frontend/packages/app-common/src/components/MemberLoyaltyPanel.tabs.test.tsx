// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemberLoyaltyPanel, type MemberCategory, type MemberView } from "./MemberLoyaltyPanel";

const member: MemberView = {
  id: "m1", customerId: "c1", memberId: "MEM-001", numMember: "001",
  balance: 12, returnCreditBalance: 3, points: 25, categoryId: "silver",
  autoCategoryLocked: false, active: true
};
const categories: MemberCategory[] = [
  { id: "silver", code: "SILVER", name: "Plata", minPoints: 0, discountPercent: 0, discountEnabled: true, manualOnly: false, active: true, sortOrder: 1 },
  { id: "gold", code: "GOLD", name: "Oro", minPoints: 100, discountPercent: 5, discountEnabled: true, manualOnly: false, active: true, sortOrder: 2 }
];
const session = {
  accessToken: "token", refreshToken: "refresh", userId: "u", username: "member-tabs-test",
  displayName: "User", roles: [], permissions: ["CUSTOMERS_READ", "CUSTOMERS_WRITE"]
} as any;
const t = (key: string) => key;

function makeRequest(movements: unknown[] = []) {
  return vi.fn(async (path: string, options?: { method?: string }) => {
    if (options?.method) return { id: "saved" };
    if (path === "/members/m1") return member;
    if (path === "/members/m1/movements") return movements;
    if (path === "/member-categories") return categories;
    if (path.startsWith("/member-card-deliveries?")) return [];
    throw new Error(`Unexpected request: ${path}`);
  });
}

function show(request = makeRequest(), permissions = session.permissions, memberNumberEditor?: React.ReactNode) {
  render(<MemberLoyaltyPanel memberId="m1" session={{ ...session, permissions }} t={t} request={request as any} memberNumberEditor={memberNumberEditor} />);
  return request;
}

async function ready() {
  await screen.findByText("MEM-001");
}

function selectTab(name: "detail" | "movements" | "deliveries" | "adjustments") {
  fireEvent.click(screen.getByRole("tab", { name: `party.members.tab.${name}` }));
  return screen.getByRole("tabpanel");
}

afterEach(() => { cleanup(); localStorage.clear(); });

describe("MemberLoyaltyPanel tabs", () => {
  it("keeps the summary read-only and groups category, manual adjustment and supplied number editor in the last tab", async () => {
    show(makeRequest(), session.permissions, <div data-testid="number-editor"><input aria-label="member number editor" /></div>);
    await ready();
    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      "party.members.tab.detail", "party.members.tab.movements", "party.members.tab.deliveries", "party.members.tab.adjustments"
    ]);
    expect(screen.getByRole("tabpanel").querySelectorAll("form, input")).toHaveLength(0);
    expect(within(screen.getByRole("tabpanel")).queryByRole("button", { name: "common.save" })).toBeNull();
    expect(screen.queryByTestId("number-editor")).toBeNull();

    const panel = selectTab("adjustments");
    expect(within(panel).getByRole("heading", { name: "party.members.category" })).toBeTruthy();
    expect(within(panel).getByRole("heading", { name: "party.members.adjustmentsTitle" })).toBeTruthy();
    expect(within(panel).getByTestId("number-editor")).toBeTruthy();
    expect(panel.querySelectorAll("form")).toHaveLength(2);
  });

  it("moves tab selection and focus with Arrow, Home and End", async () => {
    show(); await ready();
    const tab = (name: string) => screen.getByRole("tab", { name: `party.members.tab.${name}` });
    tab("detail").focus();
    fireEvent.keyDown(tab("detail"), { key: "ArrowRight" });
    expect(tab("movements").getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(tab("movements"));
    fireEvent.keyDown(tab("movements"), { key: "End" });
    expect(tab("adjustments").getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(tab("adjustments"));
    fireEvent.keyDown(tab("adjustments"), { key: "Home" });
    expect(tab("detail").getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(tab("detail"));
    fireEvent.keyDown(tab("detail"), { key: "ArrowLeft" });
    expect(tab("adjustments").getAttribute("aria-selected")).toBe("true");
  });

  it("shows one movements table with headers and an empty row without a repeated title or count", async () => {
    show(); await ready();
    const panel = selectTab("movements");
    expect(within(panel).getAllByRole("table")).toHaveLength(1);
    expect(within(panel).getAllByRole("columnheader")).toHaveLength(4);
    expect(within(panel).getByText("party.members.noMovements")).toBeTruthy();
    expect(within(panel).queryByRole("heading")).toBeNull();
    expect(panel.querySelector("header")).toBeNull();
  });

  it("preserves the category PUT body and the manual points POST body", async () => {
    const request = show(); await ready();
    const panel = selectTab("adjustments");
    const categoryCard = within(panel).getByRole("heading", { name: "party.members.category" }).closest("section")!;
    fireEvent.click(within(categoryCard).getByRole("button", { name: "Plata" }));
    fireEvent.click(within(categoryCard).getByRole("option", { name: "Oro" }));
    fireEvent.change(within(categoryCard).getByRole("textbox"), { target: { value: "  Corrección  " } });
    fireEvent.click(within(categoryCard).getByRole("checkbox"));
    fireEvent.click(within(categoryCard).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/members/m1/category", {
      token: "token", method: "PUT", body: { categoryId: "gold", lockAutomatic: true, reason: "Corrección" }
    }));

    const adjustmentCard = within(panel).getByRole("heading", { name: "party.members.adjustmentsTitle" }).closest("section")!;
    fireEvent.change(within(adjustmentCard).getByRole("spinbutton"), { target: { value: "7" } });
    fireEvent.change(within(adjustmentCard).getByRole("textbox"), { target: { value: "  Bono  " } });
    fireEvent.click(within(adjustmentCard).getByRole("button", { name: "party.members.adjust" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/members/m1/points-adjustments", {
      token: "token", method: "POST", body: { points: 7, reason: "Bono" }
    }));
  });

  it("keeps amount and reason after a failed adjustment so it can be retried", async () => {
    const request = makeRequest();
    show(request); await ready();
    const panel = selectTab("adjustments");
    const card = within(panel).getByRole("heading", { name: "party.members.adjustmentsTitle" }).closest("section")!;
    const amount = within(card).getByRole("spinbutton") as HTMLInputElement;
    const reason = within(card).getByRole("textbox") as HTMLInputElement;
    fireEvent.change(amount, { target: { value: "5" } });
    fireEvent.change(reason, { target: { value: "Retry me" } });
    request.mockRejectedValueOnce(new Error("Temporary failure"));
    fireEvent.click(within(card).getByRole("button", { name: "party.members.adjust" }));
    await screen.findByRole("alert");
    expect(amount.value).toBe("5");
    expect(reason.value).toBe("Retry me");
    fireEvent.click(within(card).getByRole("button", { name: "party.members.adjust" }));
    await waitFor(() => expect(request.mock.calls.filter(([path]) => path === "/members/m1/points-adjustments")).toHaveLength(2));
    await waitFor(() => expect(amount.value).toBe(""));
    expect(reason.value).toBe("");
  });

  it("does not expose mutation forms to a read-only session", async () => {
    show(makeRequest(), ["CUSTOMERS_READ"]); await ready();
    const panel = selectTab("adjustments");
    expect(panel.querySelectorAll("form, input")).toHaveLength(0);
    expect(within(panel).getByText("party.members.adjustmentsReadOnly")).toBeTruthy();
  });
});
