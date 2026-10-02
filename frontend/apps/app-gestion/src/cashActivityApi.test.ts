import { afterEach, describe, expect, it, vi } from "vitest";
import { loadCashActivity, loadCashActivityFilterOptions } from "./cashActivityApi";

afterEach(() => vi.unstubAllGlobals());

describe("cash activity API", () => {
  it("uses authenticated store-wide metadata and forwards abort", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      businessDate: "2026-07-31", timezone: "Atlantic/Canary", earliestDate: "2026-01-01", terminals: [], users: []
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    const controller = new AbortController();
    await loadCashActivityFilterOptions("token", controller.signal);
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/api/v1/cash/activity/filter-options"), expect.objectContaining({
      signal: controller.signal, headers: expect.objectContaining({ Authorization: "Bearer token" })
    }));
  });

  it("serializes server filters, cursor and time sort without a terminal default", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [], nextCursor: null, hasMore: false
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    await loadCashActivity({
      from: "2026-07-01", to: "2026-07-31", terminalId: "", userId: "user-1",
      action: "CLOSING", cashState: "CERRADA"
    }, "next", "token", { column: "hour", direction: "desc" });
    const url = new URL(String(fetch.mock.calls[0][0]), "http://localhost");
    expect(url.pathname).toContain("/cash/activity");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      from: "2026-07-01", to: "2026-07-31", limit: "50", userId: "user-1",
      action: "CLOSING", cashState: "CERRADA", cursor: "next", sortBy: "time", sortDirection: "desc"
    });
  });
});
