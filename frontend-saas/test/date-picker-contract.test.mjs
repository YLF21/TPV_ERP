import assert from "node:assert/strict";
import test from "node:test";
import { readSources } from "../test-support/source-helpers.mjs";

test("the shared date picker opens its accessible calendar on focus or click", async () => {
  const app = await readSources("shared/ui.tsx");
  assert.match(app, /className="date-time-popover" role="dialog"/);
  assert.match(app, /onFocus=\{\(\) => !disabled && setOpen\(true\)\}/);
  assert.match(app, /className="date-time-days" role="grid"/);
});

test("every shared date and datetime input delegates to the large calendar", async () => {
  const app = await readSources("shared/ui.tsx");

  assert.match(app, /if \(type === "date" \|\| type === "datetime-local"\)/);
  assert.match(app, /dateOnly=\{type === "date"\}/);
  assert.match(app, /disabled=\{disabled\}/);
  assert.match(app, /dateOnly \? toDateInput\(next\) : toLocalInput\(next\)/);
  assert.doesNotMatch(app, /<input[^>]+type="datetime-local"/);
  assert.doesNotMatch(app, /<input[^>]+type="date"/);
});

test("subscription UI and public API contracts are retired while billing and plan usage remain", async () => {
  const source = await readSources("app/navigation.ts", "app/App.tsx", "lib/api.ts", "lib/types.ts");
  assert.doesNotMatch(source, /createSubscription|SubscriptionView|view: "subscriptions"|\/subscriptions/);
  assert.match(source, /plan-usage/);
  assert.match(source, /createBillingInvoice/);
  assert.match(source, /createBillingPayment/);
});
