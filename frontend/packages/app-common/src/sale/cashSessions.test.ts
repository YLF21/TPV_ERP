import { describe, expect, it, vi } from "vitest";
import { loadCashSessionReadiness } from "./cashSessions";

describe("cash session readiness", () => {
  it("loads the terminal cash state without mutating it", async () => {
    const request = vi.fn().mockResolvedValue({
      cashSessionRequired: true,
      open: false,
      session: null,
      requireWithdrawalBreakdown: false,
      withdrawalDenominations: [],
    });

    await loadCashSessionReadiness("terminal 01", "token", request);

    expect(request).toHaveBeenCalledWith(
      "/cash/sessions/readiness?terminalId=terminal+01",
      { token: "token" },
    );
  });
});
