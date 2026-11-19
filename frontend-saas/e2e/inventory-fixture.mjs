export const inventoryPath = "/api/v1/admin/reports/inventory";
export const inventoryOptions = {
  companies: [{ id: "retail", name: "Comercial E2E" }, { id: "other", name: "Otra empresa" }],
  stores: [
    { id: "north", companyId: "retail", name: "Tienda Norte" },
    { id: "pending", companyId: "retail", name: "Tienda Pendiente" },
    { id: "empty", companyId: "other", name: "Tienda Vacía" },
  ],
};

const current = {
  snapshotId: "capture-current", capturedAt: "2026-11-18T09:12:10Z", completedAt: "2026-11-18T09:13:20Z",
  currentInstallation: true, productCount: 26, activeProductCount: 25, warehouseCount: 26,
  balanceCount: 3, positiveCount: 1, zeroCount: 1, negativeCount: 1,
};
const previous = {
  ...current, snapshotId: "capture-previous-installation", capturedAt: "2026-10-05T18:20:30Z",
  completedAt: "2026-10-05T18:22:00Z", currentInstallation: false,
};
const empty = {
  ...current, snapshotId: "capture-empty", productCount: 0, activeProductCount: 0, warehouseCount: 0,
  balanceCount: 0, positiveCount: 0, zeroCount: 0, negativeCount: 0,
};
const arrivals = [1, 2].map((revision) => ({
  ...current, snapshotId: `capture-arrival-${revision}`, capturedAt: `2026-11-18T1${revision}:12:10Z`,
  completedAt: `2026-11-18T1${revision}:13:20Z`, productCount: 1, activeProductCount: 1, warehouseCount: 1,
  balanceCount: 1, positiveCount: 1, zeroCount: 0, negativeCount: 0,
}));
const storeRows = inventoryOptions.stores.map((store, index) => ({
  companyId: store.companyId, companyName: inventoryOptions.companies.find((company) => company.id === store.companyId).name,
  storeId: store.id, storeName: store.name, snapshot: [current, null, empty][index],
}));

const products = [
  { productId: "p1", code: "CAF-01", name: "Café natural", active: true, productType: "UNIT", barcode: "8412345000011",
    secondaryBarcode: "8412345000028", familyId: "family", familyName: "Alimentación", subfamilyId: "coffee", subfamilyName: "Cafés" },
  { productId: "p2", code: "HAR-02", name: "Harina de trigo", active: false, productType: "WEIGHT", barcode: null,
    secondaryBarcode: null, familyId: "family", familyName: "Alimentación", subfamilyId: null, subfamilyName: null },
  { productId: "p3", code: null, name: "Instalación a domicilio", active: true, productType: "SERVICE", barcode: null,
    secondaryBarcode: null, familyId: null, familyName: null, subfamilyId: null, subfamilyName: null },
];
for (let index = 1; index <= 23; index++) {
  products.push({ ...products[0], productId: `reference-${index}`, code: `REF-${index}`, name: `Referencia original ${index}`,
    barcode: null, secondaryBarcode: null });
}
const balances = [
  { ...products[0], warehouseId: "w1", warehouseName: "Almacén Principal", quantity: "9007199254740993.123" },
  { ...products[1], warehouseId: "w1", warehouseName: "Almacén Principal", quantity: "-0.001" },
  { ...products[0], warehouseId: "w26", warehouseName: "Almacén 26", quantity: "0.000" },
];
const warehouses = Array.from({ length: 26 }, (_, index) => ({
  warehouseId: `w${index + 1}`, name: index === 0 ? "Almacén Principal" : `Almacén ${index + 1}`,
  active: true, defaultWarehouse: index === 0,
}));
const history = [
  current,
  ...Array.from({ length: 24 }, (_, index) => ({
    ...current, snapshotId: `capture-${index + 1}`,
    capturedAt: new Date(Date.UTC(2026, 10, 17 - index, 8)).toISOString(),
    completedAt: new Date(Date.UTC(2026, 10, 17 - index, 8, 2)).toISOString(),
  })),
  previous,
];

function page(rows, query) {
  const index = Number(query.get("page"));
  const size = Number(query.get("size"));
  return { page: index, size, total: rows.length, rows: rows.slice(index * size, (index + 1) * size) };
}

export function inventoryResponse(endpoint, query, revision = 0) {
  const company = query.get("companyId");
  const store = query.get("storeId");
  const latest = arrivals[revision - 1] ?? current;
  if (endpoint === "overview") {
    const rows = storeRows
      .filter((row) => (!company || company === row.companyId) && (!store || store === row.storeId))
      .map((row) => row.storeId === "north" ? { ...row, snapshot: latest } : row);
    const reported = rows.flatMap((row) => row.snapshot ? [row.snapshot] : []);
    const sum = key => reported.reduce((total, snapshot) => total + snapshot[key], 0);
    return { ...page(rows, query), totals: {
      stores: rows.length, reportedStores: reported.length, products: sum("productCount"), activeProducts: sum("activeProductCount"),
      warehouses: sum("warehouseCount"), balances: sum("balanceCount"), positiveCount: sum("positiveCount"),
      zeroCount: sum("zeroCount"), negativeCount: sum("negativeCount"),
    } };
  }
  if (endpoint === "history") return page(store === "north" ? history : store === "empty" ? [empty] : [], query);

  const snapshotId = query.get("snapshotId");
  const snapshot = store === "north" ? [...arrivals, ...history].find((row) => row.snapshotId === snapshotId) ?? latest
    : store === "empty" ? empty : null;
  if (!snapshot || store !== "north") return { ...page([], query), snapshot };
  const arrival = arrivals.indexOf(snapshot) + 1;
  const arrivalWarehouse = { warehouseId: `arrival-warehouse-${arrival}`, name: `Almacén nuevo ${arrival}`, active: true, defaultWarehouse: true };
  if (endpoint === "warehouses") return { ...page(arrival ? [arrivalWarehouse] : warehouses, query), snapshot };

  const search = query.get("search")?.toLowerCase();
  const active = query.get("active");
  const product = query.get("productId");
  const warehouse = query.get("warehouseId");
  const arrivalProduct = { ...products[0], name: `Producto nuevo ${arrival}` };
  const source = endpoint === "products" ? (arrival ? [arrivalProduct] : products)
    : arrival ? [{ ...arrivalProduct, warehouseId: arrivalWarehouse.warehouseId, warehouseName: arrivalWarehouse.name, quantity: "7.250" }] : balances;
  const rows = source.filter((row) => (
    (!search || [row.code, row.name, row.barcode, row.secondaryBarcode].some((value) => value?.toLowerCase().includes(search))) &&
    (active === null || row.active === (active === "true")) &&
    (endpoint !== "balances" || !product || row.productId === product) &&
    (!warehouse || row.warehouseId === warehouse)
  ));
  return { ...page(rows, query), snapshot };
}
