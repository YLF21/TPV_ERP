// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { apiRequest } from "../api/client";
import { CashActivityPanel } from "./CashActivityPanel";
import { loadTablePreference, saveTablePreference } from "./tableLayoutPreferences";

vi.mock("./tableLayoutPreferences", async () => {
  const actual = await vi.importActual<typeof import("./tableLayoutPreferences")>("./tableLayoutPreferences");
  return {
    ...actual,
    loadTablePreference: vi.fn(async () => undefined),
    saveTablePreference: vi.fn(async (...[app, tableKey, columns]: Parameters<typeof actual.saveTablePreference>) => ({ app, tableKey, columns })),
  };
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.clearAllMocks();
});

const session = {
  id: "session-1", terminalId: "terminal-1", status: "ABIERTA",
  openedAt: "2026-10-01T08:00:00Z", openingFund: 200,
  expectedCash: 215, availableCash: 215, retainedFund: 5,
};
const timeline = {
  businessDate: "2026-10-01", timezone: "Atlantic/Canary", items: [
    { id: "movement-1", occurredAt: "2026-10-01T12:00:00Z", userId: "user-1", username: "ana", userName: "Ana", action: "ENTRADA", concept: "Cambio", amount: 20, balance: 220, reference: "261001001", cashState: "ABIERTA", sessionId: "session-1" },
    { id: "movement-2", occurredAt: "2026-10-01T12:10:00Z", userId: "user-2", username: "luis", userName: "Luis", action: "RETIRADA", concept: "Banco", amount: -10, balance: null, reference: "261001002", cashState: "CERRADA", sessionId: "session-1" },
  ],
};

function renderCard(username = "ana") {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith("/cash/status")) return session;
    if (path.startsWith("/cash/sessions/readiness")) return {
      cashSessionRequired: true, open: true, session,
      requireEntryBreakdown: false, entryDenominations: [],
      requireWithdrawalBreakdown: false, withdrawalDenominations: [],
      requireClosingBreakdown: false, closingDenominations: [],
    };
    if (path.startsWith("/cash/timeline")) return timeline;
    if (path === "/sales/operation-security") return { storeId: "store-1", version: 1, operations: [] };
    return undefined;
  });
  return render(<CashActivityPanel locale="es" token="token" currentUsername={username}
    terminalId="terminal-1" request={request as unknown as typeof apiRequest} />);
}

async function historyTable() {
  const table = await screen.findByRole("table", { name: "Historial de hoy" });
  await within(table).findByText("Cambio");
  await waitFor(() => expect(loadTablePreference).toHaveBeenCalledWith("venta", "cash.activity", "token"));
  return table;
}

function openColumns(table: HTMLElement, key = "hour") {
  const header = table.querySelector<HTMLElement>(`th[data-column-key="${key}"]`)!;
  fireEvent.click(within(header).getByRole("button", { name: "Opciones de columna" }));
}

function columnKeys(elements: NodeListOf<Element>) {
  return Array.from(elements, element => element.getAttribute("data-column-key"));
}

describe("Cash timeline column layout", () => {
  it("shows reference first and keeps visibility scoped to the operator", async () => {
    let panel = renderCard();
    let table = await historyTable();
    expect(within(table).getAllByRole("columnheader")).toHaveLength(8);
    expect(columnKeys(table.querySelectorAll("thead th"))[0]).toBe("reference");
    expect(within(table).getByText("261001001")).toBeVisible();
    openColumns(table);
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Referencia" }));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(table.querySelector('th[data-column-key="reference"]')).toBeNull();
    panel.unmount();
    await waitFor(() => expect(saveTablePreference).toHaveBeenCalled());

    panel = renderCard("luis");
    table = await historyTable();
    expect(table.querySelector('th[data-column-key="reference"]')).toBeTruthy();
    panel.unmount();

    renderCard();
    table = await historyTable();
    expect(table.querySelector('th[data-column-key="reference"]')).toBeNull();
  });

  it("resizes with the keyboard and preserves the rendered column width after remounting", async () => {
    const card = renderCard();
    let table = await historyTable();
    expect(table.querySelector('col[data-column-key="hour"]')).toHaveStyle({ width: "90px" });
    fireEvent.keyDown(within(table).getByRole("button", { name: "Cambiar ancho de Hora" }), { key: "ArrowRight" });
    expect(table.querySelector('col[data-column-key="hour"]')).toHaveStyle({ width: "98px" });
    expect(columnKeys(table.querySelectorAll("thead th"))[0]).toBe("reference");
    card.unmount();
    await waitFor(() => expect(saveTablePreference).toHaveBeenCalledWith("venta", "cash.activity",
      expect.arrayContaining([expect.objectContaining({ key: "hour", width: 98 })]), "token"));

    renderCard();
    table = await historyTable();
    expect(table.querySelector('col[data-column-key="hour"]')).toHaveStyle({ width: "98px" });
  });

  it("moves headers together with their values and updates the empty row colspan when columns are hidden", async () => {
    renderCard();
    const table = await historyTable();
    fireEvent.keyDown(table.querySelector('th[data-column-key="quantity"]')!, { key: "ArrowRight", ctrlKey: true });
    const expectedOrder = ["reference", "hour", "user", "action", "cashState", "concept", "balance", "quantity"];
    expect(columnKeys(table.querySelectorAll("thead th"))).toEqual(expectedOrder);
    const firstRow = within(table).getByText("Cambio").closest("tr")!;
    expect(columnKeys(firstRow.querySelectorAll("td"))).toEqual(expectedOrder);
    expect(firstRow.querySelector('td[data-column-key="quantity"]')).toHaveTextContent(/20,00/);
    expect(firstRow.querySelector('td[data-column-key="balance"]')).toHaveTextContent(/220,00/);
    const secondRow = within(table).getByText("Banco").closest("tr")!;
    expect(secondRow.querySelector('td[data-column-key="quantity"]')).toHaveTextContent(/-10,00/);
    expect(secondRow.querySelector('td[data-column-key="balance"]')).toHaveTextContent("—");

    fireEvent.click(screen.getByRole("button", { name: "Filtrar usuario" }));
    fireEvent.click(screen.getByRole("option", { name: "Ana" }));
    fireEvent.click(screen.getByRole("button", { name: "Filtrar acción" }));
    fireEvent.click(screen.getByRole("option", { name: "Retirada" }));
    expect(within(table).getByText("SIN DATOS").closest("td")).toHaveAttribute("colspan", "8");
    openColumns(table);
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Saldo" }));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(within(table).getAllByRole("columnheader")).toHaveLength(7);
    expect(within(table).getByText("SIN DATOS").closest("td")).toHaveAttribute("colspan", "7");
  });
});
