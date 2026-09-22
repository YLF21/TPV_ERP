// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SalesReportScreen } from "./SalesReportScreen";

function today() {
  const value = new Date();
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
}

function selectValue(dialog: HTMLElement, field: string, value: string) {
  const trigger = within(dialog).getByRole("button", { name: field });
  fireEvent.click(trigger);
  fireEvent.click(screen.getByRole("option", { name: value }));
  fireEvent.click(trigger);
}

afterEach(() => { cleanup(); localStorage.clear(); vi.restoreAllMocks(); });

describe("Report pagination after multi-value filters", () => {
  it.each([
    { app: "venta", field: "customer", label: "Cliente", report: "salesReport.tickets" },
    { app: "gestion", field: "customer", label: "Cliente", report: "salesReport.tickets" },
    { app: "venta", field: "supplier", label: "Proveedor", report: "salesReport.inputInvoices" },
    { app: "gestion", field: "supplier", label: "Proveedor", report: "salesReport.inputInvoices" }
  ] as const)("accepts exact $field codes outside downloaded pages and exports them in $app", async ({ app, field, label, report }) => {
    vi.spyOn(Element.prototype, "clientHeight", "get").mockImplementation(function (this: Element) {
      return this.classList.contains("report-table-scroll") ? 240 : 0;
    });
    vi.spyOn(Element.prototype, "scrollHeight", "get").mockImplementation(function (this: Element) {
      return this.classList.contains("report-table-scroll") ? 42 + this.querySelectorAll("tbody tr").length * 40 : 0;
    });
    const endpoint = field === "customer" ? "/document-reports/tickets" : "/document-reports/warehouse-inputs";
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/document-reports/date-options")) return Promise.resolve({ currentDate: today(), earliestDate: "2020-01-01" });
      if (!path.startsWith(endpoint)) return Promise.resolve({ items: [], hasMore: false, nextCursor: null });
      const cursor = new URL(path, "http://report.test").searchParams.get("cursor");
      if (cursor && cursor !== "next-page") throw new Error("Must not download the entire report to find filter options");
      const codes = cursor ? ["R-2", "R-3", "R-2", "R-3", "R-2", "R-3", "R-2", "R-3", "R-2", "R-3", "R-200"]
        : Array.from({ length: 10 }, () => "R-200");
      return Promise.resolve({ items: codes.map((code, index) => field === "customer" ? {
        id: `${cursor ?? "first"}-${index}`, numero: `T-${cursor ?? "first"}-${index}`, customerCode: code,
        tipo: "TICKET", estado: "CONFIRMADO", fecha: today(), total: "10.00", paymentMethods: ["CASH"]
      } : {
        document: { id: `${cursor ?? "first"}-${index}`, number: `F-${cursor ?? "first"}-${index}`,
          documentType: "FACTURA_ENTRADA", status: "CONFIRMADO", date: today(), total: "10.00" },
        supplierCode: code, supplierName: `Proveedor ${code}`, warehouseName: "GENERAL"
      }), hasMore: true, nextCursor: cursor ? "later-page" : "next-page" });
    });
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    const previousDesktop = window.tpvDesktop;
    Object.defineProperty(window, "tpvDesktop", { configurable: true, writable: true, value: { reports: { saveFile } } });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(new Uint8Array([1, 2, 3]), { status: 200 }));
    try {
      const { container } = render(<SalesReportScreen app={app} locale="es" initialReport={report}
        session={{ username: "remote-code-test", displayName: "ADMIN", accessToken: "token", permissions: ["ADMIN"] }}
        terminalContext={{ storeName: "Prueba", terminalCode: "01" }} request={request} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
      const rows = () => container.querySelectorAll(".report-table tbody tr");
      const pageCalls = () => request.mock.calls.filter(([path]) => path.startsWith(endpoint));
      await waitFor(() => expect(rows()).toHaveLength(10));
      expect(pageCalls()).toHaveLength(1);
      fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
      const dialog = screen.getByRole("dialog", { name: "Filtrar" });
      const trigger = within(dialog).getByRole("button", { name: label });
      fireEvent.click(trigger);
      const search = within(dialog).getByRole("textbox", { name: "Buscar" });
      for (const code of ["R-2", "R-3"]) {
        fireEvent.change(search, { target: { value: code } });
        fireEvent.click(screen.getByRole("option", { name: `Código: ${code}` }));
      }
      fireEvent.click(trigger);
      expect(pageCalls()).toHaveLength(1);
      fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar filtro" }));
      await waitFor(() => expect(pageCalls()).toHaveLength(2));
      await waitFor(() => expect(rows()).toHaveLength(10));
      expect(container.querySelector(".report-table tbody")).not.toHaveTextContent("R-200");
      const chips = screen.getByRole("group", { name: "Filtros aplicados" });
      expect(chips).toHaveTextContent(`${label}: R-2`);
      expect(chips).toHaveTextContent(`${label}: R-3`);

      for (const [index, button] of ["Excel", "PDF"].entries()) {
        fireEvent.click(screen.getByRole("button", { name: button }));
        await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(index + 1));
      }
      const exports = fetchSpy.mock.calls.filter(([url]) => String(url).includes("/sales-reports/export"));
      expect(exports).toHaveLength(2);
      for (const [, init] of exports) expect(JSON.parse(String(init?.body))).toEqual(expect.objectContaining({
        reportKey: report, filters: expect.objectContaining({
          dateFrom: today(), dateTo: today(), [field === "customer" ? "customers" : "suppliers"]: ["R-2", "R-3"]
        })
      }));
      expect(pageCalls()).toHaveLength(2);
      expect(request.mock.calls.some(([path]) => /^\/(customers|suppliers)/.test(path))).toBe(false);
    } finally {
      Object.defineProperty(window, "tpvDesktop", { configurable: true, writable: true, value: previousDesktop });
    }
  });

  it.each([false, true])("continues loading when applying filters leaves too few rows (empty: %s)", async (empty) => {
    vi.spyOn(Element.prototype, "clientHeight", "get").mockImplementation(function (this: Element) {
      return this.classList.contains("report-table-scroll") ? 240 : 0;
    });
    vi.spyOn(Element.prototype, "scrollHeight", "get").mockImplementation(function (this: Element) {
      return this.classList.contains("report-table-scroll") ? 42 + this.querySelectorAll("tbody tr").length * 40 : 0;
    });
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/document-reports/date-options")) return Promise.resolve({ currentDate: today(), earliestDate: "2020-01-01" });
      if (path.startsWith("/document-reports/tickets")) {
        const nextPage = new URL(path, "http://report.test").searchParams.get("cursor") === "next-page";
        const items = nextPage ? [{ id: "next-match", numero: "T-NEXT", paymentMethods: ["CARD"], user: "LUIS" }]
          : Array.from({ length: 10 }, (_, index) => ({
            id: `initial-${index}`, numero: `T-INITIAL-${index}`, paymentMethods: [index === 0 ? "CARD" : "CASH"],
            user: index === 0 ? "ANA" : "LUIS"
          }));
        return Promise.resolve({ items: items.map(document => ({ ...document,
          tipo: "TICKET", estado: "CONFIRMADO", fecha: today(), total: nextPage ? "20.00" : "10.00", terminalOrigenNombre: "CAJA1" })),
        hasMore: !nextPage, nextCursor: nextPage ? null : "next-page" });
      }
      return Promise.resolve({ items: [], hasMore: false, nextCursor: null });
    });
    const { container } = render(<SalesReportScreen app="venta" locale="es" initialReport="salesReport.tickets"
      session={{ username: "multi-pagination-test", displayName: "ADMIN", accessToken: "token", permissions: ["ADMIN"] }}
      terminalContext={{ storeName: "Prueba", terminalCode: "01" }} request={request} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    const rows = () => container.querySelectorAll(".report-table tbody tr");
    const pageCalls = () => request.mock.calls.filter(([path]) => path.startsWith("/document-reports/tickets"));
    await waitFor(() => expect(rows()).toHaveLength(10));
    expect(pageCalls()).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
    const dialog = screen.getByRole("dialog", { name: "Filtrar" });
    selectValue(dialog, "Método de pago", "Tarjeta");
    if (empty) selectValue(dialog, "Usuario", "LUIS");
    expect(pageCalls()).toHaveLength(1);
    fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar filtro" }));

    await waitFor(() => expect(pageCalls()).toHaveLength(2));
    expect(new URL(pageCalls()[1][0], "http://report.test").searchParams.get("cursor")).toBe("next-page");
    await waitFor(() => expect(rows()).toHaveLength(empty ? 1 : 2));
    expect(container.querySelector(".report-table tbody")).toHaveTextContent("20,00 €");
    expect([...rows()].every(row => row.textContent?.includes("Tarjeta"))).toBe(true);
  });
});
