import { useCallback, useEffect, useRef, useState } from "react";

import { apiRequest, ApiError, type ApiRequestOptions } from "../api/client";

export type MemberBalanceReservationStatus = "IDLE" | "RESERVING" | "ACTIVE" | "UNAVAILABLE" | "DUPLICATE";
export type MemberBalanceRetentionStatus = "IDLE" | "PENDING" | "CONFIRMED" | "FAILED";

export type MemberBalanceRetentionSelection = {
  lineId: string;
  quantity: number;
  serialNumbers: string[];
};

export type MemberBalanceRetentionState = {
  retentionStatus: MemberBalanceRetentionStatus;
  retentionRevision: number | string | null;
  retentionFingerprint: string | null;
  retentionAttributedAmount: number;
  retentionHeldKnown: number;
  retentionPendingMissing: number;
  retentionSpentShortfall: number;
  retentionSpendable: number;
  retentionRecoveredKnown: number;
  reservedLoyaltyAmount: number;
  reservedReturnCreditAmount: number;
  reservedLots: MemberBalanceReservedLot[];
  retentionErrorCode: string | null;
};

export type MemberBalanceReservedLot = {
  lotId: string;
  balanceType: "LOYALTY" | "RETURN_CREDIT";
  remainingAmount: number;
  heldAmount: number;
  sourceMovementId: string | null;
  documentId: string | null;
};

type ReservationResponse = {
  id?: string;
  reservationId?: string;
  status?: string;
  reservedLoyaltyAmount?: number | string | null;
  reservedReturnCreditAmount?: number | string | null;
  reservedLots?: Array<{
    lotId?: string;
    id?: string;
    balanceType?: string;
    type?: string;
    remainingAmount?: number | string | null;
    heldAmount?: number | string | null;
    sourceMovementId?: string | null;
    documentId?: string | null;
  }>;
  retentionClaims?: Array<{
    lotId?: string;
    heldAmount?: number | string | null;
  }>;
};

type MemberBalanceRetryResponse = {
  outcome?: string;
  reservationId?: string | null;
  saleId?: string | null;
  blockingReservationId?: string | null;
  blockingSaleId?: string | null;
  message?: string | null;
};

type RetentionResponse = {
  retentionRevision?: number | string | null;
  retentionFingerprint?: string | null;
  fingerprint?: string | null;
  revision?: number | string | null;
  attributedAmount?: number | string | null;
  retentionAttributedAmount?: number | string | null;
  retentionHeldKnown?: number | string | null;
  retentionPendingMissing?: number | string | null;
  retentionSpentShortfall?: number | string | null;
  retentionSpendable?: number | string | null;
  retentionRecoveredKnown?: number | string | null;
  reservedLoyaltyAmount?: number | string | null;
  reservedReturnCreditAmount?: number | string | null;
  // The central gateway uses the shorter names; local reservation views use
  // the retention-prefixed names. Accept both without changing the wire body.
  heldKnown?: number | string | null;
  pendingMissing?: number | string | null;
  spentShortfall?: number | string | null;
  spendable?: number | string | null;
  recoveredKnown?: number | string | null;
  reservedLots?: ReservationResponse["reservedLots"];
  retentionClaims?: ReservationResponse["retentionClaims"];
};

export type ActiveMemberBalanceReservation = {
  reservationId: string;
  saleId: string;
};

type LocalReservationAcquisition = "PENDING" | "ACQUIRED" | "NOT_ACQUIRED" | "UNCERTAIN";

const TERMINAL_RESERVATION_STATUSES = new Set(["RELEASED", "EXPIRED", "CONSUMED"]);

function isTerminalReservationStatus(value: unknown): boolean {
  if (typeof value !== "string") return false;
  const normalized = value.trim().toUpperCase();
  return TERMINAL_RESERVATION_STATUSES.has(normalized)
    || [...TERMINAL_RESERVATION_STATUSES].some((status) => normalized.endsWith(`_${status}`));
}

export type MemberBalanceRetryOutcome =
  | "RECOVERED"
  | "BLOCKED_OTHER_TERMINAL"
  | "BLOCKED_LIVE_SALE"
  | "RECOVERY_PENDING"
  | "UNAVAILABLE";

export type MemberBalanceRetryResolution = {
  outcome: MemberBalanceRetryOutcome;
  reservationId?: string | null;
  saleId?: string | null;
  blockingReservationId?: string | null;
  blockingSaleId?: string | null;
  message?: string | null;
};

export type MemberBalanceReservationState = {
  saleId: string | null;
  reservationId: string | null;
  status: MemberBalanceReservationStatus;
  /** Result code from the explicit central retry-resolution protocol. */
  retryResolutionOutcome?: MemberBalanceRetryOutcome | null;
} & MemberBalanceRetentionState;

export type MemberBalanceReservation = MemberBalanceReservationState & {
  renew: () => Promise<ActiveMemberBalanceReservation | null>;
  retryResolution: () => Promise<MemberBalanceRetryResolution>;
  /** Release the current ACTIVE lease and clear its local owner identity. */
  releaseActiveReservation: () => Promise<boolean>;
  /** Clear the local owner identity after checkout finalization owns the lease. */
  markFinalized: () => void;
  configureRetention: (
    sourceDocumentId: string,
    selections: readonly MemberBalanceRetentionSelection[],
  ) => Promise<MemberBalanceRetentionState | null>;
};

type UseMemberBalanceReservationOptions = {
  token: string;
  customerId: string | null;
  heartbeatPaused?: boolean;
};

const HEARTBEAT_INTERVAL_MS = 30_000;
const PENDING_RETENTION_HEARTBEAT_INTERVAL_MS = 2_000;
/** Shared upper bound for wallet reads and retention mutations. */
export const MEMBER_BALANCE_REQUEST_TIMEOUT_MS = 10_000;
const MEMBER_BALANCE_DUPLICATE_CODES = new Set([
  "MEMBER_BALANCE_RESERVED_ELSEWHERE",
]);
const MEMBER_BALANCE_RETRY_OUTCOMES = new Set<MemberBalanceRetryOutcome>([
  "RECOVERED",
  "BLOCKED_OTHER_TERMINAL",
  "BLOCKED_LIVE_SALE",
  "RECOVERY_PENDING",
  "UNAVAILABLE",
]);
const MEMBER_BALANCE_RESERVATION_STORAGE_KEY = "tpv.member-balance-reservation.v1";
const MEMBER_BALANCE_RESERVATION_STORAGE_VERSION = 1;

function memberBalanceRetryOutcome(value: unknown): MemberBalanceRetryOutcome {
  return typeof value === "string" && MEMBER_BALANCE_RETRY_OUTCOMES.has(value as MemberBalanceRetryOutcome)
    ? value as MemberBalanceRetryOutcome
    : "UNAVAILABLE";
}

class MemberBalanceRequestTimeoutError extends Error {
  constructor() {
    super("member_balance_request_timeout");
  }
}

