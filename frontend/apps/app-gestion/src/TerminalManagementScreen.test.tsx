/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { apiRequest } from "@tpverp/app-common";
import {
  TerminalManagementScreen,
  terminalApprovePath,
  terminalDeactivatePath,
  terminalPairingPath,
  terminalDisplayStatus,
  workstationActionPath,
  type TerminalServerConnection
} from "./TerminalManagementScreen";

vi.mock("@tpverp/app-common", async () => ({ ...await vi.importActual("@tpverp/app-common"), apiRequest: vi.fn() }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.mocked(apiRequest).mockReset(); });

const admin = { username: "ADMIN", accessToken: "token", displayName: "ADMIN", permissions: ["ADMIN" as const] };
const key = (value: string) => value;
function backend(status = "PENDING", legacy = false) {
  const state: { status: string; connection: TerminalServerConnection; connectionError: boolean; slotIp: string | null; pdaIp: string | null; legacyIp: string | null } = {
    slotIp: "192.168.1.22", pdaIp: "192.168.1.33", legacyIp: "192.168.1.44",
    status, connection: { addresses: ["192.168.1.12", "10.0.0.7"], httpsPort: 18443, backendPort: 18080, publicUrl: "https://caja.local:18443" }, connectionError: false,
  };
  vi.mocked(apiRequest).mockImplementation(async (route, options) => {
    if (route === "/terminals/server-connection") {
      if (state.connectionError) throw new Error("Network unavailable");
      return state.connection;
    }
    if (route === "/terminals") return [{ id: "pda", name: "PDA cocina", type: "PDA", approved: false, active: false, lastIp: state.pdaIp }];
    if (route === "/terminals/workstations") return { maxWindows: 3,
      slots: [{ code: "001", status: "RESERVED" },
        { code: "002", status: state.status, name: "Caja terraza", deviceName: "PC-ANTIGUO", lastIp: state.slotIp, terminalId: "terminal", bindingId: state.status === "FREE" ? undefined : "binding-v1" },
        { code: "003", status: "FREE" }],
      legacyTerminals: legacy ? [{ id: "old", name: "Caja antigua", type: "TERMINAL_VENTA", active: true, approved: true, lastIp: state.legacyIp }] : [] };
    if (route.endsWith("/history")) return route.includes("/002/") ? [{ bindingId: "binding-v1", deviceName: "PC-ANTIGUO", status: state.status === "FREE" ? "RELEASED" : state.status, createdAt: "2026-09-01T10:00:00Z" }] : [];
    if (options?.method === "POST") { if (route.endsWith("/release")) state.status = "FREE"; if (route.endsWith("/approve")) state.status = "ACTIVE"; return {}; }
    throw new Error(`Unexpected API route ${route}`);
  });
  return state;
}

