import assert from "node:assert/strict";
import test from "node:test";
import { periodPreset, matchingPeriod, adjacentPeriod } from "../src/features/reports/period-shortcuts.mjs";
import { reportExportFilename } from "../src/shared/analytics/export-filename.mjs";

test("UTC presets cross year and leap-day boundaries and identify their visible selection", () => {
  const now = new Date("2028-03-01T00:10:00Z");
  assert.deepEqual(periodPreset("LAST_MONTH", now), { from: "2028-02-01", to: "2028-02-29" });
  assert.deepEqual(periodPreset("YESTERDAY", now), { from: "2028-02-29", to: "2028-02-29" });
  assert.deepEqual(periodPreset("LAST_MONTH", new Date("2026-01-02T01:00Z")), { from: "2025-12-01", to: "2025-12-31" });
  assert.equal(matchingPeriod("2028-02-01", "2028-02-29", now), "LAST_MONTH");
});
test("adjacent periods preserve whole calendar months and inclusive custom interval lengths", () => {
  assert.deepEqual(adjacentPeriod("2028-03-01", "2028-03-31", -1), { from: "2028-02-01", to: "2028-02-29" });
  assert.deepEqual(adjacentPeriod("2028-02-01", "2028-02-29", 1), { from: "2028-03-01", to: "2028-03-31" });
  assert.deepEqual(adjacentPeriod("2026-11-01", "2026-11-18", -1), { from: "2026-10-14", to: "2026-10-31" });
  assert.equal(adjacentPeriod("", "2026-11-18", 1), null);
  assert.equal(adjacentPeriod("2026-11-18", "2026-11-01", -1), null);
});
test("export names identify filtered reports and sanitize path characters", () => {
  assert.equal(reportExportFilename("/api/v1/admin/reports/export/articles", { from: "2026-11-01", to: "2026-11-18", storeName: "Tienda/ABC" }, "xlsx"), "espos-articulos-Tienda-ABC-2026-11-01-2026-11-18.xlsx");
  assert.equal(reportExportFilename("/api/v1/admin/reports/export/inventory?kind=balances", {}, "csv", "en", undefined, new Date("2026-11-18T20:00Z")), "espos-inventory-stock-2026-11-18T20-00-00-UTC.csv");
});
