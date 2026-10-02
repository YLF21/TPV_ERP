import { describe, expect, it, vi } from "vitest";
import { closeCashSession, loadCashSessionReadiness, loadCashTimeline, openCashSession } from "./cashSessions";

describe("cash session readiness", () => {
  it("sends counted opening money and its breakdown", async () => {
    const request = vi.fn().mockResolvedValue({});
    const denominations = [{ denomination: 20, quantity: 3 }];
    await openCashSession("T-1", "token", request, 60, denominations);
    expect(request).toHaveBeenCalledWith("/cash/sessions/open", {
      token: "token", method: "POST", body: { terminalId: "T-1", countedFund: 60, denominations },
    });
  });
  it("keeps both breakdowns and retry identities in the close request", async () => {
    const request = vi.fn().mockResolvedValue({});
    const breakdowns = { retainedFundDenominations: [{ denomination: 20, quantity: 3 }], finalWithdrawalDenominations: [{ denomination: 10, quantity: 1 }] };
    await closeCashSession("T-1", 60, 10, " Cierre ", "token", request, {}, "operation", "attempt", breakdowns);
    expect(request).toHaveBeenCalledWith("/cash/sessions/close", expect.objectContaining({ body: expect.objectContaining({
      ...breakdowns, closeOperationId: "operation", reconciliationAttemptId: "attempt", finalWithdrawalComment: "Cierre",
    }) }));
  });
  it("uses the server business day when loading today's timeline", async () => {
    const request = vi.fn().mockResolvedValue({});
    await loadCashTimeline("terminal 01", "token", request);
    expect(request).toHaveBeenCalledWith("/cash/timeline?terminalId=terminal+01", { token: "token" });
  });
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
