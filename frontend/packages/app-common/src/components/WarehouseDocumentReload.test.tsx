// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { StrictMode, useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { buildExcelImportDraft } from "./excelImport";
import { restoreWarehouseDocumentLines, WarehouseDocumentDialog, type WarehouseDocumentView } from "./WarehouseDocumentDialog";
import type { SharedExcelImportAcceptedRow, SharedExcelImportMetadata } from "./SharedExcelImportDialog";

const importer = vi.hoisted(() => ({ accept: (_rows: SharedExcelImportAcceptedRow[], _metadata: SharedExcelImportMetadata) => {} }));
vi.mock("./SharedExcelImportDialog", () => ({ SharedExcelImportDialog: (props: { onImportAccepted: typeof importer.accept }) => {
  importer.accept = props.onImportAccepted;
  return null;
} }));
vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));

const created = { id: "excel-created", code: "NEW-001", barcode: "0000001234567", name: "Ficha del producto", purchasePrice: "2.150" };
const saved: WarehouseDocumentView = { id: "input-saved", warehouseId: "warehouse-1", supplierId: "supplier-1", date: "2026-09-08",
  documentType: "FACTURA_ENTRADA", status: "BORRADOR", priceSource: "PURCHASE", hasExcelImport: true,
  excelImportPendingSupplierUpdate: true, excelImportSnapshotToken: "WXP1.D.saved",
  lines: [{ productId: created.id, productCode: created.code, productName: "Nombre del documento", quantity: 6,
    purchaseUnitPrice: "2.150", discount: "5", priceOverridden: true }] };
const base = { mode: "input" as const, open: true, locale: "es" as const, token: "token", canConfirm: true,
  products: [], warehouses: [{ id: "warehouse-1", name: "GENERAL" }], customers: [],
  suppliers: [{ id: "supplier-1", legalName: "Proveedor" }], onClose: vi.fn(), onConfirmed: vi.fn(), documentType: "FACTURA_ENTRADA" as const };
const importRow: SharedExcelImportAcceptedRow = { rowNumber: 2, rowNumbers: [2], source: [], status: "accepted", product: created, quantity: 6,
  draft: { ...buildExcelImportDraft(["NEW-001", "Nombre del documento", "2.150"], { code: "A", name: "B", purchasePrice: "C" }),
    barcode: created.barcode, purchaseDiscountPercent: "5" }, errors: [], updateFields: {} };

