import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("inventory movement type is a controlled selector", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /<Select\s+label=\{t\("movementType"\)\}/);
  assert.match(app, /options=\{\["ENTRADA", "SALIDA", "AJUSTE"\]\}/);
  assert.doesNotMatch(app, /<Input label=\{t\("movementType"\)\}/);
});
