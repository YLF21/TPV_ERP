import { useRefreshVersion } from "../../app/RefreshContext";
import { FormEvent, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";

import type { Credentials, OutboxFailure } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { errorMessage, formatDate } from "../../shared/lib";
import { SectionHeader, RetryError, EmptyState, StatusPill, Input } from "../../shared/ui";

export function OutboxRecoveryView({
  credentials,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const [channel, setChannel] = useState<"" | "SECURITY" | "INTEGRATION">("");
  const [items, setItems] = useState<OutboxFailure[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [resolution, setResolution] = useState<{ failure: OutboxFailure; action: "requeue" | "acknowledge"; reason: string } | null>(null);
  const requestId = useRef(0);
  const contextId = useRef(0);
  const canManage = permissions.has("MANAGE_OPERATIONS");

  useEffect(() => {
    requestId.current += 1;
    contextId.current += 1;
    setCursor(null); setNextCursor(null); setHistory([]); setItems([]); setResolution(null); setBusyId(null);
    void loadFailures(null, channel);
  }, [channel, credentials.accessToken, refreshVersion]);

  async function loadFailures(nextPageCursor: string | null, requestedChannel = channel) {
    const id = ++requestId.current;
    setLoading(true); setLoadError(null);
    try {
      const page = await api.outboxFailures(credentials, {
        channel: requestedChannel || undefined,
        limit: 50,
        cursor: nextPageCursor || undefined
      });
      if (id !== requestId.current || requestedChannel !== channel) return;
      setItems(page.items);
      setNextCursor(page.nextCursor);
      onNotice(null);
    } catch (error) {
      if (id !== requestId.current || requestedChannel !== channel) return;
      setItems([]); setNextCursor(null); setLoadError(errorMessage(error));
    } finally {
      if (id === requestId.current && requestedChannel === channel) setLoading(false);
    }
  }

  function nextPage() {
    if (!nextCursor) return;
    setHistory((current) => [...current, cursor]);
    setCursor(nextCursor);
    void loadFailures(nextCursor);
  }

  function previousPage() {
    const previous = history.at(-1);
    if (previous === undefined) return;
    setHistory((current) => current.slice(0, -1));
    setCursor(previous);
    void loadFailures(previous);
  }

  async function resolveFailure(event: FormEvent) {
    event.preventDefault();
    if (!resolution || !canManage) return;
    const reason = resolution.reason.trim();
    if (reason.length < 5 || reason.length > 500) {
      onNotice({ type: "error", text: t("outboxReasonInvalid") });
      return;
    }
    if (!window.confirm(t("outboxConfirm"))) return;
    const target = resolution.failure;
    const action = resolution.action;
    const operationContext = contextId.current;
    setBusyId(target.id);
    try {
      await api.resolveOutboxFailure(credentials, target.channel, target.id, action, reason);
      if (operationContext !== contextId.current) return;
      setResolution(null);
      await loadFailures(cursor);
      if (operationContext === contextId.current) onNotice({ type: "success", text: t("outboxResolved") });
    } catch (error) {
      if (operationContext === contextId.current) onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (operationContext === contextId.current) setBusyId(null);
    }
  }

  return <section className="content-section" aria-busy={loading}>
    <SectionHeader title={t("outboxRecovery")} subtitle={t("outboxRecoverySubtitle")} />
    {loadError && <RetryError message={loadError} onRetry={() => void loadFailures(cursor)} />}
    <div className="toolbar">
      <label className="toolbar-field">{t("outboxChannel")}
        <select className="control-input" value={channel} onChange={(event) => setChannel(event.target.value as "" | "SECURITY" | "INTEGRATION")} disabled={loading || busyId !== null}>
          <option value="">{t("allStatuses")}</option><option value="SECURITY">SECURITY</option><option value="INTEGRATION">INTEGRATION</option>
        </select>
      </label>
    </div>
    {loading && items.length === 0 ? <EmptyState text={t("loadingSaas")} /> : items.length === 0 ? <EmptyState text={t("outboxNoFailures")} /> : <div className="table-wrap"><table>
      <thead><tr><th>{t("outboxChannel")}</th><th>{t("outboxSubject")}</th><th>{t("outboxAttempts")}</th><th>{t("outboxError")}</th><th>{t("outboxFailedAt")}</th><th aria-label={t("operations")} /></tr></thead>
      <tbody>{items.map((failure) => <tr key={`${failure.channel}-${failure.id}`}>
        <td><StatusPill status={failure.channel} tone="warning" /></td><td>{failure.subject}</td><td>{failure.attempts}</td><td>{failure.error || t("notAvailable")}</td><td>{formatDate(failure.failedAt)}</td>
        <td className="table-actions">{canManage ? <><button className="small-button" type="button" disabled={busyId !== null} onClick={() => setResolution({ failure, action: "requeue", reason: "" })}>{t("outboxRequeue")}</button><button className="small-button danger" type="button" disabled={busyId !== null} onClick={() => setResolution({ failure, action: "acknowledge", reason: "" })}>{t("outboxAcknowledge")}</button></> : "-"}</td>
      </tr>)}</tbody>
    </table></div>}
    <nav className="pagination-controls" aria-label={t("pageLabel")}><button className="small-button" type="button" disabled={loading || busyId !== null || history.length === 0} onClick={previousPage}>{t("previousPage")}</button><button className="small-button" type="button" disabled={loading || busyId !== null || !nextCursor} onClick={nextPage}>{t("nextPage")}</button></nav>
    {resolution && <form className="stack-form" aria-label={t("outboxResolutionReason")} onSubmit={resolveFailure}>
      <p><strong>{resolution.action === "requeue" ? t("outboxRequeue") : t("outboxAcknowledge")}</strong> · {resolution.failure.channel} · {resolution.failure.subject}</p>
      <Input label={t("outboxResolutionReason")} value={resolution.reason} onChange={(reason) => setResolution({ ...resolution, reason })} minLength={5} maxLength={500} required disabled={busyId !== null} />
      <div className="table-actions"><button className="primary-button" type="submit" disabled={busyId !== null}>{resolution.action === "requeue" ? t("outboxRequeue") : t("outboxAcknowledge")}</button><button className="secondary-button" type="button" disabled={busyId !== null} onClick={() => setResolution(null)}>{t("cancel")}</button></div>
    </form>}
  </section>;
}
