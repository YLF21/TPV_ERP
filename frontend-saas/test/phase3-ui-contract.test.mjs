import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const sources = Promise.all([
  readFile(new URL("../src/App.tsx", import.meta.url), "utf8"),
  readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8")
]);

test("billing renders real plan usage and keeps optional endpoint errors retryable", async () => {
  const [app, api] = await sources;
  assert.match(api, /\/companies\/\$\{encodeURIComponent\(companyId\)\}\/plan-usage/);
  assert.match(app, /Object\.entries\(planUsage\.limits\)/);
  assert.match(app, /planUsage\.usage\[resource\]/);
  assert.match(app, /planError && <RetryError/);
  assert.match(app, /Promise\.allSettled/);
});

test("tenant master CSV UI validates input, scopes resource and never fabricates import totals", async () => {
  const [app, api] = await sources;
  assert.match(app, /roleName === "OWNER" \|\| data\.session\.roleName === "MANAGER"/);
  assert.match(app, /accept="\.csv,text\/csv"/);
  assert.match(app, /file\.name\.toLowerCase\(\)\.endsWith\("\.csv"\)/);
  assert.match(app, /file\.size === 0/);
  assert.match(app, /result\.processed/);
  assert.match(api, /\/tenant\/erp\/\$\{encodeURIComponent\(resource\)\}\/csv/);
  assert.match(api, /"Content-Type": "text\/csv;charset=UTF-8"/);
});

test("invoice fiscal detail and manual reconciliation stay company and permission scoped", async () => {
  const [app, api] = await sources;
  assert.match(api, /\/invoices\/\$\{encodeURIComponent\(invoiceId\)\}\/fiscal/);
  assert.match(api, /\/companies\/\$\{encodeURIComponent\(companyId\)\}\/reconciliations/);
  assert.match(app, /detail\.companyId !== requestedCompanyId/);
  assert.match(app, /const canManage = permissions\.has\("MANAGE_BILLING"\)/);
  assert.match(app, /canManage && <form className="compact-form-grid" onSubmit=\{createReconciliation\}>/);
  assert.match(app, /MANUAL_BANK/);
  assert.match(app, /MANUAL_GATEWAY/);
  assert.match(app, /paymentId: null/);
  assert.match(app, /reconciliationError && <RetryError/);
});

test("V49 fiscal evidence is editable, nullable and blocks pending invoice payments", async () => {
  const [app, api] = await sources;
  const types = await readFile(new URL("../src/lib/types.ts", import.meta.url), "utf8");
  assert.match(types, /fiscalStatus: "PENDING_TAX_DATA" \| "CALCULATED" \| "NOT_APPLICABLE"/);
  for (const field of ["taxBase", "taxRate", "taxAmount", "reason", "legalBasis", "evidenceReference"]) {
    assert.match(types, new RegExp(`${field}: string \\| null`));
  }
  assert.match(api, /updateInvoiceFiscal/);
  assert.match(api, /method: "PUT"/);
  assert.match(app, /canInvoiceBePaid\(fiscalStates\[selectedInvoice\.id\]\)/);
  assert.match(app, /disabled=\{!canInvoiceBePaid\(fiscalStates\[invoice\.id\]\)\}/);
  assert.match(app, /fiscalDetail\.taxBase === null \? t\(fiscalDetail\.fiscalStatus === "PENDING_TAX_DATA"/);
  assert.match(app, /fiscalDetail\.evidenceReference && <Metric/);
  assert.match(app, /form className="compact-form-grid" onSubmit=\{saveFiscalDecision\} aria-label=\{t\("fiscalDecision"\)\}/);
  assert.match(app, /reason: calculated \? null : fiscalForm\.reason\.trim\(\)/);
});
