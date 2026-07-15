import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
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

const products = [{ id: "product-1", code: "A001", name: "Cafe molido" }];
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
});
