import { apiRequest } from "../../../packages/app-common/src/api/client";
import type { WarehouseImportProduct } from "../../../packages/app-common/src/components/warehouseDocumentImport";
import type { WarehouseInputPriceSource } from "../../../packages/app-common/src/components/WarehouseDocumentDialog";

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

export type StockAdjustmentHistoryRow = {
  userName?: string | null;
  movementId: string;
  warehouseId: string;
  productId: string;
  code: string;
  barcode?: string | null;
  name: string;
  previousQuantity: number | null;
  adjustmentQuantity: number;
  nextQuantity: number | null;
  reason: string;
  createdAt: string;
};

export type StockAdjustmentHistoryPage = {
  items: StockAdjustmentHistoryRow[];
  nextCursor: string | null;
  hasMore: boolean;
};

export function loadWarehouseOptions(token: string) {
  return apiRequest<WarehouseOption[]>("/warehouses", { token });
}

export function loadWarehouseDocumentProducts(token: string) {
  return apiRequest<WarehouseImportProduct[]>("/products/warehouse-options", { token });
}

export async function searchWarehouseProducts(query: string, token: string) {
  const params = new URLSearchParams({ search: query });
  const products = await apiRequest<ProductOption[]>(`/stock/adjustment-products?${params}`, { token });
  return { items: products.map((product) => ({ product })) };
}

export function loadStockBalance(productId: string, warehouseId: string, token: string) {
  const params = new URLSearchParams({ productId, warehouseId });
  return apiRequest<StockBalance[]>(`/stock?${params}`, { token });
}

export function loadStockAdjustmentHistory(token: string, options: {
  page?: number;
  warehouseId?: string;
  search?: string;
  from?: string;
  to?: string;
} = {}) {
  const params = new URLSearchParams({ limit: "50", page: String(options.page ?? 0) });
  if (options.warehouseId) params.set("warehouseId", options.warehouseId);
  if (options.search) params.set("search", options.search);
  if (options.from) params.set("from", options.from);
  if (options.to) params.set("to", options.to);
  return apiRequest<StockAdjustmentHistoryPage>(`/stock/adjustments?${params}`, { token });
}

export type TransferResult = {
  transferId: string;
  productId: string;
  sourceWarehouseId: string;
  targetWarehouseId: string;
  sourceQuantity: number;
  targetQuantity: number;
};

export type TransferDocument = {
  id: string;
  storeId: string;
  sourceWarehouseId: string;
  targetWarehouseId: string;
  number?: string | null;
  status: "DRAFT" | "CONFIRMED" | "CANCELLED";
  notes?: string | null;
  createdAt: string;
  version: number;
  date?: string;
  externalNumber?: string | null;
  priceSource?: WarehouseInputPriceSource;
  globalDiscount?: number;
  subtotal?: number;
  total?: number;
};

export type TransferListItem = TransferDocument & { lineCount: number; totalUnits: number };

export type TransferDocumentLine = {
  id?: string;
  productId: string;
  code?: string;
  barcode?: string | null;
  name?: string;
  quantity: number;
  productName?: string;
  unitPrice?: number;
  discount?: number;
  priceOverridden?: boolean;
};

export type TransferDocumentView = { document: TransferDocument; lines: TransferDocumentLine[] };

export type TransferDocumentFilters = {
  status: TransferDocument["status"] | "";
  search: string;
  sourceWarehouseId: string;
  targetWarehouseId: string;
  dateFrom: string;
  dateTo: string;
};

export function loadTransferDocuments(page: number, filters: TransferDocumentFilters, token: string) {
  const params = transferFilterParams(filters);
  params.set("page", String(page));
  params.set("limit", "50");
  return apiRequest<{ items: TransferListItem[]; hasMore: boolean; nextCursor: string | null }>(
    `/warehouse-transfers?${params}`, { token });
}

