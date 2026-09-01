import { ApiConnectionError, ApiError, apiRequest } from "@tpverp/app-common";
import {
  clearPdaDurableStorage, queuePdaDurableRemove, queuePdaDurableWrite, readPdaDurableValue
} from "./PdaDurableStorage";

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
export const PDA_WORK_DRAFT_KEY = "tpverp.pda.work.draft.v1";
export const PDA_LAST_SYNC_KEY = "tpverp.pda.lastSyncAt.v1";
const DURABLE_QUEUE_KEY = "work-queue-v2";
const DURABLE_LAST_WORK_KEY = "last-work-v1";
const DURABLE_WORK_DRAFT_KEY = "work-draft-v1";

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
  void queuePdaDurableWrite(DURABLE_QUEUE_KEY, normalized);
  window.dispatchEvent(new CustomEvent("pda-work-queue-change", { detail: normalized }));
}

export async function hydratePdaWorkQueue() {
  const durable = await readPdaDurableValue<Array<Partial<WorkQueueEntry> & { id: string; createdAt: string }>>(DURABLE_QUEUE_KEY);
  if (Array.isArray(durable)) {
    const normalized = durable.filter((item) => item?.id && item?.createdAt).map(normalize);
    localStorage.setItem(QUEUE_KEY, JSON.stringify(normalized));
    localStorage.removeItem(LEGACY_QUEUE_KEY);
    window.dispatchEvent(new CustomEvent("pda-work-queue-change", { detail: normalized }));
    return normalized;
  }
  const legacy = readPdaWorkQueue();
  await queuePdaDurableWrite(DURABLE_QUEUE_KEY, legacy);
  return legacy;
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
  const remembered = { ...work, rememberedAt: new Date().toISOString() };
  localStorage.setItem(PDA_LAST_WORK_KEY, JSON.stringify(remembered));
  void queuePdaDurableWrite(DURABLE_LAST_WORK_KEY, remembered);
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

export async function hydrateLastWork() {
  const durable = await readPdaDurableValue<ReturnType<typeof readLastWork>>(DURABLE_LAST_WORK_KEY);
  if (durable?.id) {
    localStorage.setItem(PDA_LAST_WORK_KEY, JSON.stringify(durable));
    window.dispatchEvent(new Event("pda-last-work-change"));
    return durable;
  }
  const legacy = readLastWork();
  if (legacy) await queuePdaDurableWrite(DURABLE_LAST_WORK_KEY, legacy);
  return legacy;
}

export function readPdaWorkDraft<T>(): T | null {
  try {
    return JSON.parse(localStorage.getItem(PDA_WORK_DRAFT_KEY) ?? "null") as T | null;
  } catch {
    return null;
  }
}

export function writePdaWorkDraft<T>(draft: T) {
  localStorage.setItem(PDA_WORK_DRAFT_KEY, JSON.stringify(draft));
  void queuePdaDurableWrite(DURABLE_WORK_DRAFT_KEY, draft);
}

export async function hydratePdaWorkDraft<T>() {
  const durable = await readPdaDurableValue<T>(DURABLE_WORK_DRAFT_KEY);
  if (durable !== undefined) {
    localStorage.setItem(PDA_WORK_DRAFT_KEY, JSON.stringify(durable));
    return durable;
  }
  const legacy = readPdaWorkDraft<T>();
  if (legacy) await queuePdaDurableWrite(DURABLE_WORK_DRAFT_KEY, legacy);
  return legacy;
}

export function clearPdaWorkDraft() {
  localStorage.removeItem(PDA_WORK_DRAFT_KEY);
  void queuePdaDurableRemove(DURABLE_WORK_DRAFT_KEY);
}

export function clearPdaDeviceData() {
  for (const key of [QUEUE_KEY, LEGACY_QUEUE_KEY, PDA_LAST_WORK_KEY, PDA_WORK_DRAFT_KEY, PDA_LAST_SYNC_KEY]) {
    localStorage.removeItem(key);
  }
  void clearPdaDurableStorage();
  window.dispatchEvent(new CustomEvent("pda-work-queue-change", { detail: [] }));
  window.dispatchEvent(new Event("pda-last-work-change"));
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
