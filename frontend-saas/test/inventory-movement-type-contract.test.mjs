import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { readSources } from "../test-support/source-helpers.mjs";

test("inventory movement type is a controlled selector", async () => {
  const app = await readSources("features/operations/OperationsView.tsx");

  assert.match(app, /<Select\s+label=\{t\("movementType"\)\}/);
  assert.match(app, /options=\{\["ENTRADA", "SALIDA", "AJUSTE"\]\}/);
  assert.doesNotMatch(app, /<Input label=\{t\("movementType"\)\}/);
});
