import { ApiConnectionError, ApiError, apiRequest } from "../../../packages/app-common/src/api/client";
import {
  clearPdaDurableStorage, queuePdaDurableRemove, queuePdaDurableWrite, readPdaDurableValue,
  reportPdaStorageError
} from "./PdaDurableStorage";

export type WorkAction = "create" | "finish" | "cancel";
export type WorkQueueState = "pending" | "retrying" | "conflict" | "blocked";
export type QueueFailureKind = "offline" | "server" | "validation" | "authentication" | "forbidden" | "not-found" | "conflict" | "unknown";
export type WorkQueueEntry<T = unknown> = {
  id: string; clientRequestId?: string; createdAt: string; action: WorkAction; workId?: string; payload?: T;
  attempts: number; nextRetryAt?: string | null; state: WorkQueueState; failureKind?: QueueFailureKind | null;
  lastError?: string | null; version?: number | null;
};
type QueueInput = Partial<WorkQueueEntry> & { id: string; createdAt: string };
type QueueEnvelope = { revision: number; updatedAt: string; entries: QueueInput[] };

export const PDA_WORK_QUEUE_KEY = "tpverp.pda.work.queue.v3";
const QUEUE_KEY_V2 = "tpverp.pda.work.queue.v2";
const LEGACY_QUEUE_KEY = "tpverp.pda.work.queue.v1";
export const PDA_LAST_WORK_KEY = "tpverp.pda.lastWork.v1";
export const PDA_WORK_DRAFT_KEY = "tpverp.pda.work.draft.v1";
export const PDA_LAST_SYNC_KEY = "tpverp.pda.lastSyncAt.v1";
export const PDA_DURABLE_QUEUE_KEY = "work-queue-v3";
const DURABLE_QUEUE_KEY_V2 = "work-queue-v2";
const DURABLE_LAST_WORK_KEY = "last-work-v1";
const DURABLE_WORK_DRAFT_KEY = "work-draft-v1";
let queueRevision = 0;
let memoryQueueSnapshot: QueueEnvelope | null = null;
export function resetPdaWorkQueueMemoryForTests(){memoryQueueSnapshot=null;queueRevision=0;}

function normalize(value: QueueInput): WorkQueueEntry {
  const action = value.action ?? "create";
  return { id: value.id, ...(action === "create" ? { clientRequestId: value.clientRequestId || value.id } : value.clientRequestId ? { clientRequestId: value.clientRequestId } : {}),
    createdAt: value.createdAt, action, workId: value.workId, payload: value.payload,
    attempts: Math.max(0, Number(value.attempts ?? 0)), nextRetryAt: value.nextRetryAt ?? null,
    state: value.state ?? "pending", failureKind: value.failureKind ?? null, lastError: value.lastError ?? null,
    version: value.version ?? null };
}
function validEntries(value: unknown): QueueInput[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is QueueInput => Boolean(item && typeof item === "object" && "id" in item && "createdAt" in item
    && typeof item.id === "string" && typeof item.createdAt === "string"));
}
function parseEnvelope(raw: unknown): QueueEnvelope | null {
  if (!raw || typeof raw !== "object" || !("entries" in raw)) return null;
  const value = raw as Partial<QueueEnvelope>; if (!Array.isArray(value.entries)) return null;
  return { revision: Math.max(0, Number(value.revision ?? 0)), updatedAt: typeof value.updatedAt === "string" ? value.updatedAt : "", entries: validEntries(value.entries) };
}
function readJson(key: string): unknown { try { return JSON.parse(localStorage.getItem(key) ?? "null"); } catch { return null; } }
function localQueueSnapshot(): QueueEnvelope {
  if (memoryQueueSnapshot) return memoryQueueSnapshot;
  const current = parseEnvelope(readJson(PDA_WORK_QUEUE_KEY)); if (current) return current;
  const legacy = validEntries(readJson(QUEUE_KEY_V2) ?? readJson(LEGACY_QUEUE_KEY));
  const updatedAt = legacy.map((entry) => Date.parse(entry.createdAt)).filter(Number.isFinite).sort((a, b) => b - a)[0];
  return { revision: 0, updatedAt: updatedAt ? new Date(updatedAt).toISOString() : "", entries: legacy };
}
function compareSnapshots(left: QueueEnvelope, right: QueueEnvelope) {
  if (left.revision !== right.revision) return left.revision - right.revision;
  return Date.parse(left.updatedAt || "1970-01-01") - Date.parse(right.updatedAt || "1970-01-01");
}
function mergeSameSnapshot(left: QueueEnvelope, right: QueueEnvelope): QueueEnvelope {
  const entries = new Map<string, QueueInput>();
  for (const entry of [...left.entries, ...right.entries]) { const previous = entries.get(entry.id); if (!previous || Number(entry.attempts ?? 0) >= Number(previous.attempts ?? 0)) entries.set(entry.id, entry); }
  return { revision: left.revision, updatedAt: left.updatedAt || right.updatedAt, entries: [...entries.values()] };
}
function selectSnapshot(local: QueueEnvelope, durable: QueueEnvelope): QueueEnvelope {
  const order = compareSnapshots(local, durable); return order > 0 ? local : order < 0 ? durable : mergeSameSnapshot(local, durable);
}
function storeLocalEnvelope(envelope: QueueEnvelope) {
  memoryQueueSnapshot = envelope;
  try { localStorage.setItem(PDA_WORK_QUEUE_KEY, JSON.stringify(envelope)); localStorage.removeItem(QUEUE_KEY_V2); localStorage.removeItem(LEGACY_QUEUE_KEY); return true; }
  catch (cause) { reportPdaStorageError("local-write", cause); return false; }
}
function announceQueue(entries: WorkQueueEntry[]) { if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent("pda-work-queue-change", { detail: entries })); }

