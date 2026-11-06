// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AppKind, TerminalContext, UserSession } from "../types";
import { SalesReportScreen } from "./SalesReportScreen";

const terminalContext: TerminalContext = { storeName: "Tienda de prueba", terminalCode: "01" };
const session: UserSession = { username: "report-filter-test", displayName: "ADMIN", permissions: ["ADMIN"] };
const reportKeys = ["tickets", "deliveryNotes", "invoices", "warehouseOutputs", "inputInvoices", "inputDeliveryNotes", "inputWarehouse"];

function isoDate(value: Date) {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
}

function ticketRequest() {
  const today = isoDate(new Date());
  return vi.fn().mockImplementation((path: string) => {
    if (path.startsWith("/document-reports/date-options")) {
      return Promise.resolve({ earliestDate: "2020-01-01", currentDate: today });
    }
    if (path.startsWith("/document-reports/tickets")) {
      const date = new URL(path, "http://report.test").searchParams.get("dateFrom") ?? today;
      return Promise.resolve({ items: [
        { id: "north-one", numero: "T-NORTE-1", customerCode: "NORTE", user: "ANA", terminalOrigenNombre: "CAJA1" },
        { id: "north-two", numero: "T-NORTE-2", customerCode: "NORTE", user: "LUIS", terminalOrigenNombre: "CAJA2" },
        { id: "south-one", numero: "T-SUR-1", customerCode: "SUR", user: "ANA", terminalOrigenNombre: "CAJA1" }
      ].map((document) => ({ ...document, tipo: "TICKET", estado: "CONFIRMADO", fecha: date, total: "10.00", paymentMethods: ["CASH"] })),
      nextCursor: null, hasMore: false });
    }
    if (path.startsWith("/document-reports/warehouse-inputs")) {
      const query = new URL(path, "http://report.test").searchParams;
      const date = query.get("dateFrom") ?? today;
      return Promise.resolve({ items: ["Norte", "Sur"].map((name) => ({
        document: { id: `input-${name}`, number: `E-${name}`, documentType: query.get("type"),
          date, status: "CONFIRMADO", subtotal: "10.00", total: "10.00", lines: [] },
        supplierCode: `P-${name.toUpperCase()}`, supplierName: `Proveedor ${name}`, warehouseName: "GENERAL"
      })), nextCursor: null, hasMore: false });
    }
    return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
  });
}

function showReport(report = "tickets", app: AppKind = "venta", request?: ReturnType<typeof ticketRequest>) {
  return render(<SalesReportScreen app={app} locale="es" session={request ? { ...session, accessToken: "token" } : session}
    terminalContext={terminalContext} onBack={vi.fn()} onLocaleChange={vi.fn()}
    initialReport={`salesReport.${report}`} request={request ?? vi.fn()} />);
}

function openFilters() {
  fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
  return screen.queryByRole("dialog", { name: "Filtrar" }) ?? document.getElementById(screen.getByRole("button", { name: "Filtrar" }).getAttribute("aria-controls")!)!;
}

function choose(dialog: HTMLElement, label: string, option: string) {
  const trigger = within(dialog).getByRole("button", { name: label });
  fireEvent.click(trigger);
  fireEvent.click(screen.getByRole("option", { name: option }));
  if (trigger.getAttribute("aria-expanded") === "true") fireEvent.click(trigger);
}

function apply(dialog: HTMLElement) {
  fireEvent.click(within(dialog).queryByRole("button", { name: "Aplicar filtro" }) ?? within(dialog).queryByRole("button", { name: "Cerrar" }) ?? screen.getByRole("button", { name: "Filtrar" }));
}

