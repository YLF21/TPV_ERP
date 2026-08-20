import { useEffect, useRef, useState } from "react";

import { apiRequest } from "../api/client";

export type MemberBalanceReservationStatus = "IDLE" | "RESERVING" | "ACTIVE" | "UNAVAILABLE";

type ReservationResponse = {
  id?: string;
  reservationId?: string;
};

type ActiveReservation = {
  reservationId: string;
  saleId: string;
};

export type MemberBalanceReservationState = {
  saleId: string | null;
  reservationId: string | null;
  status: MemberBalanceReservationStatus;
};

type UseMemberBalanceReservationOptions = {
  token: string;
  customerId: string | null;
  heartbeatPaused?: boolean;
};

const HEARTBEAT_INTERVAL_MS = 30_000;

async function releaseReservation(token: string, reservation: ActiveReservation): Promise<void> {
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
}: UseMemberBalanceReservationOptions): MemberBalanceReservationState {
  const activeReservation = useRef<ActiveReservation | null>(null);
  const [state, setState] = useState<MemberBalanceReservationState>({
    saleId: null,
    reservationId: null,
    status: "IDLE",
  });

  useEffect(() => {
    let cancelled = false;
    const saleId = customerId ? crypto.randomUUID() : null;

    setState({
      saleId,
      reservationId: null,
      status: customerId ? "RESERVING" : "IDLE",
    });

    if (!customerId || !saleId) {
      return () => {
        cancelled = true;
      };
    }

    void apiRequest<ReservationResponse>("/member-balance-reservations", {
      method: "POST",
      token,
      body: { customerId, saleId },
    })
      .then(async (response) => {
        const reservationId = response.reservationId ?? response.id;
        if (!reservationId) {
          throw new Error("member balance reservation response has no identifier");
        }

        const reservation = { reservationId, saleId };
        if (cancelled) {
          await releaseReservation(token, reservation);
          return;
        }

        activeReservation.current = reservation;
        setState({ saleId, reservationId, status: "ACTIVE" });
      })
      .catch(() => {
        if (!cancelled) {
          activeReservation.current = null;
          setState({ saleId, reservationId: null, status: "UNAVAILABLE" });
        }
      });

    return () => {
      cancelled = true;
      const reservation = activeReservation.current;
      if (reservation?.saleId === saleId) {
        activeReservation.current = null;
        void releaseReservation(token, reservation);
      }
    };
  }, [customerId, token]);

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

  return state;
}
