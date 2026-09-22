// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SalesReportScreen } from "./SalesReportScreen";

function today() {
  const value = new Date();
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
}
const documents = [
  { id: "mixed", numero: "T-MIXTO", paymentMethods: ["CARD", "CASH"], customerCode: "C-1", user: "ANA" },
  { id: "card", numero: "T-TARJETA", paymentMethods: ["CARD"], customerCode: "C-2", user: "ANA" },
  { id: "cash", numero: "T-EFECTIVO", paymentMethods: ["CASH"], customerCode: "C-1", user: "ANA" },
  { id: "cash-other", numero: "T-OTRO", paymentMethods: ["CASH"], customerCode: "C-10", user: "LUIS" },
  { id: "transfer", numero: "T-TRANSFERENCIA", paymentMethods: ["TRANSFER"], customerCode: "C-1", user: "ANA" }
];

function show(app: "venta" | "gestion" = "venta") {
  const request = vi.fn().mockImplementation((path: string) => {
    if (path.startsWith("/document-reports/date-options")) return Promise.resolve({ currentDate: today(), earliestDate: "2020-01-01" });
    if (path.startsWith("/document-reports/tickets")) return Promise.resolve({ items: documents.map(document => ({ ...document,
      tipo: "TICKET", estado: "CONFIRMADO", fecha: today(), total: "10.00", terminalOrigenNombre: "CAJA1" })), hasMore: false, nextCursor: null });
    return Promise.resolve({ items: [], hasMore: false, nextCursor: null });
  });
  return render(<SalesReportScreen app={app} locale="es" initialReport="salesReport.tickets"
    session={{ username: "multi-test", displayName: "ADMIN", accessToken: "token", permissions: ["ADMIN"] }}
    terminalContext={{ storeName: "Prueba", terminalCode: "01" }} request={request} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
}

function openFilters() {
  fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
  return screen.getByRole("dialog", { name: "Filtrar" });
}
function selectValues(dialog: HTMLElement, field: string, values: string[]) {
  const trigger = within(dialog).getByRole("button", { name: field });
  fireEvent.click(trigger);
  values.forEach(value => fireEvent.click(screen.getByRole("option", { name: value })));
  fireEvent.click(trigger);
}
function apply(dialog: HTMLElement) { fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar filtro" })); }
function documentRows(container: HTMLElement) { return container.querySelectorAll(".report-table tbody tr"); }
function rowText(container: HTMLElement) { return [...documentRows(container)].map(row => row.textContent); }

afterEach(() => { cleanup(); localStorage.clear(); vi.restoreAllMocks(); });

describe("Desktop multi-value report filtering", () => {
  it.each(["venta", "gestion"] as const)("orders payments and counts mixed payments once in %s", async app => {
    const { container } = show(app);
    await waitFor(() => expect(documentRows(container)).toHaveLength(5));
    const dialog = openFilters();
    const trigger = within(dialog).getByRole("button", { name: "Método de pago" });
    fireEvent.click(trigger);
    expect(within(screen.getByRole("listbox", { name: "Método de pago" })).getAllByRole("option")
      .map(option => option.textContent?.replace("✓", "").trim()))
      .toEqual(["Todos", "Efectivo", "Tarjeta", "Transferencia"]);
    fireEvent.click(screen.getByRole("option", { name: "Efectivo" }));
    fireEvent.click(screen.getByRole("option", { name: "Tarjeta" }));
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("option", { name: "Efectivo" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("option", { name: "Tarjeta" })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(trigger);
    selectValues(dialog, "Usuario", ["ANA"]);
    apply(dialog);
    expect(documentRows(container)).toHaveLength(3);
    expect(rowText(container).filter(text => text?.includes("Tarjeta + Efectivo"))).toHaveLength(1);
    expect(screen.getByText(/^Total: 30,00/)).toBeInTheDocument();
    const chips = screen.getByRole("group", { name: "Filtros aplicados" });
    expect(chips).toHaveTextContent("Método de pago: Efectivo");
    expect(chips).toHaveTextContent("Método de pago: Tarjeta");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Método de pago: Efectivo" }));
    expect(documentRows(container)).toHaveLength(2);
    expect(chips).toHaveTextContent("Método de pago: Tarjeta");
    expect(chips).toHaveTextContent("Usuario: ANA");
    expect([...documentRows(container)].every(row => within(row as HTMLElement).getByText(/Tarjeta/))).toBe(true);
  });

  it("accepts several exact customer codes, cancels draft edits, and clears only that selector with Todos", async () => {
    const { container } = show();
    await waitFor(() => expect(documentRows(container)).toHaveLength(5));
    let dialog = openFilters();
    selectValues(dialog, "Cliente", ["C-1", "C-2"]);
    selectValues(dialog, "Método de pago", ["Efectivo"]);
    apply(dialog);
    expect(documentRows(container)).toHaveLength(2);
    expect(rowText(container).some(text => text?.includes("C-10"))).toBe(false);
    dialog = openFilters();
    selectValues(dialog, "Cliente", ["C-10"]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    expect(documentRows(container)).toHaveLength(2);
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).not.toHaveTextContent("C-10");
    dialog = openFilters();
    selectValues(dialog, "Cliente", ["Todos"]);
    apply(dialog);
    expect(documentRows(container)).toHaveLength(3);
    const chips = screen.getByRole("group", { name: "Filtros aplicados" });
    expect(chips).not.toHaveTextContent("Cliente:");
    expect(chips).toHaveTextContent("Método de pago: Efectivo");
  });

  it.each(["venta", "gestion"] as const)("sends the same multiple selections and bounded period to Excel and PDF in %s", async app => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    const previousDesktop = window.tpvDesktop;
    Object.defineProperty(window, "tpvDesktop", { configurable: true, writable: true, value: { reports: { saveFile } } });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(new Uint8Array([1, 2, 3]), { status: 200 }));
    try {
      const { container } = show(app);
      await waitFor(() => expect(documentRows(container)).toHaveLength(5));
      const dialog = openFilters();
      selectValues(dialog, "Método de pago", ["Efectivo", "Tarjeta"]);
      selectValues(dialog, "Cliente", ["C-1", "C-2"]);
      selectValues(dialog, "Usuario", ["ANA"]);
      apply(dialog);
      for (const [index, button] of ["Excel", "PDF"].entries()) {
        fireEvent.click(screen.getByRole("button", { name: button }));
        await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(index + 1));
      }
      const calls = fetchSpy.mock.calls.filter(([url]) => String(url).includes("/sales-reports/export"));
      expect(calls).toHaveLength(2);
      expect(String(calls[0][0])).toMatch(/\/sales-reports\/export$/);
      expect(String(calls[1][0])).toMatch(/\/sales-reports\/export-pdf$/);
      calls.forEach(([, init]) => expect(JSON.parse(String(init?.body))).toEqual(expect.objectContaining({
        reportKey: "salesReport.tickets", filters: expect.objectContaining({
          dateFrom: today(), dateTo: today(), customer: "", payment: "", user: "",
          customers: ["C-1", "C-2"], payments: ["EFECTIVO", "TARJETA"], users: ["ANA"]
        })
      })));
    } finally {
      Object.defineProperty(window, "tpvDesktop", { configurable: true, writable: true, value: previousDesktop });
    }
  });
});