export function readPdaWorkQueue(): WorkQueueEntry[] { const snapshot = localQueueSnapshot(); queueRevision = Math.max(queueRevision, snapshot.revision); return snapshot.entries.map(normalize); }
export function writePdaWorkQueue(values: QueueInput[]) {
  const normalized = values.map(normalize); const envelope: QueueEnvelope = { revision: Math.max(queueRevision, localQueueSnapshot().revision) + 1, updatedAt: new Date().toISOString(), entries: normalized };
  queueRevision = envelope.revision; storeLocalEnvelope(envelope); void queuePdaDurableWrite(PDA_DURABLE_QUEUE_KEY, envelope); announceQueue(normalized);
}
export async function persistPdaWorkQueue(values: QueueInput[]) {
  const normalized = values.map(normalize); const envelope: QueueEnvelope = { revision: Math.max(queueRevision, localQueueSnapshot().revision) + 1, updatedAt: new Date().toISOString(), entries: normalized };
  queueRevision = envelope.revision; const localSaved = storeLocalEnvelope(envelope); const durableSaved = await queuePdaDurableWrite(PDA_DURABLE_QUEUE_KEY, envelope); announceQueue(normalized);
  return localSaved || durableSaved;
}
export async function hydratePdaWorkQueue() {
  const local = localQueueSnapshot(); const durableV3 = parseEnvelope(await readPdaDurableValue<unknown>(PDA_DURABLE_QUEUE_KEY));
  const durableV2 = durableV3 ? null : validEntries(await readPdaDurableValue<unknown>(DURABLE_QUEUE_KEY_V2));
  const durable = durableV3 ?? { revision: 0, updatedAt: "", entries: durableV2 ?? [] }; const selected = selectSnapshot(local, durable); const normalized = selected.entries.map(normalize);
  const migrated: QueueEnvelope = { revision: Math.max(selected.revision, local.revision, durable.revision), updatedAt: selected.updatedAt || new Date().toISOString(), entries: normalized };
  queueRevision = Math.max(queueRevision, migrated.revision); storeLocalEnvelope(migrated); await queuePdaDurableWrite(PDA_DURABLE_QUEUE_KEY, migrated); await queuePdaDurableRemove(DURABLE_QUEUE_KEY_V2); announceQueue(normalized); return normalized;
}

export function queueRetryDelay(attempts: number) { return Math.min(60_000, 2_000 * (2 ** Math.max(0, attempts - 1))); }
export function queuedActionPath(entry: WorkQueueEntry) { return entry.action === "create" ? "/pda-work" : `/pda-work/${encodeURIComponent(entry.workId ?? "")}/${entry.action}`; }
export function classifyQueueError(error: unknown): QueueFailureKind {
  if (error instanceof ApiConnectionError) return "offline"; if (!(error instanceof ApiError)) return "unknown";
  if (error.status === 400 || error.status === 422) return "validation"; if (error.status === 401) return "authentication"; if (error.status === 403) return "forbidden";
  if (error.status === 404) return "not-found"; if (error.status === 409 || error.status === 412) return "conflict"; if (error.status >= 500) return "server"; return "unknown";
}
export function shouldRetryQueueError(error: unknown) { const kind = classifyQueueError(error); return kind === "offline" || kind === "server"; }
export function isQueueConflict(error: unknown) { return classifyQueueError(error) === "conflict"; }
export async function sendQueuedWork<T>(entry: WorkQueueEntry, token: string) {
  const createPayload = entry.payload && typeof entry.payload === "object" ? { ...(entry.payload as Record<string, unknown>), clientRequestId: entry.clientRequestId || entry.id } : { payload: entry.payload, clientRequestId: entry.clientRequestId || entry.id };
  return apiRequest<T>(queuedActionPath(entry), { method: entry.action === "create" ? undefined : "POST", token,
    ...(entry.action === "create" ? { body: createPayload } : entry.version == null ? {} : { body: { version: entry.version } }) });
}
export function nextQueueWakeup(queue: WorkQueueEntry[], now = Date.now()) {
  const retryTimes = queue.filter((entry) => entry.state === "pending" || entry.state === "retrying").map((entry) => entry.nextRetryAt ? new Date(entry.nextRetryAt).getTime() : now).filter(Number.isFinite);
  return retryTimes.length ? Math.max(0, Math.min(...retryTimes) - now) : null;
}

