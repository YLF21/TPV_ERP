import { apiRequest } from "../../../packages/app-common/src/api/client";
import { queuePdaDurableWrite, readPdaDurableValue } from "./PdaDurableStorage";

export type PdaObservationType =
  | "SYNC_FAILURE"
  | "SYNC_CONFLICT"
  | "TASK_BLOCKED"
  | "REQUEST_LATENCY"
  | "RECOVERED";

export type PdaObservation = {
  type: PdaObservationType;
  code: string;
  workId?: string | null;
  durationMs?: number | null;
  traceId?: string | null;
};

const SAFE_CODE = /^[A-Z][A-Z0-9_]{0,63}$/;
const SAFE_TRACE = /^[A-Za-z0-9._-]{8,64}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const OBSERVATION_QUEUE_KEY = "tpverp.pda.observability.queue.v1";
const OBSERVATION_DURABLE_KEY = "observability-queue-v1";
type QueuedObservation = PdaObservation & { clientEventId: string; occurredAt: string };

function eventId() {
  if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  globalThis.crypto?.getRandomValues?.(bytes);
  if (!bytes.some(Boolean)) for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map(value => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function normalize(event: PdaObservation): QueuedObservation | null {
  if (!SAFE_CODE.test(event.code)) return null;
  const workId = event.workId && UUID.test(event.workId) ? event.workId : undefined;
  const traceId = event.traceId && SAFE_TRACE.test(event.traceId) ? event.traceId : undefined;
  const durationMs = event.durationMs == null ? undefined : Math.max(0, Math.min(120_000, Math.round(event.durationMs)));
  return { type: event.type, code: event.code, clientEventId: eventId(), occurredAt: new Date().toISOString(), ...(workId ? { workId } : {}), ...(durationMs == null ? {} : { durationMs }), ...(traceId ? { traceId } : {}) };
}
function localQueue(): QueuedObservation[] { try { const value=JSON.parse(localStorage.getItem(OBSERVATION_QUEUE_KEY)??"[]"); return Array.isArray(value)?value:[]; } catch { return []; } }
async function saveQueue(values: QueuedObservation[]) { const bounded=values.slice(-100); try { localStorage.setItem(OBSERVATION_QUEUE_KEY,JSON.stringify(bounded)); } catch { /* IndexedDB remains available. */ } await queuePdaDurableWrite(OBSERVATION_DURABLE_KEY,bounded); }
async function enqueueObservation(value:QueuedObservation){const durable=await readPdaDurableValue<QueuedObservation[]>(OBSERVATION_DURABLE_KEY);const existing=durable?.length?durable:localQueue();await saveQueue([...existing.filter(item=>item.clientEventId!==value.clientEventId),value]);}
async function send(token:string,event:QueuedObservation) { await apiRequest("/pda-observability/events",{method:"POST",token,body:event}); }
export async function flushPdaObservations(token:string|undefined) {
  if(!token||!navigator.onLine)return false;
  const durable=await readPdaDurableValue<QueuedObservation[]>(OBSERVATION_DURABLE_KEY); const pending=(durable?.length?durable:localQueue());
  let index=0; try { for(;index<pending.length;index+=1)await send(token,pending[index]); } catch { await saveQueue(pending.slice(index)); return false; }
  if(pending.length)await saveQueue([]); return true;
}
/** Durable best effort: telemetry never blocks the operational queue, but survives outages. */
export async function observePdaEvent(token: string | undefined, event: PdaObservation): Promise<boolean> {
  const value=normalize(event); if(!token||!value)return false;
  if(typeof navigator!=="undefined"&&!navigator.onLine){await enqueueObservation(value);return false;}
  try {
    await flushPdaObservations(token); await send(token,value);
    return true;
  } catch {
    await enqueueObservation(value);
    return false;
  }
}