describe("TerminalManagementScreen", () => {
  function displayedTerminalIp() {
    return screen.getByText("gestion.terminals.lastIp").parentElement!.querySelector("dd")!.textContent;
  }

  it("shows the selected terminal IP and does not substitute a server address for missing IPs", async () => {
    const state = backend(); state.slotIp = null;
    render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /002 · Caja terraza/ }));
    expect(displayedTerminalIp()).toBe("—");
    const detail = screen.getByText("gestion.terminals.lastIp").closest("aside")!;
    expect(within(detail).queryByText("192.168.1.12")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /PDA cocina/ }));
    expect(displayedTerminalIp()).toBe("192.168.1.33");
    fireEvent.click(screen.getByRole("button", { name: /003 ·/ }));
    expect(displayedTerminalIp()).toBe("—");
    expect(screen.queryByText("gestion.terminals.loadError")).toBeNull();
  });

  it("reloads the IP for the selected workstation on refresh", async () => {
    const state = backend();
    render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /002 · Caja terraza/ }));
    expect(displayedTerminalIp()).toBe("192.168.1.22");
    await waitFor(() => expect((screen.getByRole("button", { name: "common.refresh" }) as HTMLButtonElement).disabled).toBe(false));
    state.slotIp = "2001:db8::22";
    fireEvent.click(screen.getByRole("button", { name: "common.refresh" }));
    await waitFor(() => expect(displayedTerminalIp()).toBe("2001:db8::22"));
    expect(screen.queryByText("192.168.1.22")).toBeNull();
  });

  it("shows each PDA and legacy IP in its own detail panel", async () => {
    backend("ACTIVE", true);
    render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /Caja antigua/ }));
    expect(displayedTerminalIp()).toBe("192.168.1.44");
    fireEvent.click(screen.getByRole("button", { name: /PDA cocina/ }));
    expect(displayedTerminalIp()).toBe("192.168.1.33");
    expect(screen.queryByText("192.168.1.44")).toBeNull();
  });

  it("renders a placeholder for PDA and legacy registrations without an observed IP", async () => {
    const state = backend("ACTIVE", true); state.pdaIp = null; state.legacyIp = null;
    render(<TerminalManagementScreen session={admin} t={key} />);
    fireEvent.click(await screen.findByRole("button", { name: /Caja antigua/ }));
    expect(displayedTerminalIp()).toBe("—");
    fireEvent.click(screen.getByRole("button", { name: /PDA cocina/ }));
    expect(displayedTerminalIp()).toBe("—");
  });

  it("shows every server address and actual ports with the configured URL as text", async () => {
    backend(); render(<TerminalManagementScreen session={admin} t={key} />);
    expect(await screen.findByText("192.168.1.12")).toBeTruthy();
    expect(screen.getByText("10.0.0.7")).toBeTruthy();
    expect(screen.getByText("18443")).toBeTruthy();
    expect(screen.getByText("18080")).toBeTruthy();
    expect(screen.getByText("https://caja.local:18443").tagName).toBe("DD");
    expect(screen.queryByRole("link")).toBeNull();
    expect(apiRequest).toHaveBeenCalledWith("/terminals/server-connection", { token: "token" });
  });

  it("does not invent an HTTPS port, public URL or IP when none is configured", async () => {
    const state = backend();
    state.connection = { addresses: [], httpsPort: null, backendPort: 18080, publicUrl: null };
    render(<TerminalManagementScreen session={admin} t={key} />);
    expect(await screen.findByText("18080")).toBeTruthy();
    expect(screen.getAllByText("gestion.terminals.serverConnection.notConfigured")).toHaveLength(2);
    expect(screen.getByText("gestion.terminals.serverConnection.noAddresses")).toBeTruthy();
    expect(screen.queryByText("8443")).toBeNull();
    expect(screen.queryByText("127.0.0.1")).toBeNull();
  });

  it("keeps terminal approval available when the connection query fails", async () => {
    const state = backend(); state.connectionError = true;
    render(<TerminalManagementScreen session={admin} t={key} />);
    expect(await screen.findByText("gestion.terminals.serverConnection.loadError")).toBeTruthy();
    fireEvent.click(await screen.findByRole("button", { name: /002 · Caja terraza/ }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.terminals.workstations.approve" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/terminals/workstations/002/approve", expect.objectContaining({ method: "POST" })));
    expect(screen.queryByText("gestion.terminals.loadError")).toBeNull();
  });

  it("reloads server addresses on refresh and clears stale data after failure", async () => {
    const state = backend(); render(<TerminalManagementScreen session={admin} t={key} />);
    await screen.findByText("192.168.1.12");
    state.connection = { ...state.connection, addresses: ["192.168.2.20"] };
    fireEvent.click(screen.getByRole("button", { name: "common.refresh" }));
    expect(await screen.findByText("192.168.2.20")).toBeTruthy();
    expect(screen.queryByText("192.168.1.12")).toBeNull();
    await waitFor(() => expect((screen.getByRole("button", { name: "common.refresh" }) as HTMLButtonElement).disabled).toBe(false));
    state.connectionError = true;
    fireEvent.click(screen.getByRole("button", { name: "common.refresh" }));
    await screen.findByText("gestion.terminals.serverConnection.loadError");
    expect(screen.queryByText("192.168.2.20")).toBeNull();
    expect(screen.queryByText("18443")).toBeNull();
  });

  it("ignores a late connection response from the previous session", async () => {
    const state = backend();
    const original = vi.mocked(apiRequest).getMockImplementation()!;
    let resolvePrevious!: (value: TerminalServerConnection) => void;
    const previous = new Promise<TerminalServerConnection>(resolve => { resolvePrevious = resolve; });
    vi.mocked(apiRequest).mockImplementation((route, options) => route === "/terminals/server-connection" && options?.token === "token" ? previous : original(route, options));
    const view = render(<TerminalManagementScreen session={admin} t={key} />);
    view.rerender(<TerminalManagementScreen session={{ ...admin, accessToken: "new-token" }} t={key} />);
    await screen.findByText("192.168.1.12");
    await act(async () => { resolvePrevious({ ...state.connection, addresses: ["10.99.99.99"] }); await previous; });
    expect(screen.queryByText("10.99.99.99")).toBeNull();
    expect(screen.getByText("192.168.1.12")).toBeTruthy();
  });

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
