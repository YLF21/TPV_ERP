// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { openWarehouseDocumentPreview } from "../warehouse/warehouseDocumentPrinting";
import { SharedExcelImportDialog } from "./SharedExcelImportDialog";
import { WarehouseDocumentDialog, type WarehouseDocumentView } from "./WarehouseDocumentDialog";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
vi.mock("../warehouse/warehouseDocumentPrinting", async (original) => ({
  ...await original<typeof import("../warehouse/warehouseDocumentPrinting")>(),
  openWarehouseDocumentPreview: vi.fn(() => true)
}));
vi.mock("./SharedExcelImportDialog", () => ({ SharedExcelImportDialog: vi.fn(() => null) }));

const product = { id: "product-1", code: "A001", barcode: "841000000001", name: "Cafe",
  purchasePrice: 10, salePrice: 20, memberPrice: 18, wholesalePrice: 15, offerPrice: 12 };
const document: WarehouseDocumentView = {
  id: "transfer-1", number: "TR-2026-000001", warehouseId: "origin", targetWarehouseId: "target",
  version: 4, date: "2026-09-23", status: "BORRADOR", priceSource: "PURCHASE", globalDiscount: 5,
  lines: [{ productId: product.id, productName: "Cafe", quantity: 2, purchaseUnitPrice: 10, discount: 10 }]
};

function setup() {
  const saved = { ...document, version: 5 };
  const persistence = { save: vi.fn().mockResolvedValue(saved), confirm: vi.fn().mockResolvedValue({ ...saved, status: "CONFIRMADA" }) };
  const onSaved = vi.fn();
  const onConfirmed = vi.fn();
  const view = render(<WarehouseDocumentDialog mode="transfer" app="gestion" open locale="es" token="token"
    products={[product]} warehouses={[{ id: "origin", name: "Origen" }, { id: "target", name: "Destino" }]}
    customers={[]} suppliers={[]} document={document} persistence={persistence} canConfirm
    onClose={vi.fn()} onSaved={onSaved} onConfirmed={onConfirmed} />);
  const dialog = view.container.querySelector<HTMLElement>(".warehouse-document-overlay")!;
  const menu = () => { fireEvent.click(screen.getByRole("button", { name: "Archivo" })); return within(screen.getByRole("menu")); };
  return { ...view, dialog, menu, persistence, onSaved, onConfirmed, saved };
}

describe("Warehouse transfer document shared workspace", () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it("provides the full file menu, distinct warehouses and all five document prices", () => {
    const view = setup();
    expect(screen.getByRole("button", { name: "Almacén de origen" })).toHaveTextContent("Origen");
    expect(screen.getByRole("button", { name: "Almacén de destino" })).toHaveTextContent("Destino");
    expect(screen.queryByText("Proveedor")).not.toBeInTheDocument();
    const menu = view.menu();
    for (const name of ["Almacén de destino", "Guardar", "Confirmar", "Vista previa Ctrl+P", "Imprimir",
      "Eliminar todos los artículos", "Eliminar todos los descuentos", "Importar Excel", "Exportar Excel", "Usar precio", "Salir"]) {
      expect(menu.getByRole("button", { name })).toBeInTheDocument();
    }
    fireEvent.click(menu.getByRole("button", { name: "Usar precio" }));
    for (const name of ["Precio de compra", "Precio de venta", "Precio de miembro", "Precio mayorista", "Precio de oferta"]) {
      expect(screen.getByRole("button", { name })).toBeInTheDocument();
    }
    expect(view.persistence.save).not.toHaveBeenCalled();
  });

  it("saves edited quantity, name, price and discount with F9 and the current version", async () => {
    const view = setup();
    fireEvent.doubleClick(view.container.querySelector("tbody tr")!);
    const editor = within(screen.getByRole("dialog", { name: "Editar línea" }));
    fireEvent.change(editor.getByLabelText("Cantidad"), { target: { value: "3" } });
    fireEvent.change(editor.getByLabelText("Nombre"), { target: { value: "Cafe traslado" } });
    fireEvent.change(editor.getByLabelText("Precio"), { target: { value: "25" } });
    fireEvent.change(editor.getByLabelText("Descuento"), { target: { value: "20" } });
    fireEvent.click(editor.getByRole("button", { name: "Guardar" }));
    fireEvent.keyDown(view.dialog, { key: "F9" });
    await waitFor(() => expect(view.persistence.save).toHaveBeenCalledOnce());
    expect(view.persistence.save).toHaveBeenCalledWith(expect.objectContaining({ warehouseId: "origin", targetWarehouseId: "target",
      lines: [expect.objectContaining({ quantity: 3, productName: "Cafe traslado", unitPrice: 25, discountPercent: "20", priceOverridden: true })]
    }), "transfer-1", 4);
    expect(view.onSaved).toHaveBeenCalledWith(view.saved);
    expect(view.persistence.confirm).not.toHaveBeenCalled();
  });

  it("clears line and document discounts, then clears all items without writing", () => {
    const view = setup();
    fireEvent.click(view.menu().getByRole("button", { name: "Eliminar todos los descuentos" }));
    expect(view.container.querySelector("tbody tr")!.textContent).toContain("20,00");
    expect(screen.getByLabelText("Descuento total del documento %")).toHaveValue(0);
    fireEvent.click(view.menu().getByRole("button", { name: "Eliminar todos los artículos" }));
    expect(screen.queryByText("Cafe")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar (F9)" })).toBeDisabled();
    expect(view.persistence.save).not.toHaveBeenCalled();
  });

  it("opens Ctrl+P preview without saving or confirming and confirms only after save", async () => {
    const view = setup();
    fireEvent.keyDown(view.dialog, { key: "p", ctrlKey: true });
    expect(openWarehouseDocumentPreview).toHaveBeenCalledOnce();
    expect(view.persistence.save).not.toHaveBeenCalled();
    expect(view.persistence.confirm).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    await waitFor(() => expect(view.persistence.confirm).toHaveBeenCalledWith(view.saved));
    expect(view.persistence.save).toHaveBeenCalledOnce();
    expect(view.onConfirmed).toHaveBeenCalledWith(expect.objectContaining({ status: "CONFIRMADA" }));
  });

  it("opens the full transfer Excel importer and exports the current draft without saving it", async () => {
    vi.mocked(apiRequest).mockResolvedValue(new Blob(["xlsx"]));
    vi.stubGlobal("URL", class extends URL { static createObjectURL = vi.fn(() => "blob:export"); static revokeObjectURL = vi.fn(); });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const view = setup();
    fireEvent.click(view.menu().getByRole("button", { name: "Importar Excel" }));
    expect(vi.mocked(SharedExcelImportDialog).mock.calls.at(-1)?.[0]).toMatchObject({ open: true,
      context: "WAREHOUSE_TRANSFER", requireQuantity: true, showDocumentPriceSource: true,
      warehouseId: "origin", documentDate: "2026-09-23", supplier: null });
    fireEvent.click(view.menu().getByRole("button", { name: "Exportar Excel" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/warehouse-transfers/export.xlsx", expect.objectContaining({
      method: "POST", responseType: "blob", body: expect.objectContaining({ sourceWarehouseId: "origin", targetWarehouseId: "target",
        number: "TR-2026-000001", locale: "es", lines: [expect.objectContaining({ quantity: 2, unitPrice: 10, discount: 10 })] })
    })));
    expect(view.persistence.save).not.toHaveBeenCalled();
    expect(view.persistence.confirm).not.toHaveBeenCalled();
  });
});
