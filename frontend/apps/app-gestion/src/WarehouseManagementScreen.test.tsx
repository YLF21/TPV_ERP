// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type UserSession } from "@tpverp/app-common";
import { WarehouseManagementScreen } from "./WarehouseManagementScreen";
import * as api from "./warehouseManagementApi";

vi.mock("./warehouseManagementApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./warehouseManagementApi")>();
  return { ...original,
    loadManagedWarehouses: vi.fn(), loadWarehouseOverview: vi.fn(), loadGeneralStockConfiguration: vi.fn(),
    loadWarehouseStockConfiguration: vi.fn(), createManagedWarehouse: vi.fn(),
    renameManagedWarehouse: vi.fn(), setManagedWarehouseActive: vi.fn(),
    applyWarehouseStockConfigurationToAll: vi.fn(), saveManagedWarehouseOrder: vi.fn()
  };
});

const general: api.WarehouseManagementRecord = { id: "general", storeId: "store-1", name: "GENERAL",
  address: "Calle Mayor 12", notes: "Principal", defaultWarehouse: true, active: true, displayOrder: 0, version: 0 };
const secondary: api.WarehouseManagementRecord = { id: "secondary", storeId: "store-1", name: "RESERVA",
  address: "Calle Norte 8", notes: "Reposición", defaultWarehouse: false, active: true, displayOrder: 1, version: 1 };
const settings: api.GeneralStockConfiguration = { defaultWarehouseId: "general", allowNegativeStock: false,
  defaultMinimumStock: 5, alertsEnabled: true, allowInactiveProductSales: false };
const t = (key: string) => key;
const session = (permissions: UserSession["permissions"]): UserSession => ({
  username: "manager", displayName: "Manager", accessToken: "token", permissions
});

