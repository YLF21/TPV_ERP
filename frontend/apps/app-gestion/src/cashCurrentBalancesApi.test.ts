import { afterEach, describe, expect, it, vi } from "vitest";
import { loadCashCurrentBalances } from "./cashCurrentBalancesApi";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("current cash balances API", () => {
  it("loads all terminal balances with the authenticated request", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      asOf: "2026-08-01T10:15:00Z",
      timezone: "Atlantic/Canary",
      terminals: []
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);

    await loadCashCurrentBalances("token");

    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/api/v1/cash/current-balances"), expect.objectContaining({
      headers: expect.objectContaining({ Authorization: "Bearer token" })
    }));
  });
});
