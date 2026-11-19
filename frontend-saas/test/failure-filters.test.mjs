import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import test from "node:test";
import { failureDateRange, validInstallationId } from "../src/features/supervision/failure-filters.mjs";

test("an inclusive single-day filter uses UTC midnight and the next UTC midnight as the exclusive end", () => {
  const interval = failureDateRange("2026-09-20", "2026-09-20");
  assert.deepEqual(interval, { from: "2026-09-20T00:00:00.000Z", to: "2026-09-21T00:00:00.000Z" });
});
test("date boundaries remain UTC across timezones and daylight-saving transitions", () => {
  const module = new URL("../src/features/supervision/failure-filters.mjs", import.meta.url).href;
  const script = `import { failureDateRange } from ${JSON.stringify(module)}; console.log(JSON.stringify(failureDateRange('2026-03-29', '2026-03-29')));`;
  for (const zone of ["UTC", "Europe/Madrid", "Asia/Shanghai"]) {
    const result = JSON.parse(execFileSync(process.execPath, ["--input-type=module", "-e", script], { env: { ...process.env, TZ: zone }, encoding: "utf8" }));
    assert.deepEqual(result, { from: "2026-03-29T00:00:00.000Z", to: "2026-03-30T00:00:00.000Z" });
  }
});
test("empty dates impose no bounds and reversed dates are rejected", () => {
  assert.deepEqual(failureDateRange("", ""), { from: "", to: "" });
  assert.throws(() => failureDateRange("2026-09-21", "2026-09-20"), RangeError);
  assert.throws(() => failureDateRange("2026-02-30", ""), RangeError);
});
test("installation filter accepts an empty scope or a UUID but rejects references", () => {
  assert.equal(validInstallationId(""), true);
  assert.equal(validInstallationId("da5afc4a-968b-47d9-afef-69e6e4bc9b64"), true);
  assert.equal(validInstallationId("INSTALLATION-001"), false);
});
