// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SalesActivityPanel } from "./SalesActivityPanel";
import type { AppKind } from "../types";

vi.mock("../api/client", async () => ({
  ...await vi.importActual<typeof import("../api/client")>("../api/client"),
  apiRequest: vi.fn().mockResolvedValue(null)
}));

const today = "2026-09-22";
function fixture() {
  return vi.fn().mockImplementation(async (path: string) => {
    if (path === "/sales-activity/filter-options") return { earliestDate: "2026-01-01", currentDate: today };
    const from = new URL(path, "http://test").searchParams.get("dateFrom") || today;
    const row = { id: `document-${from}`, date: from, ticketNumber: `T-${from}`, invoiceNumber: "",
      userName: "DEMO", paymentMethods: ["EFECTIVO"], kind: "SALE", status: "CONFIRMADO", total: "12.00" };
    return { items: path.includes("/by-day?") ? [{ date: from, ticketCount: 1, invoiceCount: 0, total: "12.00" }] : [row],
      nextCursor: null, hasMore: false, ticketCount: 1, invoiceCount: 0, total: "12.00",
      dateFrom: from, dateTo: from, currentDate: today };
  });
}
function mount(request = fixture(), app: AppKind = "venta") {
  return render(<SalesActivityPanel app={app} mode="documents" locale="es" username="report-filter-test"
    token="test" terminalContext={{ storeName: "Demo", terminalCode: "01" }} request={request} />);
}
afterEach(() => { cleanup(); localStorage.clear(); vi.restoreAllMocks(); });

describe("sales document period chips", () => {
  it.each((["venta", "gestion"] as const).flatMap(app => ["DAY", "DOCUMENT"].map(view => [app, view] as const)))("restores today from a period chip in %s while preserving the %s view", async (app, view) => {
    const request = fixture(); const { container } = mount(request, app);
    await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled());
    if (view === "DOCUMENT") fireEvent.click(screen.getByRole("button", { name: "Por documento" }));
    await screen.findByRole("table");
    expect(container.querySelector(".sales-documents-table-scroll")).toHaveClass("erp-classic-tables", "report-classic-document-table");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Mes" }), { target: { value: "2026-08" } });
    const chips = await screen.findByRole("group", { name: "Filtros aplicados" });
    expect(chips).toHaveTextContent("1/8/2026 — 31/8/2026");
    await waitFor(() => expect(request).toHaveBeenCalledWith(expect.stringContaining("dateFrom=2026-08-01&dateTo=2026-08-31"), { token: "test" }));
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Periodo seleccionado" }));
    const resource = view === "DAY" ? "documents/by-day" : "documents";
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(`/sales-activity/${resource}?dateFrom=${today}&dateTo=${today}&limit=250`, { token: "test" }));
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: view === "DAY" ? "Por día" : "Por documento" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Hoy" })).toHaveAttribute("aria-pressed", "true");
  });

  it("does not show a draft period as an applied chip and clears the applied range", async () => {
    mount(); await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Periodo personalizado" }));
    fireEvent.change(screen.getByLabelText("Desde"), { target: { value: "2026-07-01" } });
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    expect(await screen.findByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("21/9/2026");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toHaveAttribute("aria-pressed", "true"));
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
  });

  it("discards a pending page when the chip restores the current period", async () => {
    const request = fixture(); const base = request.getMockImplementation()!;
    let finish!: (value: unknown) => void;
    const pending = new Promise(resolve => { finish = resolve; });
    request.mockImplementation(async path => {
      if (path.includes("cursor=old")) return pending;
      const result = await base(path);
      return path.includes("dateFrom=2026-08-01") ? { ...result, hasMore: true, nextCursor: "old" } : result;
    });
    mount(request); await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled());
    fireEvent.change(screen.getByRole("combobox", { name: "Mes" }), { target: { value: "2026-08" } });
    fireEvent.click(await screen.findByRole("button", { name: "Cargar más" }));
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Periodo seleccionado" }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(`/sales-activity/documents/by-day?dateFrom=${today}&dateTo=${today}&limit=250`, { token: "test" }));
    await act(async () => finish({ items: [{ date: "2026-08-02", ticketCount: 9999, invoiceCount: 0, total: "9999.00" }], hasMore: false, currentDate: today }));
    expect(screen.queryByText("9999")).not.toBeInTheDocument();
  });

  it("leaves the PDA presentation unchanged", async () => {
    const { container } = mount(fixture(), "pda");
    await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    await screen.findByRole("table");
    expect(container.querySelector(".erp-classic-tables")).toBeNull();
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
  });
});