function rows(container: HTMLElement) {
  return container.querySelectorAll(".report-table tbody tr");
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("ERP report filters with removable tags", () => {
  it.each(["tickets", "deliveryNotes", "invoices"].flatMap(report => (["venta", "gestion"] as const).map(app => [report, app] as const)))("keeps %s filters inline and dates only in the footer in %s", async (report, app) => {
    const { container } = showReport(report, app, ticketRequest());
    const toggle = screen.getByRole("button", { name: "Filtrar" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    const panel = openFilters();
    expect(panel).toBeVisible();
    expect(panel.closest(".erp-list-filter-panel")).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Filtrar" })).not.toBeInTheDocument();
    expect(within(panel).queryByRole("button", { name: "Aplicar filtro" })).not.toBeInTheDocument();
    expect(panel.querySelector('input[type="date"], .date-range-control')).toBeNull();
    expect(within(panel).getByRole("button", { name: "Cliente" })).toBeVisible();
    const footer = screen.getByRole("contentinfo", { name: "Periodo seleccionado" });
    expect(footer.querySelectorAll('input[type="date"]')).toHaveLength(2);
    expect(container.querySelectorAll('input[type="date"]')).toHaveLength(2);
    await waitFor(() => expect(within(footer).getByLabelText("Desde")).toBeEnabled());
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "T-NORTE" } });
    fireEvent.click(within(panel).getByRole("button", { name: "Limpiar" }));
    expect(screen.getByRole("searchbox", { name: "Buscar" })).toHaveValue("");
    expect(panel).toBeVisible();
    fireEvent.keyDown(within(panel).getByRole("button", { name: "Cliente" }), { key: "Escape" });
    expect(panel).not.toBeVisible();
    expect(toggle).toHaveFocus();
    fireEvent.click(toggle);
    expect(panel).toBeVisible();
  });

  it("auto-applies valid footer dates while preserving an invalid draft without requesting it", async () => {
    const request = ticketRequest();
    showReport("tickets", "venta", request);
    const footer = screen.getByRole("contentinfo", { name: "Periodo seleccionado" });
    const from = within(footer).getByLabelText("Desde");
    const to = within(footer).getByLabelText("Hasta");
    await waitFor(() => expect(from).toBeEnabled());
    const calls = () => request.mock.calls.filter(([path]) => String(path).startsWith("/document-reports/tickets"));
    fireEvent.change(from, { target: { value: "2026-01-02" } });
    await waitFor(() => expect(String(calls().at(-1)?.[0])).toContain("dateFrom=2026-01-02"));
    const count = calls().length;
    fireEvent.change(to, { target: { value: "2026-01-01" } });
    expect(to).toHaveValue("2026-01-01");
    expect(calls()).toHaveLength(count);
    fireEvent.change(to, { target: { value: "2026-01-03" } });
    await waitFor(() => expect(String(calls().at(-1)?.[0])).toContain("dateTo=2026-01-03"));
    expect(within(footer).queryByRole("button", { name: "Aplicar" })).not.toBeInTheDocument();
  });

  it("keeps an invalid date draft and applies a valid range without a confirm button", async () => {
    const request = ticketRequest();
    showReport("inputWarehouse", "venta", request);
    await waitFor(() => expect(request.mock.calls.some(([path]) => String(path).startsWith("/document-reports/warehouse-inputs"))).toBe(true));
    const dialog = openFilters();
    const input = dialog.querySelector<HTMLInputElement>(".date-range-control input")!;
    const pageCalls = () => request.mock.calls.filter(([path]) => String(path).startsWith("/document-reports/warehouse-inputs"));
    const before = pageCalls().length;
    fireEvent.change(input, { target: { value: "01/01/2026 - 99/99/2026" } });
    expect(input).toHaveValue("01/01/2026 - 99/99/2026");
    expect(pageCalls()).toHaveLength(before);
    expect(within(dialog).queryByRole("button", { name: "Aplicar filtro" })).not.toBeInTheDocument();
    fireEvent.change(input, { target: { value: "01/01/2026 - 02/01/2026" } });
    await waitFor(() => expect(pageCalls().length).toBeGreaterThan(before));
    const query = new URL(String(pageCalls().at(-1)![0]), "http://report.test").searchParams;
    expect(query.get("dateFrom")).toBe("2026-01-01");
    expect(query.get("dateTo")).toBe("2026-01-02");
  });

  it.each(reportKeys.flatMap(report => (["venta", "gestion"] as const).map(app => [report, app] as const)))("opts %s in %s into the classic table and exposes removable live search", (report, app) => {
    const { container } = showReport(report, app);
    expect(container.querySelector(".report-table-scroll")).toHaveClass("erp-classic-tables", "report-classic-document-table");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "referencia" } });
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Buscar: referencia");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(screen.getByRole("searchbox", { name: "Buscar" })).toHaveValue("");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
  });

  it("shows live criteria, keeps changes on close, and removes only the selected field", async () => {
    const request = ticketRequest();
    const { container } = showReport("tickets", "venta", request);
    await waitFor(() => expect(rows(container)).toHaveLength(3));
    let dialog = openFilters();
    choose(dialog, "Cliente", "NORTE");
    choose(dialog, "Usuario", "ANA");
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Cliente: NORTE");
    expect(rows(container)).toHaveLength(1);
    apply(dialog);
    expect(rows(container)).toHaveLength(1);
    const chips = screen.getByRole("group", { name: "Filtros aplicados" });
    expect(chips).toHaveTextContent("Cliente: NORTE");
    expect(chips).toHaveTextContent("Usuario: ANA");

    dialog = openFilters();
    choose(dialog, "Cliente", "NORTE");
    choose(dialog, "Cliente", "SUR");
    fireEvent.click(within(dialog).queryByRole("button", { name: "Cerrar" }) ?? screen.getByRole("button", { name: "Filtrar" }));
    expect(chips).toHaveTextContent("Cliente: SUR");
    expect(chips).not.toHaveTextContent("NORTE");

    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Cliente: SUR" }));
    expect(rows(container)).toHaveLength(2);
    expect(chips).toHaveTextContent("Usuario: ANA");
    dialog = openFilters();
    expect(within(dialog).getByRole("button", { name: "Cliente" })).toHaveTextContent("Sin filtro");
    expect(within(dialog).getByRole("button", { name: "Usuario" })).toHaveTextContent("ANA");
  });

  it("restores today's bounded query when removing a period while retaining customer and search", async () => {
    const request = ticketRequest();
    const { container } = showReport("tickets", "venta", request);
    await waitFor(() => expect(rows(container)).toHaveLength(3));
    const dialog = openFilters();
    choose(dialog, "Cliente", "NORTE");
    apply(dialog);
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "T-NORTE" } });
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    await waitFor(() => expect(rows(container)).toHaveLength(2));
    expect(screen.getByRole("button", { name: "Quitar filtro Fecha" })).toBeInTheDocument();
    request.mockClear();
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Fecha" }));
    await waitFor(() => expect(rows(container)).toHaveLength(2));
    expect(screen.queryByRole("button", { name: "Quitar filtro Fecha" })).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Cliente: NORTE");
    expect(screen.getByRole("searchbox", { name: "Buscar" })).toHaveValue("T-NORTE");
    const pageCalls = request.mock.calls.filter(([path]) => String(path).startsWith("/document-reports/tickets"));
    expect(pageCalls).toHaveLength(1);
    const url = new URL(String(pageCalls[0][0]), "http://report.test");
    expect(url.searchParams.get("dateFrom")).toBe(isoDate(new Date()));
    expect(url.searchParams.get("dateTo")).toBe(isoDate(new Date()));
    expect(url.searchParams.has("cursor")).toBe(false);
  });

  it("clears tags and search, restores today, and keeps the chosen sort", async () => {
    const { container } = showReport("tickets", "venta", ticketRequest());
    await waitFor(() => expect(rows(container)).toHaveLength(3));
    const dialog = openFilters();
    choose(dialog, "Cliente", "NORTE");
    choose(dialog, "Terminal", "CAJA1");
    apply(dialog);
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "T-NORTE" } });
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    await waitFor(() => expect(rows(container)).toHaveLength(1));
    const customerSort = screen.getByRole("button", { name: "Cliente ordenar" });
    fireEvent.click(customerSort);
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    await waitFor(() => expect(rows(container)).toHaveLength(3));
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "Buscar" })).toHaveValue("");
    expect(customerSort).toHaveAttribute("data-sort-direction", "asc");
    expect(screen.getByRole("button", { name: "Hoy" })).toHaveAttribute("aria-pressed", "true");
  });

  it.each(["inputInvoices", "inputDeliveryNotes"])("removes an applied supplier filter in %s", async (report) => {
    const { container } = showReport(report, "venta", ticketRequest());
    await waitFor(() => expect(rows(container)).toHaveLength(2));
    const dialog = openFilters();
    choose(dialog, "Proveedor", "P-NORTE · Proveedor Norte");
    apply(dialog);
    expect(rows(container)).toHaveLength(1);
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Proveedor: P-NORTE · Proveedor Norte");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Proveedor: P-NORTE · Proveedor Norte" }));
    expect(rows(container)).toHaveLength(2);
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    expect(within(openFilters()).getByRole("button", { name: "Proveedor" })).toHaveTextContent("Todos");
  });

  it("keeps PDA's existing summary and table styling", () => {
    const { container } = showReport("tickets", "pda");
    const dialog = openFilters();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Cliente" }), { target: { value: "NORTE" } });
    apply(dialog);
    expect(container.querySelector(".active-filter-summary")).toHaveTextContent("Cliente:NORTE");
    expect(container.querySelector(".active-filter-summary")).not.toHaveClass("report-print-filter-summary");
    expect(container.querySelector(".report-table-scroll")).not.toHaveClass("erp-classic-tables");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
  });
});
