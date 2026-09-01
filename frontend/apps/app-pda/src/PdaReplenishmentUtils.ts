export type WarehouseOption = { id: string; name?: string | null; nombre?: string | null; defaultWarehouse?: boolean; active?: boolean };
export type StockItem = { productId: string; warehouseId: string; quantity: number | string };
export type InventoryProduct = { id: string; code?: string | null; name?: string | null; active?: boolean | null; productType?: string | null; stockMin?: number | string | null; stockMax?: number | string | null };
export type StockPageItem = { product: InventoryProduct; stock: StockItem[] };
export type ReplenishmentSuggestion = { product: InventoryProduct; stock: StockItem[]; sourceWarehouseId: string; targetWarehouseId: string; currentQuantity: number; minimumQuantity: number; targetQuantity: number; sourceQuantity: number; suggestedQuantity: number };

export function numeric(value: unknown) { const parsed = Number(value); return Number.isFinite(parsed) ? parsed : 0; }
export function hasAtMostThreeDecimals(value: number) { return Math.abs(value * 1000 - Math.round(value * 1000)) < 1e-7; }

export function suggestedReplenishmentWarehouses(stock: StockItem[], warehouses: WarehouseOption[], targetWarehouseId = "") {
  const balances = warehouses.filter((warehouse) => warehouse.id !== targetWarehouseId).map((warehouse) => ({ id: warehouse.id, quantity: numeric(stock.find((item) => item.warehouseId === warehouse.id)?.quantity) })).sort((left, right) => right.quantity - left.quantity);
  return { sourceId: balances[0]?.id ?? "", targetId: targetWarehouseId || balances[1]?.id || warehouses.find((warehouse) => warehouse.id !== balances[0]?.id)?.id || "" };
}

export function buildReplenishmentSuggestions(items: StockPageItem[], warehouses: WarehouseOption[], targetWarehouseId: string) {
  if (!targetWarehouseId) return [];
  return items.flatMap<ReplenishmentSuggestion>((item) => {
    const minimumQuantity = numeric(item.product.stockMin);
    const currentQuantity = numeric(item.stock.find((stockItem) => stockItem.warehouseId === targetWarehouseId)?.quantity);
    if (item.product.active === false || item.product.productType === "SERVICE" || minimumQuantity <= 0 || currentQuantity >= minimumQuantity) return [];
    const configuredMaximum = numeric(item.product.stockMax);
    const targetQuantity = configuredMaximum > minimumQuantity ? configuredMaximum : minimumQuantity;
    const source = warehouses.filter((warehouse) => warehouse.id !== targetWarehouseId).map((warehouse) => ({ id: warehouse.id, quantity: numeric(item.stock.find((stockItem) => stockItem.warehouseId === warehouse.id)?.quantity) })).sort((left, right) => right.quantity - left.quantity)[0];
    const sourceQuantity = Math.max(0, source?.quantity ?? 0);
    return [{ product: item.product, stock: item.stock, sourceWarehouseId: source?.id ?? "", targetWarehouseId, currentQuantity, minimumQuantity, targetQuantity, sourceQuantity, suggestedQuantity: Math.min(Math.max(0, targetQuantity - currentQuantity), sourceQuantity) }];
  }).sort((left, right) => {
    const leftRatio = left.minimumQuantity > 0 ? left.currentQuantity / left.minimumQuantity : 1;
    const rightRatio = right.minimumQuantity > 0 ? right.currentQuantity / right.minimumQuantity : 1;
    return leftRatio - rightRatio || String(left.product.name ?? "").localeCompare(String(right.product.name ?? ""));
  });
}

export function pdaReplenishmentPagePath(cursor?: string | null) {
  const parameters = new URLSearchParams({ limit: "100", sortBy: "name", sortDirection: "asc" });
  if (cursor) parameters.set("cursor", cursor);
  return `/stock/page?${parameters.toString()}`;
}
