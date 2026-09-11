import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("global search offers company, store and tax ID criteria", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /type GlobalSearchCriterion = "company" \| "store" \| "taxId"/);
  assert.match(app, /<option value="company">/);
  assert.match(app, /<option value="store">/);
  assert.match(app, /<option value="taxId">/);
  assert.match(app, /setSearchCriterion\(event\.target\.value as GlobalSearchCriterion\)/);
});

test("global search renders selectable suggestions while typing", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /function buildGlobalSearchSuggestions\(/);
  assert.match(app, /className="global-search-suggestions" role="listbox"/);
  assert.match(app, /searchSuggestions\.map\(\(suggestion\)/);
  assert.match(app, /setSearchQuery\(suggestion\.value\)/);
  assert.match(app, /aria-autocomplete="list"/);
});

test("store names come from the fiscal inventory without blocking dashboard refresh", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /api\.fiscalStatus\(activeCredentials\)\.catch\(\(\) => \[\] as FiscalStatusAdmin\[\]\)/);
  assert.match(app, /store\.storeName \|\| store\.storeId/);
  assert.match(app, /filterDashboardData\(data, searchQuery, searchCriterion, searchStores\)/);
});
