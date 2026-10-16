import assert from "node:assert/strict";
import test from "node:test";
import { RequestDeadlineError, withinRequestDeadline } from "../src/lib/request-deadline.mjs";

test("request deadline includes a stalled response body after headers arrive", async () => {
  let headersReceived = false;
  let signal;
  const operation = withinRequestDeadline(async requestSignal => {
    signal = requestSignal;
    const response = { text: () => new Promise(() => {}) };
    headersReceived = true;
    return await response.text();
  }, 15);
  await assert.rejects(operation, RequestDeadlineError);
  assert.equal(headersReceived, true);
  assert.equal(signal.aborted, true);
});

test("successful response consumption returns data without later aborting", async () => {
  let signal;
  assert.deepEqual(await withinRequestDeadline(async requestSignal => {
    signal = requestSignal;
    return JSON.parse(await Promise.resolve('{"status":"CONTACTED"}'));
  }, 15), { status: "CONTACTED" });
  await new Promise(resolve => setTimeout(resolve, 25));
  assert.equal(signal.aborted, false);
});

test("external cancellation stops a stalled operation and an already aborted request never starts", async () => {
  const controller = new AbortController();
  let signal;
  const pending = withinRequestDeadline(async requestSignal => { signal = requestSignal; return new Promise(() => {}); }, 100, controller.signal);
  await Promise.resolve();
  controller.abort();
  await assert.rejects(pending, { name: "AbortError" });
  assert.equal(signal.aborted, true);
  let started = false;
  await assert.rejects(withinRequestDeadline(async () => { started = true; }, 10, controller.signal), { name: "AbortError" });
  assert.equal(started, false);
});
