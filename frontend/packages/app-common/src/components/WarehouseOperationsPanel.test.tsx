import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  WarehouseOperationsPanel,
  warehouseOperationsCanDelete,
  warehouseOperationsCanOpen,
  warehouseOperationsDelete,
  warehouseOperationsErrorMessage,
  warehouseOperationsFilter,
  warehouseOperationsLoad,
  warehouseOperationsNextId,
  warehouseOperationsResolvePermissions,
  warehouseOperationsTotalUnits,
  type WarehouseOperationView,
  type WarehouseOperationsPanelRequest
} from "./WarehouseOperationsPanel";

const products = [{ id: "product-1", code: "A001", name: "Cafe molido" }];
const warehouses = [
  { id: "warehouse-1", name: "GENERAL" },
  { id: "warehouse-2", name: "TIENDA" }
];
const customers = [{ id: "customer-1", fiscalName: "Cliente Uno", documentNumber: "B00000000" }];
const suppliers = [{ id: "supplier-1", legalName: "Proveedor Norte", documentNumber: "B12345678" }];
const draft: WarehouseOperationView = {
  id: "input-1",
  warehouseId: "warehouse-1",
  supplierId: "supplier-1",
  number: null,
  date: "2026-07-11",
  origin: "Proveedor Norte - B12345678",
  concept: "Reposicion",
  status: "BORRADOR",
  lines: [
    { productId: "product-1", quantity: 3 },
    { productId: "product-2", quantity: 2 }
  ]
};
const confirmed: WarehouseOperationView = {
  ...draft,
  id: "input-2",
  warehouseId: "warehouse-2",
  number: "ENT-2026-000001",
  origin: "Proveedor Sur",
  status: "CONFIRMADA",
  lines: [{ productId: "product-1", quantity: 4 }]
};

const messages: Record<string, string> = {
  "common.delete": "Eliminar",
  "common.loading": "Cargando...",
  "salesReport.column.date": "Fecha",
  "salesReport.filter.all": "Todos",
  "salesReport.filter.status": "Estado",
  "salesReport.search": "Buscar",
  "stock.column.warehouse": "Almacen",
  "stock.nav.inputWarehouse": "Entrada almacen",
  "warehouseDocument.create": "Crear documento",
  "warehouseDocument.edit": "Editar borrador",
  "warehouseDocument.supplier": "Proveedor",
  "warehouseDocument.view": "Consultar documento"
};
const t = (key: string) => messages[key] ?? key;

describe("WarehouseOperationsPanel", () => {
  it("loads the input and output collections from their existing GET endpoints", async () => {
    const request = vi.fn().mockResolvedValue([draft]) as unknown as WarehouseOperationsPanelRequest;

    await expect(warehouseOperationsLoad("input", "token-1", request)).resolves.toEqual([draft]);
    expect(request).toHaveBeenCalledWith("/warehouse-inputs", { token: "token-1" });

    await warehouseOperationsLoad("output", "token-2", request);
    expect(request).toHaveBeenLastCalledWith("/warehouse-outputs", { token: "token-2" });
  });

  it("filters by status and searches number, counterparty and warehouse labels", () => {
    const context = {
      mode: "input" as const,
      warehouses,
      customers,
      suppliers
    };

    expect(warehouseOperationsFilter([draft, confirmed], {
      ...context,
      query: "ENT-2026",
      status: "CONFIRMADA"
    })).toEqual([confirmed]);
    expect(warehouseOperationsFilter([draft, confirmed], {
      ...context,
      query: "proveedor norte",
      status: ""
    })).toEqual([draft]);
    expect(warehouseOperationsFilter([draft, confirmed], {
      ...context,
      query: "tienda",
      status: ""
    })).toEqual([confirmed]);
    expect(warehouseOperationsTotalUnits(draft)).toBe(5);
  });

  it("opens confirmed documents for consultation and drafts only with edit permission", () => {
    expect(warehouseOperationsCanOpen(draft, { read: true, edit: true })).toBe(true);
    expect(warehouseOperationsCanOpen(draft, { read: true, edit: false })).toBe(false);
    expect(warehouseOperationsCanOpen(confirmed, { read: true, edit: false })).toBe(true);
    expect(warehouseOperationsCanOpen(confirmed, { read: false, edit: true })).toBe(false);

    expect(warehouseOperationsNextId([draft, confirmed], draft.id, "ArrowDown")).toBe(confirmed.id);
    expect(warehouseOperationsNextId([draft, confirmed], confirmed.id, "ArrowUp")).toBe(draft.id);
    expect(warehouseOperationsNextId([draft, confirmed], "", "Home")).toBe(draft.id);
  });

  it("deletes only drafts through the existing DELETE endpoint", async () => {
    const request = vi.fn().mockResolvedValue(undefined) as unknown as WarehouseOperationsPanelRequest;

    await warehouseOperationsDelete("input", draft, "token", request);
    expect(request).toHaveBeenCalledWith("/warehouse-inputs/input-1", {
      token: "token",
      method: "DELETE"
    });

    await expect(warehouseOperationsDelete("input", confirmed, "token", request))
      .rejects.toThrow("warehouse_operation_not_draft");
    expect(request).toHaveBeenCalledTimes(1);
  });

  it("applies operation and confirmation permissions without weakening draft rules", () => {
    expect(warehouseOperationsResolvePermissions({
      read: true,
      create: false,
      edit: false,
      delete: true,
      canConfirm: true
    })).toEqual({ read: true, create: false, edit: false, delete: true, canConfirm: true });
    expect(warehouseOperationsResolvePermissions(undefined).canConfirm).toBe(false);
    expect(warehouseOperationsCanDelete(draft, { read: true, delete: true })).toBe(true);
    expect(warehouseOperationsCanDelete(confirmed, { read: true, delete: true })).toBe(false);
    expect(warehouseOperationsCanDelete(draft, { read: true, delete: false })).toBe(false);

    const html = renderToStaticMarkup(
      <WarehouseOperationsPanel
        mode="input"
        token="token"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        permissions={{ read: true, create: false, edit: false, delete: false, canConfirm: false }}
        t={t}
      />
    );

    expect(html).not.toContain("Crear documento");
    expect(html).not.toContain(">Eliminar<");
    expect(html).toContain("Consultar documento");
    expect(html).toContain("disabled");
  });

  it("renders the formal operational columns and initial loading state", () => {
    const html = renderToStaticMarkup(
      <WarehouseOperationsPanel
        mode="input"
        token="token"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        t={t}
      />
    );

    expect(html).toContain("Entrada almacen");
    expect(html).toContain("Numero");
    expect(html).toContain("Fecha");
    expect(html).toContain("Proveedor");
    expect(html).toContain("Almacen");
    expect(html).toContain("Lineas");
    expect(html).toContain("Total unidades");
    expect(html).toContain("Cargando...");
    expect(html).not.toContain("Imprimir");
    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
  });

  it("does not expose low-level write errors in the operations list", () => {
    expect(warehouseOperationsErrorMessage(
      new TypeError("Failed to write request"),
      "No se pudieron cargar los documentos de almacen"
    )).toBe("No se pudieron cargar los documentos de almacen");
    expect(warehouseOperationsErrorMessage(
      new Error("Failed to write request"),
      "No se pudieron cargar los documentos de almacen"
    )).toBe("No se pudieron cargar los documentos de almacen");
  });
});
