import type { TicketInterventionRequest } from "../../lib/workspace-api";
import type { Credentials } from "../../lib/types";
export type PendingRepair = { requestId: string; reason: string };
export type PendingTicketWrite = { kind: "comment"; value: string; requestId?: string; username: string; uncertain: boolean };
export type InterventionDraft = { note: string; remoteId: string; assigneeUserId: string; visitAt: string; resolution: string; checks: string; confirmedBy: string; nextReviewAt: string; planningDirty: boolean; revisions: Record<string, number> };
export type InterventionSubmission = { fields: Partial<Record<keyof InterventionDraft, number>> };
type RepairSession = { commentDrafts: Map<string, string>; id: number; repairs: Map<string, PendingRepair>; tickets: Map<string, PendingTicketWrite>; interventions: Map<string, TicketInterventionRequest>; interventionDrafts: Map<string, InterventionDraft>; interventionSubmissions: Map<string, InterventionSubmission> };
// Retain uncertain writes across module navigation, only for this authenticated object.
// Weak keys release everything on logout; no tokens or drafts enter browser storage.
let nextSessionId = 0;
const sessions = new WeakMap<Credentials, RepairSession>();
export function repairSession(credentials: Credentials): RepairSession {
  let session = sessions.get(credentials);
  if (!session) { session = { id: ++nextSessionId, commentDrafts: new Map(), repairs: new Map(), tickets: new Map(), interventions: new Map(), interventionDrafts: new Map(), interventionSubmissions: new Map() }; sessions.set(credentials, session); }
  return session;
}
