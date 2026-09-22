import assert from "node:assert/strict";
import test from "node:test";
import { failureDateRange, validInstallationId } from "../src/features/supervision/failure-filters.mjs";

test("an inclusive single-day filter includes the next midnight only as the exclusive end", () => {
  const interval = failureDateRange("2026-09-20", "2026-09-20");
  assert.equal(new Date(interval.from).getDate(), 20);
  assert.equal(new Date(interval.to).getDate(), 21);
  assert.equal(new Date(interval.from).getHours(), 0);
  assert.equal(new Date(interval.to).getHours(), 0);
});
test("date boundaries preserve local midnight through daylight-saving transitions", () => {
  const interval = failureDateRange("2026-03-29", "2026-03-29");
  assert.equal(new Date(interval.to).getDate(), 30);
  assert.equal(new Date(interval.to).getHours(), 0);
});
test("empty dates impose no bounds and reversed dates are rejected", () => {
  assert.deepEqual(failureDateRange("", ""), { from: "", to: "" });
  assert.throws(() => failureDateRange("2026-09-21", "2026-09-20"), RangeError);
});
test("installation filter accepts an empty scope or a UUID but rejects references", () => {
  assert.equal(validInstallationId(""), true);
  assert.equal(validInstallationId("da5afc4a-968b-47d9-afef-69e6e4bc9b64"), true);
  assert.equal(validInstallationId("INSTALLATION-001"), false);
});
