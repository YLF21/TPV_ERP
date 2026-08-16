import { afterEach, describe, expect, it, vi } from "vitest";
import {
  loadVouchers,
  reactivateVoucher,
  recordVoucherPrintResult,
  saveVoucherConfiguration
} from "./vouchersApi";

afterEach(() => vi.unstubAllGlobals());

describe("voucher management API", () => {
  it("serializes server-side management filters and pagination", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [], page: 2, size: 50, totalElements: 0, totalPages: 0
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);

    await loadVouchers({
      query: "V123",
      status: "EXPIRED",
      from: "2026-01-01",
      to: "2026-12-31"
    }, 2, "token");

    const url = String(fetch.mock.calls[0]?.[0]);
    expect(url).toContain("/api/v1/vouchers/management?");
    expect(url).toContain("query=V123");
    expect(url).toContain("status=EXPIRED");
    expect(url).toContain("from=2026-01-01");
    expect(url).toContain("to=2026-12-31");
    expect(url).toContain("page=2");
  });

  it("keeps configuration and reactivation as explicit authenticated mutations", async () => {
    const fetch = vi.fn().mockImplementation(async () => new Response(JSON.stringify({}), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetch);

    await saveVoucherConfiguration("DAYS", 365, "token");
    await reactivateVoucher("V 1", "2028-01-01", "Atención al cliente", "token");

    expect(fetch).toHaveBeenNthCalledWith(1,
      expect.stringContaining("/api/v1/vouchers/configuration"),
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ expirationMode: "DAYS", validityDays: 365 })
      }));
    expect(fetch).toHaveBeenNthCalledWith(2,
      expect.stringContaining("/api/v1/vouchers/V%201/reactivate"),
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          expiresOn: "2028-01-01",
          reason: "Atención al cliente"
        })
      }));
  });

  it("returns the updated detail from the audited print mutation", async () => {
    const detail = {
      voucher: {
        code: "V-1",
        familyIdentifier: "001-000001",
        initialAmount: 10,
        balance: 10,
        status: "ACTIVE",
        createdAt: "2026-08-16T10:00:00Z",
        expiresOn: null,
        originTickets: ["T-1"]
      },
      events: [{
        type: "REPRINTED",
        userId: "00000000-0000-0000-0000-000000000001",
        operatorUsername: "ADMIN",
        terminalId: null,
        occurredAt: "2026-08-16T12:00:00Z",
        reason: null
      }]
    };
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(detail), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetch);

    await expect(recordVoucherPrintResult("V-1", true, "token"))
      .resolves.toEqual(detail);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/vouchers/V-1/print-events"),
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ success: true })
      })
    );
  });
});
