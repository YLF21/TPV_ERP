// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  MEMBER_BALANCE_REQUEST_TIMEOUT_MS,
  memberBalanceRetentionKey,
  useMemberBalanceReservation,
} from "./memberBalanceReservation";
import { ApiError } from "../api/client";

const { apiRequestMock } = vi.hoisted(() => ({ apiRequestMock: vi.fn() }));

const confirmedRetention = (extra: Record<string, unknown> = {}) => ({
  retentionRevision: 1,
  retentionFingerprint: "confirmed",
  retentionAttributedAmount: "0.00",
  retentionHeldKnown: "0.00",
  retentionPendingMissing: "0.00",
  retentionSpentShortfall: "0.00",
  retentionRecoveredKnown: "0.00",
  ...extra,
});

vi.mock("../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/client")>()),
  apiRequest: apiRequestMock,
}));

afterEach(() => {
  cleanup();
  globalThis.sessionStorage?.clear();
  apiRequestMock.mockReset();
});

describe("useMemberBalanceReservation", () => {
  it("canonicalizes return retention identity independently of line order", () => {
    const first = memberBalanceRetentionKey("source-1", [
      { lineId: "line-b", quantity: 1, serialNumbers: ["S2", "S1"] },
      { lineId: "line-a", quantity: 2, serialNumbers: [] },
    ]);
    const second = memberBalanceRetentionKey("source-1", [
      { lineId: "line-a", quantity: 2, serialNumbers: [] },
      { lineId: "line-b", quantity: 1, serialNumbers: ["S1", "S2"] },
    ]);
    expect(first).toBe(second);
    expect(memberBalanceRetentionKey(null, [])).toBe("");
  });

  it("derives missing reserved-lot holds from central retention claims", async () => {
    const missingHeldLotId = "lot-held-from-claim";
    const explicitHeldLotId = "lot-explicit-held";
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") {
        return {
          reservationId: "reservation-claims",
          status: "ACTIVE",
          reservedLoyaltyAmount: "2.00",
          reservedReturnCreditAmount: "0.00",
          reservedLots: [
            {
              lotId: missingHeldLotId,
              balanceType: "LOYALTY",
              remainingAmount: "1.00",
            },
            {
              lotId: explicitHeldLotId,
              balanceType: "LOYALTY",
              remainingAmount: "1.00",
              heldAmount: "0.01",
            },
          ],
          retentionClaims: [
            { lotId: missingHeldLotId, heldAmount: "0.04" },
            { lotId: explicitHeldLotId, heldAmount: "0.04" },
          ],
        };
      }
      throw new Error("unexpected request " + path);
    });

    const { result } = renderHook(() => useMemberBalanceReservation({
      token: "token",
      customerId: "customer-1",
      heartbeatPaused: true,
    }));

    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    expect(result.current.reservedLots).toEqual(expect.arrayContaining([
      expect.objectContaining({ lotId: missingHeldLotId, heldAmount: 0.04 }),
      expect.objectContaining({ lotId: explicitHeldLotId, heldAmount: 0.01 }),
    ]));
  });

  it("serializes StrictMode reservation generations instead of reserving twice", async () => {
    const reserve = vi.fn(async (_path: string, _options: unknown) => ({ reservationId: "reservation-strict" }));
    apiRequestMock.mockImplementation((path: string, options?: { body?: { customerId?: string } }) => {
      if (path === "/member-balance-reservations") return reserve(path, options);
      if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
      throw new Error(`unexpected request ${path}`);
    });

    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }), { wrapper: StrictMode });

    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    expect(reserve).toHaveBeenCalledTimes(1);
    unmount();
  });

  it("releases an obsolete in-flight lease before the next reservation POST", async () => {
    let resolveFirst!: (value: { reservationId: string }) => void;
    const firstResponse = new Promise<{ reservationId: string }>((resolve) => { resolveFirst = resolve; });
    const calls: string[] = [];
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") {
        calls.push("reserve");
        if (calls.filter((value) => value === "reserve").length === 1) return firstResponse;
        return { reservationId: "reservation-2" };
      }
      if (path.endsWith("/release")) {
        calls.push("release");
        return undefined;
      }
      throw new Error(`unexpected request ${path}`);
    });

    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(calls).toEqual(["reserve"]));
    const nextRenewal = result.current.renew();
    resolveFirst({ reservationId: "reservation-1" });
    await act(async () => { await nextRenewal; });
    await waitFor(() => expect(result.current.reservationId).toBe("reservation-2"));
    expect(calls).toEqual(["reserve", "release", "reserve"]);
    unmount();
  });

  it("releases the current lease and creates a new sale id when explicitly renewed", async () => {
    let reserveCalls = 0;
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") {
        reserveCalls += 1;
        return { reservationId: `reservation-${reserveCalls}` };
      }
      if (path.endsWith("/release")) return { status: "RELEASED" };
      throw new Error(`unexpected request ${path}`);
    });

    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token",
      customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    const firstSaleId = result.current.saleId;

    await act(async () => {
      await result.current.renew();
    });

    expect(result.current.status).toBe("ACTIVE");
    expect(result.current.saleId).not.toBe(firstSaleId);
    expect(result.current.reservationId).toBe("reservation-2");
    expect(reserveCalls).toBe(2);
    expect(apiRequestMock).toHaveBeenCalledWith(
      "/member-balance-reservations/reservation-1/release",
      expect.objectContaining({ body: { saleId: firstSaleId } }),
    );
    unmount();
  });

  it("coalesces overlapping renewals and never leaves the previous lease orphaned", async () => {
    let reserveCalls = 0;
    let releaseCalls = 0;
    let resolveFirstRelease!: (value: { status: string }) => void;
    let resolveSecondRelease!: (value: { status: string }) => void;
    const firstReleaseResponse = new Promise<{ status: string }>((resolve) => {
      resolveFirstRelease = resolve;
    });
    const secondReleaseResponse = new Promise<{ status: string }>((resolve) => {
      resolveSecondRelease = resolve;
    });
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") {
        reserveCalls += 1;
        return { reservationId: `reservation-${reserveCalls}` };
      }
      if (path.endsWith("/release")) {
        releaseCalls += 1;
        return releaseCalls === 1 ? firstReleaseResponse : secondReleaseResponse;
      }
      throw new Error(`unexpected request ${path}`);
    });

    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.reservationId).toBe("reservation-1"));
    const firstRenewal = result.current.renew();
    await waitFor(() => expect(releaseCalls).toBe(1));
    const secondRenewal = result.current.renew();

    expect(secondRenewal).toBe(firstRenewal);
    resolveFirstRelease({ status: "RELEASED" });
    await act(async () => {
      await Promise.all([firstRenewal, secondRenewal]);
    });

    expect(releaseCalls).toBe(1);
    expect(reserveCalls).toBe(2);
    expect(result.current.reservationId).toBe("reservation-2");
    expect(result.current.status).toBe("ACTIVE");

    const releaseCurrent = result.current.releaseActiveReservation();
    await waitFor(() => expect(releaseCalls).toBe(2));
    resolveSecondRelease({ status: "RELEASED" });
    await act(async () => {
      await expect(releaseCurrent).resolves.toBe(true);
    });

    expect(result.current.status).toBe("IDLE");
    expect(result.current.reservationId).toBeNull();
    expect(result.current.saleId).toBeNull();
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    unmount();
  });

  it("marks a duplicate reservation separately without blocking the sale", async () => {
    apiRequestMock.mockRejectedValue(new ApiError("reserved", 409, {
      code: "MEMBER_BALANCE_RESERVED_ELSEWHERE",
    }));
    const { result } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("DUPLICATE"));
    expect(result.current.reservationId).toBeNull();
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
  });

  it("does not restore a deterministic duplicate identity after remount", async () => {
    const saleIds: string[] = [];
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: { saleId?: string } }) => {
      if (path !== "/member-balance-reservations") throw new Error(`unexpected request ${path}`);
      saleIds.push(options?.body?.saleId ?? "");
      throw new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" });
    });
    const first = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(first.result.current.status).toBe("DUPLICATE"));
    const firstSaleId = first.result.current.saleId;
    first.unmount();

    const second = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(second.result.current.status).toBe("DUPLICATE"));
    expect(saleIds).toHaveLength(2);
    expect(saleIds[1]).not.toBe(firstSaleId);
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    second.unmount();
  });

  it("clears an unacquired duplicate without releasing another sale", async () => {
    const reserve = vi.fn(async () => {
      throw new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" });
    });
    const release = vi.fn();
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return reserve();
      if (path.endsWith("/release")) return release();
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("DUPLICATE"));
    const saleId = result.current.saleId;

    await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(true); });
    expect(reserve).toHaveBeenCalledOnce();
    expect(release).not.toHaveBeenCalled();
    expect(result.current.status).toBe("IDLE");
    expect(result.current.saleId).toBeNull();
    expect(result.current.reservationId).toBeNull();
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    expect(saleId).toBeTruthy();
    unmount();
  });

  it("does not classify an untyped conflict as a duplicate", async () => {
    apiRequestMock.mockRejectedValue(new ApiError("conflict", 409, { code: "STATE_CONFLICT" }));
    const { result } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("UNAVAILABLE"));
  });

  it("does not classify the generic central conflict as a duplicate", async () => {
    apiRequestMock.mockRejectedValue(new ApiError("conflict", 409, {
      code: "MEMBER_BALANCE_CENTRAL_CONFLICT",
    }));
    const { result } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("UNAVAILABLE"));
  });

  it("clears the previous reservation before changing customer and serializes release", async () => {
    let releaseNow!: () => void;
    const releaseGate = new Promise<void>((resolve) => { releaseNow = resolve; });
    const calls: string[] = [];
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: { customerId?: string } }) => {
      if (path === "/member-balance-reservations") {
        calls.push(`reserve:${options?.body?.customerId}`);
        return { reservationId: `reservation-${calls.length}` };
      }
      if (path.endsWith("/release")) {
        calls.push("release:start");
        await releaseGate;
        calls.push("release:end");
        return { status: "RELEASED" };
      }
      throw new Error(`unexpected request ${path}`);
    });
    const { result, rerender } = renderHook(({ customerId }) => useMemberBalanceReservation({
      token: "token", customerId,
    }), { initialProps: { customerId: "customer-1" } });
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    expect(result.current.reservationId).toBe("reservation-1");

    rerender({ customerId: "customer-2" });
    await waitFor(() => expect(result.current.status).toBe("RESERVING"));
    expect(result.current.reservationId).toBeNull();
    expect(calls).toEqual(["reserve:customer-1", "release:start"]);
    releaseNow();
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    expect(calls).toEqual(["reserve:customer-1", "release:start", "release:end", "reserve:customer-2"]);
  });

  it("ignores a stale retention response and keeps only the latest CONFIRMED snapshot", async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    const first = new Promise((resolve) => { resolveFirst = resolve; });
    const second = new Promise((resolve) => { resolveSecond = resolve; });
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
      if (path.endsWith("/retention")) return path.includes("retention") && apiRequestMock.mock.calls.filter(([value]) => String(value).endsWith("/retention")).length === 1 ? first : second;
      if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));

    const firstRequest = result.current.configureRetention("source-1", [{ lineId: "line-1", quantity: 1, serialNumbers: [] }]);
    await waitFor(() => expect(result.current.retentionStatus).toBe("PENDING"));
    const secondRequest = result.current.configureRetention("source-1", []);
    resolveFirst(confirmedRetention({ retentionRevision: 1, retentionFingerprint: "old", retentionSpendable: "1.00", reservedLoyaltyAmount: "2.00" }));
    await firstRequest;
    await waitFor(() => expect(apiRequestMock.mock.calls.filter(([value]) => String(value).endsWith("/retention"))).toHaveLength(2));
    resolveSecond(confirmedRetention({ retentionRevision: 2, retentionFingerprint: "new", retentionSpendable: "4.00", reservedLoyaltyAmount: "5.00" }));
    await secondRequest;
    await waitFor(() => expect(result.current.retentionStatus).toBe("CONFIRMED"));
    expect(result.current.retentionFingerprint).toBe("new");
    expect(result.current.reservedLoyaltyAmount).toBe(5);
    expect(apiRequestMock).toHaveBeenCalledWith(
      "/member-balance-reservations/reservation-1/retention",
      expect.objectContaining({ method: "PUT", body: { saleId: result.current.saleId, sourceDocumentId: "source-1", selections: [] } }),
    );
    unmount();
  });

  it("invalidates retention when customer changes and sends an empty selection to clear a source", async () => {
    let reservationCounter = 0;
    let resolveRetention!: (value: unknown) => void;
    const retention = new Promise((resolve) => { resolveRetention = resolve; });
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: `reservation-${++reservationCounter}` });
      if (path.endsWith("/retention")) return retention;
      if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
      throw new Error(`unexpected request ${path}`);
    });
    const { result, rerender, unmount } = renderHook(({ customerId }) => useMemberBalanceReservation({ token: "token", customerId }), { initialProps: { customerId: "customer-a" } });
    await waitFor(() => expect(result.current.reservationId).toBe("reservation-1"));
    const oldSaleId = result.current.saleId;
    const oldRetention = result.current.configureRetention("source-a", []);
    await waitFor(() => expect(result.current.retentionStatus).toBe("PENDING"));
    rerender({ customerId: "customer-b" });
    await waitFor(() => expect(result.current.status).toBe("RESERVING"));
    expect(result.current.retentionStatus).toBe("IDLE");
    resolveRetention(confirmedRetention({ retentionFingerprint: "old-response", reservedLoyaltyAmount: "9.00" }));
    await oldRetention;
    await waitFor(() => expect(result.current.reservationId).toBe("reservation-2"));
    expect(result.current.saleId).not.toBe(oldSaleId);
    expect(result.current.retentionStatus).toBe("IDLE");
    unmount();
  });

  it("does not carry a late revision from the previous reservation into the next one", async () => {
    let reservationCounter = 0;
    let retentionCalls = 0;
    let resolveOldRetention!: (value: unknown) => void;
    let resolveNewRetention!: (value: unknown) => void;
    const oldRetention = new Promise((resolve) => { resolveOldRetention = resolve; });
    const newRetention = new Promise((resolve) => { resolveNewRetention = resolve; });
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") {
        return Promise.resolve({ reservationId: `reservation-${++reservationCounter}` });
      }
      if (path.endsWith("/retention")) {
        retentionCalls += 1;
        return retentionCalls === 1 ? oldRetention : newRetention;
      }
      if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
      throw new Error(`unexpected request ${path}`);
    });
    const { result, rerender, unmount } = renderHook(
      ({ customerId }) => useMemberBalanceReservation({ token: "token", customerId }),
      { initialProps: { customerId: "customer-old" } },
    );
    await waitFor(() => expect(result.current.reservationId).toBe("reservation-1"));
    const oldRequest = result.current.configureRetention("source-old", []);
    await waitFor(() => expect(retentionCalls).toBe(1));

    rerender({ customerId: "customer-new" });
    await waitFor(() => expect(result.current.reservationId).toBe("reservation-2"));
    const newRequest = result.current.configureRetention("source-new", []);
    await waitFor(() => expect(retentionCalls).toBe(2));

    resolveOldRetention(confirmedRetention({ retentionRevision: 5, retentionFingerprint: "old" }));
    await oldRequest;
    resolveNewRetention(confirmedRetention({ retentionRevision: 1, retentionFingerprint: "new" }));
    await newRequest;

    await waitFor(() => expect(result.current.retentionStatus).toBe("CONFIRMED"));
    expect(result.current.retentionRevision).toBe(1);
    expect(result.current.retentionFingerprint).toBe("new");
    unmount();
  });

  it("marks central retention failures FAILED without changing other reservation status", async () => {
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
      if (path.endsWith("/retention")) return Promise.reject(new Error("central unavailable"));
      if (path.endsWith("/release")) return Promise.resolve(undefined);
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    await act(async () => { await result.current.configureRetention("source-1", []); });
    expect(result.current.status).toBe("ACTIVE");
    expect(result.current.retentionStatus).toBe("FAILED");
    unmount();
  });

  it("times out a hung create request and leaves the reservation unavailable", async () => {
    vi.useFakeTimers();
    try {
      let reserveSignal: AbortSignal | undefined;
      apiRequestMock.mockImplementation((path: string, options?: { signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") {
          reserveSignal = options?.signal;
          return new Promise((_resolve, reject) => {
            options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
          });
        }
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS);
        await Promise.resolve();
      });
      expect(reserveSignal?.aborted).toBe(true);
      expect(result.current.status).toBe("UNAVAILABLE");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("retries an unknown create with the same persisted sale id", async () => {
    vi.useFakeTimers();
    try {
      let reserveCalls = 0;
      const saleIds: string[] = [];
      apiRequestMock.mockImplementation((path: string, options?: { body?: { saleId?: string }; signal?: AbortSignal }) => {
        if (path !== "/member-balance-reservations") throw new Error(`unexpected request ${path}`);
        reserveCalls += 1;
        saleIds.push(options?.body?.saleId ?? "");
        if (reserveCalls === 1) {
          return new Promise((_resolve, reject) => {
            options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
          });
        }
        return Promise.resolve({ reservationId: "reservation-after-unknown" });
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({
        token: "token", customerId: "customer-1",
      }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS);
        await Promise.resolve();
      });
      expect(result.current.status).toBe("UNAVAILABLE");
      await act(async () => { await result.current.renew(); });
      expect(result.current.status).toBe("ACTIVE");
      expect(reserveCalls).toBe(2);
      expect(saleIds[0]).toBeTruthy();
      expect(saleIds[1]).toBe(saleIds[0]);
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("resolves a duplicate through the central retry protocol and restores the heartbeat snapshot", async () => {
    const retryBodies: Array<{ customerId: string; saleId: string }> = [];
    let reserveCalls = 0;
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: { customerId?: string; saleId?: string } }) => {
      if (path === "/member-balance-reservations") {
        reserveCalls += 1;
        throw new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" });
      }
      if (path === "/member-balance-reservations/retry") {
        retryBodies.push(options?.body as { customerId: string; saleId: string });
        return {
          outcome: "RECOVERED",
          reservationId: "reservation-recovered",
          saleId: retryBodies[0].saleId,
        };
      }
      if (path.endsWith("/heartbeat")) {
        return {
          status: "ACTIVE",
          reservedLoyaltyAmount: "4.25",
          reservedReturnCreditAmount: "0.75",
        };
      }
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("DUPLICATE"));
    const saleId = result.current.saleId;

    const recovered = await act(async () => result.current.retryResolution());
    expect(recovered.outcome).toBe("RECOVERED");
    expect(retryBodies).toEqual([{ customerId: "customer-1", saleId }]);
    expect(reserveCalls).toBe(1);
    expect(result.current.status).toBe("ACTIVE");
    expect(result.current.saleId).toBe(saleId);
    expect(result.current.reservationId).toBe("reservation-recovered");
    expect(result.current.reservedLoyaltyAmount).toBe(4.25);
    expect(result.current.reservedReturnCreditAmount).toBe(0.75);
    unmount();
  });

  it.each([
    ["BLOCKED_LIVE_SALE", "live sale"],
    ["BLOCKED_OTHER_TERMINAL", "other terminal"],
    ["RECOVERY_PENDING", "pending recovery"],
  ] as const)("keeps the owner identity for a %s retry outcome (%s)", async (outcome, _label) => {
    const retryBodies: Array<{ customerId: string; saleId: string }> = [];
    let reserveCalls = 0;
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: { customerId?: string; saleId?: string } }) => {
      if (path === "/member-balance-reservations") {
        reserveCalls += 1;
        throw new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" });
      }
      if (path === "/member-balance-reservations/retry") {
        retryBodies.push(options?.body as { customerId: string; saleId: string });
        return { outcome, saleId: retryBodies[0].saleId, blockingSaleId: "blocking-sale" };
      }
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("DUPLICATE"));
    const saleId = result.current.saleId;

    const resolved = await act(async () => result.current.retryResolution());
    expect(resolved.outcome).toBe(outcome);
    expect(retryBodies[0].saleId).toBe(saleId);
    expect(reserveCalls).toBe(1);
    expect(result.current.status).toBe("DUPLICATE");
    expect(result.current.retryResolutionOutcome).toBe(outcome);
    expect(result.current.saleId).toBe(saleId);
    expect(result.current.reservationId).toBeNull();
    unmount();
  });

  it("does not persist a blocked retry identity across remount", async () => {
    let reserveCalls = 0;
    let retryCalls = 0;
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: { saleId?: string } }) => {
      if (path === "/member-balance-reservations") {
        reserveCalls += 1;
        throw new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" });
      }
      if (path === "/member-balance-reservations/retry") {
        retryCalls += 1;
        return { outcome: "BLOCKED_LIVE_SALE", saleId: options?.body?.saleId, blockingSaleId: "live-sale" };
      }
      throw new Error(`unexpected request ${path}`);
    });
    const first = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(first.result.current.status).toBe("DUPLICATE"));
    await act(async () => { await first.result.current.retryResolution(); });
    expect(retryCalls).toBe(1);
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    first.unmount();

    const second = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(second.result.current.status).toBe("DUPLICATE"));
    expect(reserveCalls).toBe(2);
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    second.unmount();
  });

  it("treats a retry timeout as uncertain and releases after same-sale recovery", async () => {
    vi.useFakeTimers();
    try {
      let reserveCalls = 0;
      let retrySignal: AbortSignal | undefined;
      const saleIds: string[] = [];
      const release = vi.fn(async () => ({ status: "RELEASED" }));
      apiRequestMock.mockImplementation((path: string, options?: { body?: { saleId?: string }; signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") {
          reserveCalls += 1;
          saleIds.push(options?.body?.saleId ?? "");
          if (reserveCalls === 1) {
            return Promise.reject(new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" }));
          }
          return Promise.resolve({ reservationId: "recovered-after-retry-timeout" });
        }
        if (path === "/member-balance-reservations/retry") {
          retrySignal = options?.signal;
          return new Promise((_resolve, reject) => {
            options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
          });
        }
        if (path.endsWith("/release")) return release();
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); await Promise.resolve(); });
      expect(result.current.status).toBe("DUPLICATE");
      const saleId = result.current.saleId;

      const retry = result.current.retryResolution();
      await act(async () => { await Promise.resolve(); await Promise.resolve(); });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS);
        await Promise.resolve();
      });
      await retry;
      expect(retrySignal?.aborted).toBe(true);
      expect(result.current.status).toBe("UNAVAILABLE");
      expect(result.current.retryResolutionOutcome).toBe("UNAVAILABLE");
      expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain(saleId!);

      await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(true); });
      expect(saleIds[1]).toBe(saleId);
      expect(release).toHaveBeenCalledOnce();
      expect(result.current.status).toBe("IDLE");
      expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("treats an unavailable retry outcome as uncertain before releasing", async () => {
    let reserveCalls = 0;
    const saleIds: string[] = [];
    const release = vi.fn(async () => ({ status: "RELEASED" }));
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: { saleId?: string } }) => {
      if (path === "/member-balance-reservations") {
        reserveCalls += 1;
        saleIds.push(options?.body?.saleId ?? "");
        if (reserveCalls === 1) {
          throw new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" });
        }
        return { reservationId: "reservation-after-unavailable" };
      }
      if (path === "/member-balance-reservations/retry") return { outcome: "UNAVAILABLE" };
      if (path.endsWith("/release")) return release();
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("DUPLICATE"));
    const saleId = result.current.saleId;
    await act(async () => { await result.current.retryResolution(); });
    expect(result.current.retryResolutionOutcome).toBe("UNAVAILABLE");
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain(saleId!);

    await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(true); });
    expect(saleIds[1]).toBe(saleId);
    expect(release).toHaveBeenCalledOnce();
    expect(result.current.status).toBe("IDLE");
    unmount();
  });

  it("recovers a timed-out create with the same sale id before releasing it", async () => {
    vi.useFakeTimers();
    try {
      let reserveCalls = 0;
      const saleIds: string[] = [];
      const release = vi.fn(async () => ({ status: "RELEASED" }));
      apiRequestMock.mockImplementation((path: string, options?: { body?: { saleId?: string }; signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") {
          reserveCalls += 1;
          saleIds.push(options?.body?.saleId ?? "");
          if (reserveCalls === 1) {
            return new Promise((_resolve, reject) => {
              options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
            });
          }
          return Promise.resolve({ reservationId: "reservation-recovered" });
        }
        if (path.endsWith("/release")) return release();
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({
        token: "token", customerId: "customer-1",
      }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS); });
      expect(result.current.status).toBe("UNAVAILABLE");

      await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(true); });
      expect(reserveCalls).toBe(2);
      expect(saleIds[1]).toBe(saleIds[0]);
      expect(release).toHaveBeenCalledOnce();
      expect(result.current.status).toBe("IDLE");
      expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("blocks release when an uncertain identity cannot be recovered", async () => {
    vi.useFakeTimers();
    try {
      let reserveCalls = 0;
      const release = vi.fn();
      apiRequestMock.mockImplementation((path: string, options?: { signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") {
          reserveCalls += 1;
          if (reserveCalls === 1) {
            return new Promise((_resolve, reject) => {
              options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
            });
          }
          throw new ApiError("central unavailable", 503, { code: "CENTRAL_UNAVAILABLE" });
        }
        if (path.endsWith("/release")) return release();
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({
        token: "token", customerId: "customer-1",
      }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS); });
      const saleId = result.current.saleId;

      await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(false); });
      expect(result.current.status).toBe("UNAVAILABLE");
      expect(result.current.saleId).toBe(saleId);
      expect(result.current.reservationId).toBeNull();
      expect(release).not.toHaveBeenCalled();
      expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain(saleId);
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("recovers an uncertain owner from memory when session storage is unavailable", async () => {
    vi.useFakeTimers();
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage unavailable");
    });
    try {
      let reserveCalls = 0;
      const saleIds: string[] = [];
      const release = vi.fn(async () => ({ status: "RELEASED" }));
      apiRequestMock.mockImplementation((path: string, options?: { body?: { saleId?: string }; signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") {
          reserveCalls += 1;
          saleIds.push(options?.body?.saleId ?? "");
          if (reserveCalls === 1) {
            return new Promise((_resolve, reject) => {
              options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
            });
          }
          return Promise.resolve({ reservationId: "reservation-memory-recovered" });
        }
        if (path.endsWith("/release")) return release();
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({
        token: "token", customerId: "customer-1",
      }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS); });

      await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(true); });
      expect(setItem).toHaveBeenCalled();
      expect(reserveCalls).toBe(2);
      expect(saleIds[1]).toBe(saleIds[0]);
      expect(release).toHaveBeenCalledOnce();
      expect(result.current.status).toBe("IDLE");
      unmount();
    } finally {
      setItem.mockRestore();
      vi.useRealTimers();
    }
  });

  it("keeps the sale id when the timed-out create responds late", async () => {
    vi.useFakeTimers();
    try {
      let reserveCalls = 0;
      let resolveLate!: (value: unknown) => void;
      const saleIds: string[] = [];
      apiRequestMock.mockImplementation((path: string, options?: { body?: { saleId?: string }; signal?: AbortSignal }) => {
        if (path !== "/member-balance-reservations") throw new Error(`unexpected request ${path}`);
        reserveCalls += 1;
        saleIds.push(options?.body?.saleId ?? "");
        if (reserveCalls === 1) {
          return new Promise((resolve) => { resolveLate = resolve; });
        }
        return Promise.resolve({ reservationId: "reservation-after-late-response" });
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({
        token: "token", customerId: "customer-1",
      }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS); });
      const retry = result.current.renew();
      await act(async () => { await Promise.resolve(); });
      expect(reserveCalls).toBe(1);
      resolveLate({ reservationId: "reservation-late" });
      await act(async () => { await retry; });
      expect(reserveCalls).toBe(2);
      expect(saleIds[1]).toBe(saleIds[0]);
      expect(result.current.reservationId).toBe("reservation-after-late-response");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("releases an ACTIVE reservation explicitly and clears its local identity", async () => {
    const release = vi.fn(async () => ({ status: "RELEASED" }));
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") return { reservationId: "reservation-release" };
      if (path.endsWith("/release")) return release();
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(true); });
    expect(release).toHaveBeenCalledOnce();
    expect(result.current.status).toBe("IDLE");
    expect(result.current.reservationId).toBeNull();
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    unmount();
  });

  it("preserves an ACTIVE reservation when release is rejected by a protected 409", async () => {
    const release = vi.fn(async () => {
      throw new ApiError("reservation is already prepared", 409, { code: "MEMBER_BALANCE_RESERVATION_PREPARED" });
    });
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") return { reservationId: "reservation-prepared" };
      if (path.endsWith("/release")) return release();
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));

    await act(async () => { expect(await result.current.releaseActiveReservation()).toBe(false); });
    expect(release).toHaveBeenCalledOnce();
    expect(result.current.status).toBe("UNAVAILABLE");
    expect(result.current.saleId).toBeTruthy();
    expect(result.current.reservationId).toBe("reservation-prepared");
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain("reservation-prepared");
    unmount();
  });

  it("marks finalization ownership locally without releasing the prepared lease", async () => {
    const release = vi.fn(async () => ({ status: "RELEASED" }));
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") return { reservationId: "reservation-committed" };
      if (path.endsWith("/release")) return release();
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    act(() => { result.current.markFinalized(); });
    expect(release).not.toHaveBeenCalled();
    expect(result.current.status).toBe("IDLE");
    expect(result.current.reservationId).toBeNull();
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    unmount();
  });

  it("times out a hung restore heartbeat while preserving its lease identity", async () => {
    vi.useFakeTimers();
    try {
      sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify({
        version: 1,
        customerId: "customer-1",
        saleId: "sale-restore",
        reservationId: "reservation-restore",
      }));
      let restoreSignal: AbortSignal | undefined;
      apiRequestMock.mockImplementation((path: string, options?: { signal?: AbortSignal }) => {
        if (path.endsWith("/heartbeat")) {
          restoreSignal = options?.signal;
          return new Promise((_resolve, reject) => {
            options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
          });
        }
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS);
        await Promise.resolve();
      });
      expect(restoreSignal?.aborted).toBe(true);
      expect(result.current.status).toBe("UNAVAILABLE");
      expect(result.current.reservationId).toBe("reservation-restore");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not overlap heartbeats and ignores a late response after timeout", async () => {
    vi.useFakeTimers();
    try {
      let heartbeatCalls = 0;
      let resolveHeartbeat!: (value: unknown) => void;
      let heartbeatSignal: AbortSignal | undefined;
      apiRequestMock.mockImplementation((path: string, options?: { signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1", status: "ACTIVE" });
        if (path.endsWith("/heartbeat")) {
          heartbeatCalls += 1;
          heartbeatSignal = options?.signal;
          return new Promise((resolve) => { resolveHeartbeat = resolve; });
        }
        if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      expect(heartbeatCalls).toBe(1);
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS); });
      expect(heartbeatSignal?.aborted).toBe(true);
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      expect(heartbeatCalls).toBe(1);
      resolveHeartbeat({ status: "ACTIVE", ...confirmedRetention({ retentionFingerprint: "late" }) });
      await act(async () => { await Promise.resolve(); });
      expect(result.current.status).toBe("UNAVAILABLE");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("keeps an uncertain release identity after timeout and ignores late completion", async () => {
    vi.useFakeTimers();
    try {
      let releaseSignal: AbortSignal | undefined;
      let resolveRelease!: (value: unknown) => void;
      apiRequestMock.mockImplementation((path: string, options?: { signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1", status: "ACTIVE" });
        if (path.endsWith("/release")) {
          releaseSignal = options?.signal;
          return new Promise((resolve) => { resolveRelease = resolve; });
        }
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      const renewal = result.current.renew();
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS); });
      expect(releaseSignal?.aborted).toBe(true);
      resolveRelease({ status: "RELEASED" });
      await act(async () => { await renewal; });
      expect(result.current.status).toBe("UNAVAILABLE");
      expect(result.current.reservationId).toBe("reservation-1");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("aborts a hung retention request at the shared timeout and recovers as FAILED", async () => {
    vi.useFakeTimers();
    try {
      let retentionSignal: AbortSignal | undefined;
      apiRequestMock.mockImplementation((path: string, options?: { signal?: AbortSignal }) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
        if (path.endsWith("/retention")) {
          retentionSignal = options?.signal;
          return new Promise((_resolve, reject) => {
            options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
          });
        }
        if (path.endsWith("/heartbeat")) return Promise.resolve({ status: "ACTIVE" });
        if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      const retentionRequest = result.current.configureRetention("source-1", []);
      await act(async () => { await Promise.resolve(); });
      expect(result.current.retentionStatus).toBe("PENDING");
      await act(async () => {
        await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS);
        await retentionRequest;
      });
      expect(retentionSignal?.aborted).toBe(true);
      expect(result.current.status).toBe("ACTIVE");
      expect(result.current.retentionStatus).toBe("FAILED");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("turns a central pending retention snapshot into FAILED after the shared timeout", async () => {
    vi.useFakeTimers();
    try {
      apiRequestMock.mockImplementation((path: string) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
        if (path.endsWith("/retention")) return Promise.resolve({
          retentionRevision: 1,
          retentionFingerprint: "pending",
          retentionAttributedAmount: "2.00",
          retentionHeldKnown: "0.00",
          retentionPendingMissing: "2.00",
          retentionSpentShortfall: "0.00",
          retentionRecoveredKnown: "0.00",
        });
        if (path.endsWith("/heartbeat")) return Promise.resolve({ status: "ACTIVE" });
        if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await result.current.configureRetention("source-1", []); });
      expect(result.current.retentionStatus).toBe("PENDING");
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS); });
      expect(result.current.status).toBe("ACTIVE");
      expect(result.current.retentionStatus).toBe("FAILED");
      expect(result.current.retentionErrorCode).toBe("RETENTION_PENDING_TIMEOUT");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("keeps a timed-out pending retention FAILED when its heartbeat resolves late", async () => {
    vi.useFakeTimers();
    try {
      let resolveHeartbeat!: (value: unknown) => void;
      const heartbeat = new Promise((resolve) => { resolveHeartbeat = resolve; });
      apiRequestMock.mockImplementation((path: string) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
        if (path.endsWith("/retention")) return Promise.resolve({
          retentionRevision: 1,
          retentionFingerprint: "pending",
          retentionAttributedAmount: "2.00",
          retentionHeldKnown: "0.00",
          retentionPendingMissing: "2.00",
          retentionSpentShortfall: "0.00",
          retentionRecoveredKnown: "0.00",
        });
        if (path.endsWith("/heartbeat")) return heartbeat;
        if (path.endsWith("/release")) return Promise.resolve(undefined);
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await result.current.configureRetention("source-1", []); });
      expect(result.current.retentionStatus).toBe("PENDING");

      // The pending heartbeat starts before the shared 10-second deadline.
      await act(async () => { await vi.advanceTimersByTimeAsync(2_000); });
      await act(async () => { await vi.advanceTimersByTimeAsync(MEMBER_BALANCE_REQUEST_TIMEOUT_MS - 2_000); });
      expect(result.current.retentionStatus).toBe("FAILED");
      expect(result.current.retentionErrorCode).toBe("RETENTION_PENDING_TIMEOUT");

      resolveHeartbeat({
        status: "ACTIVE",
        ...confirmedRetention({ retentionRevision: 2, retentionFingerprint: "late", retentionHeldKnown: "2.00" }),
      });
      await act(async () => { await Promise.resolve(); });
      expect(result.current.retentionStatus).toBe("FAILED");
      expect(result.current.retentionErrorCode).toBe("RETENTION_PENDING_TIMEOUT");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("keeps retention pending when central still misses the returned lot", async () => {
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
      if (path.endsWith("/retention")) return Promise.resolve({
        retentionAttributedAmount: "2.00", retentionHeldKnown: "0.00", retentionPendingMissing: "2.00",
      });
      if (path.endsWith("/release")) return Promise.resolve({ status: "RELEASED" });
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    await act(async () => { await result.current.configureRetention("source-1", []); });
    expect(result.current.retentionStatus).toBe("PENDING");
    unmount();
  });

  it("fails closed when central reports a spent shortfall or incomplete retention", async () => {
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
      if (path.endsWith("/retention")) return Promise.resolve({
        retentionAttributedAmount: "2.00", retentionHeldKnown: "1.00", retentionSpentShortfall: "1.00",
      });
      if (path.endsWith("/release")) return Promise.resolve(undefined);
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    await act(async () => { await result.current.configureRetention("source-1", []); });
    expect(result.current.retentionStatus).toBe("FAILED");
    unmount();
  });

  it("fails closed on a malformed retention snapshot", async () => {
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
      if (path.endsWith("/retention")) return Promise.resolve({
        retentionAttributedAmount: "not-a-number", retentionHeldKnown: "0.00",
        retentionPendingMissing: "0.00", retentionSpentShortfall: "0.00", retentionRecoveredKnown: "0.00",
      });
      if (path.endsWith("/release")) return Promise.resolve(undefined);
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    await act(async () => { await result.current.configureRetention("source-1", []); });
    expect(result.current.retentionStatus).toBe("FAILED");
    unmount();
  });

  it("never confirms a configured return from an empty revision-zero snapshot", async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") return { reservationId: "reservation-1" };
      if (path.endsWith("/retention")) return confirmedRetention({
        retentionRevision: 0, retentionFingerprint: "", retentionAttributedAmount: "0.00",
      });
      if (path.endsWith("/release")) return undefined;
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    await act(async () => { await result.current.configureRetention("source-1", []); });
    expect(result.current.retentionStatus).toBe("FAILED");
    expect(result.current.retentionErrorCode).toBe("RETENTION_SNAPSHOT_STALE");
    unmount();
  });

  it("rejects a heartbeat or PUT snapshot from the previous retention revision", async () => {
    let retentionCalls = 0;
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") return { reservationId: "reservation-1" };
      if (path.endsWith("/retention")) {
        retentionCalls += 1;
        return confirmedRetention({ retentionRevision: 1, retentionFingerprint: "first" });
      }
      if (path.endsWith("/release")) return undefined;
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    await act(async () => { await result.current.configureRetention("source-1", []); });
    expect(result.current.retentionStatus).toBe("CONFIRMED");
    await act(async () => { await result.current.configureRetention("source-2", []); });
    expect(retentionCalls).toBe(2);
    expect(result.current.retentionStatus).toBe("FAILED");
    expect(result.current.retentionErrorCode).toBe("RETENTION_SNAPSHOT_STALE");
    unmount();
  });

  it("does not retry a recoverable retention failure until explicitly requested", async () => {
    vi.useFakeTimers();
    try {
      let retentionCalls = 0;
      apiRequestMock.mockImplementation(async (path: string) => {
        if (path === "/member-balance-reservations") return { reservationId: "reservation-1" };
        if (path.endsWith("/retention")) {
          retentionCalls += 1;
          if (retentionCalls === 1) throw new ApiError("offline", 503, { code: "CENTRAL_UNAVAILABLE" });
          return confirmedRetention({ retentionRevision: 1, retentionFingerprint: "confirmed" });
        }
        if (path.endsWith("/heartbeat")) return { status: "ACTIVE", ...confirmedRetention({ retentionRevision: 0, retentionFingerprint: "" }) };
        if (path.endsWith("/release")) return undefined;
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await result.current.configureRetention("source-1", []); });
      expect(result.current.retentionStatus).toBe("FAILED");
      await act(async () => { await vi.advanceTimersByTimeAsync(5_000); });
      await act(async () => { await Promise.resolve(); });
      expect(result.current.retentionStatus).toBe("FAILED");
      expect(retentionCalls).toBe(1);
      await act(async () => { await result.current.configureRetention("source-1", []); });
      expect(result.current.retentionStatus).toBe("CONFIRMED");
      expect(retentionCalls).toBe(2);
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not retry a permanent retention rejection or confirm its revision-zero heartbeat", async () => {
    vi.useFakeTimers();
    try {
      let retentionCalls = 0;
      let heartbeatCalls = 0;
      apiRequestMock.mockImplementation(async (path: string) => {
        if (path === "/member-balance-reservations") return { reservationId: "reservation-1" };
        if (path.endsWith("/retention")) {
          retentionCalls += 1;
          throw new ApiError("central endpoint missing", 422, { code: "MEMBER_BALANCE_CENTRAL_REJECTED" });
        }
        if (path.endsWith("/heartbeat")) {
          heartbeatCalls += 1;
          return { status: "ACTIVE", ...confirmedRetention({ retentionRevision: 0, retentionFingerprint: "" }) };
        }
        if (path.endsWith("/release")) return undefined;
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await result.current.configureRetention("source-1", []); });
      expect(result.current.retentionStatus).toBe("FAILED");
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      expect(retentionCalls).toBe(1);
      expect(heartbeatCalls).toBe(1);
      expect(result.current.retentionStatus).toBe("FAILED");
      expect(result.current.retentionErrorCode).toBe("MEMBER_BALANCE_CENTRAL_REJECTED");
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("applies retention metrics returned by a heartbeat for the current reservation", async () => {
    vi.useFakeTimers();
    try {
      apiRequestMock.mockImplementation((path: string) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
        if (path.endsWith("/heartbeat")) return Promise.resolve({
          status: "ACTIVE", ...confirmedRetention({ retentionHeldKnown: "2.00", retentionAttributedAmount: "2.00", reservedLoyaltyAmount: "5.00" }),
        });
        if (path.endsWith("/release")) return Promise.resolve(undefined);
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      expect(result.current.status).toBe("ACTIVE");
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      expect(result.current.retentionHeldKnown).toBe(2);
      expect(result.current.reservedLoyaltyAmount).toBe(5);
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not let a heartbeat started before a newer retention overwrite it", async () => {
    vi.useFakeTimers();
    try {
      let resolveHeartbeat!: (value: unknown) => void;
      const heartbeat = new Promise((resolve) => { resolveHeartbeat = resolve; });
      apiRequestMock.mockImplementation((path: string) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
        if (path.endsWith("/heartbeat")) return heartbeat;
        if (path.endsWith("/retention")) return Promise.resolve({
          ...confirmedRetention({ retentionHeldKnown: "2.00", retentionAttributedAmount: "2.00", reservedLoyaltyAmount: "5.00" }), retentionFingerprint: "B",
        });
        if (path.endsWith("/release")) return Promise.resolve(undefined);
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      await act(async () => { await result.current.configureRetention("source-b", []); });
      resolveHeartbeat({ ...confirmedRetention({ retentionHeldKnown: "9.00", retentionAttributedAmount: "9.00", reservedLoyaltyAmount: "20.00" }), retentionFingerprint: "A", status: "ACTIVE" });
      await act(async () => { await Promise.resolve(); });
      expect(result.current.retentionFingerprint).toBe("B");
      expect(result.current.retentionHeldKnown).toBe(2);
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("ignores retention metrics from a heartbeat started while PUT retention is in flight", async () => {
    vi.useFakeTimers();
    try {
      let resolveRetention!: (value: unknown) => void;
      let resolveHeartbeat!: (value: unknown) => void;
      const retention = new Promise((resolve) => { resolveRetention = resolve; });
      const heartbeat = new Promise((resolve) => { resolveHeartbeat = resolve; });
      apiRequestMock.mockImplementation((path: string) => {
        if (path === "/member-balance-reservations") return Promise.resolve({ reservationId: "reservation-1" });
        if (path.endsWith("/heartbeat")) return heartbeat;
        if (path.endsWith("/retention")) return retention;
        if (path.endsWith("/release")) return Promise.resolve(undefined);
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      const retentionRequest = result.current.configureRetention("source-b", []);
      await act(async () => { await Promise.resolve(); });
      expect(result.current.retentionStatus).toBe("PENDING");
      // Keep both requests below the shared 10-second deadline; this test is
      // about ordering, not timeout recovery.
      await act(async () => { await vi.advanceTimersByTimeAsync(9_000); });
      resolveRetention(confirmedRetention({ retentionFingerprint: "B", retentionHeldKnown: "2.00", retentionAttributedAmount: "2.00", reservedLoyaltyAmount: "5.00" }));
      await retentionRequest;
      resolveHeartbeat({ ...confirmedRetention({ retentionHeldKnown: "9.00", retentionAttributedAmount: "9.00", reservedLoyaltyAmount: "20.00" }), retentionFingerprint: "A", status: "ACTIVE" });
      await act(async () => { await Promise.resolve(); });
      expect(result.current.retentionFingerprint).toBe("B");
      expect(result.current.retentionHeldKnown).toBe(2);
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("restores the persisted lease after a remount without a second reservation", async () => {
    const reserve = vi.fn(async () => ({ reservationId: "reservation-1" }));
    const heartbeat = vi.fn(async () => ({ status: "ACTIVE" }));
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return reserve();
      if (path.endsWith("/heartbeat")) return heartbeat();
      throw new Error(`unexpected request ${path}`);
    });

    const first = renderHook(({ customerId }) => useMemberBalanceReservation({ token: "token", customerId }), {
      initialProps: { customerId: "customer-1" as string | null },
    });
    await waitFor(() => expect(first.result.current.status).toBe("ACTIVE"));
    const saleId = first.result.current.saleId;
    first.unmount();

    const second = renderHook(({ customerId }) => useMemberBalanceReservation({ token: "token", customerId }), {
      initialProps: { customerId: "customer-1" as string | null },
    });
    await waitFor(() => expect(second.result.current.status).toBe("ACTIVE"));
    expect(reserve).toHaveBeenCalledTimes(1);
    expect(heartbeat).toHaveBeenCalledTimes(1);
    expect(apiRequestMock).toHaveBeenCalledWith(
      expect.stringContaining("/heartbeat"),
      expect.objectContaining({ body: { saleId } }),
    );
    second.unmount();
  });

  it("clears a missing persisted lease and creates a new owner identity", async () => {
    globalThis.sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify({
      version: 1, customerId: "customer-1", saleId: "old-sale", reservationId: "old-reservation",
    }));
    const reserve = vi.fn(async () => ({ reservationId: "new-reservation" }));
    apiRequestMock.mockImplementation((path: string) => {
      if (path.endsWith("/heartbeat")) throw new ApiError("gone", 404, { code: "MEMBER_BALANCE_RESERVATION_NOT_FOUND" });
      if (path === "/member-balance-reservations") return reserve();
      if (path.endsWith("/release")) return { status: "RELEASED" };
      throw new Error(`unexpected request ${path}`);
    });

    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    expect(result.current.saleId).not.toBe("old-sale");
    expect(reserve).toHaveBeenCalledTimes(1);
    expect(JSON.parse(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")!).reservationId)
      .toBe("new-reservation");
    unmount();
  });

  it("preserves a persisted PREPARED lease on restore without creating a competitor", async () => {
    const stored = {
      version: 1, customerId: "customer-1", saleId: "prepared-sale", reservationId: "prepared-reservation",
    };
    globalThis.sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify(stored));
    const reserve = vi.fn();
    const heartbeat = vi.fn(async () => {
      throw new ApiError("reservation is prepared", 409, { code: "MEMBER_BALANCE_RESERVATION_PREPARED" });
    });
    apiRequestMock.mockImplementation((path: string) => {
      if (path.endsWith("/heartbeat")) return heartbeat();
      if (path === "/member-balance-reservations") return reserve();
      throw new Error(`unexpected request ${path}`);
    });

    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    await waitFor(() => expect(result.current.status).toBe("UNAVAILABLE"));
    expect(result.current.saleId).toBe(stored.saleId);
    expect(result.current.reservationId).toBe(stored.reservationId);
    expect(heartbeat).toHaveBeenCalledOnce();
    expect(reserve).not.toHaveBeenCalled();
    expect(JSON.parse(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")!))
      .toMatchObject(stored);
    unmount();
  });

  it("keeps a persisted identity after a transient restore failure and never creates a competitor", async () => {
    globalThis.sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify({
      version: 1, customerId: "customer-1", saleId: "sale-1", reservationId: "reservation-1",
    }));
    const reserve = vi.fn();
    apiRequestMock.mockImplementation((path: string) => {
      if (path.endsWith("/heartbeat")) throw new ApiError("offline", 503, { code: "CENTRAL_UNAVAILABLE" });
      if (path === "/member-balance-reservations") return reserve();
      if (path.endsWith("/release")) return { status: "RELEASED" };
      throw new Error(`unexpected request ${path}`);
    });

    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
    await waitFor(() => expect(result.current.status).toBe("UNAVAILABLE"));
    expect(result.current.saleId).toBe("sale-1");
    expect(result.current.reservationId).toBe("reservation-1");
    expect(reserve).not.toHaveBeenCalled();
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain("reservation-1");
    unmount();
  });

  it("keeps an initial duplicate until explicit retry and reuses its sale id", async () => {
    vi.useFakeTimers();
    try {
      const reserve = vi.fn()
        .mockRejectedValueOnce(new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" }))
        .mockResolvedValueOnce({ reservationId: "reservation-2" });
      apiRequestMock.mockImplementation((path: string, options?: { body: { saleId: string } }) => {
        if (path === "/member-balance-reservations") return reserve(path, options!);
        if (path.endsWith("/release")) return { status: "RELEASED" };
        throw new Error(`unexpected request ${path}`);
      });
      const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-1" }));
      await act(async () => { await Promise.resolve(); });
      expect(result.current.status).toBe("DUPLICATE");
      expect(reserve).toHaveBeenCalledTimes(1);
      await act(async () => { await vi.advanceTimersByTimeAsync(120_001); });
      expect(reserve).toHaveBeenCalledTimes(1);
      await act(async () => { await result.current.renew(); });
      expect(result.current.status).toBe("ACTIVE");
      expect(reserve).toHaveBeenCalledTimes(2);
      expect(reserve.mock.calls[0][1].body.saleId)
        .toBe(reserve.mock.calls[1][1].body.saleId);
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it("releases a stored reservation from another customer before replacing it", async () => {
    globalThis.sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify({
      version: 1, customerId: "customer-old", saleId: "old-sale", reservationId: "old-reservation",
    }));
    const calls: string[] = [];
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path.endsWith("/release")) { calls.push("release"); return { status: "RELEASED" }; }
      if (path === "/member-balance-reservations") { calls.push("reserve"); return { reservationId: "new-reservation" }; }
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({ token: "token", customerId: "customer-new" }));
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    expect(calls).toEqual(["release", "reserve"]);
    expect(JSON.parse(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")!).customerId)
      .toBe("customer-new");
    unmount();
  });

  it.each([404])(
    "treats a stored reservation release status %s as invalid and replaces it",
    async (status) => {
      globalThis.sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify({
        version: 1, customerId: "customer-old", saleId: "old-sale", reservationId: "old-reservation",
      }));
      const calls: string[] = [];
      apiRequestMock.mockImplementation(async (path: string) => {
        if (path.endsWith("/release")) {
          calls.push("release");
          throw new ApiError("lease is already invalid", status, { code: "MEMBER_BALANCE_RESERVATION_INVALID" });
        }
        if (path === "/member-balance-reservations") {
          calls.push("reserve");
          return { reservationId: "new-reservation" };
        }
        throw new Error(`unexpected request ${path}`);
      });

      const { result, unmount } = renderHook(() => useMemberBalanceReservation({
        token: "token", customerId: "customer-new",
      }));
      await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
      expect(calls).toEqual(["release", "reserve"]);
      expect(JSON.parse(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")!).customerId)
        .toBe("customer-new");
      unmount();
    },
  );

  it("preserves a stored reservation after a transient release failure and does not compete",
    async () => {
      globalThis.sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify({
        version: 1, customerId: "customer-old", saleId: "old-sale", reservationId: "old-reservation",
      }));
      const release = vi.fn(async () => {
        throw new ApiError("central unavailable", 503, { code: "CENTRAL_UNAVAILABLE" });
      });
      const reserve = vi.fn(async () => ({ reservationId: "unexpected" }));
      apiRequestMock.mockImplementation((path: string) => {
        if (path.endsWith("/release")) return release();
        if (path === "/member-balance-reservations") return reserve();
        throw new Error(`unexpected request ${path}`);
      });

      const { result, unmount } = renderHook(() => useMemberBalanceReservation({
        token: "token", customerId: "customer-new",
      }));
      await waitFor(() => expect(result.current.status).toBe("UNAVAILABLE"));
      expect(release).toHaveBeenCalledTimes(1);
      expect(reserve).not.toHaveBeenCalled();
      expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain("old-reservation");
      unmount();
    });

  it("clears storage after confirmed release and preserves it on release failure", async () => {
    const release = vi.fn(async () => ({ status: "RELEASED" }));
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return { reservationId: "reservation-1" };
      if (path.endsWith("/release")) return release();
      throw new Error(`unexpected request ${path}`);
    });
    const first = renderHook(({ customerId }) => useMemberBalanceReservation({ token: "token", customerId }), {
      initialProps: { customerId: "customer-1" as string | null },
    });
    await waitFor(() => expect(first.result.current.status).toBe("ACTIVE"));
    first.rerender({ customerId: null });
    await waitFor(() => expect(release).toHaveBeenCalledTimes(1));
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toBeNull();
    first.unmount();

    apiRequestMock.mockReset();
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return { reservationId: "reservation-2" };
      if (path.endsWith("/release")) throw new ApiError("offline", 503, { code: "CENTRAL_UNAVAILABLE" });
      throw new Error(`unexpected request ${path}`);
    });
    const second = renderHook(({ customerId }) => useMemberBalanceReservation({ token: "token", customerId }), {
      initialProps: { customerId: "customer-1" as string | null },
    });
    await waitFor(() => expect(second.result.current.status).toBe("ACTIVE"));
    second.rerender({ customerId: null });
    await waitFor(() => expect(second.result.current.status).toBe("UNAVAILABLE"));
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain("reservation-2");
    second.unmount();
  });

  it("does not consume a stored customer while customer selection is still null", async () => {
    globalThis.sessionStorage.setItem("tpv.member-balance-reservation.v1", JSON.stringify({
      version: 1, customerId: "customer-1", saleId: "sale-1", reservationId: "reservation-1",
    }));
    const reserve = vi.fn(async () => ({ reservationId: "unexpected" }));
    const heartbeat = vi.fn(async () => ({ status: "ACTIVE" }));
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/member-balance-reservations") return reserve();
      if (path.endsWith("/heartbeat")) return heartbeat();
      if (path.endsWith("/release")) return { status: "RELEASED" };
      throw new Error(`unexpected request ${path}`);
    });
    const { result, rerender, unmount } = renderHook(({ customerId }) =>
      useMemberBalanceReservation({ token: "token", customerId }), { initialProps: { customerId: null as string | null } });
    await act(async () => { await Promise.resolve(); });
    expect(globalThis.sessionStorage.getItem("tpv.member-balance-reservation.v1")).toContain("reservation-1");
    rerender({ customerId: "customer-1" });
    await waitFor(() => expect(result.current.status).toBe("ACTIVE"));
    expect(reserve).not.toHaveBeenCalled();
    unmount();
  });

  it("does not retry an explicit renew after a duplicate response", async () => {
    vi.useFakeTimers();
    let reserveCalls = 0;
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") {
        reserveCalls += 1;
        if (reserveCalls === 1) return { reservationId: "reservation-1" };
        throw new ApiError("reserved", 409, { code: "MEMBER_BALANCE_RESERVED_ELSEWHERE" });
      }
      if (path.endsWith("/release")) return { status: "RELEASED" };
      throw new Error(`unexpected request ${path}`);
    });
    const { result, unmount } = renderHook(() => useMemberBalanceReservation({
      token: "token", customerId: "customer-1",
    }));
    try {
      await act(async () => { await Promise.resolve(); });
      expect(result.current.status).toBe("ACTIVE");
      await act(async () => {
        await result.current.renew();
      });
      expect(result.current.status).toBe("DUPLICATE");
      expect(reserveCalls).toBe(2);
      await act(async () => { await vi.advanceTimersByTimeAsync(120_000); });
      expect(reserveCalls).toBe(2);
    } finally {
      vi.useRealTimers();
      unmount();
    }
  });
});
