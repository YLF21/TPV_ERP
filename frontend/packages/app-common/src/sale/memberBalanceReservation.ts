import { useCallback, useEffect, useRef, useState } from "react";

import { apiRequest } from "../api/client";

export type MemberBalanceReservationStatus = "IDLE" | "RESERVING" | "ACTIVE" | "UNAVAILABLE";

type ReservationResponse = {
  id?: string;
  reservationId?: string;
};

export type ActiveMemberBalanceReservation = {
  reservationId: string;
  saleId: string;
};

export type MemberBalanceReservationState = {
  saleId: string | null;
  reservationId: string | null;
  status: MemberBalanceReservationStatus;
};

export type MemberBalanceReservation = MemberBalanceReservationState & {
  renew: () => Promise<ActiveMemberBalanceReservation | null>;
};

type UseMemberBalanceReservationOptions = {
  token: string;
  customerId: string | null;
  heartbeatPaused?: boolean;
};

const HEARTBEAT_INTERVAL_MS = 30_000;

async function releaseReservation(
  token: string,
  reservation: ActiveMemberBalanceReservation,
): Promise<void> {
  try {
    await apiRequest(`/member-balance-reservations/${reservation.reservationId}/release`, {
      method: "POST",
      token,
      body: { saleId: reservation.saleId },
    });
  } catch {
    // Best effort only. The central lease expires after two minutes without heartbeats.
  }
}

export function useMemberBalanceReservation({
  token,
  customerId,
  heartbeatPaused = false,
}: UseMemberBalanceReservationOptions): MemberBalanceReservation {
  const activeReservation = useRef<ActiveMemberBalanceReservation | null>(null);
  const reservationGeneration = useRef(0);
  const [state, setState] = useState<MemberBalanceReservationState>({
    saleId: null,
    reservationId: null,
    status: "IDLE",
  });

  const renew = useCallback(async (): Promise<ActiveMemberBalanceReservation | null> => {
    const generation = ++reservationGeneration.current;
    const previous = activeReservation.current;
    activeReservation.current = null;
    if (previous) {
      await releaseReservation(token, previous);
    }
    const saleId = customerId ? crypto.randomUUID() : null;

    setState({
      saleId,
      reservationId: null,
      status: customerId ? "RESERVING" : "IDLE",
    });

    if (!customerId || !saleId) {
      return null;
    }

    try {
      const response = await apiRequest<ReservationResponse>("/member-balance-reservations", {
        method: "POST",
        token,
        body: { customerId, saleId },
      });
      const reservationId = response.reservationId ?? response.id;
      if (!reservationId) {
        throw new Error("member balance reservation response has no identifier");
      }
      const reservation = { reservationId, saleId };
      if (generation !== reservationGeneration.current) {
        await releaseReservation(token, reservation);
        return null;
      }
      activeReservation.current = reservation;
      setState({ saleId, reservationId, status: "ACTIVE" });
      return reservation;
    } catch {
      if (generation === reservationGeneration.current) {
        activeReservation.current = null;
        setState({ saleId, reservationId: null, status: "UNAVAILABLE" });
      }
      return null;
    }
  }, [customerId, token]);

  useEffect(() => {
    void renew();

    return () => {
      reservationGeneration.current += 1;
      const reservation = activeReservation.current;
      if (reservation) {
        activeReservation.current = null;
        void releaseReservation(token, reservation);
      }
    };
  }, [renew, token]);

  useEffect(() => {
    if (!state.reservationId || !state.saleId || heartbeatPaused) {
      return;
    }

    const reservationId = state.reservationId;
    const saleId = state.saleId;
    const heartbeat = () => {
      void apiRequest(`/member-balance-reservations/${reservationId}/heartbeat`, {
        method: "POST",
        token,
        body: { saleId },
      })
        .then(() => {
          setState((current) => current.reservationId === reservationId
            ? { ...current, status: "ACTIVE" }
            : current);
        })
        .catch(() => {
          setState((current) => current.reservationId === reservationId
            ? { ...current, status: "UNAVAILABLE" }
            : current);
        });
    };

    const interval = window.setInterval(heartbeat, HEARTBEAT_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [heartbeatPaused, state.reservationId, state.saleId, token]);

  return { ...state, renew };
}
