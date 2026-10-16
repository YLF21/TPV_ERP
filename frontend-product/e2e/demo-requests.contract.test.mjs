import assert from "node:assert/strict";
import { test } from "node:test";
import { checkDemoAvailability, createDemoRequestAttempts, createDemoRequestKey, demoFieldLimits, DemoRequestError, submitDemoRequest } from "../src/demoRequests.ts";

// Pure transport-contract tests: no browser, running server, real lead or clipboard access.
const receipt = { id: "7b3dbe84-8d67-48db-bc48-f355d7ad7f82", receivedAt: "2026-10-03T12:00:00.123456Z" };
const jsonResponse = (body, status = 200, contentType = "application/json") => new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
const signal = () => new AbortController().signal;

test("public demo transport contract", async (suite) => {
  const previousFetch = globalThis.fetch;
  const previousWindow = globalThis.window;
  globalThis.window = { setTimeout, clearTimeout };
  try {
    await suite.test("availability requires the explicit JSON contract, not an HTML SPA fallback", async () => {
      globalThis.fetch = async (url, options) => {
        assert.match(url, /\/api\/v1\/public\/demo-requests\/availability$/);
        assert.equal(options.method, "GET");
        assert.equal(options.cache, "no-store");
        return jsonResponse({ available: true });
      };
      assert.equal(await checkDemoAvailability(signal()), true);
      for (const body of [{ available: false }, { available: "true" }, { status: "UP" }, null]) {
        globalThis.fetch = async () => jsonResponse(body);
        assert.equal(await checkDemoAvailability(signal()), false);
      }
      globalThis.fetch = async () => jsonResponse({ available: false }, 503);
      assert.equal(await checkDemoAvailability(signal()), false);
      globalThis.fetch = async () => new Response("<!doctype html>", { headers: { "Content-Type": "text/html" } });
      await assert.rejects(checkDemoAvailability(signal()), error => error instanceof DemoRequestError && error.kind === "invalidReceipt");
    });

    await suite.test("submission carries the exact caller key and only accepts a receipt with UUID and ISO time", async () => {
      const key = createDemoRequestKey();
      assert.match(key, /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i);
      assert.notEqual(createDemoRequestKey(), key);
      globalThis.fetch = async (url, options) => {
        assert.match(url, /\/api\/v1\/public\/demo-requests$/);
        assert.equal(options.method, "POST");
        assert.equal(options.headers["Idempotency-Key"], key);
        assert.equal(options.body, "{}");
        assert.equal(options.credentials, "omit");
        return jsonResponse(receipt, 201);
      };
      assert.deepEqual(await submitDemoRequest("{}", key, signal()), receipt);
      for (const invalid of [null, {}, { ...receipt, id: "not-a-uuid" }, { ...receipt, receivedAt: "yesterday" }, { ...receipt, receivedAt: "2026-10-03" }]) {
        globalThis.fetch = async () => jsonResponse(invalid, 201);
        await assert.rejects(submitDemoRequest("{}", key, signal()), error => error.kind === "invalidReceipt");
      }
      globalThis.fetch = async () => new Response("<!doctype html>", { status: 200, headers: { "Content-Type": "text/html" } });
      await assert.rejects(submitDemoRequest("{}", key, signal()), error => error.kind === "invalidReceipt");
      globalThis.fetch = async () => new Response("{broken", { status: 201, headers: { "Content-Type": "application/json" } });
      await assert.rejects(submitDemoRequest("{}", key, signal()), error => error.kind === "invalidReceipt");
    });

    await suite.test("errors remain distinguishable and rejected values are never exposed", async () => {
      for (const [status, kind] of [[400, "validation"], [422, "validation"], [409, "conflict"], [429, "rateLimited"], [500, "unavailableError"], [503, "unavailableError"]]) {
        globalThis.fetch = async () => jsonResponse({ code: "ERROR", fields: { name: "REQUIRED", message: "TOO_LONG", email: "INVALID", privacyAccepted: "MUST_ACCEPT", unknown: "REQUIRED", company: "Rejected private value" } }, status, "application/problem+json");
        await assert.rejects(submitDemoRequest("{}", createDemoRequestKey(), signal()), error => {
          assert.equal(error.kind, kind);
          if (kind === "validation") assert.deepEqual(error.fields, { name: "REQUIRED", message: "TOO_LONG", email: "INVALID", privacyAccepted: "MUST_ACCEPT" });
          assert.doesNotMatch(error.message, /private value/);
          return true;
        });
      }
      globalThis.fetch = async () => { throw new TypeError("Failed to fetch"); };
      await assert.rejects(submitDemoRequest("{}", createDemoRequestKey(), signal()), error => error.kind === "network");
    });

    await suite.test("a hung request times out and caller cancellation remains cancellation", async () => {
      globalThis.window = { setTimeout: callback => { queueMicrotask(callback); return 1; }, clearTimeout: () => {} };
      globalThis.fetch = async (_url, options) => new Promise((_resolve, reject) => {
        const abort = () => reject(new DOMException("Aborted", "AbortError"));
        if (options.signal.aborted) abort();
        else options.signal.addEventListener("abort", abort, { once: true });
      });
      await assert.rejects(submitDemoRequest("{}", createDemoRequestKey(), signal()), error => error.kind === "timeout");
      const cancelled = new AbortController();
      cancelled.abort();
      await assert.rejects(checkDemoAvailability(cancelled.signal), error => error.name === "AbortError" && !(error instanceof DemoRequestError));
      globalThis.window = { setTimeout, clearTimeout };
    });

    await suite.test("field limits remain aligned with the backend DTO", () => {
      assert.deepEqual(demoFieldLimits, { name: 160, company: 200, email: 160, phone: 40, message: 2000 });
    });

    await suite.test("reversible edits reuse normalized-body keys while confirmed or evicted requests get a new key", () => {
      const attempts = createDemoRequestAttempts(3);
      const firstBody = JSON.stringify({ name: "Demo", products: ["APP_VENTA"] });
      const firstKey = attempts.keyFor(firstBody);
      const reversedWhitespaceBody = JSON.stringify({ name: "  Demo  ".trim(), products: ["APP_VENTA"] });
      assert.equal(attempts.keyFor(reversedWhitespaceBody), firstKey, "Equivalent trimmed form data must not create a duplicate key");
      const secondBody = JSON.stringify({ name: "Demo", products: ["APP_VENTA", "APP_PDA"] });
      assert.notEqual(attempts.keyFor(secondBody), firstKey);
      assert.equal(attempts.keyFor(firstBody), firstKey, "A failed A → B → A sequence must retain the original key");
      attempts.complete(firstBody);
      const freshKey = attempts.keyFor(firstBody);
      assert.notEqual(freshKey, firstKey, "A confirmed receipt starts a new, explicit request next time");
      attempts.keyFor("third");
      attempts.keyFor("fourth");
      attempts.keyFor("fifth");
      assert.notEqual(attempts.keyFor(firstBody), freshKey, "The in-memory cache must remain bounded");
    });
  } finally {
    globalThis.fetch = previousFetch;
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  }
});
