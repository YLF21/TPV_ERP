import { afterEach, describe, expect, it, vi } from "vitest";
import { loadCashClosureFilterOptions, loadCashClosures } from "./cashClosuresApi";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("cash closures API", () => {
  it("loads filter options with the authenticated request", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      businessDate: "2026-07-31",
      timezone: "Atlantic/Canary",
      terminals: [],
      users: []
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);

    await loadCashClosureFilterOptions("token");

    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/api/v1/cash/closures/filter-options"), expect.objectContaining({
      headers: expect.objectContaining({ Authorization: "Bearer token" })
    }));
  });

  it("serializes the date range and optional terminal user and cursor", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [], nextCursor: null, hasMore: false
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);

    await loadCashClosures({
      from: "2026-07-01",
      to: "2026-07-31",
      terminalId: "terminal-1",
      userId: "user-1",
      onlyDiscrepancies: true
    }, "next-page", "token", { column: "retainedFund", direction: "desc" });

    const url = String(fetch.mock.calls[0]?.[0]);
    expect(url).toContain("/api/v1/cash/closures?");
    expect(url).toContain("from=2026-07-01");
    expect(url).toContain("to=2026-07-31");
    expect(url).toContain("terminalId=terminal-1");
    expect(url).toContain("userId=user-1");
    expect(url).toContain("onlyDiscrepancies=true");
    expect(url).toContain("cursor=next-page");
    expect(url).toContain("sortBy=retainedFund");
    expect(url).toContain("sortDirection=desc");
  });
});
