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

  it("includes granular warehouse input and output permissions", () => {
    expect(stockPermissionMatrixColumns.map((column) => column.code)).toEqual(expect.arrayContaining([
      "WAREHOUSE_INPUTS_READ",
      "WAREHOUSE_INPUTS_WRITE",
      "WAREHOUSE_INPUTS_DELETE",
      "WAREHOUSE_INPUTS_CONFIRM",
      "WAREHOUSE_OUTPUTS_READ",
      "WAREHOUSE_OUTPUTS_EDIT",
      "WAREHOUSE_OUTPUTS_DELETE",
      "WAREHOUSE_OUTPUTS_CONFIRM"
    ]));
  });

  it("recognizes explicit permissions and the ALL role permission", () => {
    expect(roleHasStockPermission({ permissions: ["WAREHOUSE_INPUTS_READ"] }, "WAREHOUSE_INPUTS_READ")).toBe(true);
    expect(roleHasStockPermission({ permissions: ["ALL"] }, "WAREHOUSE_INPUTS_CONFIRM")).toBe(true);
    expect(roleHasStockPermission({ permissions: [] }, "WAREHOUSE_OUTPUTS_READ")).toBe(false);
  });

  it("edits stock permissions without losing unrelated role permissions", () => {
    const granted = setRoleStockPermission(editableRole, "WAREHOUSE_INPUTS_READ", true);
    const revoked = setRoleStockPermission(granted, "STOCK_READ", false);

    expect(granted.permissions).toEqual([
      "GESTION_VENTAS",
      "STOCK_READ",
      "WAREHOUSE_INPUTS_READ"
    ]);
    expect(revoked.permissions).toEqual(["GESTION_VENTAS", "WAREHOUSE_INPUTS_READ"]);
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
    const changedRole = setRoleStockPermission(editableRole, "WAREHOUSE_INPUTS_CONFIRM", true);
    apiRequestMock.mockResolvedValueOnce(changedRole);

    await expect(persistStockRolePermissions([changedRole], "admin-token"))
      .resolves.toEqual([changedRole]);
    expect(stockRolePermissionsPath(editableRole.id)).toBe("/roles/role%2Fstock/permissions");
    expect(apiRequestMock).toHaveBeenCalledWith("/roles/role%2Fstock/permissions", {
      method: "PUT",
      token: "admin-token",
      body: {
        codes: ["GESTION_VENTAS", "STOCK_READ", "WAREHOUSE_INPUTS_CONFIRM"]
      }
    });
  });

  it("renders the formal role matrix with input labels", () => {
    const html = renderToStaticMarkup(
      <StockPermissionsDialog open locale="es" onClose={vi.fn()} />
    );

    expect(html).toContain('class="filter-dialog stock-settings-dialog stock-permissions-dialog"');
    expect(html).toContain("Leer entradas");
    expect(html).toContain("Confirmar entradas");
    expect(html).toContain("Leer salidas");
    expect(html).toContain("Guardar");
  });
});
