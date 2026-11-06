// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { WarehouseOperationsPanel, type WarehouseOperationView } from "./WarehouseOperationsPanel";
import type { WarehouseInputDocumentType } from "./WarehouseDocumentDialog";

vi.mock("../api/client", async () => ({ ...await vi.importActual("../api/client"), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const warehouses = [{ id: "wh-1", name: "GENERAL" }];
const suppliers = [{ id: "supplier-1", legalName: "NORTE" }];
const customers = [{ id: "customer-1", fiscalName: "NORTE" }];
const rows: WarehouseOperationView[] = [
  { id: "one", number: "DOC-001", concept: "CAFE", status: "BORRADOR" },
  { id: "two", number: "DOC-002", concept: "CAFE", status: "CONFIRMADA" },
  { id: "three", number: "DOC-003", concept: "ARROZ", status: "CONFIRMADA" }
].map(row => ({ ...row, date: "2026-09-22", warehouseId: "wh-1", supplierId: "supplier-1", customerId: "customer-1",
  lines: [{ productId: "product-1", quantity: 2 }] }));
const cases: Array<["input" | "output", WarehouseInputDocumentType | undefined]> = [
  ["input", "ENTRADA_ALMACEN"], ["input", "ALBARAN_ENTRADA"], ["input", "FACTURA_ENTRADA"], ["output", undefined]
];
function show(mode: "input" | "output", documentType?: WarehouseInputDocumentType, app: "venta" | "gestion" | "pda" = "venta") {
  return render(<WarehouseOperationsPanel app={app} mode={mode} documentType={documentType} locale="es" token="fixture"
    products={[]} warehouses={warehouses} customers={customers} suppliers={suppliers} t={createTranslator("es")} />);
}
function filterStatus(value: string) {
  const toggle = screen.queryByRole("button", { name: "Filtrar" });
  if (toggle?.getAttribute("aria-expanded") === "false") fireEvent.click(toggle);
  fireEvent.click(screen.getByRole("button", { name: "Estado" }));
  fireEvent.click(screen.getByRole("option", { name: value }));
}
beforeEach(() => {
  request.mockReset();
  request.mockImplementation(async (path) => String(path).startsWith("/document-reports/date-options")
    ? { currentDate: "2026-09-25", earliestDate: "2026-09-22" }
    : { items: rows, hasMore: false });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Classic warehouse lists and filter tags", () => {
  it.each((["ENTRADA_ALMACEN", "ALBARAN_ENTRADA", "FACTURA_ENTRADA"] as const).flatMap(type =>
    (["venta", "gestion"] as const).map(app => [type, app] as const)))("combines supplier and warehouse without losing other criteria in %s for %s", async (documentType, app) => {
    const purchaseRows = [
      { ...rows[0], id: "n-general", number: "AE-N-G", supplierId: "north", warehouseId: "general" },
      { ...rows[1], id: "n-reserve", number: "AE-N-R", supplierId: "north", warehouseId: "reserve" },
      { ...rows[1], id: "s-reserve", number: "AE-S-R", supplierId: "south", warehouseId: "reserve" },
      { ...rows[1], id: "s-general", number: "AE-S-G", supplierId: "south", warehouseId: "general" },
      { ...rows[1], id: "no-supplier", number: "AE-X-R", supplierId: null, warehouseId: "reserve" }
    ];
    request.mockImplementation(async path => String(path).startsWith("/document-reports/date-options")
      ? { currentDate: "2026-09-25", earliestDate: "2026-09-22" } : { items: purchaseRows, hasMore: false });
    const { container } = render(<WarehouseOperationsPanel app={app} mode="input" documentType={documentType} locale="es" token="fixture"
      products={[]} warehouses={[{ id: "general", name: "General" }, { id: "reserve", name: "Reserva" }]} customers={customers}
      suppliers={[{ id: "north", supplierId: "P-01", legalName: "Norte" }, { id: "south", supplierId: "P-02", legalName: "Sur" }]} t={createTranslator("es")} />);
    await screen.findByText("AE-N-R");
    fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
    fireEvent.click(screen.getByRole("button", { name: "Proveedor" }));
    fireEvent.click(screen.getByRole("option", { name: "P-01 · Norte" }));
    expect(container.querySelectorAll("tr[data-operation-id]")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Almacén" }));
    fireEvent.click(screen.getByRole("option", { name: "Reserva" }));
    filterStatus("Confirmada");
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "CAFE" } });
    expect(container.querySelectorAll("tr[data-operation-id]")).toHaveLength(1);
    expect(container.querySelector("tr[data-operation-id]")).toHaveTextContent("AE-N-R");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Proveedor" }));
    expect(container.querySelectorAll("tr[data-operation-id]")).toHaveLength(3);
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Almacén: Reserva");
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Estado: Confirmada");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    expect(container.querySelectorAll("tr[data-operation-id]")).toHaveLength(5);
    expect(request.mock.calls.every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
  });

  it.each(["venta", "gestion"] as const)("combines multiple output customers and warehouses in %s", async app => {
    const outputs = [
      { ...rows[1], id: "one", number: "SAL-001", destination: "Ana - 123", warehouseId: "general" },
      { ...rows[1], id: "two", number: "SAL-002", destination: "Cliente histórico", warehouseId: "archive" },
      { ...rows[1], id: "three", number: "SAL-003", destination: "Ana - 1234", warehouseId: "general" },
      { ...rows[1], id: "four", number: "SAL-004", destination: "Ana - 123", warehouseId: "reserve" },
      { ...rows[1], id: "five", number: "SAL-005", destination: null, warehouseId: "general" },
    ];
    request.mockImplementation(async path => String(path).startsWith("/document-reports/date-options")
      ? { currentDate: "2026-09-25", earliestDate: "2026-09-22" } : { items: outputs, hasMore: false });
    const { container } = render(<WarehouseOperationsPanel app={app} mode="output" locale="es" token="fixture"
      products={[]} warehouses={[{ id: "general", name: "General" }, { id: "reserve", name: "Reserva" },
        { id: "archive", name: "Histórico", active: false }]} customers={[]} suppliers={[]} t={createTranslator("es")} />);
    await screen.findByText("SAL-005");
    fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
    expect(screen.queryByRole("button", { name: "Proveedor" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cliente" }));
    fireEvent.click(screen.getByRole("option", { name: "Ana - 123" }));
    fireEvent.click(screen.getByRole("option", { name: "Cliente histórico" }));
    fireEvent.click(screen.getByRole("button", { name: "Almacén" }));
    fireEvent.click(screen.getByRole("option", { name: "General" }));
    fireEvent.click(screen.getByRole("option", { name: "Histórico" }));
    const visibleIds = () => [...container.querySelectorAll("tr[data-operation-id]")].map(row => row.getAttribute("data-operation-id"));
    expect(visibleIds()).toEqual(["one", "two"]);
    fireEvent.click(screen.getByRole("option", { name: "General" }));
    expect(visibleIds()).toEqual(["two"]);
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    expect(visibleIds()).toHaveLength(5);
    expect(request.mock.calls.every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
  });

  it.each(["venta", "gestion"] as const)("keeps Enter navigation from search to the folded status filter in %s", async app => {
    const { container } = show("input", "ENTRADA_ALMACEN", app);
    await screen.findByText("DOC-003");
    const toggle = screen.getByRole("button", { name: "Filtrar" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    const search = screen.getByRole("searchbox", { name: "Buscar" });
    search.focus();
    fireEvent.keyDown(search, { key: "Enter" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await waitFor(() => expect(screen.getByRole("button", { name: "Estado" })).toHaveFocus());
    expect(container.querySelectorAll("tbody tr[data-operation-id]")).toHaveLength(3);
  });

  it.each(cases.flatMap(([mode, type]) => (["venta", "gestion"] as const).map(app => [mode, type, app] as const)))("keeps independent search/status tags in %s %s for %s", async (mode, documentType, app) => {
    const { container } = show(mode, documentType, app);
    await screen.findByText("DOC-003");
    expect(container.querySelector(".stock-history-table-scroll")).toHaveClass("erp-classic-tables", "warehouse-classic-table");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Desde")).toBeVisible();
    expect(screen.getByLabelText("Hasta")).toBeVisible();
    const search = screen.getByRole("searchbox", { name: "Buscar" });
    fireEvent.change(search, { target: { value: "CAFE" } });
    filterStatus("Confirmada");
    expect(container.querySelectorAll("tbody tr[data-operation-id]")).toHaveLength(1);
    const selected = container.querySelector('[data-operation-id="two"]')!;
    fireEvent.click(selected);
    expect(selected).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Buscar: CAFE");
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Estado: Confirmada");

    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(search).toHaveValue("");
    expect(container.querySelectorAll("tbody tr[data-operation-id]")).toHaveLength(2);
    expect(selected).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Estado: Confirmada");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Estado" }));
    expect(container.querySelectorAll("tbody tr[data-operation-id]")).toHaveLength(3);
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    await waitFor(() => expect(search).toHaveFocus());
    const warehouseDates = app === "gestion";
    expect(request.mock.calls.filter(([path]) => String(path).startsWith("/warehouse-"))).toHaveLength(1);
    expect(request).toHaveBeenCalledTimes(warehouseDates ? 2 : 1);
    if (warehouseDates) {
      const report = mode === "output" ? "warehouseOutputs" : documentType === "FACTURA_ENTRADA" ? "inputInvoices" : documentType === "ALBARAN_ENTRADA" ? "inputDeliveryNotes" : "inputWarehouse";
      expect(request).toHaveBeenCalledWith(`/document-reports/date-options?report=${report}`, { token: "fixture" });
      expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled();
    }
  });

  it("clears tags while preserving sorting and isolates the opened document editor", async () => {
    const { container } = show("input", "ENTRADA_ALMACEN");
    await screen.findByText("DOC-003");
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "CAFE" } });
    filterStatus("Confirmada");
    const sortButton = screen.getByRole("button", { name: /Ordenar por Número/ });
    fireEvent.click(sortButton);
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    expect(sortButton).toHaveAttribute("data-sort-direction", "asc");
    expect(container.querySelectorAll("tbody tr[data-operation-id]")).toHaveLength(3);
    const draft = container.querySelector('[data-operation-id="one"]')!;
    fireEvent.doubleClick(draft);
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveClass("erp-classic-tables");
    expect(dialog.closest(".warehouse-classic-table")).toBeNull();
  });

  it("keeps PDA's current presentation and filters", async () => {
    const { container } = show("input", "ENTRADA_ALMACEN", "pda");
    await screen.findByText("DOC-003");
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "CAFE" } });
    expect(container.querySelectorAll("tbody tr[data-operation-id]")).toHaveLength(2);
    expect(container.querySelector(".erp-classic-tables")).toBeNull();
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
  });
});
