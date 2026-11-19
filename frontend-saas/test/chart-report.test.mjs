import assert from "node:assert/strict";
import test from "node:test";
import { chartGroupLimit, loadChartReport } from "../src/features/reports/chart-report.ts";

const groupCount = report => report.totalGroups;
const groupKey = row => String(row.key);

function source(totalGroups, calls) {
  const rows = Array.from({ length: totalGroups }, (_, key) => ({ key, amount: key ? "9007199254740993.17" : null }));
  return async (page, size) => {
    calls.push({ page, size });
    return { page, size, totalGroups, rows: rows.slice(page * size, (page + 1) * size) };
  };
}

test("chart covers all 366 daily groups without changing missing or precise amounts", async () => {
  const calls = [];
  const { report, limitReached } = await loadChartReport(source(366, calls), groupCount, groupKey);
  assert.equal(limitReached, false);
  assert.deepEqual(calls.map(({ page, size }) => [page, size]), [[0, 100], [1, 100], [2, 100], [3, 100]]);
  assert.equal(report.rows.length, 366);
  assert.deepEqual(report.rows[0], { key: 0, amount: null });
  assert.deepEqual(report.rows.at(-1), { key: 365, amount: "9007199254740993.17" });
});

test("chart does not make more requests when the full ranking exceeds the limit", async () => {
  const calls = [];
  const result = await loadChartReport(source(chartGroupLimit + 1, calls), groupCount, groupKey);
  assert.deepEqual(result, { report: null, limitReached: true });
  assert.deepEqual(calls, [{ page: 0, size: 100 }]);
});

test("chart withholds a partial response if the number of groups changes during pagination", async () => {
  const calls = [];
  const load = source(126, calls);
  const result = await loadChartReport(async (page, size) => {
    const report = await load(page, size);
    return page === 1 ? { ...report, totalGroups: 127 } : report;
  }, groupCount, groupKey);
  assert.deepEqual(result, { report: null, limitReached: false });
  assert.deepEqual(calls, [{ page: 0, size: 100 }, { page: 1, size: 100 }]);
});

test("chart withholds duplicate groups even when page sizes and totals match", async () => {
  const load = source(126, []);
  const result = await loadChartReport(async (page, size) => {
    const report = await load(page, size);
    if (page === 1) report.rows[0] = { ...report.rows[0], key: 99 };
    return report;
  }, groupCount, groupKey);
  assert.deepEqual(result, { report: null, limitReached: false });
});
