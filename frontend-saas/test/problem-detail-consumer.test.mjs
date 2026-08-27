import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { extractApiErrorMessage } from "../src/lib/problem-detail.mjs";

test("frontend SaaS shows RFC 9457 detail for actionable API errors", async () => {
  const appSource = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  for (const [status, detail] of [
    [400, "El NIF de la empresa no es válido."],
    [409, "La licencia ya está bloqueada."],
    [429, "Se ha superado el límite de peticiones."]
  ]) {
    const raw = JSON.stringify({ type: "about:blank", title: "Error", status, detail });
    assert.equal(extractApiErrorMessage(raw), detail);
  }

  assert.match(appSource, /extractApiErrorMessage\(error\.message\)/);
});

test("legacy fallbacks remain supported but HTML responses are never shown", () => {
  assert.equal(extractApiErrorMessage(JSON.stringify({ message: "Respuesta legacy" })), "Respuesta legacy");
  assert.equal(extractApiErrorMessage(JSON.stringify({ error: "Not Found" })), "Not Found");
  assert.equal(extractApiErrorMessage("<html><body>proxy failure</body></html>"), null);
});
