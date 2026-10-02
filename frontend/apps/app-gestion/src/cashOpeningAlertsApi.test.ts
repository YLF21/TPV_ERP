import { afterEach, describe, expect, it, vi } from "vitest";
import { loadCashAlertDetail, loadCashAlerts, reviewCashAlert } from "./cashOpeningAlertsApi";

afterEach(() => vi.unstubAllGlobals());

describe("cash alert API", () => {
  it("serializes review filters and authenticates the query", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], nextCursor: null, hasMore: false, pendingCount: 0 }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    await loadCashAlerts({ from: "2026-10-01", to: "2026-10-02", terminalId: "terminal-1", userId: "user-1", status: "PENDING" }, "next", "token");
    const [url, init] = fetch.mock.calls[0];
    expect(String(url)).toContain("/api/v1/cash/alerts?");
    expect(String(url)).toContain("from=2026-10-01");
    expect(String(url)).toContain("to=2026-10-02");
    expect(String(url)).toContain("status=PENDING");
    expect(String(url)).toContain("terminalId=terminal-1");
    expect(String(url)).toContain("userId=user-1");
    expect(String(url)).toContain("cursor=next");
    expect(init.headers.Authorization).toBe("Bearer token");
  });

  it("sends the review comment and expected version", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "alert-1", status: "REVIEWED" }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    await reviewCashAlert("alert-1", "CLOSING", "Count checked", 3, "token");
    const [url, init] = fetch.mock.calls[0];
    expect(String(url)).toContain("/api/v1/cash/alerts/alert-1/review");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ type: "CLOSING", comment: "Count checked", expectedVersion: 3 });
  });

  it("loads one alert detail with its type, auth token and cancellation signal", async () => {
    const response = { alert: { id: "attempt-2", type: "CLOSING" }, attempts: [{ id: "attempt-1", attemptNumber: 1 }, { id: "attempt-2", attemptNumber: 2 }] };
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    const controller = new AbortController();
    expect(await loadCashAlertDetail("attempt/2", "CLOSING", "token", controller.signal)).toEqual(response);
    const [url, init] = fetch.mock.calls[0];
    expect(String(url)).toContain("/api/v1/cash/alerts/attempt%2F2?type=CLOSING");
    expect(init.headers.Authorization).toBe("Bearer token");
    expect(init.signal).toBe(controller.signal);
  });

  it("omits dates for the all-history pending counter and limits it to one row", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], nextCursor: null, hasMore: false, pendingCount: 2 }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    await loadCashAlerts({ from: "", to: "", terminalId: "", userId: "", status: "PENDING" }, null, "token", 1);
    const url = new URL(String(fetch.mock.calls[0]?.[0]), "http://localhost");
    expect(url.searchParams.has("from")).toBe(false);
    expect(url.searchParams.has("to")).toBe(false);
    expect(url.searchParams.get("limit")).toBe("1");
  });
});
