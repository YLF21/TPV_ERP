import { describe, expect, it } from "vitest";
import { evaluateCompatibility, InvalidCompatibilityContractError, loadBackendCompatibility, type BackendCompatibility } from "./compatibility";
import { afterEach, vi } from "vitest";

const compatible: BackendCompatibility = {
  backendVersion: "2.0.0",
  apiVersion: "1",
  minimumFrontendVersion: "0.0.1",
  capabilities: ["PAYMENT_IDEMPOTENCY", "PAYMENT_RECOVERY", "PAYMENT_STATUS_QUERY", "PAYMENT_VOID",
    "PAYMENT_REFUND", "PAYMENT_RECONCILIATION", "CORRELATION_ID"],
  paymentStates: {}
};

describe("backend compatibility", () => {
  afterEach(() => vi.unstubAllGlobals());
  it("accepts a backend with the required contract", () => {
    expect(evaluateCompatibility(compatible, "1.0.0")).toMatchObject({ compatible: true });
  });

  it("rejects an incompatible API or missing payment recovery capability", () => {
    expect(evaluateCompatibility({ ...compatible, apiVersion: "2" }, "1.0.0").reason).toBe("API_VERSION");
    const withoutRecovery = { ...compatible, capabilities: compatible.capabilities.filter(value => value !== "PAYMENT_RECOVERY") };
    expect(evaluateCompatibility(withoutRecovery, "1.0.0")).toMatchObject({
      compatible: false,
      reason: "MISSING_CAPABILITIES",
      missingCapabilities: ["PAYMENT_RECOVERY"]
    });
  });

  it("rejects a frontend older than the backend minimum", () => {
    expect(evaluateCompatibility({ ...compatible, minimumFrontendVersion: "1.2.0" }, "1.1.9").reason)
      .toBe("FRONTEND_TOO_OLD");
  });

  it("rejects a malformed compatibility contract", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ apiVersion: "1" }), { status: 200 })));
    await expect(loadBackendCompatibility("token")).rejects.toBeInstanceOf(InvalidCompatibilityContractError);
  });
});
