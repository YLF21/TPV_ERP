import assert from "node:assert/strict";
import test from "node:test";
import { readAnalyticsFilters } from "../src/features/reports/analytics-context.ts";

const credentials = { username: "CONTEXT_TEST", mode: "admin", accessToken: "never-persist" };
const key = "espos.analytics.context.v1.CONTEXT_TEST.admin.";
const common = { from: "2026-11-01", to: "2026-11-18", companyId: "company-a", storeId: "store-a", province: "35", comparison: "CUSTOM", comparisonFrom: "2026-10-01", comparisonTo: "2026-10-18" };
const state = { common, sections: {
  documents: { productId: "old-product", currency: "USD", sort: "AMOUNT", page: "3" },
  articles: { ...common, from: "2026-10-01", to: "2026-10-18", page: "4", search: "coffee" },
  "inventory-detail:store-a": { productId: "p1", snapshotId: "capture-a", companyId: "company-a", storeId: "store-a" },
} };

test("an explicit document link does not inherit another product, currency or company", () => {
  globalThis.localStorage = { getItem: name => name === key ? JSON.stringify(state) : null };
  globalThis.window = { location: { hash: "#/reports/documents?from=2026-11-01&to=2026-11-18&province=28" } };
  const actual = readAnalyticsFilters(credentials, "documents");
  assert.equal(actual.productId, undefined);
  assert.equal(actual.currency, undefined);
  assert.equal(actual.companyId, "");
  assert.equal(actual.storeId, "");
  assert.equal(actual.province, "28");
  assert.equal(actual.page, undefined);
});
test("shared scope overrides stale report dates and resets page without leaking between users", () => {
  globalThis.localStorage = { getItem: name => name === key ? JSON.stringify(state) : null };
  globalThis.window = { location: { hash: "#/reports/sales" } };
  const actual = readAnalyticsFilters(credentials, "articles");
  assert.equal(actual.from, common.from);
  assert.equal(actual.search, "coffee");
  assert.equal(actual.page, "0");
  assert.deepEqual(readAnalyticsFilters({ ...credentials, username: "OTHER" }, "articles"), {});
});
test("isolated snapshot preferences retain their own scope and only permitted fields are restored", () => {
  globalThis.localStorage = { getItem: () => JSON.stringify({ ...state, common: { ...common, accessToken: "not-allowed", page: 123 } }) };
  globalThis.window = { location: { hash: "#/reports/inventory" } };
  const actual = readAnalyticsFilters(credentials, "inventory-detail:store-a", { shareScope: false });
  assert.deepEqual(actual, state.sections["inventory-detail:store-a"]);
  assert.equal(readAnalyticsFilters(credentials, "articles").accessToken, undefined);
});
test("moving to another store drops product and warehouse selectors from the previous store", () => {
  globalThis.localStorage = { getItem: () => JSON.stringify({ common: { ...common, storeId: "store-b" }, sections: {
    movements: { ...common, productId: "p1", warehouseId: "w1", warehouseName: "Old warehouse", page: "3" },
  } }) };
  globalThis.window = { location: { hash: "#/reports/sales" } };
  const actual = readAnalyticsFilters(credentials, "movements");
  assert.equal(actual.storeId, "store-b");
  assert.equal(actual.productId, undefined);
  assert.equal(actual.warehouseId, undefined);
  assert.equal(actual.page, "0");
});
