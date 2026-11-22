import { useEffect, useRef, useState, type ReactNode } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useI18n } from "../../i18n";
import {
  COMMUNICATION_PAGE_SIZE, COMMUNICATION_RECIPIENT_LIMIT, communicationsApi,
  type AudienceEntry, type CommunicationRecipient, type CommunicationStatus, type CommunicationSummary,
  type DeliveryCounts, type DeliveryStatus,
} from "../../lib/communications-api";
import type { Credentials } from "../../lib/types";
import { formatDate } from "../../shared/lib";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import { Input, StatusPill } from "../../shared/ui";
import { LoadState, PageButtons } from "../../shared/workspace-ui";
import { useCommunicationCopy, type CommunicationCopyKey } from "./communication-copy";

const serverSort = { key: "", direction: "asc" } as const;
const countLabels = {
  pending: "PENDING", sending: "SENDING", sent: "SENT", failed: "FAILED", uncertain: "UNCERTAIN", cancelled: "CANCELLED",
} as const satisfies Record<keyof DeliveryCounts, CommunicationCopyKey>;

function CommunicationState({ status }: { status: CommunicationStatus | DeliveryStatus }) {
  const copy = useCommunicationCopy();
  const tone = status === "SENT" ? "ok" : status === "FAILED" || status === "UNCERTAIN" ? "warning" : "muted";
  return <StatusPill status={copy(status)} tone={tone} />;
}

function DateCell({ value }: { value: string | null }) {
  return value ? <time dateTime={value}>{formatDate(value)}</time> : "—";
}

function PagedTable({ page, onPage, total, loading, error, reload, empty, children }: {
  page: number;
  onPage: (page: number) => void;
  total: number | undefined;
  loading: boolean;
  error: string | null;
  reload: () => void;
  empty: string;
  children: ReactNode;
}) {
  const copy = useCommunicationCopy();
  const lastPage = total === undefined ? undefined : Math.max(0, Math.ceil(total / COMMUNICATION_PAGE_SIZE) - 1);
  useEffect(() => {
    if (lastPage !== undefined && page > lastPage) onPage(lastPage);
  }, [lastPage, page, onPage]);
  return <div className="communication-table" aria-busy={loading}>
    <div className="communication-table-region">
      {children}
      {(loading || error || total === 0) && <div className="communication-table-state">
        <LoadState loading={loading} error={error && copy("loadError")} reload={reload} />
        {total === 0 && <p role="status">{empty}</p>}
      </div>}
    </div>
    {lastPage !== undefined && lastPage > 0 && <PageButtons page={page} totalPages={lastPage + 1} onPage={onPage} />}
  </div>;
}

export function CommunicationHistory({ credentials, onOpen, disabled, revision, page, onPage }: {
  credentials: Credentials;
  onOpen: (row: CommunicationSummary) => void;
  disabled: boolean;
  revision: number;
  page: number;
  onPage: (page: number) => void;
}) {
  const copy = useCommunicationCopy();
  const { t } = useI18n();
  const remote = useRemote(() => communicationsApi.list(credentials, page), [credentials.accessToken, page, revision]);
  const columns: DataColumn<CommunicationSummary, string>[] = [
    { key: "subject", label: copy("subject"), defaultWidth: 270, render: row => <span title={row.subject}>{row.subject || copy("untitled")}</span> },
    { key: "status", label: t("status"), defaultWidth: 125, render: row => <CommunicationState status={row.status} /> },
    { key: "updatedAt", label: copy("updatedAt"), defaultWidth: 175, render: row => <DateCell value={row.updatedAt} /> },
    { key: "createdBy", label: copy("author"), defaultWidth: 130, render: row => <span title={row.createdBy}>{row.createdBy}</span> },
    { key: "accepted", label: copy("SENT"), defaultWidth: 180, align: "right", render: row => row.status === "DRAFT" ? "—" : row.counts.sent },
    { key: "open", label: copy("open"), defaultWidth: 96, render: row => <button type="button" className="small-button"
      disabled={disabled} onClick={() => onOpen(row)} aria-label={`${copy("open")} ${row.subject || copy("untitled")}`}>{copy("open")}</button> },
  ];
  return <PagedTable {...remote} total={remote.data?.total} page={page} onPage={onPage} empty={copy("noCommunications")}>
    <SaasDataTable username={credentials.username} tableKey="communications-history" label={copy("history")} columns={columns}
      rows={remote.data?.items ?? []} sort={serverSort} onSort={() => {}} onOpen={onOpen} disabled={disabled || remote.loading} />
  </PagedTable>;
}

