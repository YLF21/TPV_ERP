import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  normalizeStockMinimum,
  persistStockSettings,
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
    expect(roleHasStockPermission({ permissions: ["ALL"] }, "GESTION_ALMACEN")).toBe(true);
    expect(stockPermissionMatrixColumns.map((item) => item.code)).toContain("GESTION_ALMACEN");
    expect(stockPermissionMatrixColumns.map((item) => item.code)).toContain("GESTION_PRODUCTO");
  });

  it("renders formal global and selected product settings", () => {
    const html = renderToStaticMarkup(
      <StockSettingsDialog
        open
        mode="configuration"
        app="venta"
        username="admin"
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
    expect(html).toContain("Permitir vender productos desactivados");
    expect(html).toContain("Mínimo específico");
    expect(html).toContain("Cafe / GENERAL");
    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
  });

  it("uses the restricted PATCH endpoint for inactive product sales", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({
        defaultWarehouseId: "warehouse-1",
        allowNegativeStock: false,
        allowInactiveProductSales: false,
        defaultMinimumStock: 0,
        alertsEnabled: true
      })
      .mockResolvedValueOnce({ allowInactiveProductSales: true });

    const saved = await persistStockSettings({
      defaultWarehouseId: "warehouse-1",
      allowNegativeStock: false,
      allowInactiveProductSales: true,
      defaultMinimumStock: 0,
      alertsEnabled: true
    }, false, true, "token", request);

    expect(request).toHaveBeenNthCalledWith(1, "/stock/settings", {
      token: "token",
      method: "PUT",
      body: {
        defaultWarehouseId: "warehouse-1",
        allowNegativeStock: false,
        defaultMinimumStock: 0,
        alertsEnabled: true
      }
    });
    expect(request).toHaveBeenNthCalledWith(2, "/stock/settings/inactive-product-sales", {
      token: "token",
      method: "PATCH",
      body: { allowInactiveProductSales: true }
    });
    expect(saved.allowInactiveProductSales).toBe(true);
  });

  it("does not call the restricted endpoint without product management permission", async () => {
    const request = vi.fn().mockResolvedValue({
      defaultWarehouseId: "warehouse-1",
      allowNegativeStock: false,
      allowInactiveProductSales: false,
      defaultMinimumStock: 0,
      alertsEnabled: true
    });

    const saved = await persistStockSettings({
      defaultWarehouseId: "warehouse-1",
      allowNegativeStock: false,
      allowInactiveProductSales: true,
      defaultMinimumStock: 0,
      alertsEnabled: true
    }, false, false, "token", request);

    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith("/stock/settings", expect.objectContaining({ method: "PUT" }));
    expect(saved.allowInactiveProductSales).toBe(false);
  });

  it("hides the inactive sale setting without product management permission", () => {
    const html = renderToStaticMarkup(
      <StockSettingsDialog
        open
        mode="configuration"
        app="venta"
        username="warehouse-manager"
        locale="es"
        warehouses={[]}
        isAdmin={false}
        canEdit
        onClose={vi.fn()}
      />
    );

    expect(html).not.toContain("Permitir vender productos desactivados");
  });

  it("shows the inactive sale setting to product managers", () => {
    const html = renderToStaticMarkup(
      <StockSettingsDialog
        open
        mode="configuration"
        app="venta"
        username="product-manager"
        locale="es"
        warehouses={[]}
        isAdmin={false}
        canEdit
        canManageProducts
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("Permitir vender productos desactivados");
  });

  it("shows an access state instead of invented role data to non admins", () => {
    const html = renderToStaticMarkup(
      <StockSettingsDialog
        open
        mode="permissions"
        app="venta"
        username="admin"
        locale="es"
        warehouses={[]}
        isAdmin={false}
        canEdit={false}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("Permisos stock");
    expect(html).toContain("Lectura");
    expect(html).toContain('data-column-key="role"');
    expect(html).not.toContain("ADMIN</td>");
  });
});
