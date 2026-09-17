import { useEffect, useMemo, useSyncExternalStore } from "react";
import type { TerminalContext, UserSession } from "../types";
import { SaleControlDelivery, type SaleControlDeliveryState } from "./saleControlDelivery";
import { saleControlStorage, type SaleControlStorage } from "./saleControlOutboxStorage";

const emptyState: SaleControlDeliveryState = { ready: false, pending: 0, sending: false, error: null };
const emptySubscribe = () => () => undefined;
const emptySnapshot = () => emptyState;

export function useSaleControlDelivery(
  session: UserSession | null,
  terminal: TerminalContext | null | undefined,
  shared?: SaleControlDelivery | null,
) {
  const delivery = useMemo(() => {
    if (shared !== undefined) return shared;
    if (!session?.accessToken || !terminal?.terminalId
      || !session.permissions.some(permission => ["ADMIN", "GESTION_VENTAS", "VENTA"].includes(permission))) return null;
    let storage: SaleControlStorage;
    try { storage = saleControlStorage(); }
    catch {
      const unavailable = async (): Promise<never> => { throw new Error("CONTROL_STORAGE_UNAVAILABLE"); };
      storage = { list: unavailable, put: unavailable, remove: unavailable };
    }
    return new SaleControlDelivery({
      token: session.accessToken, userId: session.userId, terminalId: terminal.terminalId, storage,
    });
  }, [session?.accessToken, session?.userId, session?.permissions, terminal?.terminalId, shared]);
  useEffect(() => {
    if (!delivery || shared !== undefined) return;
    void delivery.start();
    window.addEventListener("online", delivery.online);
    return () => {
      window.removeEventListener("online", delivery.online);
      delivery.stop();
    };
  }, [delivery, shared]);
  const state = useSyncExternalStore(delivery?.subscribe ?? emptySubscribe, delivery?.getSnapshot ?? emptySnapshot);
  return { delivery, state };
}