function transferFilterParams(filters: TransferDocumentFilters) {
  const params = new URLSearchParams();
  if (filters.status) params.set("status", filters.status);
  if (filters.search.trim()) params.set("search", filters.search.trim());
  if (filters.sourceWarehouseId) params.set("sourceWarehouseId", filters.sourceWarehouseId);
  if (filters.targetWarehouseId) params.set("targetWarehouseId", filters.targetWarehouseId);
  if (filters.dateFrom) params.set("from", new Date(`${filters.dateFrom}T00:00:00`).toISOString());
  if (filters.dateTo) {
    const nextDay = new Date(`${filters.dateTo}T00:00:00`);
    nextDay.setDate(nextDay.getDate() + 1);
    params.set("before", nextDay.toISOString());
  }
  return params;
}

export function exportTransferList(format: "pdf" | "xlsx", filters: TransferDocumentFilters, locale: string, token: string) {
  return apiRequest<Blob>(`/warehouse-transfers/report.${format}`, {
    token, method: "POST", responseType: "blob", body: { ...Object.fromEntries(transferFilterParams(filters)), locale }
  });
}

export function loadTransferDocument(id: string, token: string) {
  return apiRequest<TransferDocumentView>(`/warehouse-transfers/${encodeURIComponent(id)}`, { token });
}

export function saveTransferDocument(input: {
  sourceWarehouseId: string;
  targetWarehouseId: string;
  notes: string;
  expectedVersion?: number;
  date?: string;
  externalNumber?: string;
  priceSource?: WarehouseInputPriceSource;
  globalDiscount?: number;
  lines: Array<{ productId: string; quantity: number; productName?: string; unitPrice?: number;
    discount?: number; priceOverridden?: boolean }>;
}, token: string, id?: string) {
  return apiRequest<TransferDocumentView>(id ? `/warehouse-transfers/${encodeURIComponent(id)}` : "/warehouse-transfers", {
    method: id ? "PUT" : "POST", body: input, token
  });
}

export function confirmTransferDocument(id: string, expectedVersion: number, token: string) {
  return apiRequest<TransferDocumentView>(`/warehouse-transfers/${encodeURIComponent(id)}/confirm?expectedVersion=${expectedVersion}`,
    { method: "POST", token });
}

export function cancelTransferDocument(id: string, token: string) {
  return apiRequest<TransferDocumentView>(`/warehouse-transfers/${encodeURIComponent(id)}/cancel`,
    { method: "POST", token });
}

export type StockCountStatus = "DRAFT" | "CONFIRMED" | "CANCELLED";

export type StockCountLine = {
  productId: string;
  productCode?: string | null;
  productBarcode?: string | null;
  productName?: string | null;
  expectedQuantity: number;
  countedQuantity?: number | null;
  difference?: number | null;
  appliedDifference?: number | null;
};

export type StockCountSummary = {
  version?: number;
  documentDate?: string;
  id: string;
  number: string;
  storeId: string;
  warehouseId: string;
  status: StockCountStatus;
  notes?: string | null;
  createdBy: string;
  createdByName?: string | null;
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

export function exportStockCount(id: string, format: "pdf" | "xlsx", token: string) {
  return apiRequest<Blob>(`/stock-counts/${encodeURIComponent(id)}/export.${format}`, {
    token, responseType: "blob"
  });
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

export function confirmStockCount(id: string, token: string, lines?: StockCountLine[], expectedVersion?: number) {
  return apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(id)}/confirm`, {
    method: "POST",
    token,
    body: lines ? { lines, ...(expectedVersion != null ? { expectedVersion } : {}) } : undefined
  });
}

export function saveStockCountDraft(id: string, input: {
  expectedVersion: number; documentDate: string; notes: string;
  lines: { productId: string; countedQuantity: number | null; expectedQuantity?: number }[];
}, token: string) {
  return apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(id)}/draft`, { method: "PUT", token, body: input });
}

export function cancelStockCount(id: string, token: string) {
  return apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(id)}/cancel`, {
    method: "POST",
    token
  });
}
