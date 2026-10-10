import type { TerminalContext } from "../types";
import { cashCloseRecoveryKey } from "./cashCloseRecovery";
import { pendingSaleRecoveryKey } from "./pendingSaleRecovery";

export type OfflineWorkRecoveryBridge = {
  load(): Promise<{ ok: boolean; value?: unknown }>;
  save(value: unknown): Promise<{ ok: boolean }>;
  clear(): Promise<{ ok: boolean }>;
};
export type OfflineSaleRecovery<T = Record<string, unknown>> = {
  schemaVersion: 1;
  tombstone?: true;
  ownerUserId: string;
  installationId: string;
  storeId: string;
  terminalId: string;
  bindingId: string;
  ticket: T;
  paymentSessionId: string | null;
  allocationAttempt: string | null;
  cashAttempt: { sessionId?: string; receivedCents: number } | null;
  memberReservation: string | null;
  pendingSale: string | null;
  cashClose: string | null;
};

export function offlineWorkRecoveryBridge(): OfflineWorkRecoveryBridge | undefined {
  return (window.tpvDesktop as (typeof window.tpvDesktop & { workRecovery?: OfflineWorkRecoveryBridge }))?.workRecovery;
}

export function offlineSaleIdentity(ownerUserId: string | undefined, terminal: TerminalContext) {
  const identity = { ownerUserId, installationId: terminal.installationId, storeId: terminal.storeId,
    terminalId: terminal.terminalId, bindingId: terminal.bindingId };
  if (Object.values(identity).some(value => typeof value !== "string" || !value.trim())) {
    throw new Error("OFFLINE_RECOVERY_IDENTITY_REQUIRED");
  }
  return identity as Pick<OfflineSaleRecovery, "ownerUserId" | "installationId" | "storeId" | "terminalId" | "bindingId">;
}

export function validateOfflineSaleRecovery(value: unknown, owner: string | undefined, terminal: TerminalContext): OfflineSaleRecovery {
  if (!value || typeof value !== "object") throw new Error("OFFLINE_RECOVERY_INVALID");
  const snapshot = value as OfflineSaleRecovery;
  const identity = offlineSaleIdentity(owner, terminal);
  if (snapshot.schemaVersion !== 1 || Object.entries(identity).some(([key, id]) => snapshot[key as keyof typeof identity] !== id)) {
    throw new Error("OFFLINE_RECOVERY_IDENTITY_MISMATCH");
  }
  if (!snapshot.ticket || typeof snapshot.ticket !== "object" || !Array.isArray(snapshot.ticket.lines)
    || snapshot.ticket.lines.some(line => !line || typeof line !== "object"
      || !Number.isFinite((line as { quantity?: number }).quantity)
      || !(line as { product?: { id?: string } }).product?.id)) throw new Error("OFFLINE_RECOVERY_INVALID");
  for (const key of ["paymentSessionId", "allocationAttempt", "memberReservation", "pendingSale", "cashClose"] as const) {
    if (snapshot[key] !== null && typeof snapshot[key] !== "string") throw new Error("OFFLINE_RECOVERY_INVALID");
  }
  return snapshot;
}

export function isOfflineSaleRecoveryTombstone(value: unknown, terminal: TerminalContext): boolean {
  if (!value || typeof value !== "object") return false;
  const snapshot = value as OfflineSaleRecovery;
  return snapshot.schemaVersion === 1 && snapshot.tombstone === true
    && snapshot.installationId === terminal.installationId && snapshot.storeId === terminal.storeId
    && snapshot.terminalId === terminal.terminalId && snapshot.bindingId === terminal.bindingId
    && !!snapshot.ticket && Array.isArray(snapshot.ticket.lines) && snapshot.ticket.lines.length === 0
    && [snapshot.paymentSessionId, snapshot.allocationAttempt, snapshot.cashAttempt,
      snapshot.memberReservation, snapshot.pendingSale, snapshot.cashClose].every(value => value === null);
}

export function paymentRecoveryStorageKey(terminal: TerminalContext) {
  return `tpverp.payment-session.${terminal.terminalId ?? terminal.terminalCode}`;
}

/** Explicit allowlist: never export auth/session credentials or operation grants. */
export function readOfflineRecoveryStorage(terminal: TerminalContext) {
  const key = paymentRecoveryStorageKey(terminal);
  return {
    paymentSessionId: sessionStorage.getItem(key),
    allocationAttempt: localStorage.getItem(`${key}.allocation-attempt`),
    memberReservation: sessionStorage.getItem("tpv.member-balance-reservation.v1"),
    pendingSale: localStorage.getItem(pendingSaleRecoveryKey(terminal.terminalCode)),
    cashClose: localStorage.getItem(cashCloseRecoveryKey(terminal.terminalCode)),
  };
}

export function restoreOfflineRecoveryStorage(snapshot: OfflineSaleRecovery, terminal: TerminalContext) {
  const key = paymentRecoveryStorageKey(terminal);
  const write = (storage: Storage, name: string, value: string | null) => {
    if (value === null) storage.removeItem(name); else storage.setItem(name, value);
  };
  write(sessionStorage, key, snapshot.paymentSessionId);
  write(localStorage, `${key}.allocation-attempt`, snapshot.allocationAttempt);
  write(sessionStorage, "tpv.member-balance-reservation.v1", snapshot.memberReservation);
  write(localStorage, pendingSaleRecoveryKey(terminal.terminalCode), snapshot.pendingSale);
  write(localStorage, cashCloseRecoveryKey(terminal.terminalCode), snapshot.cashClose);
}

export function withoutOfflineAuthorizationSecrets<T>(value: T): T {
  return JSON.parse(JSON.stringify(value, (key, item) =>
    /password|credential|token|temporaryPriceAuthorization|operationAuthorizations/i.test(key) ? undefined : item)) as T;
}

export async function saveOfflineSaleRecovery(bridge: OfflineWorkRecoveryBridge, value: OfflineSaleRecovery<unknown>) {
  const sanitized = withoutOfflineAuthorizationSecrets(value);
  for (const key of ["allocationAttempt", "memberReservation", "pendingSale", "cashClose"] as const) {
    if (sanitized[key] !== null) {
      try { sanitized[key] = JSON.stringify(withoutOfflineAuthorizationSecrets(JSON.parse(sanitized[key]!))); }
      catch { throw new Error("OFFLINE_RECOVERY_INVALID"); }
    }
  }
  if (!(await bridge.save(sanitized)).ok) throw new Error("OFFLINE_RECOVERY_SAVE_FAILED");
}

/** A durable empty marker prevents a discarded ticket from reappearing if unlink fails. */
export async function retireOfflineSaleRecovery(bridge: OfflineWorkRecoveryBridge, owner: string | undefined, terminal: TerminalContext) {
  try { if ((await bridge.clear()).ok) return; } catch { /* Try an atomic replacement instead. */ }
  await saveOfflineSaleRecovery(bridge, {
    schemaVersion: 1, tombstone: true, ...offlineSaleIdentity(owner, terminal), ticket: { lines: [] },
    paymentSessionId: null, allocationAttempt: null, cashAttempt: null,
    memberReservation: null, pendingSale: null, cashClose: null,
  });
}