async function apiRequestWithDeadline<T>(
  path: string,
  options: ApiRequestOptions,
  rejectOnAbort = false,
): Promise<T> {
  const controller = new AbortController();
  const upstreamSignal = options.signal;
  const abortFromUpstream = () => controller.abort();
  if (upstreamSignal?.aborted) controller.abort();
  else upstreamSignal?.addEventListener("abort", abortFromUpstream, { once: true });
  let timedOut = false;
  const timeoutId = globalThis.setTimeout(
    () => {
      timedOut = true;
      controller.abort();
    },
    MEMBER_BALANCE_REQUEST_TIMEOUT_MS,
  );
  try {
    const response = await apiRequest<T>(path, { ...options, signal: controller.signal });
    if (timedOut) throw new MemberBalanceRequestTimeoutError();
    // Treat a late response from an obsolete scope as aborted even when the
    // transport resolves after AbortController was signalled.
    if (rejectOnAbort && controller.signal.aborted) {
      const aborted = new Error("Member balance request aborted");
      aborted.name = "AbortError";
      throw aborted;
    }
    return response;
  } catch (error) {
    if (timedOut) throw new MemberBalanceRequestTimeoutError();
    throw error;
  } finally {
    globalThis.clearTimeout(timeoutId);
    upstreamSignal?.removeEventListener("abort", abortFromUpstream);
  }
}

type StoredMemberBalanceReservation = {
  version: number;
  customerId: string;
  saleId: string;
  reservationId: string | null;
};

