// @vitest-environment jsdom

import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { tableLayoutStorageKey, writeStoredTableLayout } from "./tableLayoutPreferences";
import {
  buildWarehouseDocumentCommand,
  canConfirmWarehouseDocument,
  createManualWarehouseDocumentLine,
  documentLineTotal,
  documentTotalAfterDiscount,
  warehouseDocumentRequestErrorMessage,
  warehouseDocumentPath,
  WarehouseDocumentDialog
} from "./WarehouseDocumentDialog";

const products = [{ id: "product-1", code: "A001", barcode: "841000000001", name: "Cafe molido", salePrice: 4.5 }];
const warehouses = [{ id: "warehouse-1", name: "GENERAL" }];
const customers = [{ id: "customer-1", fiscalName: "Cliente SL", documentNumber: "B00000000" }];
const suppliers = [{ id: "supplier-1", legalName: "Proveedor SL", documentNumber: "B12345678" }];
const lines = [
  {
    rowNumber: 2,
    productId: "product-1",
    productLabel: "A001 - Cafe molido",
    importedProduct: "A001",
    quantity: 3,
    valid: true,
    errorKey: ""
  }
];

describe("WarehouseDocumentDialog", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it("renders output mode with document workspace and file actions", () => {
    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="output"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        canConfirm
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html).toContain("Salida almacén");
    expect(html).toContain("Archivo");
    expect(html).toContain("Guardar F9");
    expect(html).toContain("Salir Esc");
    expect(html).toContain("Cliente/Destino");
    expect(html).toContain("Descuento total documento %");
    expect(html).toContain("Importe total");
    expect(html).not.toContain("Acciones");
    expect(html).not.toContain("Eliminar</button>");
    expect(html).toContain("Confirmar");
    expect(html).toContain('class="warehouse-document-dialog warehouse-document-dialog-v2"');
    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
  });

  it("renders input mode with supplier fields", () => {
    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="input"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html).toContain("Entrada almacén");
    expect(html).toContain("Proveedor/Origen");
    expect(html).not.toContain("Cliente</span>");
  });

  it("keeps draft saving available and hides confirmation without explicit permission", () => {
    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="input"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html).toContain("Guardar F9");
    expect(html).toContain("Confirmar");
    expect(html).toContain('disabled=""');
  });

  it("blocks confirmation with no valid lines", () => {
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "supplier-1",
      partnerText: "",
      lines: []
    })).toBe(false);
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "supplier-1",
      partnerText: "",
      lines: [{ ...lines[0], valid: false, errorKey: "warehouseDocument.error.invalidQuantity" }]
    })).toBe(false);
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "",
      partnerText: "",
      lines
    })).toBe(true);
  });

  it("builds output and input commands for backend endpoints", () => {
    expect(warehouseDocumentPath("input")).toBe("/warehouse-inputs");
    expect(warehouseDocumentPath("output")).toBe("/warehouse-outputs");
    expect(buildWarehouseDocumentCommand("output", {
      warehouseId: "warehouse-1",
      partnerId: "customer-1",
      partnerText: "Cliente SL",
      date: "2026-07-08",
      concept: "Rotura",
      lines
    })).toEqual({
      warehouseId: "warehouse-1",
      date: "2026-07-08",
      destination: "Cliente SL",
      concept: "Rotura",
      lines: [{ productId: "product-1", quantity: 3 }]
    });
    expect(buildWarehouseDocumentCommand("input", {
      warehouseId: "warehouse-1",
      partnerId: "supplier-1",
      partnerText: "Proveedor SL",
      date: "2026-07-08",
      concept: "Compra",
      lines
    })).toEqual({
      warehouseId: "warehouse-1",
      date: "2026-07-08",
      supplierId: "supplier-1",
      origin: "Proveedor SL",
      concept: "Compra",
      lines: [{ productId: "product-1", quantity: 3 }]
    });
  });

  it("creates a valid manual line from the product master", () => {
    expect(createManualWarehouseDocumentLine("product-1", 4, products, 1)).toEqual(expect.objectContaining({
      productId: "product-1",
      productLabel: "A001 - Cafe molido",
      quantity: 4,
      valid: true
    }));
    expect(createManualWarehouseDocumentLine("missing", 4, products, 2).valid).toBe(false);
  });

  it("applies line discount before document discount", () => {
    const lineTotal = documentLineTotal(100, 2, "20");
    expect(lineTotal).toBe(160);
    expect(documentTotalAfterDiscount(lineTotal, "5")).toBe(152);
  });

  it("keeps backend conflict detail when available", () => {
    expect(warehouseDocumentRequestErrorMessage(
      new ApiError("La operacion entra en conflicto con los datos existentes", 409, { code: "DATA_INTEGRITY_CONFLICT" }),
      "No se pudo confirmar",
      {
        integrityConflict: "Revisa el documento",
        stateConflict: "Recarga el borrador"
      }
    )).toBe("La operacion entra en conflicto con los datos existentes");
  });

  it("persists output line order and widths while keeping existing and new rows aligned", async () => {
    writeStoredTableLayout("gestion", "maria", "warehouse.outputs.lines", [
      { key: "total", width: 160, visible: true },
      { key: "code", width: 180, visible: true },
      { key: "barcode", width: 200, visible: true },
      { key: "name", width: 260, visible: true },
      { key: "discount", width: 150, visible: true },
      { key: "price", width: 120, visible: true },
      { key: "quantity", width: 170, visible: true }
    ], localStorage);

    const { container } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        app="gestion"
        username="maria"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        document={{
          id: "output-1",
          number: null,
          warehouseId: "warehouse-1",
          date: "2026-07-15",
          status: "BORRADOR",
          lines: [{ productId: "product-1", quantity: 3 }]
        }}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    await waitFor(() => expect(container.querySelectorAll("tbody tr").length).toBeGreaterThan(1));

    const headerKeys = () => Array.from(container.querySelectorAll<HTMLElement>("thead [data-column-key]"))
      .map((header) => header.dataset.columnKey);
    const firstRowCells = () => Array.from(container.querySelectorAll<HTMLTableCellElement>("tbody tr:first-child td"));

    expect(headerKeys().slice(0, 2)).toEqual(["total", "code"]);
    expect(firstRowCells()[0].textContent).toContain("13,50");
    expect(firstRowCells()[1].querySelector("input")?.value).toBe("A001");
    expect(Array.from(container.querySelectorAll("colgroup col")).map((col) => (col as HTMLElement).style.width).slice(0, 2))
      .toEqual(["160px", "180px"]);
    expect(Array.from(container.querySelectorAll<HTMLElement>("thead [data-column-key]")).every((header) => header.draggable))
      .toBe(true);

    const values = new Map<string, string>();
    const dataTransfer = {
      effectAllowed: "move",
      dropEffect: "move",
      setData: (type: string, value: string) => values.set(type, value),
      getData: (type: string) => values.get(type) ?? ""
    };
    fireEvent.dragStart(container.querySelector('[data-column-key="name"]') as HTMLElement, { dataTransfer });
    fireEvent.dragOver(container.querySelector('[data-column-key="total"]') as HTMLElement, { dataTransfer });
    fireEvent.drop(container.querySelector('[data-column-key="total"]') as HTMLElement, { dataTransfer });
    expect(headerKeys()[0]).toBe("name");
    expect(firstRowCells()[0].textContent).toContain("Cafe molido");

    fireEvent.keyDown(container.querySelector('[data-column-key="name"]') as HTMLElement, {
      key: "ArrowRight",
      ctrlKey: true
    });
    expect(headerKeys().slice(0, 2)).toEqual(["total", "name"]);
    expect(firstRowCells()[0].textContent).toContain("13,50");

    const nameHeader = container.querySelector('[data-column-key="name"]') as HTMLElement;
    fireEvent.keyDown(nameHeader.querySelector("button") as HTMLButtonElement, { key: "ArrowRight" });
    const stored = JSON.parse(localStorage.getItem(
      tableLayoutStorageKey("gestion", "maria", "warehouse.outputs.lines")
    ) ?? "[]") as Array<{ key: string; width: number }>;
    expect(stored.map((column) => column.key).slice(0, 2)).toEqual(["total", "name"]);
    expect(stored.find((column) => column.key === "name")?.width).toBe(268);
  });

  it("uses the independent input line preference key", () => {
    writeStoredTableLayout("venta", "ana", "warehouse.inputs.lines", [
      { key: "quantity", width: 170, visible: true },
      { key: "code", width: 180, visible: true }
    ], localStorage);

    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="input"
        open
        app="venta"
        username="ana"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html.indexOf('data-column-key="quantity"')).toBeLessThan(html.indexOf('data-column-key="code"'));
  });
});
