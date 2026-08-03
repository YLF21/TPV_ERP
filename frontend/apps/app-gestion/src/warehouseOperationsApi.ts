import { apiRequest } from "../../../packages/app-common/src/api/client";

export type WarehouseOption = {
  id: string;
  name: string;
  active?: boolean;
};

export type ProductOption = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
  active?: boolean | null;
  productType?: string | null;
};

export type StockBalance = {
  productId: string;
  warehouseId: string;
  quantity: number;
};

export type TransferResult = {
  transferId: string;
  productId: string;
  sourceWarehouseId: string;
  targetWarehouseId: string;
  sourceQuantity: number;
  targetQuantity: number;
};

export type StockCountStatus = "DRAFT" | "CONFIRMED" | "CANCELLED";

export type StockCountLine = {
  productId: string;
  productCode?: string | null;
  productName?: string | null;
  expectedQuantity: number;
  countedQuantity?: number | null;
  difference?: number | null;
  appliedDifference?: number | null;
};

export type StockCountSummary = {
  id: string;
  storeId: string;
  warehouseId: string;
  status: StockCountStatus;
  notes?: string | null;
  createdBy: string;
  createdAt: string;
  confirmedBy?: string | null;
  confirmedAt?: string | null;
  cancelledBy?: string | null;
  cancelledAt?: string | null;
  lineCount: number;
  totalDifference: number;
};

export type StockCountDetail = Omit<StockCountSummary, "lineCount" | "totalDifference"> & {
  lines: StockCountLine[];
};

export async function loadWarehouseOperationResources(token: string) {
  const [warehouses, products, stock] = await Promise.all([
    apiRequest<WarehouseOption[]>("/warehouses", { token }),
    apiRequest<ProductOption[]>("/products", { token }),
    apiRequest<StockBalance[]>("/stock", { token })
  ]);
  return { warehouses, products, stock };
}

export function createStockTransfer(input: {
  productId: string;
  sourceWarehouseId: string;
  targetWarehouseId: string;
  quantity: number;
}, token: string) {
  return apiRequest<TransferResult>("/stock/transfers", { token, body: input });
}

export function createStockAdjustment(input: {
  productId: string;
  warehouseId: string;
  quantity: number;
  reason: string;
}, token: string) {
  return apiRequest<StockBalance>("/stock/adjustments", { token, body: input });
}

export function loadStockCounts(token: string, filters: {
  status?: StockCountStatus;
  warehouseId?: string;
} = {}) {
  const query = new URLSearchParams();
  if (filters.status) query.set("status", filters.status);
  if (filters.warehouseId) query.set("warehouseId", filters.warehouseId);
  const suffix = query.size > 0 ? `?${query.toString()}` : "";
  return apiRequest<StockCountSummary[]>(`/stock-counts${suffix}`, { token });
}

export function loadStockCount(id: string, token: string) {
  return apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(id)}`, { token });
}

export function createStockCount(input: { warehouseId: string; notes?: string }, token: string) {
  return apiRequest<StockCountDetail>("/stock-counts", { token, body: input });
}

export function updateStockCountLine(
  countId: string,
  productId: string,
  countedQuantity: number,
  token: string
) {
  return apiRequest<StockCountDetail>(
    `/stock-counts/${encodeURIComponent(countId)}/lines/${encodeURIComponent(productId)}`,
    { method: "PUT", token, body: { countedQuantity } }
  );
}

export function confirmStockCount(id: string, token: string) {
  return apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(id)}/confirm`, {
    method: "POST",
    token
  });
}

export function cancelStockCount(id: string, token: string) {
  return apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(id)}/cancel`, {
    method: "POST",
    token
  });
}
