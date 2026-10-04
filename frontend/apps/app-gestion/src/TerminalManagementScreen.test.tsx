/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { apiRequest } from "@tpverp/app-common";
import {
  TerminalManagementScreen,
  terminalApprovePath,
  terminalDeactivatePath,
  terminalPairingPath,
  terminalDisplayStatus,
  workstationActionPath
} from "./TerminalManagementScreen";

vi.mock("@tpverp/app-common", async () => ({ ...await vi.importActual("@tpverp/app-common"), apiRequest: vi.fn() }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.mocked(apiRequest).mockReset(); });

const admin = { username: "ADMIN", accessToken: "token", displayName: "ADMIN", permissions: ["ADMIN" as const] };
const key = (value: string) => value;
function backend(status = "PENDING", legacy = false) {
  const state = { status };
  vi.mocked(apiRequest).mockImplementation(async (route, options) => {
    if (route === "/terminals") return [{ id: "pda", name: "PDA cocina", type: "PDA", approved: false, active: false }];
    if (route === "/terminals/workstations") return { maxWindows: 3,
      slots: [{ code: "001", status: "RESERVED" },
        { code: "002", status: state.status, name: "Caja terraza", deviceName: "PC-ANTIGUO", terminalId: "terminal", bindingId: state.status === "FREE" ? undefined : "binding-v1" },
        { code: "003", status: "FREE" }],
      legacyTerminals: legacy ? [{ id: "old", name: "Caja antigua", type: "TERMINAL_VENTA", active: true, approved: true }] : [] };
    if (route.endsWith("/history")) return route.includes("/002/") ? [{ bindingId: "binding-v1", deviceName: "PC-ANTIGUO", status: state.status === "FREE" ? "RELEASED" : state.status, createdAt: "2026-09-01T10:00:00Z" }] : [];
    if (options?.method === "POST") { if (route.endsWith("/release")) state.status = "FREE"; if (route.endsWith("/approve")) state.status = "ACTIVE"; return {}; }
    throw new Error(`Unexpected API route ${route}`);
  });
  return state;
}

describe("TerminalManagementScreen", () => {
  it("approves the selected binding and refreshes its history", async () => {
    backend(); render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /002 · Caja terraza/ }));
    fireEvent.click(await screen.findByRole("button", { name: "gestion.terminals.workstations.approve" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/terminals/workstations/002/approve", {
      token: "token", method: "POST", body: { bindingId: "binding-v1" },
    }));
    await waitFor(() => expect(screen.getByRole("button", { name: "gestion.terminals.workstations.release" })).toBeTruthy());
    expect(vi.mocked(apiRequest).mock.calls.filter(([route]) => route === "/terminals/workstations/002/history").length).toBeGreaterThanOrEqual(2);
  });
  it("can release an offline active computer, with explicit confirmation and expected binding", async () => {
    backend("ACTIVE"); const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /002 · Caja terraza/ }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.terminals.workstations.release" }));
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("002 · Caja terraza\nPC-ANTIGUO"));
    expect(vi.mocked(apiRequest).mock.calls.some(([route]) => route.endsWith("/release"))).toBe(false);
    confirm.mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "gestion.terminals.workstations.release" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/terminals/workstations/002/release", {
      token: "token", method: "POST", body: { bindingId: "binding-v1" },
    }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "gestion.terminals.workstations.release" })).toBeNull());
  });
  it("reactivates a disabled binding without freeing its code", async () => {
    backend("DISABLED"); render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /002 · Caja terraza/ }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.terminals.workstations.reactivate" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/terminals/workstations/002/approve", expect.objectContaining({ body: { bindingId: "binding-v1" } })));
  });
  it("does not attach another legacy history to a released logical terminal", async () => {
    backend("FREE", true); render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /Caja antigua/ }));
    const select = screen.getByRole("combobox") as HTMLSelectElement;
    expect(Array.from(select.options).map(option => option.value)).toEqual(["", "003"]);
  });
  it("encodes terminal identifiers in mutation paths", () => {
    expect(terminalApprovePath("pda/1")).toBe("/terminals/pda%2F1/approve");
    expect(terminalDeactivatePath("pda/1")).toBe("/terminals/pda%2F1/deactivate");
    expect(terminalPairingPath("pda/1")).toBe("/terminals/pda%2F1/pairing-code");
    expect(workstationActionPath("00/2", "release")).toBe("/terminals/workstations/00%2F2/release");
  });

  it("shows an inactive unapproved registration as pending", () => {
    expect(terminalDisplayStatus({ approved: false, active: false })).toBe("pending");
    expect(terminalDisplayStatus({ approved: true, active: false })).toBe("inactive");
    expect(terminalDisplayStatus({ approved: true, active: true })).toBe("approved");
  });

  it("renders approval metrics for administrators", () => {
    const html = renderToStaticMarkup(
      <TerminalManagementScreen
        session={{ username: "ADMIN", accessToken: "token", displayName: "ADMIN", permissions: ["ADMIN"] }}
        t={(key) => key}
      />
    );

    expect(html).toContain("gestion.terminals.title");
    expect(html).toContain("gestion.terminals.pending");
    expect(html).toContain("gestion.terminals.workstations.capacity");
    expect(html).toContain("gestion.terminals.workstations.free");
  });
});