function readStoredMemberBalanceReservation(): StoredMemberBalanceReservation | null {
  try {
    const raw = globalThis.sessionStorage?.getItem(MEMBER_BALANCE_RESERVATION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredMemberBalanceReservation>;
    if (parsed.version !== MEMBER_BALANCE_RESERVATION_STORAGE_VERSION
      || typeof parsed.customerId !== "string" || parsed.customerId.length === 0
      || typeof parsed.saleId !== "string" || parsed.saleId.length === 0
      || (parsed.reservationId !== null && typeof parsed.reservationId !== "string")) {
      return null;
    }
    return {
      version: MEMBER_BALANCE_RESERVATION_STORAGE_VERSION,
      customerId: parsed.customerId,
      saleId: parsed.saleId,
      reservationId: parsed.reservationId ?? null,
    };
  } catch {
    return null;
  }
}

function writeStoredMemberBalanceReservation(value: StoredMemberBalanceReservation): void {
  try {
    globalThis.sessionStorage?.setItem(
      MEMBER_BALANCE_RESERVATION_STORAGE_KEY,
      JSON.stringify(value),
    );
  } catch {
    // Browser storage can be unavailable; the in-memory reservation remains authoritative.
  }
}

function clearStoredMemberBalanceReservationIfMatches(
  expected: StoredMemberBalanceReservation,
): void {
  const current = readStoredMemberBalanceReservation();
  if (!current || current.customerId !== expected.customerId
    || current.saleId !== expected.saleId || current.reservationId !== expected.reservationId) {
    return;
  }
  try {
    globalThis.sessionStorage?.removeItem(MEMBER_BALANCE_RESERVATION_STORAGE_KEY);
  } catch {
    // Best effort only; a later mount can still attempt the same owner identity.
  }
}

const EMPTY_RETENTION: MemberBalanceRetentionState = {
  retentionStatus: "IDLE",
  retentionRevision: null,
  retentionFingerprint: null,
  retentionAttributedAmount: 0,
  retentionHeldKnown: 0,
  retentionPendingMissing: 0,
  retentionSpentShortfall: 0,
  retentionSpendable: 0,
  retentionRecoveredKnown: 0,
  reservedLoyaltyAmount: 0,
  reservedReturnCreditAmount: 0,
  reservedLots: [],
  retentionErrorCode: null,
};

function reservedLotsFromResponse(response: ReservationResponse | RetentionResponse): MemberBalanceReservedLot[] {
  if (!Array.isArray(response.reservedLots)) return [];
  const heldByLotId = new Map<string, number>();
  for (const claim of response.retentionClaims ?? []) {
    if (!claim?.lotId) continue;
    heldByLotId.set(
      claim.lotId,
      (heldByLotId.get(claim.lotId) ?? 0) + retentionNumber(claim.heldAmount),
    );
  }
  return response.reservedLots.flatMap((lot) => {
    const lotId = lot.lotId ?? lot.id;
    const type = lot.balanceType ?? lot.type;
    if (!lotId || (type !== "LOYALTY" && type !== "RETURN_CREDIT")) return [];
    return [{
      lotId,
      balanceType: type,
      remainingAmount: retentionNumber(lot.remainingAmount),
      // Central responses from older gateways omit heldAmount from each lot
      // while still returning retentionClaims. Derive only the missing field;
      // an explicit lot value remains authoritative, including explicit 0.
      heldAmount: lot.heldAmount == null
        ? (heldByLotId.get(lotId) ?? 0)
        : retentionNumber(lot.heldAmount),
      sourceMovementId: lot.sourceMovementId ?? null,
      documentId: lot.documentId ?? null,
    }];
  });
}

function reservedAmountsFromResponse(response: ReservationResponse | RetentionResponse): Pick<
  MemberBalanceRetentionState, "reservedLoyaltyAmount" | "reservedReturnCreditAmount" | "reservedLots"
> {
  return {
    reservedLoyaltyAmount: retentionNumber(response.reservedLoyaltyAmount),
    reservedReturnCreditAmount: retentionNumber(response.reservedReturnCreditAmount),
    reservedLots: reservedLotsFromResponse(response),
  };
}

/** Stable semantic identity for a return retention request. */
export function memberBalanceRetentionKey(
  sourceDocumentId: string | null | undefined,
  selections: readonly MemberBalanceRetentionSelection[],
): string {
  if (!sourceDocumentId) return "";
  const canonical = selections
    .map((selection) => ({
      lineId: selection.lineId,
      quantity: Number(selection.quantity),
      serialNumbers: [...selection.serialNumbers].sort(),
    }))
    .sort((left, right) => left.lineId.localeCompare(right.lineId)
      || left.quantity - right.quantity
      || JSON.stringify(left.serialNumbers).localeCompare(JSON.stringify(right.serialNumbers)));
  return JSON.stringify({ sourceDocumentId, selections: canonical });
}

function retentionNumber(value: number | string | null | undefined): number {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;
}

function retentionMetric(
  response: RetentionResponse,
  primary: keyof RetentionResponse,
  alias: keyof RetentionResponse,
): { value: number; valid: boolean } {
  const raw = response[primary] ?? response[alias];
  const parsed = Number(raw);
  return {
    value: Number.isFinite(parsed) && parsed >= 0 ? parsed : 0,
    valid: raw !== undefined && raw !== null && Number.isFinite(parsed) && parsed >= 0,
  };
}

function retentionStateFromResponse(
  response: RetentionResponse,
  minimumRevision = 0,
  requireMetrics = false,
): MemberBalanceRetentionState {
  const hasRetentionMetrics = [
    "retentionRevision", "revision", "retentionFingerprint", "fingerprint",
    "retentionAttributedAmount", "attributedAmount", "retentionHeldKnown", "heldKnown",
    "retentionPendingMissing", "pendingMissing", "retentionSpentShortfall", "spentShortfall",
    "retentionRecoveredKnown", "recoveredKnown",
  ].some((key) => Object.prototype.hasOwnProperty.call(response, key));
  if (!hasRetentionMetrics) {
    return {
      ...EMPTY_RETENTION,
      ...reservedAmountsFromResponse(response),
      ...(requireMetrics
        ? { retentionStatus: "FAILED" as const, retentionErrorCode: "RETENTION_SNAPSHOT_INVALID" }
        : {}),
    };
  }
  const pendingMetric = retentionMetric(response, "retentionPendingMissing", "pendingMissing");
  const spentMetric = retentionMetric(response, "retentionSpentShortfall", "spentShortfall");
  const attributedMetric = retentionMetric(response, "retentionAttributedAmount", "attributedAmount");
  const heldMetric = retentionMetric(response, "retentionHeldKnown", "heldKnown");
  const recoveredMetric = retentionMetric(response, "retentionRecoveredKnown", "recoveredKnown");
  const pendingMissing = pendingMetric.value;
  const spentShortfall = spentMetric.value;
  const attributedAmount = attributedMetric.value;
  const heldKnown = heldMetric.value;
  const recoveredKnown = recoveredMetric.value;
  const rawRevision = response.retentionRevision ?? response.revision;
  const revision = Number(rawRevision);
  const revisionValid = rawRevision !== undefined && rawRevision !== null
    && Number.isInteger(revision) && revision >= 0;
  const fingerprint = response.retentionFingerprint ?? response.fingerprint;
  const fingerprintValid = typeof fingerprint === "string" && fingerprint.trim().length > 0;
  const revisionStale = minimumRevision > 0
    && (!fingerprintValid || !revisionValid || revision < minimumRevision);
  const snapshotMalformed = !pendingMetric.valid || !spentMetric.valid || !attributedMetric.valid
    || !heldMetric.valid || !recoveredMetric.valid || !revisionValid || revisionStale;
  const snapshotIncomplete = snapshotMalformed || attributedAmount !== heldKnown;
  return {
    // A successful HTTP response is not enough to expose F10. Missing lots
    // are still pending central reconciliation and a spent lot is a failed
    // retention; only a complete, usable snapshot is confirmed.
    retentionStatus: spentShortfall > 0 || recoveredKnown > 0
      ? "FAILED" : pendingMissing > 0 ? "PENDING" : snapshotIncomplete ? "FAILED" : "CONFIRMED",
    retentionRevision: rawRevision ?? null,
    retentionFingerprint: fingerprint ?? null,
    retentionAttributedAmount: attributedAmount,
    retentionHeldKnown: heldKnown,
    retentionPendingMissing: pendingMissing,
    retentionSpentShortfall: spentShortfall,
    retentionSpendable: retentionNumber(response.retentionSpendable ?? response.spendable),
    retentionRecoveredKnown: recoveredKnown,
    reservedLoyaltyAmount: retentionNumber(response.reservedLoyaltyAmount),
    reservedReturnCreditAmount: retentionNumber(response.reservedReturnCreditAmount),
    reservedLots: reservedLotsFromResponse(response),
    retentionErrorCode: revisionStale
      ? "RETENTION_SNAPSHOT_STALE"
      : snapshotMalformed ? "RETENTION_SNAPSHOT_INVALID" : null,
  };
}

function isDuplicateMemberBalanceError(error: unknown): boolean {
  const code = error instanceof ApiError ? error.problem?.code : undefined;
  return typeof code === "string" && MEMBER_BALANCE_DUPLICATE_CODES.has(code);
}

async function releaseReservation(
  token: string,
  reservation: ActiveMemberBalanceReservation,
  signal?: AbortSignal,
): Promise<boolean> {
  try {
    const response = await apiRequestWithDeadline<ReservationResponse>(
      `/member-balance-reservations/${reservation.reservationId}/release`, {
      method: "POST",
      token,
      body: { saleId: reservation.saleId },
      signal,
      },
      true,
    );
    // Only an explicit terminal response proves that the lease is gone. A
    // missing status (or a non-terminal status such as PREPARED) must remain
    // recoverable; in particular, a generic 409 is not evidence of release.
    return typeof response?.status === "string"
      && ["RELEASED", "EXPIRED", "CONSUMED"].includes(response.status);
  } catch (error) {
    // A 404 proves that this lease no longer exists. A 409 is deliberately
    // not treated as success: PREPARED and other protected states must keep
    // their owner identity until a terminal response is confirmed.
    if (error instanceof ApiError && error.status === 404) {
      return true;
    }
    // Keep the owner identity for a later recovery. The central lease expires only
    // after its normal timeout; dropping it here could create a competing lease.
    return false;
  }
}

export function useMemberBalanceReservation({
  token,
  customerId,
  heartbeatPaused = false,
}: UseMemberBalanceReservationOptions): MemberBalanceReservation {
  const activeReservation = useRef<ActiveMemberBalanceReservation | null>(null);
  // A typed conflict means this terminal never acquired the lease. Keep that
  // fact separate from an uncertain transport result: the former may clear
  // its local identity without touching another terminal's reservation.
  const localReservationAcquisition = useRef<LocalReservationAcquisition>("PENDING");
  // Keep the owner identity in memory as well as sessionStorage. Storage can
  // be blocked by the host/browser, but an uncertain create still needs the
  // same saleId for an idempotent recovery before it can be released.
  const ownerIdentity = useRef<StoredMemberBalanceReservation | null>(null);
  const reservationGeneration = useRef(0);
  const retentionGeneration = useRef(0);
  const retentionInFlight = useRef(0);
  const desiredRetention = useRef<{
    sourceDocumentId: string;
    selections: readonly MemberBalanceRetentionSelection[];
    minimumRevision: number;
  } | null>(null);
  const retentionRetryable = useRef(false);
  const lastRetentionRevision = useRef(0);
  const retentionRequestAbort = useRef<AbortController | null>(null);
  const retentionPendingTimeout = useRef<ReturnType<typeof globalThis.setTimeout> | null>(null);
  const reservationRequestAbort = useRef<AbortController | null>(null);
  const heartbeatAbort = useRef<AbortController | null>(null);
  const heartbeatInFlight = useRef(false);
  const heartbeatAbortForRetentionTimeout = useRef(false);

  const clearRetentionPendingTimeout = useCallback(() => {
    if (retentionPendingTimeout.current === null) return;
    globalThis.clearTimeout(retentionPendingTimeout.current);
    retentionPendingTimeout.current = null;
  }, []);

  const observeRetentionRevision = (response: RetentionResponse) => {
    const rawRevision = response.retentionRevision ?? response.revision;
    const revision = Number(rawRevision);
    if (rawRevision !== undefined && rawRevision !== null
      && Number.isInteger(revision) && revision >= 0) {
      lastRetentionRevision.current = Math.max(lastRetentionRevision.current, revision);
    }
  };
  const pendingRetention = useRef(Promise.resolve());
  // A release queue alone is not enough: StrictMode (and a fast customer
  // change) can start a second reservation while the first POST is still in
  // flight. Keep the whole release/reserve transaction ordered so an obsolete
  // lease is always released before the next lease is requested.
  const pendingReservation = useRef(Promise.resolve());
  // Explicit renewals are user/lifecycle retries, not independent lease
  // acquisitions. Coalesce overlapping calls so the first call's snapshot of
  // the active owner cannot be cleared by a second call before the queue
  // releases it.
  const reservationRenewInFlight = useRef<Promise<ActiveMemberBalanceReservation | null> | null>(null);
  const retryResolutionInFlight = useRef<Promise<MemberBalanceRetryResolution> | null>(null);
  const [state, setState] = useState<MemberBalanceReservationState>({
    saleId: null,
    reservationId: null,
    status: "IDLE",
    retryResolutionOutcome: null,
    ...EMPTY_RETENTION,
  });

  const beginReservation = useCallback(async (
    restoreExisting: boolean,
  ): Promise<ActiveMemberBalanceReservation | null> => {
    reservationRequestAbort.current?.abort();
    reservationRequestAbort.current = null;
    retentionRequestAbort.current?.abort();
    retentionRequestAbort.current = null;
    heartbeatAbort.current?.abort();
    heartbeatAbort.current = null;
    heartbeatAbortForRetentionTimeout.current = false;
    heartbeatInFlight.current = false;
    clearRetentionPendingTimeout();
    const generation = ++reservationGeneration.current;
    ++retentionGeneration.current;
    desiredRetention.current = null;
    retentionRetryable.current = false;
    lastRetentionRevision.current = 0;
    const previous = activeReservation.current;
    const previousOwner = ownerIdentity.current;
    const requestScope = new AbortController();
    reservationRequestAbort.current = requestScope;
    const stored = readStoredMemberBalanceReservation();
    // An uncertain/duplicate create has no reservation id, but its saleId is
    // still the idempotent owner identity. On an explicit retry, reuse that
    // identity while no active lease is known; only an active lease renewal
    // gets a fresh saleId after its release.
    const rememberedUnacquiredOwner = !previous && customerId
      && previousOwner?.customerId === customerId
      && previousOwner.reservationId === null
      && localReservationAcquisition.current === "NOT_ACQUIRED"
      ? previousOwner : null;
    const storedForCustomer = customerId && stored?.customerId === customerId
      && (restoreExisting || (!previous && stored.reservationId === null))
      ? stored : rememberedUnacquiredOwner;
    localReservationAcquisition.current = customerId
      ? storedForCustomer?.reservationId ? "ACQUIRED" : "PENDING"
      : "NOT_ACQUIRED";
    activeReservation.current = null;
    pendingRetention.current = Promise.resolve();
    let identity: StoredMemberBalanceReservation | null = customerId
      ? {
          version: MEMBER_BALANCE_RESERVATION_STORAGE_VERSION,
          customerId,
          saleId: storedForCustomer?.saleId ?? crypto.randomUUID(),
          reservationId: storedForCustomer?.reservationId ?? null,
        }
      : null;
    ownerIdentity.current = identity;
    setState({
      saleId: identity?.saleId ?? null,
      reservationId: identity?.reservationId ?? null,
      status: customerId ? "RESERVING" : "IDLE",
      retryResolutionOutcome: null,
      ...EMPTY_RETENTION,
    });

    const operation = pendingReservation.current.then(async () => {
      const currentStored = readStoredMemberBalanceReservation();
      const previousIsRestoredIdentity = restoreExisting && identity?.reservationId === previous?.reservationId
        && identity?.saleId === previous?.saleId;
      if (previous && !previousIsRestoredIdentity) {
        if (!await releaseReservation(token, previous, requestScope.signal)) {
          activeReservation.current = previous;
          localReservationAcquisition.current = "ACQUIRED";
          ownerIdentity.current = previousOwner?.saleId === previous.saleId
            ? previousOwner
            : stored?.saleId === previous.saleId
              ? stored
              : null;
          if (generation === reservationGeneration.current) {
            setState({ saleId: previous.saleId, reservationId: previous.reservationId, status: "UNAVAILABLE", ...EMPTY_RETENTION });
          }
          return null;
        }
        if (stored) clearStoredMemberBalanceReservationIfMatches(stored);
      }

      // A stale record from another customer must be released before its slot
      // is replaced. If this fails, retain it for recovery and do not compete.
      if (currentStored && customerId && currentStored.customerId !== customerId
        && currentStored.reservationId && previous?.reservationId !== currentStored.reservationId) {
        if (!await releaseReservation(token, {
          reservationId: currentStored.reservationId,
          saleId: currentStored.saleId,
        }, requestScope.signal)) {
          activeReservation.current = {
            reservationId: currentStored.reservationId,
            saleId: currentStored.saleId,
          };
          localReservationAcquisition.current = "ACQUIRED";
          ownerIdentity.current = currentStored;
          if (generation === reservationGeneration.current) {
            setState({ saleId: currentStored.saleId, reservationId: currentStored.reservationId, status: "UNAVAILABLE", ...EMPTY_RETENTION });
          }
          return null;
        }
        clearStoredMemberBalanceReservationIfMatches(currentStored);
      } else if (currentStored && customerId && currentStored.customerId !== customerId && !currentStored.reservationId) {
        clearStoredMemberBalanceReservationIfMatches(currentStored);
      }

      // If this generation was superseded while an earlier request was in
      // flight, do not create a short-lived lease that nobody can use.
      if (generation !== reservationGeneration.current) return null;

      if (!identity) return null;

      activeReservation.current = identity.reservationId
        ? { reservationId: identity.reservationId, saleId: identity.saleId }
        : null;
      if (identity.reservationId) localReservationAcquisition.current = "ACQUIRED";

      if (identity.reservationId) {
        try {
          const response = await apiRequestWithDeadline<ReservationResponse>(
            `/member-balance-reservations/${encodeURIComponent(identity.reservationId)}/heartbeat`,
            { method: "POST", token, body: { saleId: identity.saleId }, signal: requestScope.signal },
          );
          if (response.status !== "ACTIVE") {
            if (!isTerminalReservationStatus(response.status)) {
              // PREPARED and other protected states still have a local owner;
              // keep the identity and let the explicit resolver handle it.
              if (generation === reservationGeneration.current) {
                setState({
                  saleId: identity.saleId,
                  reservationId: identity.reservationId,
                  status: "UNAVAILABLE",
                  ...EMPTY_RETENTION,
                });
              }
              return null;
            }
            // An explicit terminal response proves that this lease is gone;
            // fall through to replace the stale local owner below.
          } else {
            if (generation !== reservationGeneration.current) return null;
            const restoredState = retentionStateFromResponse(response);
            setState({
              saleId: identity.saleId,
              reservationId: identity.reservationId,
              status: "ACTIVE",
              retryResolutionOutcome: null,
              ...restoredState,
            });
            localReservationAcquisition.current = "ACQUIRED";
            return { reservationId: identity.reservationId, saleId: identity.saleId };
          }
        } catch (error) {
          const terminal = error instanceof ApiError && isTerminalReservationStatus(error.problem?.code);
          if (!(error instanceof ApiError && error.status === 404) && !terminal) {
            const generationWasCurrent = generation === reservationGeneration.current;
            if (error instanceof MemberBalanceRequestTimeoutError && generationWasCurrent) {
              ++reservationGeneration.current;
            }
            // A protected 409 (for example PREPARED) and transient failures
            // retain the known owner; no competing sale id is generated.
            localReservationAcquisition.current = "ACQUIRED";
            ownerIdentity.current = identity;
            writeStoredMemberBalanceReservation(identity);
            if (generationWasCurrent) {
              setState({
                saleId: identity.saleId,
                reservationId: identity.reservationId,
                status: "UNAVAILABLE",
                ...EMPTY_RETENTION,
              });
            }
            return null;
          }
          clearStoredMemberBalanceReservationIfMatches(identity);
          activeReservation.current = null;
          localReservationAcquisition.current = "PENDING";
          identity = {
            version: MEMBER_BALANCE_RESERVATION_STORAGE_VERSION,
            customerId: identity.customerId,
            saleId: crypto.randomUUID(),
            reservationId: null,
          };
          ownerIdentity.current = identity;
          if (generation === reservationGeneration.current) {
            setState({ saleId: identity.saleId, reservationId: null, status: "RESERVING", ...EMPTY_RETENTION });
          }
        }
      }

      writeStoredMemberBalanceReservation(identity);
      if (generation !== reservationGeneration.current) return null;
      try {
        const response = await apiRequestWithDeadline<ReservationResponse>("/member-balance-reservations", {
          method: "POST",
          token,
          body: { customerId: identity.customerId, saleId: identity.saleId },
          signal: requestScope.signal,
        });
        const reservationId = response.reservationId ?? response.id;
        if (!reservationId) throw new Error("member balance reservation response has no identifier");
        const reservation = { reservationId, saleId: identity.saleId };
        if (generation !== reservationGeneration.current) {
          // A stale create may have completed after its scope was aborted;
          // release it with its own bounded request and preserve uncertainty.
          if (!await releaseReservation(token, reservation)) {
            activeReservation.current = reservation;
            localReservationAcquisition.current = "ACQUIRED";
            ownerIdentity.current = { ...identity, reservationId };
          }
          return null;
        }
        activeReservation.current = reservation;
        localReservationAcquisition.current = "ACQUIRED";
        ownerIdentity.current = { ...identity, reservationId };
        writeStoredMemberBalanceReservation({ ...identity, reservationId });
        setState({
          saleId: identity.saleId,
          reservationId,
          status: "ACTIVE",
          retryResolutionOutcome: null,
          ...retentionStateFromResponse(response),
        });
        return reservation;
      } catch (error) {
        const duplicate = isDuplicateMemberBalanceError(error);
        const generationWasCurrent = generation === reservationGeneration.current;
        if (error instanceof MemberBalanceRequestTimeoutError && generationWasCurrent) {
          ++reservationGeneration.current;
        }
        if (generationWasCurrent) {
          activeReservation.current = null;
          ownerIdentity.current = identity;
          localReservationAcquisition.current = duplicate ? "NOT_ACQUIRED" : "UNCERTAIN";
          if (duplicate) clearStoredMemberBalanceReservationIfMatches(identity);
          setState({ saleId: identity.saleId, reservationId: null, status: duplicate ? "DUPLICATE" : "UNAVAILABLE", retryResolutionOutcome: null, ...EMPTY_RETENTION });
        }
        return null;
      }
    });
    pendingReservation.current = operation.then(() => undefined, () => undefined);
    return operation;
  }, [clearRetentionPendingTimeout, customerId, token]);

  const renew = useCallback(() => {
    if (reservationRenewInFlight.current) return reservationRenewInFlight.current;
    const operation = beginReservation(false);
    const inFlight = operation.finally(() => {
      if (reservationRenewInFlight.current === inFlight) {
        reservationRenewInFlight.current = null;
      }
    });
    reservationRenewInFlight.current = inFlight;
    return inFlight;
  }, [beginReservation]);

  const retryResolution = useCallback((): Promise<MemberBalanceRetryResolution> => {
    if (retryResolutionInFlight.current) return retryResolutionInFlight.current;

    const active = activeReservation.current;
    const remembered = ownerIdentity.current;
    const stateIdentity = !active && state.saleId && customerId
      ? {
          version: MEMBER_BALANCE_RESERVATION_STORAGE_VERSION,
          customerId,
          saleId: state.saleId,
          reservationId: state.reservationId,
        }
      : null;
    const identity = remembered && (!customerId || remembered.customerId === customerId)
      ? remembered
      : stateIdentity;
    const unavailable: MemberBalanceRetryResolution = {
      outcome: "UNAVAILABLE",
      saleId: identity?.saleId ?? null,
      reservationId: identity?.reservationId ?? null,
    };
    if (!customerId || !identity) return Promise.resolve(unavailable);

    reservationRequestAbort.current?.abort();
    reservationRequestAbort.current = null;
    heartbeatAbort.current?.abort();
    heartbeatAbort.current = null;
    ++reservationGeneration.current;
    const generation = reservationGeneration.current;
    const operation = pendingReservation.current.then(async () => {
      let rawResponse: MemberBalanceRetryResponse;
      try {
        rawResponse = await apiRequestWithDeadline<MemberBalanceRetryResponse>(
          "/member-balance-reservations/retry", {
            method: "POST",
            token,
            body: { customerId, saleId: identity.saleId },
          },
        );
      } catch (error) {
        const result: MemberBalanceRetryResolution = {
          ...unavailable,
          message: error instanceof Error ? error.message : null,
        };
        ownerIdentity.current = identity;
        // The retry request may have reached the central service before its
        // response was lost. Treat transport failure as uncertain so release
        // recovers the same sale id instead of assuming no lease exists.
        localReservationAcquisition.current = "UNCERTAIN";
        writeStoredMemberBalanceReservation(identity);
        if (generation === reservationGeneration.current) {
          setState((current) => current.saleId === identity.saleId
            ? { ...current, status: "UNAVAILABLE", retryResolutionOutcome: result.outcome }
            : current);
        }
        return result;
      }

      const result: MemberBalanceRetryResolution = {
        outcome: memberBalanceRetryOutcome(rawResponse.outcome),
        reservationId: rawResponse.reservationId ?? null,
        saleId: rawResponse.saleId ?? identity.saleId,
        blockingReservationId: rawResponse.blockingReservationId ?? null,
        blockingSaleId: rawResponse.blockingSaleId ?? null,
        message: rawResponse.message ?? null,
      };
      if (result.outcome !== "RECOVERED") {
        ownerIdentity.current = identity;
        if (identity.reservationId == null
          && (result.outcome === "BLOCKED_LIVE_SALE" || result.outcome === "BLOCKED_OTHER_TERMINAL")) {
          localReservationAcquisition.current = "NOT_ACQUIRED";
          clearStoredMemberBalanceReservationIfMatches(identity);
        } else if (identity.reservationId == null
          && (result.outcome === "RECOVERY_PENDING" || result.outcome === "UNAVAILABLE")) {
          localReservationAcquisition.current = "UNCERTAIN";
          writeStoredMemberBalanceReservation(identity);
        } else {
          writeStoredMemberBalanceReservation(identity);
        }
        if (generation === reservationGeneration.current) {
          setState((current) => current.saleId === identity.saleId
            ? { ...current, retryResolutionOutcome: result.outcome }
            : current);
        }
        return result;
      }

      const recoveredSaleId = result.saleId ?? identity.saleId;
      const recoveredReservationId = result.reservationId;
      if (recoveredSaleId !== identity.saleId || !recoveredReservationId) {
        const blocked: MemberBalanceRetryResolution = {
          ...result,
          outcome: "UNAVAILABLE",
          saleId: identity.saleId,
          reservationId: identity.reservationId,
        };
        ownerIdentity.current = identity;
        localReservationAcquisition.current = "UNCERTAIN";
        writeStoredMemberBalanceReservation(identity);
        if (generation === reservationGeneration.current) {
          setState((current) => current.saleId === identity.saleId
            ? { ...current, status: "UNAVAILABLE", retryResolutionOutcome: blocked.outcome }
            : current);
        }
        return blocked;
      }

      const recovered = { saleId: recoveredSaleId, reservationId: recoveredReservationId };
      const recoveredIdentity = { ...identity, saleId: recoveredSaleId, reservationId: recoveredReservationId };
      activeReservation.current = recovered;
      localReservationAcquisition.current = "ACQUIRED";
      ownerIdentity.current = recoveredIdentity;
      writeStoredMemberBalanceReservation(recoveredIdentity);
      try {
        const heartbeat = await apiRequestWithDeadline<ReservationResponse>(
          `/member-balance-reservations/${encodeURIComponent(recoveredReservationId)}/heartbeat`, {
            method: "POST",
            token,
            body: { saleId: recoveredSaleId },
          },
        );
        if (heartbeat.status !== "ACTIVE") throw new ApiError(
          "member balance reservation is no longer active",
          409,
          { code: "MEMBER_BALANCE_RESERVATION_INVALID" },
        );
        if (generation !== reservationGeneration.current) return result;
        setState({
          saleId: recoveredSaleId,
          reservationId: recoveredReservationId,
          status: "ACTIVE",
          retryResolutionOutcome: null,
          ...retentionStateFromResponse(heartbeat),
        });
        return result;
      } catch (error) {
        const failed: MemberBalanceRetryResolution = {
          ...result,
          outcome: "UNAVAILABLE",
          message: error instanceof Error ? error.message : result.message,
        };
        if (generation === reservationGeneration.current) {
          setState((current) => current.saleId === recoveredSaleId
            ? {
                ...current,
                saleId: recoveredSaleId,
                reservationId: recoveredReservationId,
                status: "UNAVAILABLE",
                retryResolutionOutcome: failed.outcome,
              }
            : current);
        }
        return failed;
      }
    });
    pendingReservation.current = operation.then(() => undefined, () => undefined);
    const inFlight = operation.finally(() => {
      if (retryResolutionInFlight.current === inFlight) retryResolutionInFlight.current = null;
    });
    retryResolutionInFlight.current = inFlight;
    return inFlight;
  }, [customerId, state.reservationId, state.saleId, token]);

  const releaseActiveReservation = useCallback(async (): Promise<boolean> => {
    const active = activeReservation.current;
    const remembered = ownerIdentity.current;
    const stateIdentity = !active && state.saleId && customerId
      ? {
          version: MEMBER_BALANCE_RESERVATION_STORAGE_VERSION,
          customerId,
          saleId: state.saleId,
          reservationId: state.reservationId,
        }
      : null;
    const identity = remembered && (!customerId || remembered.customerId === customerId)
      ? remembered
      : stateIdentity;
    const reservation = active ?? (identity?.reservationId
      ? { reservationId: identity.reservationId, saleId: identity.saleId }
      : null);
    if (identity && !reservation && identity.reservationId == null
      && localReservationAcquisition.current === "NOT_ACQUIRED") {
      // DUPLICATE/BLOCKED_LIVE_SALE means this terminal never owned a lease.
      // Clear only our idempotency identity; never send release for a lease
      // that belongs to another terminal or sale.
      activeReservation.current = null;
      ownerIdentity.current = null;
      localReservationAcquisition.current = "NOT_ACQUIRED";
      clearStoredMemberBalanceReservationIfMatches(identity);
      setState({ saleId: null, reservationId: null, status: "IDLE", retryResolutionOutcome: null, ...EMPTY_RETENTION });
      return true;
    }
    // No local owner means there is nothing to release. An uncertain owner
    // (saleId without reservationId), however, must be recovered first.
    if (!reservation && !identity) return true;
    const targetIdentity = identity ?? {
      version: MEMBER_BALANCE_RESERVATION_STORAGE_VERSION,
      customerId: customerId ?? "",
      saleId: reservation!.saleId,
      reservationId: reservation!.reservationId,
    };
    if (!targetIdentity.customerId && !reservation) return false;

    reservationRequestAbort.current?.abort();
    reservationRequestAbort.current = null;
    retentionRequestAbort.current?.abort();
    retentionRequestAbort.current = null;
    heartbeatAbort.current?.abort();
    heartbeatAbort.current = null;
    clearRetentionPendingTimeout();
    ++reservationGeneration.current;
    ++retentionGeneration.current;
    desiredRetention.current = null;
    retentionRetryable.current = false;
    pendingRetention.current = Promise.resolve();

    const generation = reservationGeneration.current;
    const operation = pendingReservation.current.then(async () => {
      // A newer reservation lifecycle may already have replaced this owner.
      const currentOwner = ownerIdentity.current;
      if (currentOwner && (currentOwner.saleId !== targetIdentity.saleId
        || currentOwner.customerId !== targetIdentity.customerId)) {
        return false;
      }
      let current = activeReservation.current;
      if (current && (current.saleId !== targetIdentity.saleId
        || (targetIdentity.reservationId && current.reservationId !== targetIdentity.reservationId))) {
        return false;
      }

      if (!current) {
        if (targetIdentity.reservationId) {
          current = {
            reservationId: targetIdentity.reservationId,
            saleId: targetIdentity.saleId,
          };
          activeReservation.current = current;
        } else {
          // The create timed out (or returned an unknown result). Re-issue it
          // with the persisted owner saleId before attempting the release.
          if (!targetIdentity.customerId) return false;
          const recoveryScope = new AbortController();
          reservationRequestAbort.current = recoveryScope;
          try {
            const response = await apiRequestWithDeadline<ReservationResponse>(
              "/member-balance-reservations", {
                method: "POST",
                token,
                body: {
                  customerId: targetIdentity.customerId,
                  saleId: targetIdentity.saleId,
                },
                signal: recoveryScope.signal,
              },
            );
            const reservationId = response.reservationId ?? response.id;
            if (!reservationId) throw new Error("member balance reservation response has no identifier");
            current = { reservationId, saleId: targetIdentity.saleId };
            activeReservation.current = current;
            ownerIdentity.current = { ...targetIdentity, reservationId };
            writeStoredMemberBalanceReservation(ownerIdentity.current);
          } catch {
            ownerIdentity.current = targetIdentity;
            localReservationAcquisition.current = "UNCERTAIN";
            writeStoredMemberBalanceReservation(targetIdentity);
            if (generation === reservationGeneration.current) {
              setState({
                saleId: targetIdentity.saleId,
                reservationId: targetIdentity.reservationId,
                status: "UNAVAILABLE",
                ...EMPTY_RETENTION,
              });
            }
            return false;
          } finally {
            if (reservationRequestAbort.current === recoveryScope) {
              reservationRequestAbort.current = null;
            }
          }
        }
      }

      if (!current) return false;
      const released = await releaseReservation(token, current);
      if (!released) {
        ownerIdentity.current = {
          ...targetIdentity,
          reservationId: current.reservationId,
        };
        localReservationAcquisition.current = "ACQUIRED";
        writeStoredMemberBalanceReservation(ownerIdentity.current);
        if (generation === reservationGeneration.current) {
          setState((currentState) => currentState.saleId === current.saleId
            ? { ...currentState, saleId: current.saleId, reservationId: current.reservationId, status: "UNAVAILABLE" }
            : currentState);
        }
        return false;
      }
      activeReservation.current = null;
      ownerIdentity.current = null;
      localReservationAcquisition.current = "NOT_ACQUIRED";
      clearStoredMemberBalanceReservationIfMatches({
        ...targetIdentity,
        reservationId: current.reservationId,
      });
      if (generation === reservationGeneration.current) {
        setState({ saleId: null, reservationId: null, status: "IDLE", retryResolutionOutcome: null, ...EMPTY_RETENTION });
      }
      return true;
    });
    pendingReservation.current = operation.then(() => undefined, () => undefined);
    return operation;
  }, [clearRetentionPendingTimeout, customerId, state.reservationId, state.saleId, token]);

  const markFinalized = useCallback(() => {
    reservationRequestAbort.current?.abort();
    reservationRequestAbort.current = null;
    retentionRequestAbort.current?.abort();
    retentionRequestAbort.current = null;
    heartbeatAbort.current?.abort();
    heartbeatAbort.current = null;
    clearRetentionPendingTimeout();
    ++reservationGeneration.current;
    ++retentionGeneration.current;
    desiredRetention.current = null;
    retentionRetryable.current = false;
    pendingRetention.current = Promise.resolve();
    const reservation = activeReservation.current;
    activeReservation.current = null;
    ownerIdentity.current = null;
    localReservationAcquisition.current = "NOT_ACQUIRED";
    const stored = readStoredMemberBalanceReservation();
    if (stored && (!customerId || stored.customerId === customerId)
      && (!reservation
        || (stored.saleId === reservation.saleId && stored.reservationId === reservation.reservationId))) {
      clearStoredMemberBalanceReservationIfMatches(stored);
    }
    setState({ saleId: null, reservationId: null, status: "IDLE", retryResolutionOutcome: null, ...EMPTY_RETENTION });
  }, [clearRetentionPendingTimeout, customerId]);

  const configureRetention = useCallback(async (
    sourceDocumentId: string,
    selections: readonly MemberBalanceRetentionSelection[],
  ): Promise<MemberBalanceRetentionState | null> => {
    const reservation = activeReservation.current;
    const reservationId = reservation?.reservationId;
    const saleId = reservation?.saleId;
    if (!reservationId || !saleId || !sourceDocumentId) {
      return null;
    }

    const reservationGenerationAtStart = reservationGeneration.current;
    const generation = ++retentionGeneration.current;
    desiredRetention.current = {
      sourceDocumentId,
      selections: selections.map((selection) => ({
        ...selection,
        serialNumbers: [...selection.serialNumbers],
      })),
      minimumRevision: 0,
    };
    retentionRetryable.current = false;
    retentionInFlight.current += 1;
    setState((current) => current.reservationId === reservationId && current.saleId === saleId
      ? { ...current, retentionStatus: "PENDING", retentionErrorCode: null }
      : current);

    const operation = pendingRetention.current.then(async () => {
      try {
        // Do not send an obsolete selection after a newer selection has been
        // requested. Requests that were already in flight are followed by the
        // latest queued mutation, so central state has the same order as UI.
        if (generation !== retentionGeneration.current
          || reservationGenerationAtStart !== reservationGeneration.current
          || activeReservation.current?.reservationId !== reservationId
          || activeReservation.current?.saleId !== saleId) return null;
        const minimumRevision = lastRetentionRevision.current + 1;
        desiredRetention.current = {
          sourceDocumentId,
          selections: desiredRetention.current?.selections ?? selections,
          minimumRevision,
        };
        const requestController = new AbortController();
        retentionRequestAbort.current = requestController;
        try {
          const response = await apiRequestWithDeadline<RetentionResponse>(
            `/member-balance-reservations/${encodeURIComponent(reservationId)}/retention`,
            {
              method: "PUT",
              token,
              body: { saleId, sourceDocumentId, selections },
              signal: requestController.signal,
            },
          );
          if (reservationGenerationAtStart === reservationGeneration.current
            && activeReservation.current?.reservationId === reservationId
            && activeReservation.current?.saleId === saleId) {
            // A stale retention generation in this same reservation still
            // advances the revision floor for queued B/C updates. A response
            // from an older customer reservation must never do so.
            observeRetentionRevision(response);
          }
          const retention = retentionStateFromResponse(response, minimumRevision, true);
          if (generation !== retentionGeneration.current
            || reservationGenerationAtStart !== reservationGeneration.current
            || activeReservation.current?.reservationId !== reservationId
            || activeReservation.current?.saleId !== saleId) return null;
          if (retention.retentionStatus === "PENDING") {
            if (retentionPendingTimeout.current === null) {
              retentionPendingTimeout.current = globalThis.setTimeout(() => {
                retentionPendingTimeout.current = null;
                if (activeReservation.current?.reservationId !== reservationId
                  || activeReservation.current?.saleId !== saleId) return;
                ++retentionGeneration.current;
                retentionRetryable.current = false;
                if (heartbeatAbort.current) {
                  heartbeatAbortForRetentionTimeout.current = true;
                  heartbeatAbort.current.abort();
                }
                setState((current) => current.reservationId === reservationId
                  && current.saleId === saleId
                  && current.retentionStatus === "PENDING"
                  ? {
                      ...current,
                      retentionStatus: "FAILED",
                      retentionErrorCode: "RETENTION_PENDING_TIMEOUT",
                    }
                  : current);
              }, MEMBER_BALANCE_REQUEST_TIMEOUT_MS);
            }
          } else {
            clearRetentionPendingTimeout();
          }
          setState((current) => current.reservationId === reservationId && current.saleId === saleId
            ? { ...current, ...retention }
            : current);
          if (retention.retentionStatus === "CONFIRMED") {
            lastRetentionRevision.current = Number(retention.retentionRevision);
          }
          return retention;
        } catch (error) {
          clearRetentionPendingTimeout();
          const retentionGenerationWasCurrent = generation === retentionGeneration.current;
          const reservationGenerationIsCurrent = reservationGenerationAtStart === reservationGeneration.current
            && activeReservation.current?.reservationId === reservationId
            && activeReservation.current?.saleId === saleId;
          if (error instanceof MemberBalanceRequestTimeoutError && retentionGenerationWasCurrent) {
            ++retentionGeneration.current;
          }
          retentionRetryable.current = !(error instanceof ApiError && error.status >= 400 && error.status < 500);
          if (retentionGenerationWasCurrent && reservationGenerationIsCurrent) {
            const unavailable: MemberBalanceRetentionState = {
              ...EMPTY_RETENTION,
              retentionStatus: "FAILED",
              retentionErrorCode: error instanceof ApiError
                ? (typeof error.problem?.code === "string" ? error.problem.code : `HTTP_${error.status}`)
                : "RETENTION_REQUEST_FAILED",
            };
            setState((current) => current.reservationId === reservationId && current.saleId === saleId
              ? { ...current, ...unavailable }
              : current);
          }
          return null;
        } finally {
          if (retentionRequestAbort.current === requestController) {
            retentionRequestAbort.current = null;
          }
        }
      } finally {
        retentionInFlight.current = Math.max(0, retentionInFlight.current - 1);
      }
    });
    pendingRetention.current = operation.then(() => undefined, () => undefined);
    return operation;
  }, [clearRetentionPendingTimeout, token]);

  useEffect(() => {
    void beginReservation(true);

    return () => {
      reservationGeneration.current += 1;
      retentionGeneration.current += 1;
      reservationRequestAbort.current?.abort();
      reservationRequestAbort.current = null;
      retentionRequestAbort.current?.abort();
      retentionRequestAbort.current = null;
      heartbeatAbort.current?.abort();
      heartbeatAbort.current = null;
      clearRetentionPendingTimeout();
      // Keep the owner identity across a remount/reload. A subsequent
      // customer change or explicit renew performs the authoritative release.
    };
  }, [beginReservation, clearRetentionPendingTimeout, token]);

  useEffect(() => {
    if (!state.reservationId || !state.saleId || state.status !== "ACTIVE" || heartbeatPaused) {
      return;
    }

    const reservationId = state.reservationId;
    const saleId = state.saleId;
    const heartbeat = () => {
      if (heartbeatInFlight.current) return;
      heartbeatInFlight.current = true;
      const requestController = new AbortController();
      heartbeatAbort.current = requestController;
      const reservationGenerationAtStart = reservationGeneration.current;
      const retentionGenerationAtStart = retentionGeneration.current;
      const retentionWasInFlightAtStart = retentionInFlight.current > 0;
      void apiRequestWithDeadline<RetentionResponse & { status?: string }>(`/member-balance-reservations/${reservationId}/heartbeat`, {
        method: "POST",
        token,
        body: { saleId },
        signal: requestController.signal,
      })
        .then((response) => {
          const hasRetentionSnapshot = [
            "retentionRevision", "retentionFingerprint", "retentionAttributedAmount",
            "retentionHeldKnown", "retentionPendingMissing", "retentionSpentShortfall",
            "retentionSpendable", "retentionRecoveredKnown", "reservedLoyaltyAmount",
            "reservedReturnCreditAmount", "heldKnown", "pendingMissing", "spentShortfall",
            "spendable", "recoveredKnown",
          ].some((key) => Object.prototype.hasOwnProperty.call(response, key));
          const expected = desiredRetention.current;
          if (reservationGenerationAtStart === reservationGeneration.current
            && activeReservation.current?.reservationId === reservationId
            && activeReservation.current?.saleId === saleId) {
            observeRetentionRevision(response);
          }
          const retention = hasRetentionSnapshot
            ? retentionStateFromResponse(response, expected?.minimumRevision ?? 0) : null;
          const reservationAmounts = reservedAmountsFromResponse(response);
          const effectiveRetention = retention?.retentionErrorCode === "RETENTION_SNAPSHOT_STALE"
            ? (retentionRetryable.current || state.retentionStatus === "PENDING"
              ? { ...retention, retentionStatus: "PENDING" as const }
              // A permanent PUT failure must remain observable. Do not let a
              // later revision-zero heartbeat overwrite its error state.
              : null)
            : retention;
          const canApplyRetention = retentionGenerationAtStart === retentionGeneration.current
            && !retentionWasInFlightAtStart
            && retentionInFlight.current === 0;
          setState((current) => current.reservationId === reservationId
            && current.saleId === saleId
            && reservationGenerationAtStart === reservationGeneration.current
            ? { ...current, ...reservationAmounts, status: response.status === "ACTIVE" ? "ACTIVE" : "UNAVAILABLE", ...(canApplyRetention ? (effectiveRetention ?? {}) : {}) }
            : current);
          if (canApplyRetention && retention?.retentionStatus === "CONFIRMED") {
            lastRetentionRevision.current = Math.max(
              lastRetentionRevision.current,
              Number(retention.retentionRevision),
            );
            retentionRetryable.current = false;
          }
        })
        .catch((error) => {
          const generationWasCurrent = reservationGenerationAtStart === reservationGeneration.current
            && activeReservation.current?.reservationId === reservationId
            && activeReservation.current?.saleId === saleId;
          if (heartbeatAbortForRetentionTimeout.current) return;
          if (error instanceof MemberBalanceRequestTimeoutError && generationWasCurrent) {
            ++reservationGeneration.current;
          }
          setState((current) => generationWasCurrent && current.reservationId === reservationId
            && current.saleId === saleId
            ? { ...current, status: isDuplicateMemberBalanceError(error)
              ? "DUPLICATE" : "UNAVAILABLE" }
            : current);
        })
        .finally(() => {
          heartbeatInFlight.current = false;
          heartbeatAbortForRetentionTimeout.current = false;
          if (heartbeatAbort.current === requestController) heartbeatAbort.current = null;
        });
    };
    const interval = window.setInterval(
      heartbeat,
      state.retentionStatus === "PENDING"
        ? PENDING_RETENTION_HEARTBEAT_INTERVAL_MS
        : HEARTBEAT_INTERVAL_MS,
    );
    return () => {
      window.clearInterval(interval);
      heartbeatAbort.current?.abort();
    };
  }, [heartbeatPaused, state.reservationId, state.saleId, state.retentionStatus, state.status, token]);

  return {
    ...state,
    renew,
    retryResolution,
    releaseActiveReservation,
    markFinalized,
    configureRetention,
  };
}
