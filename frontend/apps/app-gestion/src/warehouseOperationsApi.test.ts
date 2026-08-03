import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../../../packages/app-common/src/api/client";
import {
  cancelStockCount,
  confirmStockCount,
  createStockAdjustment,
  createStockTransfer,
  loadStockCounts,
  updateStockCountLine
} from "./warehouseOperationsApi";

vi.mock("../../../packages/app-common/src/api/client", () => ({ apiRequest: vi.fn() }));

describe("warehouseOperationsApi", () => {
  beforeEach(() => vi.mocked(apiRequest).mockReset());

  it("uses the stock transfer and adjustment contracts", async () => {
    vi.mocked(apiRequest).mockResolvedValue({});
    await createStockTransfer({ productId: "p", sourceWarehouseId: "a", targetWarehouseId: "b", quantity: 2 }, "token");
    await createStockAdjustment({ productId: "p", warehouseId: "a", quantity: -1, reason: "Rotura" }, "token");
    expect(apiRequest).toHaveBeenNthCalledWith(1, "/stock/transfers", {
      token: "token",
      body: { productId: "p", sourceWarehouseId: "a", targetWarehouseId: "b", quantity: 2 }
    });
    expect(apiRequest).toHaveBeenNthCalledWith(2, "/stock/adjustments", {
      token: "token",
      body: { productId: "p", warehouseId: "a", quantity: -1, reason: "Rotura" }
    });
  });

  it("serializes count filters and lifecycle actions", async () => {
    vi.mocked(apiRequest).mockResolvedValue({});
    await loadStockCounts("token", { status: "DRAFT", warehouseId: "warehouse 1" });
    await updateStockCountLine("count/1", "product/1", 3.5, "token");
    await confirmStockCount("count/1", "token");
    await cancelStockCount("count/1", "token");
    expect(apiRequest).toHaveBeenNthCalledWith(1, "/stock-counts?status=DRAFT&warehouseId=warehouse+1", { token: "token" });
    expect(apiRequest).toHaveBeenNthCalledWith(2, "/stock-counts/count%2F1/lines/product%2F1", {
      method: "PUT", token: "token", body: { countedQuantity: 3.5 }
    });
    expect(apiRequest).toHaveBeenNthCalledWith(3, "/stock-counts/count%2F1/confirm", { method: "POST", token: "token" });
    expect(apiRequest).toHaveBeenNthCalledWith(4, "/stock-counts/count%2F1/cancel", { method: "POST", token: "token" });
  });
});
