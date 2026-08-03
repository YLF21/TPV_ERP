// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "../../../packages/app-common/src/types";
import { WarehouseOperationsScreen } from "./WarehouseOperationsScreen";
import * as api from "./warehouseOperationsApi";

vi.mock("./warehouseOperationsApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./warehouseOperationsApi")>();
  return {
    ...original,
    loadWarehouseOperationResources: vi.fn(),
    createStockTransfer: vi.fn(),
    createStockAdjustment: vi.fn(),
    loadStockCounts: vi.fn(),
    loadStockCount: vi.fn(),
    createStockCount: vi.fn(),
    updateStockCountLine: vi.fn(),
    confirmStockCount: vi.fn(),
    cancelStockCount: vi.fn()
  };
});

const resources = {
  warehouses: [
    { id: "general", name: "GENERAL", active: true },
    { id: "reserve", name: "RESERVA", active: true }
  ],
  products: [{ id: "coffee", code: "CAF", name: "Café", active: true, productType: "UNIT" }],
  stock: [{ productId: "coffee", warehouseId: "general", quantity: 10 }]
};

const draftCount: api.StockCountDetail = {
  id: "count-1",
  storeId: "store",
  warehouseId: "general",
  status: "DRAFT",
  notes: "Conteo mensual",
  createdBy: "admin",
  createdAt: "2026-08-03T10:00:00Z",
  lines: [{
    productId: "coffee",
    productCode: "CAF",
    productName: "Café",
    expectedQuantity: 10,
    countedQuantity: null,
    difference: null,
    appliedDifference: null
  }]
};

function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "admin", displayName: "Admin", accessToken: "token", permissions };
}

const t = (key: string) => key;

describe("WarehouseOperationsScreen", () => {
  afterEach(cleanup);
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.loadWarehouseOperationResources).mockResolvedValue(resources);
    vi.mocked(api.loadStockCounts).mockResolvedValue([]);
    vi.mocked(api.createStockTransfer).mockResolvedValue({
      transferId: "transfer", productId: "coffee", sourceWarehouseId: "general",
      targetWarehouseId: "reserve", sourceQuantity: 8, targetQuantity: 2
    });
    vi.mocked(api.createStockAdjustment).mockResolvedValue({
      productId: "coffee", warehouseId: "general", quantity: 8
    });
  });

  it("validates distinct warehouses before creating a transfer", async () => {
    render(<WarehouseOperationsScreen session={session(["STOCK_TRANSFER"])} mode="transfer" t={t} />);
    await screen.findByRole("heading", { name: "warehouse.transfer.title" });
    const selects = screen.getAllByRole("combobox");
    fireEvent.change(selects[2], { target: { value: "general" } });
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "warehouse.transfer.submit" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("warehouse.transfer.sameWarehouse");
    expect(api.createStockTransfer).not.toHaveBeenCalled();
  });

  it("sends negative adjustments with an audit reason", async () => {
    render(<WarehouseOperationsScreen session={session(["STOCK_ADJUST"])} mode="adjustment" t={t} />);
    await screen.findByRole("heading", { name: "warehouse.adjustment.title" });
    fireEvent.click(screen.getByRole("radio", { name: /warehouse.adjustment.negative/ }));
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "2" } });
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "Rotura inventariada" } });
    fireEvent.click(screen.getByRole("button", { name: "warehouse.adjustment.submit" }));
    await waitFor(() => expect(api.createStockAdjustment).toHaveBeenCalledWith({
      productId: "coffee", warehouseId: "general", quantity: -2, reason: "Rotura inventariada"
    }, "token"));
    expect(await screen.findByRole("status")).toHaveTextContent("warehouse.adjustment.completed");
  });

  it("restricts physical counts to warehouse managers", () => {
    render(<WarehouseOperationsScreen session={session(["STOCK_ADJUST"])} mode="count" t={t} />);
    expect(screen.getByRole("alert")).toHaveTextContent("warehouse.operations.noAccess");
    expect(api.loadWarehouseOperationResources).not.toHaveBeenCalled();
  });

  it("opens a draft count, saves a line and confirms it", async () => {
    vi.mocked(api.loadStockCounts).mockResolvedValue([{
      ...draftCount,
      lineCount: 1,
      totalDifference: 0
    }]);
    vi.mocked(api.loadStockCount).mockResolvedValue(draftCount);
    vi.mocked(api.updateStockCountLine).mockResolvedValue({
      ...draftCount,
      lines: [{ ...draftCount.lines[0], countedQuantity: 9, difference: -1 }]
    });
    vi.mocked(api.confirmStockCount).mockResolvedValue({
      ...draftCount,
      status: "CONFIRMED",
      confirmedBy: "admin",
      confirmedAt: "2026-08-03T10:05:00Z",
      lines: [{ ...draftCount.lines[0], countedQuantity: 9, difference: -1, appliedDifference: -1 }]
    });

    render(<WarehouseOperationsScreen session={session(["GESTION_ALMACEN"])} mode="count" t={t} />);
    const countButton = await screen.findByRole("button", { name: /GENERAL/ });
    fireEvent.click(countButton);
    const counted = await screen.findByRole("spinbutton", { name: /warehouse.count.counted Café/ });
    fireEvent.change(counted, { target: { value: "9" } });
    fireEvent.click(screen.getByRole("button", { name: "warehouse.count.saveLine" }));
    await waitFor(() => expect(api.updateStockCountLine).toHaveBeenCalledWith("count-1", "coffee", 9, "token"));
    fireEvent.click(screen.getByRole("button", { name: "warehouse.count.confirm" }));
    await waitFor(() => expect(api.confirmStockCount).toHaveBeenCalledWith("count-1", "token"));
  });

  it("adds the first product to an empty physical count", async () => {
    const emptyDraft = { ...draftCount, lines: [] };
    vi.mocked(api.loadStockCounts).mockResolvedValue([{
      ...emptyDraft,
      lineCount: 0,
      totalDifference: 0
    }]);
    vi.mocked(api.loadStockCount).mockResolvedValue(emptyDraft);
    vi.mocked(api.updateStockCountLine).mockResolvedValue({
      ...draftCount,
      lines: [{ ...draftCount.lines[0], countedQuantity: 7, difference: -3 }]
    });

    render(<WarehouseOperationsScreen session={session(["GESTION_ALMACEN"])} mode="count" t={t} />);
    fireEvent.click(await screen.findByRole("button", { name: /GENERAL/ }));
    const quantity = await screen.findByRole("spinbutton", { name: "warehouse.count.counted" });
    fireEvent.change(quantity, { target: { value: "7" } });
    fireEvent.click(screen.getByRole("button", { name: "warehouse.count.addLine" }));

    await waitFor(() => expect(api.updateStockCountLine)
      .toHaveBeenCalledWith("count-1", "coffee", 7, "token"));
    expect(await screen.findByRole("status")).toHaveTextContent("warehouse.count.lineAdded");
  });
});
