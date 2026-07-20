// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { WarehouseManagementScreen } from "./WarehouseManagementScreen";
import * as api from "./warehouseManagementApi";

vi.mock("./warehouseManagementApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./warehouseManagementApi")>();
  return {
    ...original,
    loadManagedWarehouses: vi.fn(),
    createManagedWarehouse: vi.fn(),
    renameManagedWarehouse: vi.fn(),
    setManagedWarehouseActive: vi.fn()
  };
});

const general: api.WarehouseManagementRecord = {
  id: "warehouse-general",
  storeId: "store-1",
  name: "GENERAL",
  defaultWarehouse: true,
  active: true,
  version: 0
};

const secondary: api.WarehouseManagementRecord = {
  id: "warehouse-secondary",
  storeId: "store-1",
  name: "SECUNDARIO",
  defaultWarehouse: false,
  active: true,
  version: 2
};

function session(permissions: UserSession["permissions"]): UserSession {
  return {
    username: "warehouse-user",
    displayName: "Responsable almacén",
    accessToken: "warehouse-token",
    permissions
  };
}

const t = (key: string) => key;

beforeEach(() => {
  vi.mocked(api.loadManagedWarehouses).mockResolvedValue([secondary, general]);
  vi.mocked(api.createManagedWarehouse).mockResolvedValue({
    ...secondary,
    id: "warehouse-new",
    name: "NUEVO"
  });
  vi.mocked(api.renameManagedWarehouse).mockResolvedValue({ ...secondary, name: "RESERVA", version: 3 });
  vi.mocked(api.setManagedWarehouseActive).mockResolvedValue({ ...secondary, active: false, version: 3 });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("WarehouseManagementScreen", () => {
  it("loads real warehouses and keeps GENERAL protected without physical delete actions", async () => {
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);

    expect(await screen.findByRole("row", { name: /GENERAL/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("warehouse.management.generalProtected")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "common.delete" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "warehouse.management.rename" })).not.toBeInTheDocument();
    expect(api.loadManagedWarehouses).toHaveBeenCalledWith("warehouse-token");
  });

  it("allows GESTION_ALMACEN to create a warehouse", async () => {
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    await screen.findByRole("row", { name: /GENERAL/ });

    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.create" }));
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.dialog.create" });
    fireEvent.change(within(dialog).getByRole("textbox"), { target: { value: "  NUEVO  " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));

    await waitFor(() => expect(api.createManagedWarehouse).toHaveBeenCalledWith("NUEVO", "warehouse-token"));
    expect(await screen.findByText("warehouse.management.created")).toBeInTheDocument();
    expect(screen.getByRole("row", { name: /NUEVO/ })).toHaveAttribute("aria-selected", "true");
  });

  it("allows WAREHOUSES_MANAGE to rename and deactivate a non-default warehouse", async () => {
    render(<WarehouseManagementScreen session={session(["WAREHOUSES_MANAGE"])} t={t} />);
    fireEvent.click(await screen.findByRole("row", { name: /SECUNDARIO/ }));

    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.rename" }));
    const renameDialog = screen.getByRole("dialog", { name: "warehouse.management.dialog.rename" });
    fireEvent.change(within(renameDialog).getByRole("textbox"), { target: { value: "RESERVA" } });
    fireEvent.click(within(renameDialog).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(api.renameManagedWarehouse).toHaveBeenCalledWith(
      "warehouse-secondary", "RESERVA", "warehouse-token"
    ));

    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.deactivate" }));
    const statusDialog = screen.getByRole("dialog", { name: "warehouse.management.dialog.deactivate" });
    fireEvent.click(within(statusDialog).getByRole("button", { name: "warehouse.management.deactivate" }));
    await waitFor(() => expect(api.setManagedWarehouseActive).toHaveBeenCalledWith(
      "warehouse-secondary", false, "warehouse-token"
    ));
    expect(await screen.findByText("warehouse.management.deactivated")).toBeInTheDocument();
  });

  it("keeps the confirmation open when a warehouse still has stock", async () => {
    vi.mocked(api.setManagedWarehouseActive).mockRejectedValueOnce(
      new Error("Solo se puede desactivar un almacén con stock cero")
    );
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    fireEvent.click(await screen.findByRole("row", { name: /SECUNDARIO/ }));
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.deactivate" }));

    const dialog = screen.getByRole("dialog", { name: "warehouse.management.dialog.deactivate" });
    fireEvent.click(within(dialog).getByRole("button", { name: "warehouse.management.deactivate" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("stock cero");
    expect(screen.getByRole("dialog", { name: "warehouse.management.dialog.deactivate" })).toBeInTheDocument();
  });

  it("does not load data without a structural warehouse permission", () => {
    render(<WarehouseManagementScreen session={session(["STOCK_READ"])} t={t} />);

    expect(screen.getByRole("alert")).toHaveTextContent("warehouse.management.noAccess");
    expect(api.loadManagedWarehouses).not.toHaveBeenCalled();
  });
});