describe("saved warehouse document product resolution", () => {
  beforeEach(() => { localStorage.clear(); vi.mocked(apiRequest).mockReset(); });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); });

  it("never discards persisted product identity or line values when the catalog is incomplete", () => {
    expect(restoreWarehouseDocumentLines(saved, [])[0]).toMatchObject({ productId: created.id, importedProduct: "NEW-001",
      productName: "Nombre del documento", quantity: 6, unitPrice: 2.15, discountPercent: "5", priceOverridden: true,
      valid: false, errorKey: "warehouseDocument.error.productNotFound" });
  });

  it("retains newly imported products through first save, repeated save and confirmation without catalog reloads", async () => {
    const confirmed = vi.fn();
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/warehouse-inputs" || path === "/warehouse-inputs/input-saved") return { ...saved } as never;
      if (path === "/warehouse-inputs/input-saved/confirm") return { ...saved, status: "CONFIRMADA" } as never;
      if (String(path).includes("ALBARAN_ENTRADA")) return { items: [] } as never;
      throw new Error("Unexpected request " + path);
    });
    function Parent() {
      const [document, setDocument] = useState<WarehouseDocumentView>({ ...saved, id: "", lines: [], excelImportSnapshotToken: null });
      return <WarehouseDocumentDialog {...base} document={document} onSaved={setDocument} onConfirmed={confirmed} />;
    }
    const view = render(<StrictMode><Parent /></StrictMode>);
    await act(async () => importer.accept([importRow], { formulas: [], updateSupplier: true, documentPriceSource: "purchasePrice" }));
    fireEvent.click(view.getByRole("button", { name: "Guardar (F9)" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/warehouse-inputs", expect.objectContaining({ method: "POST" })));
    await waitFor(() => expect(view.getByRole("button", { name: "Guardar (F9)" })).not.toBeDisabled());
    expect(view.container.querySelectorAll(".warehouse-document-line-error")).toHaveLength(0);
    expect(view.getByText(created.code)).toBeInTheDocument();
    expect(view.getByText(created.barcode)).toBeInTheDocument();
    fireEvent.click(view.getByRole("button", { name: "Guardar (F9)" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/warehouse-inputs/input-saved", expect.objectContaining({
      method: "PUT", body: expect.objectContaining({ expectedExcelImportSnapshotToken: "WXP1.D.saved",
        lines: [expect.objectContaining({ productId: created.id, quantity: 6, unitPrice: 2.15, discount: 5, productName: "Nombre del documento" })] })
    })));
    await waitFor(() => expect(view.getByRole("button", { name: "Confirmar" })).not.toBeDisabled());
    fireEvent.click(view.getByRole("button", { name: "Confirmar" }));
    await waitFor(() => expect(confirmed).toHaveBeenCalledOnce());
    const paths = vi.mocked(apiRequest).mock.calls.map(([path]) => path);
    expect(paths).not.toContain("/products/warehouse-options");
    expect(paths.filter(path => String(path).startsWith("/warehouse-inputs") && !String(path).includes("?")))
      .toEqual(["/warehouse-inputs", "/warehouse-inputs/input-saved", "/warehouse-inputs/input-saved", "/warehouse-inputs/input-saved/confirm"]);
  });

  it("recovers all missing products with one read and preserves document-specific edits", async () => {
    let resolveCatalog!: (value: unknown) => void;
    vi.mocked(apiRequest).mockImplementation((path) => path === "/products/warehouse-options"
      ? new Promise(resolve => { resolveCatalog = resolve; }) as never : Promise.resolve({ items: [] }) as never);
    const view = render(<WarehouseDocumentDialog {...base} document={saved} />);
    expect(view.getByRole("button", { name: "Guardar (F9)" })).toBeDisabled();
    fireEvent.change(view.getByRole("textbox", { name: "Comentarios" }), { target: { value: "Trabajo sin guardar" } });
    await act(async () => resolveCatalog([created]));
    await waitFor(() => expect(view.getByRole("button", { name: "Guardar (F9)" })).not.toBeDisabled());
    expect(view.getByText(created.barcode)).toBeInTheDocument();
    expect(view.getByText("Nombre del documento")).toBeInTheDocument();
    expect(view.getByRole("textbox", { name: "Comentarios" })).toHaveValue("Trabajo sin guardar");
    expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path === "/products/warehouse-options")).toHaveLength(1);
    expect(vi.mocked(apiRequest).mock.calls.every(([, options]) => !options?.method)).toBe(true);
  });

  it.each(["missing", "failure"])("keeps unresolved products blocked on %s without an automatic retry loop", async (outcome) => {
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path !== "/products/warehouse-options") return { items: [] } as never;
      if (outcome === "failure") throw new Error("Read failed");
      return [] as never;
    });
    const view = render(<WarehouseDocumentDialog {...base} document={saved} />);
    await waitFor(() => expect(view.container.querySelector(".warehouse-document-status")?.textContent).not.toBe(""));
    expect(view.getByRole("button", { name: "Guardar (F9)" })).toBeDisabled();
    expect(view.getByRole("button", { name: "Confirmar" })).toBeDisabled();
    expect(view.getByText("NEW-001")).toBeInTheDocument();
    expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path === "/products/warehouse-options")).toHaveLength(1);
  });

  it("ignores a late catalog response after switching to another document", async () => {
    let resolveCatalog!: (value: unknown) => void;
    vi.mocked(apiRequest).mockImplementation((path) => path === "/products/warehouse-options"
      ? new Promise(resolve => { resolveCatalog = resolve; }) as never : Promise.resolve({ items: [] }) as never);
    const view = render(<WarehouseDocumentDialog {...base} document={saved} />);
    view.rerender(<WarehouseDocumentDialog {...base} document={{ ...saved, id: "another", lines: [] }} />);
    await act(async () => resolveCatalog([created]));
    expect(view.queryByText(created.barcode)).not.toBeInTheDocument();
    expect(view.getByRole("button", { name: "Guardar (F9)" })).toBeDisabled();
  });

  it("resolves a late parent catalog without losing line identity", async () => {
    const view = render(<WarehouseDocumentDialog {...base} token={undefined} document={saved} />);
    view.rerender(<WarehouseDocumentDialog {...base} token={undefined} products={[created]} document={saved} />);
    await waitFor(() => expect(view.container.querySelectorAll(".warehouse-document-line-error")).toHaveLength(0));
    expect(view.getByText(created.barcode)).toBeInTheDocument();
    expect(apiRequest).not.toHaveBeenCalled();
  });
});
