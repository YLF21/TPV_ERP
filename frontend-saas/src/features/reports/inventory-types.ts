export type InventorySnapshot = {
  snapshotId: string;
  capturedAt: string;
  completedAt: string;
  currentInstallation: boolean;
  installationId?: string;
  productCount: number;
  activeProductCount: number;
  warehouseCount: number;
  balanceCount: number;
  positiveCount: number;
  zeroCount: number;
  negativeCount: number;
};

export type InventoryScope = {
  companyId: string;
  storeId: string;
};

export type InventoryStore = InventoryScope & {
  companyName: string;
  storeName: string;
  snapshot: InventorySnapshot | null;
};

export type InventoryPage<T> = {
  page: number;
  size: number;
  total: number;
  rows: T[];
};

export type InventoryOverview = InventoryPage<InventoryStore> & { totals: InventoryTotals };

export type InventoryTotals = {
  stores: number;
  reportedStores: number;
  products: number;
  activeProducts: number;
  warehouses: number;
  balances: number;
  positiveCount: number;
  zeroCount: number;
  negativeCount: number;
};

export type SnapshotPage<T> = InventoryPage<T> & {
  snapshot: InventorySnapshot | null;
};

type InventoryItem = {
  productId: string;
  code: string | null;
  name: string;
  active: boolean;
  productType: "UNIT" | "WEIGHT" | "SERVICE";
};

export type InventoryProduct = InventoryItem & {
  barcode: string | null;
  secondaryBarcode: string | null;
  familyId: string | null;
  familyName: string | null;
  subfamilyId: string | null;
  subfamilyName: string | null;
};

export type InventoryWarehouse = {
  warehouseId: string;
  name: string;
  active: boolean;
  defaultWarehouse: boolean;
};

export type InventoryBalance = InventoryItem & {
  warehouseId: string;
  warehouseName: string;
  quantity: string;
};

export type InventoryComparisonStatus = "ADDED" | "REMOVED" | "CHANGED" | "UNCHANGED";

export type InventoryComparisonRow = {
  productId: string;
  code: string | null;
  name: string;
  warehouseId: string;
  warehouseName: string;
  status: InventoryComparisonStatus;
  previousQuantity: string | null;
  currentQuantity: string | null;
  delta: string | null;
  percent: string | null;
};

export type InventoryComparison = InventoryPage<InventoryComparisonRow> & {
  previous: InventorySnapshot;
  current: InventorySnapshot;
  counts: { added: number; removed: number; changed: number; unchanged: number };
  catalogue: { added: number; removed: number; activated: number; deactivated: number; modified: number };
};
