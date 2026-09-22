import assert from "node:assert/strict";
import test from "node:test";
import { normalizeTableLayout, reorderTableColumns, moveTableColumn, toggleTableColumn, tableLayoutKey } from "../src/shared/table/table-layout.mjs";
const definitions = [{ key: "reference", defaultWidth: 160 }, { key: "company", defaultWidth: 220 }, { key: "expiry", defaultWidth: 180 }];
test("restored columns reject obsolete/duplicate keys, constrain widths and append newly defined columns", () => {
  const layout = normalizeTableLayout([{ key: "expiry", width: 9000, visible: false }, { key: "expiry", width: 9 }, { key: "secret", width: 120 }], definitions);
  assert.deepEqual(layout.map(c => c.key), ["expiry", "reference", "company"]);
  assert.equal(layout[0].width, 800); assert.equal(layout[0].visible, false); assert.equal(layout[1].width, 160);
});
test("corrupt preferences cannot hide every column and the last visible column cannot be hidden", () => {
  const layout = normalizeTableLayout(definitions.map(c => ({ ...c, visible: false })), definitions);
  assert.equal(layout.filter(c => c.visible).length, 1);
  assert.deepEqual(toggleTableColumn(layout, "reference"), layout);
});
test("drag reorder preserves widths/visibility and keyboard movement skips hidden columns", () => {
  const layout = normalizeTableLayout(null, definitions);
  const hidden = toggleTableColumn(layout, "company");
  assert.deepEqual(moveTableColumn(hidden, "reference", 1).map(c => c.key), ["company", "expiry", "reference"]);
  assert.deepEqual(reorderTableColumns(layout, "expiry", "reference").map(c => c.key), ["expiry", "reference", "company"]);
  assert.equal(reorderTableColumns(layout, "missing", "reference"), layout);
});
test("table preferences are isolated by account and table without a token", () => {
  assert.equal(tableLayoutKey(" ADMIN ", "licenses"), tableLayoutKey("admin", "licenses"));
  assert.notEqual(tableLayoutKey("admin", "licenses"), tableLayoutKey("viewer", "licenses"));
  assert.notEqual(tableLayoutKey("admin", "licenses"), tableLayoutKey("admin", "stores"));
});