beforeEach(() => {
  vi.mocked(api.loadManagedWarehouses).mockResolvedValue([general, secondary]);
  vi.mocked(api.loadWarehouseOverview).mockResolvedValue([
    { warehouseId: "general", productCount: 2, totalQuantity: 6.5,
      allowNegativeStock: false, defaultMinimumStock: 5, alertsEnabled: true, inheritsStoreSettings: true },
    { warehouseId: "secondary", productCount: 1, totalQuantity: 3,
      allowNegativeStock: true, defaultMinimumStock: 0, alertsEnabled: false, inheritsStoreSettings: false }
  ]);
  vi.mocked(api.loadGeneralStockConfiguration).mockResolvedValue(settings);
  vi.mocked(api.loadWarehouseStockConfiguration).mockResolvedValue({ warehouseId: "secondary", storeId: "store-1",
    allowNegativeStock: true, defaultMinimumStock: 0, alertsEnabled: false, inheritsStoreSettings: false, version: 1 });
  vi.mocked(api.createManagedWarehouse).mockResolvedValue({ ...secondary, id: "new", name: "NUEVO" });
  vi.mocked(api.renameManagedWarehouse).mockResolvedValue({ ...secondary, name: "CAMBIADO" });
  vi.mocked(api.applyWarehouseStockConfigurationToAll).mockResolvedValue(settings);
  vi.mocked(api.setManagedWarehouseActive).mockResolvedValue({ ...secondary, active: false });
  vi.mocked(api.saveManagedWarehouseOrder).mockResolvedValue([general, secondary]);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("WarehouseManagementScreen", () => {
  it("keeps the API order, includes inactive warehouses and saves only after confirmation", async () => {
    const inactive = { ...secondary, id: "inactive", name: "ARCHIVO", active: false, displayOrder: 2, version: 4 };
    vi.mocked(api.loadManagedWarehouses).mockResolvedValue([secondary, general, inactive]);
    vi.mocked(api.saveManagedWarehouseOrder).mockResolvedValue([
      { ...general, displayOrder: 0, version: 1 },
      { ...secondary, displayOrder: 1, version: 2 },
      { ...inactive, displayOrder: 2, version: 5 }
    ]);
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const cards = await screen.findByRole("region", { name: "warehouse.management.list" });
    expect(Array.from(cards.querySelectorAll(".gestion-warehouse-card-heading strong"), (item) => item.textContent))
      .toEqual(["RESERVA", "GENERAL", "ARCHIVO"]);
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.order" }));
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.order" });
    expect(within(dialog).getByText("warehouse.management.inactive")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "warehouse.management.moveUp RESERVA" })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "warehouse.management.moveDown ARCHIVO" })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole("button", { name: "warehouse.management.moveDown RESERVA" }));
    expect(api.saveManagedWarehouseOrder).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "common.close" }));
    expect(Array.from(cards.querySelectorAll(".gestion-warehouse-card-heading strong"), (item) => item.textContent))
      .toEqual(["RESERVA", "GENERAL", "ARCHIVO"]);
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.order" }));
    const reopened = screen.getByRole("dialog", { name: "warehouse.management.order" });
    expect(Array.from(reopened.querySelectorAll(".gestion-warehouse-order-name"), (item) => item.firstChild?.textContent))
      .toEqual(["RESERVA", "GENERAL", "ARCHIVO"]);
    fireEvent.click(within(reopened).getByRole("button", { name: "warehouse.management.moveDown RESERVA" }));
    fireEvent.click(within(reopened).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(api.saveManagedWarehouseOrder).toHaveBeenCalledWith({ warehouses: [
      { id: "general", version: 0 }, { id: "secondary", version: 1 }, { id: "inactive", version: 4 }
    ] }, "token"));
    await waitFor(() => expect(Array.from(cards.querySelectorAll(".gestion-warehouse-card-heading strong"),
      (item) => item.textContent)).toEqual(["GENERAL", "RESERVA", "ARCHIVO"]));
  });

  it("keeps the server order after a conflict and offers a fresh reload", async () => {
    vi.mocked(api.saveManagedWarehouseOrder).mockRejectedValueOnce(new ApiError("conflict", 409, { code: "STATE_CONFLICT" }));
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const cards = await screen.findByRole("region", { name: "warehouse.management.list" });
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.order" }));
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.order" });
    fireEvent.click(within(dialog).getByRole("button", { name: "warehouse.management.moveDown GENERAL" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("warehouse.management.orderConflict");
    expect(within(dialog).getByRole("button", { name: "common.save" })).toBeDisabled();
    expect(Array.from(cards.querySelectorAll(".gestion-warehouse-card-heading strong"), (item) => item.textContent))
      .toEqual(["GENERAL", "RESERVA"]);
    vi.mocked(api.loadManagedWarehouses).mockResolvedValueOnce([
      { ...secondary, displayOrder: 0, version: 2 }, { ...general, displayOrder: 1, version: 1 }
    ]);
    fireEvent.click(within(dialog).getByRole("button", { name: "warehouse.management.reloadOrder" }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "common.save" })).toBeEnabled());
    expect(Array.from(dialog.querySelectorAll(".gestion-warehouse-order-name"), (item) => item.textContent))
      .toEqual(["RESERVA", "GENERAL"]);
    expect(api.saveManagedWarehouseOrder).toHaveBeenCalledTimes(1);
  });

  it("shows a list with address, notes, configuration and both stock metrics", async () => {
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const list = await screen.findByRole("region", { name: "warehouse.management.list" });
    expect(within(list).queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
    expect(within(list).getByText("Calle Mayor 12")).toBeInTheDocument();
    expect(within(list).getByText(/warehouse\.management\.generalSalesNote Principal/)).toBeInTheDocument();
    expect(within(list).getByText("6,5")).toBeInTheDocument();
    expect(within(list).getByText("2")).toBeInTheDocument();
    expect(within(list).getAllByRole("button", { name: "warehouse.management.configure" })).toHaveLength(2);
  });

  it("keeps GENERAL's store address read-only and explains its sales role", async () => {
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const list = await screen.findByRole("region", { name: "warehouse.management.list" });
    fireEvent.click(within(list).getAllByRole("button", { name: "warehouse.management.configure" })[0]);
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.settingsTitle" });
    expect(within(dialog).getByLabelText("warehouse.management.address")).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText("warehouse.management.address")).toHaveValue("Calle Mayor 12");
    expect(within(dialog).getByText("warehouse.management.storeAddressHint")).toBeInTheDocument();
    expect(within(dialog).getByText("warehouse.management.generalSalesNote")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(api.renameManagedWarehouse).toHaveBeenCalledWith("general", {
      name: "GENERAL", address: null, notes: "Principal"
    }, "token"));
  });

  it("creates a warehouse with its address and notes", async () => {
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    await screen.findByText("RESERVA");
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.create" }));
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.dialog.create" });
    fireEvent.change(within(dialog).getByLabelText("warehouse.management.name"), { target: { value: " NUEVO " } });
    fireEvent.change(within(dialog).getByLabelText("warehouse.management.address"), { target: { value: " Calle 1 " } });
    fireEvent.change(within(dialog).getByLabelText("warehouse.management.notes"), { target: { value: " Planta baja " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(api.createManagedWarehouse).toHaveBeenCalledWith(
      { name: "NUEVO", address: "Calle 1", notes: "Planta baja" }, "token"));
  });

  it("keeps warehouse settings open after editing its details", async () => {
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const list = await screen.findByRole("region", { name: "warehouse.management.list" });
    fireEvent.click(within(list).getAllByRole("button", { name: "warehouse.management.configure" })[1]);
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.settingsTitle" });
    fireEvent.change(within(dialog).getByLabelText("warehouse.management.address"), {
      target: { value: "Calle Nueva 4" }
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "common.save" }));
    await waitFor(() => expect(api.renameManagedWarehouse).toHaveBeenCalledWith("secondary", {
      name: "RESERVA", address: "Calle Nueva 4", notes: "Reposición"
    }, "token"));
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText("warehouse.management.stockSection")).toBeInTheDocument();
  });

  it("applies general stock settings to all warehouses through one operation", async () => {
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])}
      t={(key) => key === "warehouse.management.bulkWarning" ? "Se aplicará a {count} almacenes" : key} />);
    fireEvent.click(await screen.findByRole("button", { name: "warehouse.management.generalSettings" }));
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.generalSettings" });
    expect(within(dialog).getByText(/2/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "warehouse.management.applyAll" }));
    await waitFor(() => expect(api.applyWarehouseStockConfigurationToAll).toHaveBeenCalledWith(
      { allowNegativeStock: false, defaultMinimumStock: 5, alertsEnabled: true }, "token"));
  });

  it("routes the three document buttons to their editors", async () => {
    const onCreateDocument = vi.fn();
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t}
      onCreateDocument={onCreateDocument} />);
    await screen.findByText("GENERAL");
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.createInput" }));
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.createOutput" }));
    fireEvent.click(screen.getByRole("button", { name: "warehouse.management.createTransfer" }));
    expect(onCreateDocument.mock.calls).toEqual([["input"], ["output"], ["transfer"]]);
  });

  it("keeps the deactivation confirmation open when stock remains", async () => {
    vi.mocked(api.setManagedWarehouseActive).mockRejectedValueOnce(new ApiError("conflict", 409, { code: "STATE_CONFLICT" }));
    render(<WarehouseManagementScreen session={session(["GESTION_ALMACEN"])} t={t} />);
    const list = await screen.findByRole("region", { name: "warehouse.management.list" });
    fireEvent.click(within(list).getAllByRole("button", { name: "warehouse.management.configure" })[1]);
    const dialog = screen.getByRole("dialog", { name: "warehouse.management.settingsTitle" });
    fireEvent.click(within(dialog).getByRole("button", { name: "warehouse.management.deactivate" }));
    const confirm = screen.getByRole("dialog", { name: "warehouse.management.dialog.deactivate" });
    fireEvent.click(within(confirm).getByRole("button", { name: "warehouse.management.deactivate" }));
    expect(await within(confirm).findByRole("alert")).toHaveTextContent("warehouse.management.zeroStockWarning");
    expect(confirm).toBeInTheDocument();
  });

  it("does not load data without warehouse management permission", () => {
    render(<WarehouseManagementScreen session={session(["STOCK_READ"])} t={t} />);
    expect(screen.getByRole("alert")).toHaveTextContent("warehouse.management.noAccess");
    expect(screen.queryByRole("button", { name: "warehouse.management.order" })).not.toBeInTheDocument();
    expect(api.loadManagedWarehouses).not.toHaveBeenCalled();
  });
});
