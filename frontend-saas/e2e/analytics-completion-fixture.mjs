import { inventoryOptions, inventoryPath, inventoryResponse } from "./inventory-fixture.mjs";
import { statisticsAsOf, statisticsPath, statisticsSnapshot } from "./statistics-fixture.mjs";

export const reportsPath = "/api/v1/admin/reports";
export { inventoryOptions, inventoryPath, statisticsAsOf, statisticsPath };

const basic = { from: "2026-07-01", to: "2026-07-03", days: 3 };
const prior = { from: "2026-06-01", to: "2026-06-28", days: 28 };
const coverage = { unknownRelationships: 0, currentIncompleteDocuments: 0, comparisonIncompleteDocuments: 0 };
const currentSnapshot = {
  ...inventoryResponse("history", new URLSearchParams("storeId=north&page=0&size=25")).rows[0], installationId: "install-current",
};
const earlierSnapshot = {
  ...inventoryResponse("history", new URLSearchParams("storeId=north&page=0&size=25")).rows[1], installationId: "install-current",
};
const rows = [
  {
    productId: "p1", code: "CAF-01", name: "Café natural", warehouseId: "w1", warehouseName: "Almacén Principal",
    status: "CHANGED", previousQuantity: "9007199254740993.001", currentQuantity: "9007199254740993.123", delta: "0.122", percent: "0.00",
  },
  {
    productId: "p2", code: "HAR-02", name: "Harina de trigo", warehouseId: "w1", warehouseName: "Almacén Principal",
    status: "REMOVED", previousQuantity: "0.000", currentQuantity: null, delta: null, percent: null,
  },
  {
    productId: "p3", code: "ALT-03", name: "Artículo añadido", warehouseId: "w26", warehouseName: "Almacén 26",
    status: "ADDED", previousQuantity: null, currentQuantity: "6.700", delta: null, percent: null,
  },
];

function page(rows, query) {
  const index = Number(query.get("page") ?? 0);
  const size = Number(query.get("size") ?? 25);
  return { page: index, size, total: rows.length, rows: rows.slice(index * size, index * size + size) };
}

function period(query) {
  return { from: query.get("from") ?? basic.from, to: query.get("to") ?? basic.to, days: basic.days };
}

function comparison(query) {
  return query.get("comparison") === "NONE" ? null : {
    ...prior, from: query.get("comparisonFrom") ?? prior.from, to: query.get("comparisonTo") ?? prior.to,
  };
}

function statistics(query) {
  const base = statisticsSnapshot(query);
  if (!["WEEK", "MONTH"].includes(query.get("groupBy"))) return base;
  const first = { ...base.rows[0], key: "0", label: basic.from, comparisonLabel: prior.from };
  const absent = { ...base.rows[0], key: "1", label: null, comparisonLabel: "2026-06-08", current: null };
  return { ...base, period: period(query), comparisonPeriod: comparison(query), totalGroups: 2, rows: [first, absent] };
}

function articleRows() {
  const current = { quantitySold: "7.250", quantityReturned: "0.250", netQuantity: "7.000",
    salesAmount: "9007199254740994.00", returnsAmount: "0.83", netAmount: "9007199254740993.17",
    saleDocuments: 2, averageQuantityPerSale: "3.625", amountSharePercent: "70.00" };
  const comparison = { quantitySold: "6.000", quantityReturned: "5.000", netQuantity: "1.000",
    salesAmount: "40.00", returnsAmount: "25.00", netAmount: "15.00",
    saleDocuments: 1, averageQuantityPerSale: "6.000", amountSharePercent: "24.00" };
  const empty = { quantitySold: "0.000", quantityReturned: "0.000", netQuantity: "0.000",
    salesAmount: "0.00", returnsAmount: "0.00", netAmount: "0.00",
    saleDocuments: 0, averageQuantityPerSale: null, amountSharePercent: null };
  const first = {
    rank: 1, companyId: "retail", companyName: "Comercial E2E", storeId: "north", storeName: "Tienda Norte",
    productId: "p1", productCode: "CAF-01", productName: "Café natural", currency: "EUR",
    current, comparison, deltaAmount: "9007199254740978.17", deltaPercent: "60047995031606521.13", deltaQuantity: "6.000",
  };
  return [
    first,
    { ...first, productId: "p2", productName: "Harina de trigo", productCode: "HAR-02", currency: "USD",
      current: empty, deltaAmount: "-15.00", deltaPercent: "-100.00", deltaQuantity: "-1.000" },
    ...Array.from({ length: 25 }, (_, index) => ({
      ...first, rank: index + 2, productId: "ref-" + index, productCode: "REF-" + String(index).padStart(2, "0"),
      productName: "Referencia " + index, current: { ...current, netAmount: "20.00" },
    })),
  ];
}

function articles(query) {
  let rows = articleRows();
  const search = query.get("search")?.toLowerCase();
  if (search) rows = rows.filter((row) => [row.productCode, row.productName].some((value) => value.toLowerCase().includes(search)));
  if (query.get("sort") === "CODE") rows.sort((a, b) => a.productCode.localeCompare(b.productCode) * (query.get("direction") === "DESC" ? -1 : 1));
  const result = page(rows, query);
  const comparable = comparison(query);
  return { ...result, totalRows: result.total, asOf: statisticsAsOf, period: period(query), comparisonPeriod: comparable,
    coverage: { ...coverage, comparisonIncompleteDocuments: comparable ? 0 : null } };
}

