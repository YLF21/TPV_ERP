// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "../../../packages/app-common/src/types";
import { apiRequest } from "../../../packages/app-common/src/api/client";
import { StockCountScreen } from "./StockCountScreen";
import * as api from "./warehouseOperationsApi";

vi.mock("../../../packages/app-common/src/api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../../../packages/app-common/src/api/client")>(),
  apiRequest: vi.fn()
}));
vi.mock("./warehouseOperationsApi", async (importOriginal) => ({
  ...await importOriginal<typeof import("./warehouseOperationsApi")>(),
  loadStockCounts: vi.fn(), loadStockCount: vi.fn(),
  createStockCount: vi.fn(), saveStockCountDraft: vi.fn(), cancelStockCount: vi.fn(), confirmStockCount: vi.fn(), exportStockCount: vi.fn()
}));

const warehouses: api.WarehouseOption[] = [{ id: "general", name: "GENERAL", active: true }];
const summary: api.StockCountSummary = {
  id: "inventory-1", number: "INV-2026-000001", version: 2, documentDate: "2026-09-24", storeId: "store",
  warehouseId: "general", status: "DRAFT", notes: "Recuento mensual", createdBy: "admin", createdAt: "2026-09-24T10:00:00Z",
  lineCount: 1, totalDifference: 0
};
const detail: api.StockCountDetail = { ...summary, lines: [{
  productId: "coffee", productCode: "CAF", productName: "Café", expectedQuantity: 10, countedQuantity: null, difference: null
}] };
const t = (key: string) => key;
const session = (permissions: UserSession["permissions"]): UserSession => ({ username: "admin", displayName: "Admin", accessToken: "token", permissions });

