// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { SecurityAdministrationScreen, canManageRoles, canManageUsers } from "./SecurityAdministrationScreen";
import * as api from "./securityAdministrationApi";

vi.mock("./securityAdministrationApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./securityAdministrationApi")>();
  return {
    ...original,
    loadSecurityUsers: vi.fn(),
    loadRoleOptions: vi.fn(),
    createSecurityUser: vi.fn(),
    updateSecurityUserIdentity: vi.fn(),
    updateSecurityUserRole: vi.fn(),
    updateSecurityUserActive: vi.fn(),
    resetSecurityUserPassword: vi.fn(),
    loadSecurityRoles: vi.fn(),
    loadPermissionCatalog: vi.fn(),
    createSecurityRole: vi.fn(),
    renameSecurityRole: vi.fn(),
    deleteSecurityRole: vi.fn(),
    saveSecurityRolePermissions: vi.fn()
  };
});

const admin: api.SecurityUser = {
  id: "admin-id", userId: "00000001", name: "ADMIN", userName: "ADMIN",
  role: "ADMIN", active: true, protectedUser: true
};
const cashier: api.SecurityUser = {
  id: "user-id", userId: "00000002", name: "CAJERO", userName: "cajero",
  role: "CAJA", active: true, protectedUser: false
};
const role: api.SecurityRole = {
  id: "role-id", name: "CAJA", protectedRole: false, permissions: ["VENTA"]
};
const adminRole: api.SecurityRole = {
  id: "admin-role", name: "ADMIN", protectedRole: true, permissions: ["ALL"]
};

function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "ADMIN", displayName: "ADMIN", accessToken: "token", permissions };
}

beforeEach(() => {
  vi.mocked(api.loadSecurityUsers).mockResolvedValue([admin, cashier]);
  vi.mocked(api.loadRoleOptions).mockResolvedValue([{ id: "role-id", name: "CAJA" }]);
  vi.mocked(api.loadSecurityRoles).mockResolvedValue([adminRole, role]);
  vi.mocked(api.loadPermissionCatalog).mockResolvedValue([
    { code: "VENTA", translationKey: "document.permissions.sales.operate", group: "DOCUMENTS" },
    { code: "GESTION_VENTAS", translationKey: "document.permissions.sales.manage", group: "DOCUMENTS" }
  ]);
  vi.mocked(api.updateSecurityUserIdentity).mockResolvedValue({ ...cashier, name: "CAJERO PRINCIPAL" });
  vi.mocked(api.renameSecurityRole).mockResolvedValue({ ...role, name: "SUPERVISOR" });
  vi.mocked(api.deleteSecurityRole).mockResolvedValue(undefined);
  vi.mocked(api.saveSecurityRolePermissions).mockResolvedValue({ ...role, permissions: ["GESTION_VENTAS", "VENTA"] });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 404 })));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("SecurityAdministrationScreen", () => {
  it("keeps user and role administration as independent permissions", () => {
    expect(canManageUsers(session(["GESTION_USUARIO"]))).toBe(true);
    expect(canManageUsers(session(["ROLES_MANAGE"]))).toBe(false);
    expect(canManageRoles(session(["ROLES_MANAGE"]))).toBe(true);
    expect(canManageRoles(session(["GESTION_USUARIO"]))).toBe(false);
  });

  it("loads real users, protects ADMIN and edits a normal identity", async () => {
    render(<SecurityAdministrationScreen mode="users" session={session(["ADMIN"])} t={(key) => key} />);

    expect(await screen.findByText("CAJERO")).not.toBeNull();
    expect(screen.getByText("gestion.users.adminProtected")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "gestion.users.resetPassword" })).toBeNull();

    fireEvent.click(screen.getByRole("row", { name: /00000002CAJEROcajeroCAJA/ }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.users.editIdentity" }));
    const dialog = screen.getByRole("dialog", { name: "gestion.users.dialog.identity" });
    fireEvent.change(within(dialog).getByDisplayValue("CAJERO"), { target: { value: "CAJERO PRINCIPAL" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));

    await waitFor(() => expect(api.updateSecurityUserIdentity).toHaveBeenCalledWith(
      "user-id", { name: "CAJERO PRINCIPAL", userName: "cajero" }, "token"
    ));
  });

  it("edits grouped permissions but keeps the protected ADMIN role read-only", async () => {
    render(<SecurityAdministrationScreen mode="roles" session={session(["ADMIN"])} t={(key) => key} />);

    expect(await screen.findByText("gestion.roles.adminProtected")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "gestion.roles.rename" })).toBeNull();
    expect(screen.queryByRole("button", { name: "gestion.roles.delete" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /CAJA/ }));
    const manageSales = screen.getByRole("checkbox", { name: /GESTION VENTAS/ });
    fireEvent.click(manageSales);
    fireEvent.click(screen.getByRole("button", { name: "gestion.roles.savePermissions" }));

    await waitFor(() => expect(api.saveSecurityRolePermissions).toHaveBeenCalledWith(
      "role-id", ["GESTION_VENTAS", "VENTA"], "token"
    ));
  });

  it("renames a configurable role", async () => {
    render(<SecurityAdministrationScreen mode="roles" session={session(["ROLES_MANAGE"])} t={(key) => key} />);

    fireEvent.click(await screen.findByRole("button", { name: /CAJA/ }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.roles.rename" }));
    const dialog = screen.getByRole("dialog", { name: "gestion.roles.dialog.rename" });
    fireEvent.change(within(dialog).getByDisplayValue("CAJA"), { target: { value: "SUPERVISOR" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));

    await waitFor(() => expect(api.renameSecurityRole).toHaveBeenCalledWith(
      "role-id", "SUPERVISOR", "token"
    ));
    expect(await screen.findByRole("button", { name: /SUPERVISOR/ })).not.toBeNull();
  });

  it("keeps the delete dialog open when the backend reports assigned users", async () => {
    vi.mocked(api.deleteSecurityRole).mockRejectedValueOnce(
      new Error("El rol está asignado a 1 usuario. Reasígnalo antes de eliminar el rol.")
    );
    render(<SecurityAdministrationScreen mode="roles" session={session(["ROLES_MANAGE"])} t={(key) => key} />);

    fireEvent.click(await screen.findByRole("button", { name: /CAJA/ }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.roles.delete" }));
    const dialog = screen.getByRole("dialog", { name: "gestion.roles.dialog.delete" });
    fireEvent.click(within(dialog).getByRole("button", { name: "gestion.roles.delete" }));

    expect((await within(dialog).findByRole("alert")).textContent).toContain("1 usuario");
    expect(screen.getByRole("button", { name: /CAJA/ })).not.toBeNull();
  });

  it("removes an unassigned configurable role after confirmation", async () => {
    render(<SecurityAdministrationScreen mode="roles" session={session(["ROLES_MANAGE"])} t={(key) => key} />);

    fireEvent.click(await screen.findByRole("button", { name: /CAJA/ }));
    fireEvent.click(screen.getByRole("button", { name: "gestion.roles.delete" }));
    const dialog = screen.getByRole("dialog", { name: "gestion.roles.dialog.delete" });
    fireEvent.click(within(dialog).getByRole("button", { name: "gestion.roles.delete" }));

    await waitFor(() => expect(api.deleteSecurityRole).toHaveBeenCalledWith("role-id", "token"));
    await waitFor(() => expect(screen.queryByRole("button", { name: /CAJA/ })).toBeNull());
  });
});
