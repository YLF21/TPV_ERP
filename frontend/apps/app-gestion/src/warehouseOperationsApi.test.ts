import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../../../packages/app-common/src/api/client";
import {
  searchWarehouseProducts,
  cancelStockCount,
  confirmStockCount,
  createStockAdjustment,
  createStockTransfer,
  loadTransferDocuments,
  exportTransferList,
  loadStockCounts,
  updateStockCountLine
} from "./warehouseOperationsApi";

vi.mock("../../../packages/app-common/src/api/client", () => ({ apiRequest: vi.fn() }));

describe("warehouseOperationsApi", () => {
  beforeEach(() => vi.mocked(apiRequest).mockReset());

  it("preserves the exact zero code and server ranking for adjustment products", async () => {
    const product = { id: "zero", code: "0", name: "Artículo cero" };
    vi.mocked(apiRequest).mockResolvedValue([product]);
    const result = await searchWarehouseProducts("0", "token");
    expect(apiRequest).toHaveBeenCalledWith("/stock/adjustment-products?search=0", { token: "token" });
    expect(result.items[0].product).toEqual(product);
  });

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

  it("sends transfer search and all filters to the paged endpoint", async () => {
    vi.mocked(apiRequest).mockResolvedValue({ items: [], hasMore: false });
    await loadTransferDocuments(2, { status: "CONFIRMED", search: " TRA-2026 ",
      sourceWarehouseId: "source", targetWarehouseId: "target", dateFrom: "2026-09-01", dateTo: "2026-09-30" }, "token");
    const [path, options] = vi.mocked(apiRequest).mock.calls[0];
    const query = new URLSearchParams(String(path).split("?")[1]);
    expect(options).toEqual({ token: "token" });
    expect(Object.fromEntries(query)).toEqual({ page: "2", limit: "50", status: "CONFIRMED", search: "TRA-2026",
      sourceWarehouseId: "source", targetWarehouseId: "target",
      from: new Date("2026-09-01T00:00:00").toISOString(),
      before: new Date("2026-10-01T00:00:00").toISOString() });
  });

  it("exports with the same date boundaries and filters without a page limit", async () => {
    vi.mocked(apiRequest).mockResolvedValue(new Blob());
    await exportTransferList("pdf", { status: "DRAFT", search: " TRA ", sourceWarehouseId: "source",
      targetWarehouseId: "target", dateFrom: "2026-09-01", dateTo: "2026-09-30" }, "zh", "token");
    expect(apiRequest).toHaveBeenCalledWith("/warehouse-transfers/report.pdf", {
      token: "token", method: "POST", responseType: "blob", body: {
        status: "DRAFT", search: "TRA", sourceWarehouseId: "source", targetWarehouseId: "target",
        from: new Date("2026-09-01T00:00:00").toISOString(),
        before: new Date("2026-10-01T00:00:00").toISOString(), locale: "zh"
      }
    });
  });
});