export function rememberLastWork(work: { id: string; title: string; type: string; status: string }) {
  const remembered = { ...work, rememberedAt: new Date().toISOString() };
  try { localStorage.setItem(PDA_LAST_WORK_KEY, JSON.stringify(remembered)); } catch (cause) { reportPdaStorageError("local-write", cause); }
  void queuePdaDurableWrite(DURABLE_LAST_WORK_KEY, remembered); window.dispatchEvent(new Event("pda-last-work-change"));
}
export function readLastWork(): { id: string; title: string; type: string; status: string; rememberedAt: string } | null {
  try { const value = JSON.parse(localStorage.getItem(PDA_LAST_WORK_KEY) ?? "null"); return value?.id ? value : null; } catch { return null; }
}
export async function hydrateLastWork() {
  const local = readLastWork(); const durable = await readPdaDurableValue<ReturnType<typeof readLastWork>>(DURABLE_LAST_WORK_KEY);
  const selected = durable?.id && (!local || Date.parse(durable.rememberedAt) > Date.parse(local.rememberedAt)) ? durable : local;
  if (selected) { try { localStorage.setItem(PDA_LAST_WORK_KEY, JSON.stringify(selected)); } catch (cause) { reportPdaStorageError("local-write", cause); } await queuePdaDurableWrite(DURABLE_LAST_WORK_KEY, selected); window.dispatchEvent(new Event("pda-last-work-change")); }
  return selected;
}
export function readPdaWorkDraft<T>(): T | null { const value = unwrapDraft<T>(readJson(PDA_WORK_DRAFT_KEY)); return value?.value ?? null; }
export function writePdaWorkDraft<T>(draft: T) {
  const wrapped = { updatedAt: new Date().toISOString(), value: draft };
  try { localStorage.setItem(PDA_WORK_DRAFT_KEY, JSON.stringify(wrapped)); } catch (cause) { reportPdaStorageError("local-write", cause); }
  void queuePdaDurableWrite(DURABLE_WORK_DRAFT_KEY, wrapped);
}
function unwrapDraft<T>(value: unknown): { updatedAt: string; value: T } | null {
  if (value && typeof value === "object" && "updatedAt" in value && "value" in value) return value as { updatedAt: string; value: T };
  return value == null ? null : { updatedAt: "", value: value as T };
}
export async function hydratePdaWorkDraft<T>() {
  const local = unwrapDraft<T>(readJson(PDA_WORK_DRAFT_KEY)); const durable = unwrapDraft<T>(await readPdaDurableValue<unknown>(DURABLE_WORK_DRAFT_KEY));
  const selected = !local ? durable : !durable ? local : Date.parse(local.updatedAt || "1970-01-01") >= Date.parse(durable.updatedAt || "1970-01-01") ? local : durable;
  if (!selected) return null;
  try { localStorage.setItem(PDA_WORK_DRAFT_KEY, JSON.stringify(selected)); } catch (cause) { reportPdaStorageError("local-write", cause); }
  await queuePdaDurableWrite(DURABLE_WORK_DRAFT_KEY, selected); return selected.value;
}
export function clearPdaWorkDraft() { localStorage.removeItem(PDA_WORK_DRAFT_KEY); void queuePdaDurableRemove(DURABLE_WORK_DRAFT_KEY); }
export function clearPdaDeviceData() {
  for (const key of [PDA_WORK_QUEUE_KEY, QUEUE_KEY_V2, LEGACY_QUEUE_KEY, PDA_LAST_WORK_KEY, PDA_WORK_DRAFT_KEY, PDA_LAST_SYNC_KEY,"tpverp.pda.observability.queue.v1"]) localStorage.removeItem(key);
  queueRevision = 0; memoryQueueSnapshot = null; void clearPdaDurableStorage(); announceQueue([]); window.dispatchEvent(new Event("pda-last-work-change"));
}
export function isAssignedToCurrentUser(item: { assignedTo?: string | null; assignedUserId?: string | null }, userId?: string | null) {
  const assigned = item.assignedUserId ?? item.assignedTo; if (!assigned || !userId) return true; return String(assigned).toLocaleLowerCase() === String(userId).toLocaleLowerCase();
}
