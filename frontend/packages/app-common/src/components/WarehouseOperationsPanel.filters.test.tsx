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
  it.each(cases.flatMap(([mode, type]) => (["venta", "gestion"] as const).map(app => [mode, type, app] as const)))("keeps independent search/status tags in %s %s for %s", async (mode, documentType, app) => {
    const { container } = show(mode, documentType, app);
    await screen.findByText("DOC-003");
    expect(container.querySelector(".stock-history-table-scroll")).toHaveClass("erp-classic-tables", "warehouse-classic-table");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
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
    const purchaseDates = app === "gestion" && (documentType === "ALBARAN_ENTRADA" || documentType === "FACTURA_ENTRADA");
    expect(request.mock.calls.filter(([path]) => String(path).startsWith("/warehouse-"))).toHaveLength(1);
    expect(request).toHaveBeenCalledTimes(purchaseDates ? 2 : 1);
    if (purchaseDates) {
      expect(request).toHaveBeenCalledWith(`/document-reports/date-options?report=${documentType === "FACTURA_ENTRADA" ? "inputInvoices" : "inputDeliveryNotes"}`, { token: "fixture" });
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
