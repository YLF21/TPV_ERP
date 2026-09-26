import { useEffect, useRef, useState } from "react";
import type { Credentials } from "../../lib/types";
import { workspaceApi, type FailureRepairs } from "../../lib/workspace-api";
import { formatDate } from "../../shared/lib";
import { StatusPill } from "../../shared/ui";
import { LinkedFailureTicket } from "./LinkedFailureTicket";
import { repairSession } from "./repair-session";
import { useRepairLabels } from "./repair-labels";

const active = (status: string) => status === "QUEUED" || status === "RUNNING";
export function FailureRepairsPanel({ credentials, failureKey, companyId, permissions, onSupport }: {
  credentials: Credentials; failureKey: string; companyId: string | null; permissions?: Set<string>;
  onSupport?: () => void;
}) {
  const r = useRepairLabels();
  const pendingRequests = repairSession(credentials).repairs;
  const scope = useRef({ credentials, failureKey });
  scope.current = { credentials, failureKey };
  const current = () => alive.current && scope.current.credentials === credentials && scope.current.failureKey === failureKey;
  const [data, setData] = useState<FailureRepairs | null>(null);
  const dataRef = useRef<FailureRepairs | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [actionError, setActionError] = useState(false);
  const actionKind = useRef<"remote" | "manual" | null>(null);
  const [reason, setReason] = useState(() => pendingRequests.get(failureKey)?.reason ?? "");
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alive = useRef(true);
  const generation = useRef(0);
  const [refresh, setRefresh] = useState(0);
  const [message, setMessage] = useState<string | null>(null);
  const pending = pendingRequests.get(failureKey);
  const running = data?.commands.some(command => active(command.status)) ?? false;
  const unknownStatus = data?.commands.some(command => !["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "EXPIRED"].includes(command.status)) ?? false;
  const canRepair = permissions?.has("MANAGE_OPERATIONAL_INCIDENTS") ?? false;
  const canManual = permissions?.has("MANAGE_SUPPORT_TICKETS") ?? false;
  const needsManual = companyId && data && !running && (!data.remoteEligible || unknownStatus || data.commands.some(command => command.status === "FAILED" || command.status === "EXPIRED"));
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; generation.current++; };
  }, []);
  useEffect(() => {
    const identity = ++generation.current;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let canceled = false;
    async function load() {
      if (canceled || identity !== generation.current) return;
      setLoading(true);
      try {
        const next = await workspaceApi.failureRepairs(credentials, failureKey);
        if (!next || !Array.isArray(next.commands) || typeof next.remoteEligible !== "boolean") throw new Error("Invalid repair response");
        if (canceled || identity !== generation.current) return;
        const pendingRequest = pendingRequests.get(failureKey);
        if (pendingRequest && next.commands.some(command => command.requestId === pendingRequest.requestId)) { pendingRequests.delete(failureKey); setActionError(false); }
        if (next.manualTicketId && actionKind.current === "manual") setActionError(false);
        dataRef.current = next; setData(next); setError(false);
      } catch {
        if (!canceled && identity === generation.current) setError(true);
      } finally {
        if (!canceled && identity === generation.current) {
          setLoading(false);
          if (dataRef.current?.commands.some(command => active(command.status))) timer = setTimeout(() => void load(), 5000);
        }
      }
    }
    void load();
    return () => { canceled = true; if (timer) clearTimeout(timer); };
  }, [credentials.accessToken, failureKey, refresh]);

  async function submit(kind: "remote" | "manual") {
    if (busyRef.current || loading || error || !data || running || (kind === "remote" ? !canRepair || !data.remoteEligible || unknownStatus : !canManual || !companyId)) return;
    const text = (pending?.reason ?? reason).trim();
    if (text.length < 5 || text.length > 500) { setMessage(r("reasonRequired")); return; }
    actionKind.current = kind; busyRef.current = true; setBusy(true); setMessage(null); setError(false); setActionError(false); generation.current++;
    try {
      if (kind === "remote") {
        const payload = pendingRequests.get(failureKey) ?? { requestId: crypto.randomUUID(), reason: text };
        pendingRequests.set(failureKey, payload);
        const command = await workspaceApi.requestFailureRepair(credentials, failureKey, payload);
        if (!command || command.requestId !== payload.requestId || typeof command.commandId !== "string" || typeof command.status !== "string") throw new Error("Unconfirmed repair response");
        if (!current()) return;
        if (pendingRequests.get(failureKey)?.requestId === payload.requestId) pendingRequests.delete(failureKey);
        const next = { ...dataRef.current!, commands: [command, ...(dataRef.current?.commands ?? []).filter(item => item.commandId !== command.commandId)] };
        dataRef.current = next; setData(next);
      } else {
        const result = await workspaceApi.requestManualRepair(credentials, failureKey, text);
        if (!current()) return;
        const next = { ...dataRef.current!, manualTicketId: result.ticketId };
        dataRef.current = next; setData(next);
      }
    } catch {
      if (current()) setActionError(true);
    } finally {
      if (current()) { busyRef.current = false; setBusy(false); setRefresh(value => value + 1); }
    }
  }
  async function copyTicket(id: string) {
    try { await navigator.clipboard.writeText(id); if (current()) setMessage(r("copied")); }
    catch { if (current()) setMessage(r("copyFailed")); }
  }
  return <section aria-label={r("title")} className="content-section failure-resolution">
    <div className="failure-resolution-heading"><div><h4>{r("title")}</h4><p>{r("reloadHelp")}</p></div>
      <button className="secondary-button" type="button" disabled={busy || loading} onClick={() => setRefresh(value => value + 1)}>{r("reload")}</button>
    </div>
    {loading && <p role="status">{r("loading")}</p>}
    {(error || actionError) && <p role="alert">{r("unavailable")}</p>}
    {message && <p role="status">{message}</p>}
    {data && <>
      {!error && !running && <div className="failure-next-action">
        <strong>{r("nextAction")}</strong>
        <p>{r(data.remoteEligible ? "retryHelp" : !companyId ? "centralOnly" : data.commands[0]?.status === "SUCCEEDED" ? "confirmedHelp" : "unsupported")}</p>
      </div>}
      {running && <p className="failure-next-action" role="status">{r("runningHint")}</p>}
      {pending && <p role="status">{r("pending")}</p>}
      {(data.remoteEligible || running || (needsManual && !data.manualTicketId)) && <>
        <label className="failure-reason">{r("reason")}<span>{r("reasonHelp")}</span><textarea aria-label={r("reason")} className="control-input" rows={3} placeholder={r("reasonExample")} value={reason} minLength={5} maxLength={500} required disabled={busy || running || !!pending}
          onChange={event => setReason(event.target.value)} /></label>
        <div className="failure-resolution-actions">
          {(data.remoteEligible || running) && <button className="primary-button" type="button" disabled={!canRepair || busy || loading || error || running || unknownStatus} onClick={() => void submit("remote")}>{r("retry")}</button>}
          {needsManual && !data.manualTicketId && <button className="secondary-button" type="button" disabled={!canManual || busy || loading || error || running} onClick={() => void submit("manual")}>{r("manual")}</button>}
        </div>
        {((data.remoteEligible && !canRepair) || (needsManual && !data.manualTicketId && !canManual)) && <p>{r("permission")}</p>}
      </>}
      {data.manualTicketId && <p>{r("ticket")}: <code>{data.manualTicketId}</code> <button type="button" onClick={() => void copyTicket(data.manualTicketId!)}>{r("copy")}</button> {onSupport && <button type="button" onClick={onSupport}>{r("support")}</button>}</p>}
      {data.manualTicketId && companyId && <LinkedFailureTicket key={data.manualTicketId} credentials={credentials} companyId={companyId} ticketId={data.manualTicketId} canManage={canManual} />}
      <div className="failure-attempts"><h5>{r("history")}</h5>
      {!data.commands.length && <p>{r("noCommands")}</p>}
      {data.commands.map(command => <article className="failure-attempt" key={command.commandId}>
        <StatusPill status={r(command.status)} tone={command.status === "SUCCEEDED" ? "ok" : command.status === "FAILED" || command.status === "EXPIRED" ? "warning" : "muted"} />
        {command.resultCode && <p>{r(command.resultCode)}</p>}
        <dl><dt>{r("created")}</dt><dd>{formatDate(command.createdAt)}</dd><dt>{r("expires")}</dt><dd>{formatDate(command.expiresAt)}</dd>
          <dt>{r("by")}</dt><dd>{command.requestedBy}</dd><dt>{r("reason")}</dt><dd style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{command.reason}</dd></dl>
      </article>)}
      </div>
    </>}
  </section>;
}
