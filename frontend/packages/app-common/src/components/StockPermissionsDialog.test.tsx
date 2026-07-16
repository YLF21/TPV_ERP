import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  persistStockRolePermissions,
  roleHasStockPermission,
  rolesHaveSamePermissions,
  setRoleStockPermission,
  stockPermissionMatrixColumns,
  stockRolePermissionsPath,
  type StockSecurityRole,
  StockPermissionsDialog
} from "./StockPermissionsDialog";

const apiRequestMock = vi.hoisted(() => vi.fn());

vi.mock("../api/client", () => ({ apiRequest: apiRequestMock }));

const editableRole: StockSecurityRole = {
  id: "role/stock",
  name: "ENCARGADO",
  protectedRole: false,
  permissions: ["GESTION_VENTAS", "STOCK_READ"]
};

describe("StockPermissionsDialog", () => {
  beforeEach(() => {
    apiRequestMock.mockReset();
  });

  it("uses the unified warehouse management permission", () => {
    const codes = stockPermissionMatrixColumns.map((column) => column.code);

    expect(codes).toContain("GESTION_ALMACEN");
    expect(codes.some((code) => code.startsWith("WAREHOUSE_INPUTS_") || code.startsWith("WAREHOUSE_OUTPUTS_")))
      .toBe(false);
  });

  it("recognizes explicit permissions and the ALL role permission", () => {
    expect(roleHasStockPermission({ permissions: ["GESTION_ALMACEN"] }, "GESTION_ALMACEN")).toBe(true);
    expect(roleHasStockPermission({ permissions: ["ALL"] }, "GESTION_ALMACEN")).toBe(true);
    expect(roleHasStockPermission({ permissions: [] }, "GESTION_ALMACEN")).toBe(false);
  });

  it("edits stock permissions without losing unrelated role permissions", () => {
    const granted = setRoleStockPermission(editableRole, "GESTION_ALMACEN", true);
    const revoked = setRoleStockPermission(granted, "STOCK_READ", false);

    expect(granted.permissions).toEqual([
      "GESTION_ALMACEN",
      "GESTION_VENTAS",
      "STOCK_READ"
    ]);
    expect(revoked.permissions).toEqual(["GESTION_ALMACEN", "GESTION_VENTAS"]);
    expect(rolesHaveSamePermissions(
      { permissions: ["STOCK_READ", "GESTION_VENTAS"] },
      { permissions: ["GESTION_VENTAS", "STOCK_READ"] }
    )).toBe(true);
  });

  it("keeps the protected ADMIN role immutable", () => {
    const admin: StockSecurityRole = {
      id: "admin",
      name: "ADMIN",
      protectedRole: true,
      permissions: ["ALL"]
    };

    expect(setRoleStockPermission(admin, "STOCK_READ", false)).toBe(admin);
  });

  it("persists the complete role assignment through the real role endpoint", async () => {
    const changedRole = setRoleStockPermission(editableRole, "GESTION_ALMACEN", true);
    apiRequestMock.mockResolvedValueOnce(changedRole);

    await expect(persistStockRolePermissions([changedRole], "admin-token"))
      .resolves.toEqual([changedRole]);
    expect(stockRolePermissionsPath(editableRole.id)).toBe("/roles/role%2Fstock/permissions");
    expect(apiRequestMock).toHaveBeenCalledWith("/roles/role%2Fstock/permissions", {
      method: "PUT",
      token: "admin-token",
      body: {
        codes: ["GESTION_ALMACEN", "GESTION_VENTAS", "STOCK_READ"]
      }
    });
  });

  it("renders the formal role matrix with unified warehouse management", () => {
    const html = renderToStaticMarkup(
      <StockPermissionsDialog open app="venta" username="admin" locale="es" onClose={vi.fn()} />
    );

    expect(html).toContain('class="filter-dialog stock-settings-dialog stock-permissions-dialog"');
    expect(html).toContain("Gestión almacén");
    expect(html).not.toContain("Leer entradas");
    expect(html).not.toContain("Leer salidas");
    expect(html).toContain("Guardar");
    expect(html).toContain('data-column-key="role"');
    expect(html).toContain('class="table-layout-column-resizer"');
  });
});
