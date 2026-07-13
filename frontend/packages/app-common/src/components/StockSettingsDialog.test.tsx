import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  normalizeStockMinimum,
  roleHasStockPermission,
  stockMinimumPath,
  stockPermissionMatrixColumns,
  StockSettingsDialog
} from "./StockSettingsDialog";

describe("StockSettingsDialog", () => {
  it("uses the product and warehouse minimum endpoint", () => {
    expect(stockMinimumPath("product/1", "warehouse 1"))
      .toBe("/stock/minimums/product%2F1/warehouse%201");
    expect(normalizeStockMinimum({ minimumStock: 7 })).toBe(7);
    expect(normalizeStockMinimum({ minimum: "4" })).toBe(4);
    expect(normalizeStockMinimum({})).toBeNull();
  });

  it("only marks permissions returned by the security API", () => {
    expect(roleHasStockPermission({ permissions: ["STOCK_READ"] }, "STOCK_READ")).toBe(true);
    expect(roleHasStockPermission({ permissions: ["STOCK_READ"] }, "STOCK_ADJUST")).toBe(false);
    expect(roleHasStockPermission({ permissions: ["ALL"] }, "WAREHOUSE_OUTPUTS_CONFIRM")).toBe(true);
    expect(stockPermissionMatrixColumns.map((item) => item.code)).toContain("GESTION_PRODUCTO");
  });

  it("renders formal global and selected product settings", () => {
    const html = renderToStaticMarkup(
      <StockSettingsDialog
        open
        mode="configuration"
        locale="es"
        token="token"
        warehouses={[{ id: "warehouse-1", name: "GENERAL", active: true }]}
        selectedWarehouseId="warehouse-1"
        selectedProduct={{ id: "product-1", name: "Cafe" }}
        isAdmin
        canEdit
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("Configuración stock");
    expect(html).toContain("Almacén predeterminado");
    expect(html).toContain("Permitir stock negativo");
    expect(html).toContain("Mínimo específico");
    expect(html).toContain("Cafe / GENERAL");
    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
  });

  it("shows an access state instead of invented role data to non admins", () => {
    const html = renderToStaticMarkup(
      <StockSettingsDialog
        open
        mode="permissions"
        locale="es"
        warehouses={[]}
        isAdmin={false}
        canEdit={false}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("Permisos stock");
    expect(html).toContain("Lectura");
    expect(html).not.toContain("ADMIN</td>");
  });
});
