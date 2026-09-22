import assert from "node:assert/strict";
import test from "node:test";
import { remainingActivationSeconds, activationCountdown } from "../src/features/licenses/activation-time.mjs";

const now = "2026-09-21T12:00:00Z", expiry = "2026-09-21T12:30:00Z";
test("activation countdown uses server time and elapsed time, including a suspended tab", () => {
  assert.equal(remainingActivationSeconds(expiry, now, 500, 500), 1800);
  assert.equal(remainingActivationSeconds(expiry, now, 500, 1500), 1799);
  assert.equal(remainingActivationSeconds(expiry, now, 500, 900_500), 900);
  assert.equal(remainingActivationSeconds(expiry, now, 500, 1_800_500), 0);
  assert.equal(remainingActivationSeconds(expiry, now, 500, 3_600_500), 0);
});
test("activation countdown handles boundaries, invalid timestamps and historical longer lifetimes", () => {
  assert.equal(remainingActivationSeconds(expiry, now, 500, 1_800_499), 1);
  assert.equal(remainingActivationSeconds("invalid", now, 0, 0), 0);
  assert.equal(activationCountdown(1800), "30:00");
  assert.equal(activationCountdown(1799), "29:59");
  assert.equal(activationCountdown(-1), "00:00");
  assert.equal(activationCountdown(604800), "10080:00");
});
