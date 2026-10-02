// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { apiRequest } from "../api/client";
import { CashActivityPanel } from "./CashActivityPanel";

vi.mock("./tableLayoutPreferences", async () => ({
  ...await vi.importActual<typeof import("./tableLayoutPreferences")>("./tableLayoutPreferences"),
  loadTablePreference: vi.fn().mockResolvedValue(null),
  saveTablePreference: vi.fn(async (app, tableKey, columns) => ({ app, tableKey, columns })),
}));

afterEach(() => { cleanup(); localStorage.clear(); vi.clearAllMocks(); });

const timeline = { businessDate: "2026-10-01", timezone: "Atlantic/Canary", items: [
  { id: "entry", occurredAt: "2026-10-01T12:00:00Z", userId: "u1", username: "ana", userName: "Ana", action: "ENTRADA", concept: "Cambio", amount: 20, balance: 220, reference: "261001001", sessionId: "s1", cashState: "ABIERTA" },
  { id: "withdrawal", occurredAt: "2026-10-01T12:10:00Z", userId: "u2", username: "luis", userName: "Luis", action: "RETIRADA_ENTRE_SESIONES", concept: "Banco", amount: -10, balance: null, reference: "261001002", sessionId: null, cashState: "CERRADA" },
  { id: "payment", occurredAt: "2026-10-01T12:20:00Z", userId: "u1", username: "ana", userName: "Ana", action: "COBRO_EFECTIVO", concept: "Venta oculta", amount: 10, balance: 230, reference: "legacy", sessionId: "s1", cashState: "ABIERTA" },
] };

function renderPanel(request: ReturnType<typeof vi.fn> = vi.fn(async () => timeline)) {
  return { request, ...render(<CashActivityPanel locale="es" token="token" terminalId="t1" currentUsername="ana" request={request as unknown as typeof apiRequest} />) };
}

describe("CashActivityPanel", () => {
  it.each([
    { locale: "en" as const, tableName: "Today's history", opening: "Opening", closing: "Closing" },
    { locale: "zh" as const, tableName: "今日流水", opening: "开箱", closing: "关箱" },
  ])("localizes system concepts in $locale while preserving user comments", async ({ locale, tableName, opening, closing }) => {
    const request = vi.fn(async () => ({ ...timeline, items: [
      { ...timeline.items[0], id: "opening", action: "OPENING", concept: "Apertura", reference: "261001003" },
      { ...timeline.items[1], id: "closing", action: "CLOSING", concept: "Cierre", reference: "261001004" },
      timeline.items[0],
    ] }));
    render(<CashActivityPanel locale={locale} token="token" terminalId="t1" currentUsername="ana" request={request as unknown as typeof apiRequest} />);
    const table = await screen.findByRole("table", { name: tableName });
    const openingCell = (await within(table).findByText("261001003")).closest("tr")!.querySelector('[data-column-key="concept"]');
    const closingCell = within(table).getByText("261001004").closest("tr")!.querySelector('[data-column-key="concept"]');
    expect(openingCell).toHaveTextContent(opening);
    expect(closingCell).toHaveTextContent(closing);
    expect(within(table).getByText("Cambio")).toBeVisible();
  });

  it("loads activity in descending reference order, displays historical states, and excludes legacy cash payments", async () => {
    const { request } = renderPanel();
    const table = await screen.findByRole("table", { name: "Historial de hoy" });
    await within(table).findByText("261001001");
    expect(request).toHaveBeenCalledWith("/cash/timeline?terminalId=t1", { token: "token" });
    expect(Array.from(table.querySelectorAll('tbody td[data-column-key="reference"]'), cell => cell.textContent))
      .toEqual(["261001002", "261001001"]);
    expect(within(table).getByText("Cambio").closest("tr")).toHaveTextContent("Caja abierta");
    expect(within(table).getByText("Banco").closest("tr")).toHaveTextContent("Caja cerrada");
    expect(within(table).queryByText("Venta oculta")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Filtrar acción" }));
    expect(screen.queryByRole("option", { name: "Cobro en efectivo" })).toBeNull();
  });

  it("filters each criterion independently and restores rows by removing only its tag", async () => {
    renderPanel();
    const table = await screen.findByRole("table", { name: "Historial de hoy" });
    await within(table).findByText("Banco");
    fireEvent.click(screen.getByRole("button", { name: "Filtrar usuario" }));
    fireEvent.click(screen.getByRole("option", { name: "Ana" }));
    fireEvent.click(screen.getByRole("button", { name: "Filtrar estado" }));
    fireEvent.click(screen.getByRole("option", { name: "Caja cerrada" }));
    expect(within(table).getByText("SIN DATOS")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: /Quitar.*Estado/i }));
    expect(within(table).getByText("Cambio")).toBeVisible();
    expect(within(table).queryByText("Banco")).toBeNull();
  });

  it("refreshes manually, reports failure, and ignores a stale response after terminal change", async () => {
    let resolveOld!: (value: unknown) => void;
    const request = vi.fn((path: string) => path.includes("old") ? new Promise(resolve => { resolveOld = resolve; }) : Promise.resolve(timeline));
    const view = render(<CashActivityPanel locale="es" token="token" terminalId="old" request={request as unknown as typeof apiRequest} />);
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    view.rerender(<CashActivityPanel locale="es" token="token" terminalId="new" request={request as unknown as typeof apiRequest} />);
    await screen.findByText("261001001");
    resolveOld({ ...timeline, items: [{ ...timeline.items[0], reference: "obsolete" }] });
    await waitFor(() => expect(screen.queryByText("obsolete")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Actualizar" }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(3));
  });

  it("shows the server error and can recover with a manual refresh", async () => {
    const request = vi.fn().mockRejectedValueOnce(new Error("Consulta fallida")).mockResolvedValue(timeline);
    renderPanel(request);
    expect(await screen.findByRole("alert")).toHaveTextContent("Consulta fallida");
    expect(screen.getByText("SIN DATOS")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Actualizar" }));
    expect(await screen.findByText("261001001")).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
