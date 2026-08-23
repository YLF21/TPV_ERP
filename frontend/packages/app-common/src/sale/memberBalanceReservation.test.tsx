// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useMemberBalanceReservation } from "./memberBalanceReservation";

const { apiRequestMock } = vi.hoisted(() => ({ apiRequestMock: vi.fn() }));

vi.mock("../api/client", () => ({ apiRequest: apiRequestMock }));

afterEach(() => {
  cleanup();
  apiRequestMock.mockReset();
});

describe("useMemberBalanceReservation", () => {
  it("releases the previous lease and creates a new sale id when renewed", async () => {
    let reservationCounter = 0;
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/member-balance-reservations") {
        reservationCounter += 1;
        return { reservationId: `reservation-${reservationCounter}` };
      }
      if (path.endsWith("/release")) {
        return undefined;
      }
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
    expect(apiRequestMock).toHaveBeenCalledWith(
      "/member-balance-reservations/reservation-1/release",
      expect.objectContaining({ body: { saleId: firstSaleId } }),
    );
    unmount();
  });
});
