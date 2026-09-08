import assert from "node:assert/strict";
import test from "node:test";
import { canInvoiceBePaid, formatCurrency, formatQuantity, hasVerifiableFiscalEvidence, isCurrentAuthRequest, isCurrentSelection, isCurrentSessionRequest, outstandingAmount, paginateRows, retainCompanyOperationsAfterFailure, settleWithConcurrency, shouldInvalidateSession, validateFiscalDecision } from "../src/lib/frontend-runtime.mjs";

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

test("payment is blocked until the invoice has a final fiscal decision", () => {
  assert.equal(canInvoiceBePaid(undefined), false);
  assert.equal(canInvoiceBePaid({ fiscalStatus: "PENDING_TAX_DATA" }), false);
  assert.equal(canInvoiceBePaid({ fiscalStatus: "CALCULATED" }), true);
  assert.equal(canInvoiceBePaid({ fiscalStatus: "NOT_APPLICABLE" }), true);
});

test("fiscal decisions enforce calculated amounts or verifiable non-applicable evidence", () => {
  assert.equal(validateFiscalDecision({ fiscalStatus: "CALCULATED", taxBase: "100.00", taxRate: "21.00", taxAmount: "21.00" }), true);
  assert.equal(validateFiscalDecision({ fiscalStatus: "CALCULATED", taxBase: "", taxRate: "21", taxAmount: "21" }), false);
  assert.equal(validateFiscalDecision({ fiscalStatus: "NOT_APPLICABLE", reason: "Exportacion fuera UE", legalBasis: "Ley IVA art. 21", evidenceReference: "DUA-2026-001" }), true);
  assert.equal(validateFiscalDecision({ fiscalStatus: "NOT_APPLICABLE", reason: "No aplica", legalBasis: "Ley", evidenceReference: "test" }), false);
  assert.equal(hasVerifiableFiscalEvidence("aaaaaaaaaa", 8), false);
  assert.equal(hasVerifiableFiscalEvidence("abc abc abc", 8), false);
  assert.equal(hasVerifiableFiscalEvidence("Referencia EXP-2026-01", 8), true);
});

test("bounded settlement preserves order and limits fiscal request concurrency", async () => {
  let active = 0;
  let maximum = 0;
  const results = await settleWithConcurrency([1, 2, 3, 4, 5], async (value) => {
    active += 1; maximum = Math.max(maximum, active);
    await new Promise((resolve) => setTimeout(resolve, 2));
    active -= 1;
    if (value === 3) throw new Error("expected");
    return value * 2;
  }, 2);
  assert.equal(maximum, 2);
  assert.deepEqual(results.map((result) => result.status), ["fulfilled", "fulfilled", "rejected", "fulfilled", "fulfilled"]);
  assert.equal(results[4].value, 10);
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

test("logout invalidates an in-flight authentication continuation", () => {
  assert.equal(isCurrentAuthRequest(4, 4), true);
  assert.equal(isCurrentAuthRequest(4, 5), false);
});

test("an operations load failure never fabricates or crosses company data", () => {
  const known = { companyId: "company-a", planName: "PREMIUM" };
  assert.equal(retainCompanyOperationsAfterFailure(null, "company-a"), null);
  assert.equal(retainCompanyOperationsAfterFailure(known, "company-b"), null);
  assert.equal(retainCompanyOperationsAfterFailure(known, "company-a"), known);
});
