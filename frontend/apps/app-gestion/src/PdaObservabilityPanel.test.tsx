/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { apiRequest } from "@tpverp/app-common";
import { PdaObservabilityPanel, pdaObservabilityPath, type PdaObservabilityView } from "./PdaObservabilityPanel";

vi.mock("@tpverp/app-common", async () => ({ ...await vi.importActual("@tpverp/app-common"), apiRequest: vi.fn() }));
afterEach(() => { cleanup(); vi.mocked(apiRequest).mockReset(); });

const t = (key: string) => key;
const data: PdaObservabilityView = {
  generatedAt: "2026-10-29T10:30:00Z",
  windowFrom: "2026-10-28T10:30:00Z",
  summary: { syncFailures: 2, syncConflicts: 1, blockedWork: 1, inactiveTerminals: 1, averageLatencyMs: 84, p95LatencyMs: 210 },
  syncFailures: [{ id: "event-1", terminalId: "pda-1", terminalName: "PDA almacén", code: "SYNC_REJECTED", workId: "count-14", occurredAt: "2026-10-29T10:20:00Z", traceId: "private-trace" }],
  blockedWork: [{ id: "event-2", terminalId: "pda-1", terminalName: "PDA almacén", code: "WORK_BLOCKED", workId: "receipt-5", occurredAt: "2026-10-29T10:25:00Z" }],
  inactiveTerminals: [{ terminalId: "pda-2", terminalName: "PDA tienda", lastSeenAt: "2026-10-28T08:00:00Z", inactiveSince: "2026-10-28T09:00:00Z" }],
  performance: { sampleCount: 12, averageLatencyMs: 84, p95LatencyMs: 210, maxLatencyMs: 430 },
  page: 0, size: 50, totalEvents: 3, totalPages: 1,
};

describe("PdaObservabilityPanel", () => {
  it("builds an encoded, bounded observability query", () => {
    expect(pdaObservabilityPath(24, "pda/almacén 1")).toBe("/pda-observability?windowHours=24&page=0&size=50&terminalId=pda%2Falmac%C3%A9n+1");
  });

  it("shows health indicators and switches between operational categories without exposing trace ids", async () => {
    vi.mocked(apiRequest).mockResolvedValue(data);
    render(<PdaObservabilityPanel token="token" terminals={[]} t={t} />);

    expect(await screen.findByText("SYNC_REJECTED")).toBeTruthy();
    expect(screen.getByText("210 ms")).toBeTruthy();
    expect(screen.queryByText(/private-trace/)).toBeNull();
    expect(screen.queryByText("count-14")).toBeNull();
    expect(screen.queryByRole("button", { name: "gestion.pdaObservability.next" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /gestion\.pdaObservability\.blocked/ }));
    expect(screen.getByText("WORK_BLOCKED")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /gestion\.pdaObservability\.inactive/ }));
    expect(within(screen.getByRole("table")).getByText("PDA tienda")).toBeTruthy();
  });

  it("reloads with the selected time range and terminal", async () => {
    vi.mocked(apiRequest).mockResolvedValue(data);
    render(<PdaObservabilityPanel token="token" terminals={[{ id: "pda/1", name: "PDA uno", type: "PDA", approved: true, active: true }]} t={t} />);
    await screen.findByText("SYNC_REJECTED");

    const selects = screen.getAllByRole("combobox");
    fireEvent.change(selects[0], { target: { value: "168" } });
    fireEvent.change(selects[1], { target: { value: "pda/1" } });
    await waitFor(() => expect(vi.mocked(apiRequest).mock.calls.some(([route]) => route === "/pda-observability?windowHours=168&page=0&size=50&terminalId=pda%2F1")).toBe(true));
  });

  it("only offers PDA terminals accepted by the observability endpoint", async () => {
    vi.mocked(apiRequest).mockResolvedValue(data);
    render(<PdaObservabilityPanel token="token" terminals={[
      { id: "pda-1", name: "PDA uno", type: "PDA", approved: true, active: true },
      { id: "server-1", name: "Servidor", type: "SERVIDOR", approved: true, active: true },
      { id: "sale-1", name: "Caja", type: "TERMINAL_VENTA", approved: true, active: true },
    ]} t={t} />);
    await screen.findByText("SYNC_REJECTED");
    const terminalOptions = within(screen.getAllByRole("combobox")[1]).getAllByRole("option").map(option => option.textContent);
    expect(terminalOptions).toEqual(expect.arrayContaining(["gestion.pdaObservability.allTerminals", "PDA uno", "PDA tienda"]));
    expect(terminalOptions).not.toContain("Servidor");
    expect(terminalOptions).not.toContain("Caja");
  });

  it("clears stale data when a changed filter fails", async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(data).mockRejectedValueOnce(new Error("offline"));
    render(<PdaObservabilityPanel token="token" terminals={[]} t={t} />);
    await screen.findByText("SYNC_REJECTED");
    fireEvent.change(screen.getAllByRole("combobox")[0], { target: { value: "168" } });
    await screen.findByRole("alert");
    expect(screen.queryByText("SYNC_REJECTED")).toBeNull();
    expect(screen.queryByText("210 ms")).toBeNull();
  });

  it("formats operational dates using the active locale and omits the false inactive-since value", async () => {
    vi.mocked(apiRequest).mockResolvedValue(data);
    const view = render(<PdaObservabilityPanel token="token" terminals={[]} t={t} locale="zh" />);
    await screen.findByText("SYNC_REJECTED");
    expect(view.container.textContent).toMatch(/2026[/.-]10[/.-]29/);
    fireEvent.click(screen.getByRole("button", { name: /gestion\.pdaObservability\.inactive/ }));
    expect(screen.queryByText("gestion.pdaObservability.inactiveSince")).toBeNull();
    const falseInactiveSince = new Intl.DateTimeFormat("zh-CN", { dateStyle: "short", timeStyle: "medium" }).format(new Date(data.inactiveTerminals[0].inactiveSince));
    expect(screen.queryByText(falseInactiveSince)).toBeNull();
  });

  it("offers an accessible retry after a backend failure", async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(data);
    render(<PdaObservabilityPanel token="token" terminals={[]} t={t} />);

    expect((await screen.findByRole("alert")).textContent).toContain("gestion.pdaObservability.loadError");
    fireEvent.click(screen.getByRole("button", { name: "gestion.pdaObservability.retry" }));
    expect(await screen.findByText("SYNC_REJECTED")).toBeTruthy();
  });

  it("renders a clear empty state", async () => {
    vi.mocked(apiRequest).mockResolvedValue({ ...data, syncFailures: [], summary: { ...data.summary, syncFailures: 0 } });
    render(<PdaObservabilityPanel token="token" terminals={[]} t={t} />);
    expect(await screen.findByText("gestion.pdaObservability.empty")).toBeTruthy();
  });
});
