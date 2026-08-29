import { ApiConnectionError, ApiError, apiRequest } from "@tpverp/app-common";

export type WorkAction = "create" | "finish" | "cancel";
export type WorkQueueState = "pending" | "retrying" | "conflict";
export type WorkQueueEntry<T = unknown> = {
  id: string;
  createdAt: string;
  action: WorkAction;
  workId?: string;
  payload?: T;
  attempts: number;
  nextRetryAt?: string | null;
  state: WorkQueueState;
  lastError?: string | null;
  version?: number | null;
};

const QUEUE_KEY = "tpverp.pda.work.queue.v2";
const LEGACY_QUEUE_KEY = "tpverp.pda.work.queue.v1";
export const PDA_LAST_WORK_KEY = "tpverp.pda.lastWork.v1";

function normalize(value: Partial<WorkQueueEntry> & { id: string; createdAt: string }): WorkQueueEntry {
  return {
    id: value.id,
    createdAt: value.createdAt,
    action: value.action ?? "create",
    workId: value.workId,
    payload: value.payload,
    attempts: Number(value.attempts ?? 0),
    nextRetryAt: value.nextRetryAt ?? null,
    state: value.state ?? "pending",
    lastError: value.lastError ?? null,
    version: value.version ?? null
  };
}

export function readPdaWorkQueue(): WorkQueueEntry[] {
  try {
    const stored = localStorage.getItem(QUEUE_KEY) ?? localStorage.getItem(LEGACY_QUEUE_KEY) ?? "[]";
    const values = JSON.parse(stored) as Array<Partial<WorkQueueEntry> & { id: string; createdAt: string }>;
    return Array.isArray(values) ? values.filter((item) => item?.id && item?.createdAt).map(normalize) : [];
  } catch {
    return [];
  }
}

export function writePdaWorkQueue(values: Array<Partial<WorkQueueEntry> & { id: string; createdAt: string }>) {
  const normalized = values.map(normalize);
  localStorage.setItem(QUEUE_KEY, JSON.stringify(normalized));
  localStorage.removeItem(LEGACY_QUEUE_KEY);
  window.dispatchEvent(new CustomEvent("pda-work-queue-change", { detail: normalized }));
}

export function queueRetryDelay(attempts: number) {
  return Math.min(60_000, 2_000 * (2 ** Math.max(0, attempts - 1)));
}

export function queuedActionPath(entry: WorkQueueEntry) {
  return entry.action === "create"
    ? "/pda-work"
    : `/pda-work/${encodeURIComponent(entry.workId ?? "")}/${entry.action}`;
}

export function shouldRetryQueueError(error: unknown) {
  return error instanceof ApiConnectionError || (error instanceof ApiError && error.status >= 500);
}

export function isQueueConflict(error: unknown) {
  return error instanceof ApiError && (error.status === 409 || error.status === 412);
}

export async function sendQueuedWork<T>(entry: WorkQueueEntry, token: string) {
  return apiRequest<T>(queuedActionPath(entry), {
    method: entry.action === "create" ? undefined : "POST",
    token,
    ...(entry.action === "create" ? { body: entry.payload } : entry.version == null ? {} : { body: { version: entry.version } })
  });
}

export function nextQueueWakeup(queue: WorkQueueEntry[], now = Date.now()) {
  const retryTimes = queue
    .filter((entry) => entry.state !== "conflict")
    .map((entry) => entry.nextRetryAt ? new Date(entry.nextRetryAt).getTime() : now)
    .filter(Number.isFinite);
  return retryTimes.length ? Math.max(0, Math.min(...retryTimes) - now) : null;
}

export function rememberLastWork(work: { id: string; title: string; type: string; status: string }) {
  localStorage.setItem(PDA_LAST_WORK_KEY, JSON.stringify({ ...work, rememberedAt: new Date().toISOString() }));
  window.dispatchEvent(new Event("pda-last-work-change"));
}

export function readLastWork(): { id: string; title: string; type: string; status: string; rememberedAt: string } | null {
  try {
    const value = JSON.parse(localStorage.getItem(PDA_LAST_WORK_KEY) ?? "null");
    return value?.id ? value : null;
  } catch {
    return null;
  }
}

export function tokenIdentity(token?: string) {
  if (!token) return new Set<string>();
  try {
    const part = token.split(".")[1];
    const normalized = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
    const payload = JSON.parse(atob(padded));
    return new Set([payload.sub, payload.userId, payload.username, payload.preferred_username]
      .filter(Boolean).map((value) => String(value).toLowerCase()));
  } catch {
    return new Set<string>();
  }
}

export function isAssignedToCurrentUser(item: { assignedTo?: string | null; assignedUserId?: string | null }, token?: string) {
  const assigned = item.assignedTo ?? item.assignedUserId;
  if (!assigned) return true;
  return tokenIdentity(token).has(String(assigned).toLowerCase());
}
