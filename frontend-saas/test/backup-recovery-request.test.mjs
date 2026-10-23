import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseDestinationRequest } from "../src/features/support/destination-request.mjs";

describe("support destination request", () => {
  it("accepts only a bounded public request and discards extra fields", () => {
    const key = "A".repeat(512);
    assert.deepEqual(parseDestinationRequest({ state: "DESTINATION_READY", destinationPublicKey: key,
      destinationKeyFingerprint: "f".repeat(64), secret: "never expose" }), {
      destinationPublicKey: key, destinationKeyFingerprint: "f".repeat(64)
    });
    assert.equal(parseDestinationRequest({ state: "DESTINATION_READY", destinationPublicKey: "short" }), null);
    assert.equal(parseDestinationRequest({ state: "ACTIVE", destinationPublicKey: key }), null);
    assert.equal(parseDestinationRequest({ state: "DESTINATION_READY", destinationPublicKey: `${key}\nsecret` }), null);
  });
});