function documents(query) {
  const rows = [
    { documentId: "doc-1", type: "TICKET", number: "T0001", status: "CONFIRMADO", businessDate: query.get("from"),
      companyId: "retail", companyName: "Comercial E2E", storeId: "north", storeName: "Tienda Norte", currency: query.get("currency") ?? "EUR",
      total: "9007199254740993.17", eligible: true, linesComplete: true, relationshipsComplete: true },
    { documentId: "doc-2", type: "FACTURA_VENTA", number: "F0002", status: "ANULADO", businessDate: query.get("to"),
      companyId: "retail", companyName: "Comercial E2E", storeId: "north", storeName: "Tienda Norte", currency: query.get("currency") ?? "EUR",
      total: "-8.00", eligible: false, linesComplete: false, relationshipsComplete: true },
  ];
  return { ...page(rows, query), asOf: statisticsAsOf, period: period(query), totalRows: rows.length,
    coverage: { ...coverage, currentIncompleteDocuments: 1, comparisonIncompleteDocuments: null } };
}

function inventory(endpoint, query) {
  if (endpoint === "comparison") {
    const rowsInScope = rows.filter((row) => (!query.has("warehouseId") || row.warehouseId === query.get("warehouseId"))
      && (!query.has("search") || row.name.toLowerCase().includes(query.get("search").toLowerCase())));
    return {
      ...page(query.get("changedOnly") === "true" ? rowsInScope.filter((row) => row.status !== "UNCHANGED") : rowsInScope, query),
      previous: earlierSnapshot, current: currentSnapshot,
      counts: { added: 1, removed: 1, changed: 1, unchanged: 0 },
      catalogue: { added: 1, removed: 1, activated: 0, deactivated: 1, modified: 1 },
    };
  }
  if (endpoint === "provinces") {
    return [{ province: "35", totals: { stores: 2, reportedStores: 1, products: 26, activeProducts: 25, warehouses: 26,
      balances: 3, positiveCount: 1, zeroCount: 1, negativeCount: 1 } }];
  }
  const response = inventoryResponse(endpoint, query);
  if (endpoint === "overview") {
    response.rows = response.rows.map((row) => row.storeId === "north" ? { ...row, snapshot: currentSnapshot } : row);
  }
  if (endpoint === "history") {
    response.rows = response.rows.map((row) => ({ ...row, installationId: row.currentInstallation ? "install-current" : "install-old" }));
  }
  return response;
}

const ledger = [
  { movementId: "m-1", companyId: "retail", companyName: "Comercial E2E", storeId: "north", storeName: "Tienda Norte", province: "35",
    warehouseId: "w1", warehouseName: "Almacén Principal", productId: "p1", productName: "Café natural", type: "TRANSFERENCIA_SALIDA",
    quantity: "-9007199254740993.123", occurredAt: "2026-07-01T03:45:12Z", receivedAt: "2026-07-01T04:41:06Z" },
  { movementId: "m-2", companyId: "retail", companyName: "Comercial E2E", storeId: "north", storeName: "Tienda Norte", province: "35",
    warehouseId: "w1", warehouseName: "Almacén Principal", productId: "p2", productName: "Harina de trigo", type: "AJUSTE",
    quantity: "0.000", occurredAt: "2026-07-02T16:11:11Z", receivedAt: "2026-07-02T16:12:11Z" },
];

function movements(query, isLedger) {
  const rows = ledger.filter((row) => ["type", "storeId", "warehouseId", "productId"].every((key) => !query.has(key) || row[key] === query.get(key)));
  if (isLedger) return page(rows, query);
  const groupBy = query.get("groupBy");
  const groups = groupBy === "WAREHOUSE" ? [{ key: "w1", label: "Almacén Principal", comparisonLabel: null, currentCount: rows.length, comparisonCount: 1 }]
    : rows.map((row) => ({ key: row.type, label: row.type, comparisonLabel: null, currentCount: 1, comparisonCount: 0 }));
  const result = page(groups, query);
  return { ...result, groupBy, totalGroups: result.total, asOf: statisticsAsOf, period: period(query), comparisonPeriod: comparison(query),
    currentCount: rows.length, comparisonCount: 1, coverage: { malformedReceipts: 3, conflictingMovements: 2, duplicateReceipts: 5 } };
}

export function reportFixture(path, query) {
  if (path === statisticsPath + "/options") return inventoryOptions;
  if (path === statisticsPath) return statistics(query);
  if (path === reportsPath + "/articles") return articles(query);
  if (path === reportsPath + "/documents") return documents(query);
  if (path === reportsPath + "/movements" || path === reportsPath + "/movements/ledger") return movements(query, path.endsWith("/ledger"));
  if (path.startsWith(inventoryPath + "/")) return inventory(path.slice(inventoryPath.length + 1), query);
  throw new Error("Unexpected report path " + path);
}
