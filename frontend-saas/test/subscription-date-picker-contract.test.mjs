import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("subscription date fields open the large custom calendar on focus or click", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /<DateTimePicker label=\{t\("startedAt"\)\}/);
  assert.match(app, /<DateTimePicker label=\{t\("nextBillingAt"\)\}/);
  assert.match(app, /className="date-time-popover" role="dialog"/);
  assert.match(app, /onFocus=\{\(\) => !disabled && setOpen\(true\)\}/);
  assert.match(app, /className="date-time-days" role="grid"/);
});

test("every shared date and datetime input delegates to the large calendar", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /if \(type === "date" \|\| type === "datetime-local"\)/);
  assert.match(app, /dateOnly=\{type === "date"\}/);
  assert.match(app, /disabled=\{disabled\}/);
  assert.match(app, /dateOnly \? toDateInput\(next\) : toLocalInput\(next\)/);
  assert.doesNotMatch(app, /<input[^>]+type="datetime-local"/);
  assert.doesNotMatch(app, /<input[^>]+type="date"/);
});
