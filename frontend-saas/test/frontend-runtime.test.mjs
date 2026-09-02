import assert from "node:assert/strict";
import test from "node:test";
import { formatCurrency, formatQuantity, isCurrentSelection, isCurrentSessionRequest, outstandingAmount, paginateRows, shouldInvalidateSession } from "../src/lib/frontend-runtime.mjs";

test("currency formatter respects the invoice currency instead of forcing EUR", () => {
  assert.match(formatCurrency("12.50", "USD", "en-US"), /\$12\.50/);
  assert.match(formatCurrency("12.50", "EUR", "es-ES"), /12,50/);
});

test("stock quantities are formatted as units and never as money", () => {
  const quantity = formatQuantity("12.500", "es-ES");
  assert.equal(quantity, "12,5");
  assert.doesNotMatch(quantity, /\$|USD|EUR/);
});

test("outstanding payment is calculated from amount minus already paid", () => {
  assert.equal(outstandingAmount({ amount: "100.00", paidAmount: "25.50" }), 74.5);
  assert.equal(outstandingAmount({ amount: "10", paidAmount: "15" }), 0);
});

test("late company responses are rejected by selection guard", () => {
  assert.equal(isCurrentSelection("company-a", "company-a"), true);
  assert.equal(isCurrentSelection("company-a", "company-b"), false);
});

test("pagination clamps invalid pages and returns deterministic slices", () => {
  const rows = Array.from({ length: 45 }, (_, index) => index + 1);
  assert.deepEqual(paginateRows(rows, 2, 20).rows, rows.slice(20, 40));
  const last = paginateRows(rows, 99, 20);
  assert.equal(last.page, 3);
  assert.deepEqual(last.rows, rows.slice(40));
});
test("late account responses cannot replace the current session", () => {
  const requestFromAccountA = { id: 7, token: "token-a" };
  assert.equal(isCurrentSessionRequest(requestFromAccountA.id, 7, requestFromAccountA.token, "token-a"), true);
  assert.equal(isCurrentSessionRequest(requestFromAccountA.id, 8, requestFromAccountA.token, "token-b"), false);
  assert.equal(isCurrentSessionRequest(8, 8, requestFromAccountA.token, "token-b"), false);
});

test("a stale unauthorized response cannot invalidate a newer session", () => {
  assert.equal(shouldInvalidateSession("token-a", "token-b", undefined), false);
  assert.equal(shouldInvalidateSession("token-b", "token-b", undefined), true);
  assert.equal(shouldInvalidateSession("pending-token", undefined, "pending-token"), true);
});