describe("StockCountScreen", () => {
  afterEach(cleanup);
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.loadStockCounts).mockResolvedValue([summary]);
    vi.mocked(api.loadStockCount).mockResolvedValue(detail);
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/document-reports/date-options?report=stockCounts") return {
        currentDate: "2026-09-24", earliestDate: "2025-01-01"
      } as never;
      if (path === "/stock-counts/resources") return {
        products: [{ id: "coffee", code: "CAF", name: "Café", productType: "UNIT", active: true }], families: [], warehouses
      } as never;
      if (path.startsWith("/stock-counts/balances?")) return [{ productId: "coffee", warehouseId: "general", quantity: 10 }] as never;
      throw new Error(`Unexpected request: ${path}`);
    });
  });

  it("sorts article totals numerically in both directions without changing the selected document", async () => {
    localStorage.clear();
    vi.mocked(api.loadStockCounts).mockResolvedValue([summary, { ...summary, id: "inventory-2", number: "INV-2026-000002", lineCount: 10 }]);
    render(<StockCountScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const first = await screen.findByRole("row", { name: /INV-2026-000001/ });
    fireEvent.click(first);
    const header = screen.getByRole("columnheader", { name: /Art.culos/ });
    const sort = within(header).getByRole("button", { name: /Art.culos/, pressed: false });
    fireEvent.click(sort);
    expect(screen.getAllByRole("row")[1]).toHaveTextContent("INV-2026-000001");
    fireEvent.click(sort);
    expect(screen.getAllByRole("row")[1]).toHaveTextContent("INV-2026-000002");
    expect(first).toHaveAttribute("aria-selected", "true");
    expect(header).toHaveAttribute("aria-sort", "descending");
  });

  it("keeps inventory out of reach without warehouse permission", () => {
    render(<StockCountScreen session={session(["STOCK_READ"])} t={t} />);
    expect(screen.getByRole("alert")).toHaveTextContent("No tienes permiso para gestionar inventarios.");
    expect(api.loadStockCounts).not.toHaveBeenCalled();
  });

  it("opens an independent creation dialog without creating a document until save", async () => {
    render(<StockCountScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const create = await screen.findByRole("button", { name: "Crear inventario" });
    await waitFor(() => expect(create).toBeEnabled());
    fireEvent.click(create);
    const editor = await screen.findByRole("dialog", { name: "Documento de inventario" });
    expect(within(editor).getByRole("textbox", { name: "Número" })).toHaveValue("—");
    expect(api.createStockCount).not.toHaveBeenCalled();
    expect(screen.getByRole("row", { name: /INV-2026-000001/ })).toBeInTheDocument();
    fireEvent.click(within(editor).getByRole("button", { name: "Salir Esc" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Documento de inventario" })).not.toBeInTheDocument());
    expect(api.createStockCount).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("row", { name: /INV-2026-000001/ }));
    fireEvent.click(screen.getByRole("button", { name: "warehouseDocument.open" }));
    expect(await screen.findByRole("dialog", { name: "Documento de inventario" })).toHaveTextContent("INV-2026-000001");
    expect(api.loadStockCount).toHaveBeenCalledWith("inventory-1", "token");
  });

  it("filters documents locally and requires a separate cancellation prompt", async () => {
    vi.mocked(api.loadStockCounts).mockResolvedValue([
      summary,
      { ...summary, id: "inventory-2", number: "INV-2026-000002", documentDate: "2026-08-01", status: "CONFIRMED", notes: "Verano" }
    ]);
    vi.mocked(api.cancelStockCount).mockResolvedValue({ ...detail, status: "CANCELLED" });
    render(<StockCountScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    expect(await screen.findByRole("row", { name: /INV-2026-000001/ })).toBeInTheDocument();
    fireEvent.change(screen.getByRole("searchbox", { name: "warehouse.management.search" }), { target: { value: "Recuento" } });
    expect(screen.queryByRole("row", { name: /INV-2026-000002/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("row", { name: /INV-2026-000001/ }));
    fireEvent.click(screen.getByRole("button", { name: "Anular borrador" }));
    expect(api.cancelStockCount).not.toHaveBeenCalled();
    const prompt = screen.getByRole("alertdialog", { name: "Anular borrador" });
    fireEvent.click(within(prompt).getByRole("button", { name: "Cancelar" }));
    expect(api.cancelStockCount).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Anular borrador" }));
    fireEvent.click(within(screen.getByRole("alertdialog", { name: "Anular borrador" })).getByRole("button", { name: "Anular borrador" }));
    await waitFor(() => expect(api.cancelStockCount).toHaveBeenCalledWith("inventory-1", "token"));
  });

  it("routes F5/F6/F7 from the initially selected inventory and blocks them in dialogs", async () => {
    vi.stubGlobal("URL", class extends URL {
      static createObjectURL = vi.fn(() => "blob:inventory");
      static revokeObjectURL = vi.fn();
    });
    const download = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    vi.mocked(api.exportStockCount).mockResolvedValue(new Blob(["report"]));
    render(<StockCountScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const create = await screen.findByRole("button", { name: "Crear inventario" });
    await waitFor(() => expect(create).toBeEnabled());
    expect(create).toHaveAttribute("aria-keyshortcuts", "F5");
    const first = screen.getByRole("row", { name: /INV-2026-000001/ });
    expect(first).toHaveAttribute("aria-selected", "true");
    expect(first).toHaveFocus();
    expect(api.exportStockCount).not.toHaveBeenCalled();
    expect(api.loadStockCount).not.toHaveBeenCalled();
    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(api.exportStockCount).toHaveBeenCalledWith("inventory-1", "xlsx", "token"));
    await waitFor(() => expect(download).toHaveBeenCalledTimes(1));
    fireEvent.keyDown(window, { key: "F7" });
    await waitFor(() => expect(api.exportStockCount).toHaveBeenCalledWith("inventory-1", "pdf", "token"));
    await waitFor(() => expect(download).toHaveBeenCalledTimes(2));
    fireEvent.keyDown(window, { key: "F5" });
    const editor = await screen.findByRole("dialog", { name: "Documento de inventario" });
    fireEvent.keyDown(window, { key: "F6" });
    expect(api.exportStockCount).toHaveBeenCalledTimes(2);
    expect(api.createStockCount).not.toHaveBeenCalled();
    fireEvent.click(within(editor).getByRole("button", { name: "Salir Esc" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Documento de inventario" })).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Anular borrador" }));
    fireEvent.keyDown(window, { key: "F7" });
    expect(api.exportStockCount).toHaveBeenCalledTimes(2);
    expect(api.cancelStockCount).not.toHaveBeenCalled();
    download.mockRestore();
    vi.unstubAllGlobals();
  });

  it("keeps inventory actions disabled when the list is empty", async () => {
    vi.mocked(api.loadStockCounts).mockResolvedValue([]);
    render(<StockCountScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Crear inventario" })).toBeEnabled());
    expect(screen.getByRole("button", { name: /warehouse\.count\.exportExcel/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /warehouse\.count\.exportPdf/ })).toBeDisabled();
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });
    expect(api.exportStockCount).not.toHaveBeenCalled();
    expect(api.loadStockCount).not.toHaveBeenCalled();
  });

  it("filters inventory by the store-day presets and a custom range without preselecting a date", async () => {
    vi.mocked(api.loadStockCounts).mockResolvedValue([summary,
      { ...summary, id: "inventory-2", number: "INV-2026-000002", documentDate: "2026-09-23" },
      { ...summary, id: "inventory-3", number: "INV-2026-000003", documentDate: "2026-08-01" }]);
    render(<StockCountScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const dock = await screen.findByRole("contentinfo", { name: "Periodo seleccionado" });
    await waitFor(() => expect(within(dock).getByRole("button", { name: "Hoy" })).toBeEnabled());
    expect(within(dock).getByRole("button", { name: "Hoy" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getAllByRole("row", { name: /INV-2026/ })).toHaveLength(3);
    fireEvent.click(within(dock).getByRole("button", { name: "Hoy" }));
    expect(screen.getAllByRole("row", { name: /INV-2026/ })).toHaveLength(1);
    expect(screen.getByRole("row", { name: /INV-2026-000001/ })).toBeInTheDocument();
    fireEvent.click(within(dock).getByRole("button", { name: "Ayer" }));
    expect(screen.getByRole("row", { name: /INV-2026-000002/ })).toBeInTheDocument();
    fireEvent.change(within(dock).getByLabelText("Desde"), { target: { value: "2026-08-01" } });
    fireEvent.change(within(dock).getByLabelText("Hasta"), { target: { value: "2026-08-31" } });
    fireEvent.click(within(dock).getByRole("button", { name: "Aplicar" }));
    expect(screen.getByRole("row", { name: /INV-2026-000003/ })).toBeInTheDocument();
    expect(screen.queryByRole("row", { name: /INV-2026-000001/ })).not.toBeInTheDocument();
  });

  it("disables inventory date choices on metadata error and retries", async () => {
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/document-reports/date-options?report=stockCounts") {
        if (vi.mocked(apiRequest).mock.calls.filter(([value]) => value === path).length === 1) throw new Error("unavailable");
        return { currentDate: "2026-09-24", earliestDate: "2025-01-01" } as never;
      }
      if (path === "/stock-counts/resources") return { warehouses } as never;
      throw new Error(`Unexpected request: ${path}`);
    });
    render(<StockCountScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const dock = screen.getByRole("contentinfo", { name: "Periodo seleccionado" });
    expect(within(dock).getByRole("button", { name: "Hoy" })).toBeDisabled();
    fireEvent.click(await screen.findByRole("button", { name: "salesReport.retry" }));
    await waitFor(() => expect(within(dock).getByRole("button", { name: "Hoy" })).toBeEnabled());
  });
});