export function AudienceDirectory({ credentials, selected, onChange, disabled }: {
  credentials: Credentials;
  selected: string[];
  onChange: (ids: string[]) => void;
  disabled: boolean;
}) {
  const copy = useCommunicationCopy();
  const { t } = useI18n();
  const [search, setSearch] = useState("");
  const [q, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [limitReached, setLimitReached] = useState(false);
  const scroll = useRef<HTMLDivElement>(null);
  const selectedSet = new Set(selected);
  useEffect(() => {
    if (search.trim() === q) return;
    const timer = window.setTimeout(() => {
      setPage(0);
      setQuery(search.trim());
    }, 225);
    return () => window.clearTimeout(timer);
  }, [search, q]);
  useEffect(() => { scroll.current?.scrollTo({ top: 0 }); }, [q, page]);
  const remote = useRemote(() => communicationsApi.audience(credentials, page, q), [credentials.accessToken, page, q]);
  const waiting = search.trim() !== q;
  const rows = waiting ? [] : remote.data?.items ?? [];
  const eligible = rows.filter(row => row.eligible);
  const allOnPageSelected = eligible.length > 0 && eligible.every(row => selectedSet.has(row.id));
  const selectionDisabled = disabled || remote.loading || waiting;

  function toggle(ids: string[], add: boolean) {
    if (selectionDisabled) return;
    const next = new Set(selected);
    ids.forEach(id => add ? next.add(id) : next.delete(id));
    if (next.size > COMMUNICATION_RECIPIENT_LIMIT) {
      setLimitReached(true);
      return;
    }
    setLimitReached(false);
    onChange([...next]);
  }

  const columns: DataColumn<AudienceEntry, string>[] = [
    { key: "selection", label: copy("choose"), defaultWidth: 80, render: row => <input type="checkbox"
      checked={selectedSet.has(row.id)} disabled={selectionDisabled || (!row.eligible && !selectedSet.has(row.id))}
      aria-label={`${copy("select")} ${row.label}`} onChange={event => toggle([row.id], event.target.checked)} /> },
    { key: "name", label: copy("customer"), defaultWidth: 180, render: row => <span title={row.label}>{row.label}</span> },
    { key: "email", label: t("email"), defaultWidth: 220, render: row => <span title={row.email ?? undefined}>{row.email ?? "—"}</span> },
    { key: "companies", label: copy("companies"), defaultWidth: 180, render: row => {
      const names = row.companyNames?.join(" · ") || "—";
      return <span title={names}>{names}</span>;
    } },
    { key: "source", label: copy("contactType"), defaultWidth: 160, render: row => {
      const sources = row.sources?.map(source => copy(source)).join(" · ") || "—";
      return <span title={sources}>{sources}</span>;
    } },
    { key: "status", label: t("status"), defaultWidth: 115, render: row =>
      <StatusPill status={copy(row.reason ?? "eligible")} tone={row.eligible ? "ok" : "muted"} /> },
  ];
  return <div className="communication-audience">
    <div className="communication-actions">
      <Input label={copy("search")} type="search" value={search} onChange={setSearch} maxLength={160} disabled={disabled} />
      <button type="button" className="secondary-button" disabled={selectionDisabled || eligible.length === 0}
        onClick={() => toggle(eligible.map(row => row.id), !allOnPageSelected)}>{copy(allOnPageSelected ? "unselectPage" : "selectPage")}</button>
      <button type="button" className="secondary-button" disabled={disabled || selected.length === 0} onClick={() => {
        setLimitReached(false);
        onChange([]);
      }}>{copy("clearSelection")}</button>
      <output className="communication-selection" aria-live="polite">{copy("selectionCount")} <strong>{selected.length}</strong></output>
    </div>
    {limitReached && <p className="notice error" role="alert">{copy("selectionLimit")}</p>}
    <PagedTable {...remote} loading={remote.loading || waiting} error={waiting ? null : remote.error}
      total={waiting ? undefined : remote.data?.total} page={page} onPage={setPage} empty={copy("noCustomers")}>
      <SaasDataTable username={credentials.username} tableKey="communications-audience" label={copy("recipients")} columns={columns}
        rows={rows} sort={serverSort} onSort={() => {}} scrollRef={scroll} disabled={selectionDisabled}
        onOpen={row => {
          const wasSelected = selectedSet.has(row.id);
          if (row.eligible || wasSelected) toggle([row.id], !wasSelected);
        }} />
    </PagedTable>
  </div>;
}

const errorLabels: Record<string, CommunicationCopyKey> = {
  SMTP_AUTH_FAILED: "authorizationFailed",
  SMTP_NOT_READY: "smtpNotReady",
  SMTP_ACCOUNT_NOT_FOUND: "senderUnavailable",
  SMTP_DELIVERY_FAILED: "deliveryFailed",
  SMTP_DELIVERY_UNCERTAIN: "UNCERTAIN",
  SMTP_SENDER_CHANGED: "senderChanged",
  COMMUNICATION_RECIPIENT_UNAVAILABLE: "eligibilityChanged",
};

export function RecipientTable({ credentials, id, reviewId, revision, disabled, onRetry, onLoadReview }: {
  credentials: Credentials;
  id: string;
  reviewId?: string;
  revision: number;
  disabled: boolean;
  onRetry?: (row: CommunicationRecipient) => void;
  onLoadReview?: (total: number | null) => void;
}) {
  const copy = useCommunicationCopy();
  const { t } = useI18n();
  const [page, setPage] = useState(0);
  const scroll = useRef<HTMLDivElement>(null);
  const remote = useRemote(() => communicationsApi.recipients(credentials, id, page, reviewId),
    [credentials.accessToken, id, page, reviewId, revision]);
  useEffect(() => { scroll.current?.scrollTo({ top: 0 }); }, [page]);
  useEffect(() => { onLoadReview?.(remote.data?.total ?? null); }, [remote.data, onLoadReview]);
  const columns: DataColumn<CommunicationRecipient, string>[] = [
    { key: "name", label: copy("customer"), defaultWidth: 215, render: row => <span title={row.label}>{row.label}</span> },
    { key: "email", label: t("email"), defaultWidth: 235, render: row => <span title={row.email}>{row.email}</span> },
  ];
  if (!reviewId) columns.push(
    { key: "status", label: t("status"), defaultWidth: 185, render: row => <CommunicationState status={row.status} /> },
    { key: "accepted", label: copy("sentAt"), defaultWidth: 170, render: row => <DateCell value={row.sentAt} /> },
    { key: "attempts", label: copy("attempts"), defaultWidth: 110, align: "right", render: row => row.attempts },
    { key: "result", label: copy("result"), defaultWidth: 235, render: row => {
      const message = row.lastError ? copy(errorLabels[row.lastError] ?? "requestError") : "—";
      return <span title={message}>{message}</span>;
    } },
    { key: "retry", label: copy("retry"), defaultWidth: 110, render: row =>
      onRetry && (row.status === "FAILED" || row.status === "UNCERTAIN") && <button type="button" className="small-button" disabled={disabled}
        aria-label={`${copy("retry")} ${row.label}`} onClick={() => onRetry(row)}>{copy("retry")}</button>,
    },
  );
  return <PagedTable {...remote} total={remote.data?.total} page={page} onPage={setPage} empty={copy("noRecipients")}>
    <SaasDataTable username={credentials.username} tableKey={reviewId ? "communications-review" : "communications-delivery"}
      label={copy(reviewId ? "reviewAudience" : "recipients")} columns={columns} rows={remote.data?.items ?? []}
      sort={serverSort} onSort={() => {}} onOpen={row => {
        if (row.status === "FAILED" || row.status === "UNCERTAIN") onRetry?.(row);
      }} disabled={disabled || remote.loading || !onRetry} scrollRef={scroll} />
  </PagedTable>;
}

export function DeliveryCountsRow({ counts }: { counts: DeliveryCounts }) {
  const copy = useCommunicationCopy();
  return <dl className="communication-summary">
    {Object.entries(countLabels).map(([key, label]) =>
      <div key={key}><dt>{copy(label)}</dt><dd>{counts[key as keyof DeliveryCounts]}</dd></div>)}
  </dl>;
}
