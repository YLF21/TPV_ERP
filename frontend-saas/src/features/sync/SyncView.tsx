import { useRefreshVersion } from "../../app/RefreshContext";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "../../lib/api";

import type { Credentials, LicenseSummary, OperationalIncident, StockSnapshot, SyncEventView, SyncProjectionStatus } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { uniqueCompanies, latestDate, errorMessage, isToday, formatDate, eventSummary, normalizeSearch } from "../../shared/lib";
import { SectionHeader, Metric, ProjectionMetric, Segmented, usePagination, EmptyState, Input, PaginationControls, StatusPill } from "../../shared/ui";

export function SyncView({
  credentials,
  licenses,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const [mode, setMode] = useState<"events" | "sales" | "stock" | "cash" | "incidents">("events");
  const [companyId, setCompanyId] = useState("");
  const [events, setEvents] = useState<SyncEventView[]>([]);
  const [healthEvents, setHealthEvents] = useState<SyncEventView[]>([]);
  const [stock, setStock] = useState<StockSnapshot[]>([]);
  const [incidents, setIncidents] = useState<OperationalIncident[]>([]);
  const [projectionStatus, setProjectionStatus] = useState<SyncProjectionStatus | null>(null);
  const [cancelTarget, setCancelTarget] = useState<{ incident: OperationalIncident; commandId: string } | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const [loading, setLoading] = useState(false);
  const syncRequestId = useRef(0);

  const companyOptions = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const companyNames = useMemo(
    () => new Map(companyOptions.map((company) => [company.companyId, company.companyName])),
    [companyOptions]
  );
  const canViewIncidents = permissions.has("VIEW_ADMIN_DATA");
  const canManageIncidents = permissions.has("MANAGE_OPERATIONAL_INCIDENTS");
  const lastReceivedAt = latestDate(healthEvents.map((event) => event.receivedAt));

  useEffect(() => {
    setCancelTarget(null);
    setCancelReason("");
    void load();
  }, [mode, companyId, canViewIncidents, credentials.accessToken, refreshVersion]);

  async function load() {
    const requestId = ++syncRequestId.current;
    const requestedMode = mode;
    const requestedCompanyId = companyId;
    setLoading(true);
    try {
      const selectedCompanyId = requestedCompanyId || undefined;
      const allEventsPromise = api.events(credentials, selectedCompanyId);
      const incidentsPromise = canViewIncidents
        ? api.operationalIncidents(credentials, selectedCompanyId)
        : Promise.resolve([] as OperationalIncident[]);
      const projectionStatusPromise = api.syncProjectionStatus(credentials, selectedCompanyId);
      const selectedDataPromise = mode === "sales"
        ? api.sales(credentials, selectedCompanyId)
        : mode === "stock"
          ? api.stockCurrent(credentials, selectedCompanyId)
          : mode === "cash"
            ? api.cashClosures(credentials, selectedCompanyId)
            : Promise.resolve(null);
      const [nextHealthEvents, nextIncidents, nextProjectionStatus, selectedData] = await Promise.all([
        allEventsPromise,
        incidentsPromise,
        projectionStatusPromise,
        selectedDataPromise
      ]);
      if (requestId !== syncRequestId.current || requestedMode !== mode || requestedCompanyId !== companyId) return;
      setHealthEvents(nextHealthEvents);
      setIncidents(nextIncidents);
      setProjectionStatus(nextProjectionStatus);
      if (mode === "events") setEvents(nextHealthEvents);
      if (mode === "sales" || mode === "cash") setEvents(selectedData as SyncEventView[]);
      if (mode === "stock") setStock(selectedData as StockSnapshot[]);
      onNotice(null);
    } catch (error) {
      if (requestId === syncRequestId.current && requestedMode === mode && requestedCompanyId === companyId) onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (requestId === syncRequestId.current) setLoading(false);
    }
  }

  function requestCancellation(incident: OperationalIncident) {
    setCancelTarget({ incident, commandId: crypto.randomUUID() });
    setCancelReason("");
  }

  async function cancelIncident(event: FormEvent) {
    event.preventDefault();
    if (!cancelTarget || !canManageIncidents) return;
    const reason = cancelReason.trim();
    if (reason.length < 5) {
      onNotice({ type: "error", text: t("cancelIncidentReasonRequired") });
      return;
    }
    setCancelling(true);
    try {
      await api.cancelMemberCategoryBootstrapIncident(
        credentials,
        cancelTarget.incident.companyId,
        cancelTarget.incident.targetId,
        {
          commandId: cancelTarget.commandId,
          expectedStatus: cancelTarget.incident.status,
          reason
        }
      );
      setCancelTarget(null);
      setCancelReason("");
      await load();
      onNotice({ type: "success", text: t("incidentCancelled") });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setCancelTarget(null);
        setCancelReason("");
        await load();
        onNotice({ type: "error", text: t("incidentConflictReloaded") });
      } else {
        onNotice({ type: "error", text: errorMessage(error) });
      }
    } finally {
      setCancelling(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader
        title={mode === "incidents" ? t("operationalIncidents") : t("sync")}
        subtitle={loading ? t("consultingEvents") : mode === "incidents" ? t("operationalIncidentsSubtitle") : t("syncSubtitle")}
      />
      <div className="sync-health-grid">
        <Metric label={t("eventsToday")} value={healthEvents.filter((event) => isToday(event.receivedAt)).length} />
        <Metric label={t("salesEvents")} value={healthEvents.filter((event) => event.entityType === "DOCUMENTO").length} />
        <Metric label={t("stockEvents")} value={mode === "stock" ? stock.length : healthEvents.filter((event) => event.entityType === "STOCK_MOVEMENT").length} />
        <Metric label={t("cashEvents")} value={healthEvents.filter((event) => event.entityType === "CIERRE_CAJA").length} />
        <Metric label={t("lastSync")} value={lastReceivedAt ? formatDate(lastReceivedAt) : "-"} />
        <Metric
          label={t("operationalIncidentCount")}
          value={canViewIncidents ? incidents.length : "-"}
          tone={canViewIncidents && incidents.length > 0 ? "warning" : undefined}
        />
      </div>
      <div className="projection-health-panel" aria-label={t("projectionHealth")}>
        <strong>{t("projectionHealth")}</strong>
        <div className="projection-health-grid">
          <ProjectionMetric label={t("projectionReceived")} value={projectionStatus?.received ?? 0} warning={(projectionStatus?.received ?? 0) > 0} />
          <ProjectionMetric label={t("projectionProjected")} value={projectionStatus?.projected ?? 0} />
          <ProjectionMetric label={t("projectionIgnored")} value={projectionStatus?.ignored ?? 0} />
          <ProjectionMetric label={t("projectionErrors")} value={projectionStatus?.error ?? 0} warning={(projectionStatus?.error ?? 0) > 0} />
          <ProjectionMetric
            label={t("projectionOldestReceived")}
            value={projectionStatus?.oldestReceivedAt ? formatDate(projectionStatus.oldestReceivedAt) : "-"}
            warning={Boolean(projectionStatus?.oldestReceivedAt)}
          />
        </div>
      </div>
      <div className="toolbar">
        <Segmented
          value={mode}
          options={[
            ["events", t("events")],
            ["sales", t("sales")],
            ["stock", t("stock")],
            ["cash", t("cash")],
            ["incidents", canViewIncidents ? `${t("incidents")} (${incidents.length})` : t("incidents")]
          ]}
          onChange={(value) => setMode(value as "events" | "sales" | "stock" | "cash" | "incidents")}
        />
        <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
          <option value="">{t("allCompanies")}</option>
          {companyOptions.map((company) => (
            <option key={company.companyId} value={company.companyId}>
              {company.companyName}
            </option>
          ))}
        </select>
      </div>
      {mode === "incidents" && !canViewIncidents && (
        <div className="permission-hint">{t("operationalIncidentPermission")}</div>
      )}
      {mode === "incidents" && cancelTarget && (
        <form className="incident-cancel-panel" onSubmit={cancelIncident}>
          <div>
            <span className="eyebrow">{t("cancelIncidentTitle")}</span>
            <strong>{companyNames.get(cancelTarget.incident.companyId) ?? cancelTarget.incident.companyId}</strong>
            <small>{cancelTarget.incident.targetId}</small>
            <p>{t("cancelIncidentWarning")}</p>
          </div>
          <label>
            <span>{t("cancelIncidentReason")}</span>
            <textarea
              className="control-input"
              value={cancelReason}
              onChange={(event) => setCancelReason(event.target.value)}
              placeholder={t("cancelIncidentReasonPlaceholder")}
              minLength={5}
              maxLength={1000}
              rows={3}
              required
              autoFocus
            />
          </label>
          <div className="row-actions">
            <button
              className="secondary-button"
              type="button"
              onClick={() => {
                setCancelTarget(null);
                setCancelReason("");
              }}
              disabled={cancelling}
            >
              {t("close")}
            </button>
            <button className="danger-button subtle" type="submit" disabled={cancelling || cancelReason.trim().length < 5}>
              {cancelling ? t("cancelling") : t("confirmCancellation")}
            </button>
          </div>
        </form>
      )}
      {mode === "incidents" ? (
        canViewIncidents ? (
          <OperationalIncidentsTable
            rows={incidents}
            companyNames={companyNames}
            canManage={canManageIncidents}
            busyTargetId={cancelling ? cancelTarget?.incident.targetId ?? null : null}
            onCancel={requestCancellation}
          />
        ) : null
      ) : mode === "stock" ? (
        <StockTable rows={stock} />
      ) : (
        <EventsTable events={events} />
      )}
    </section>
  );
}

export function EventsTable({ events }: { events: SyncEventView[] }) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const filtered = events.filter((event) => [event.entityType, event.entityId, event.operation, event.projectionStatus, eventSummary(event)].some((value) => normalizeSearch(value).includes(normalizeSearch(query))));
  const paging = usePagination(filtered);
  if (events.length === 0) return <EmptyState text={t("noEventsForFilter")} />;
  return (
    <>
      <div className="toolbar table-filter"><Input label={t("filterRecords")} value={query} onChange={setQuery} /></div>
      <div className="event-list">
      {paging.rows.map((event) => (
        <EventLine key={event.eventId} event={event} />
      ))}
      </div>
      <PaginationControls {...paging} />
    </>
  );
}

export function EventLine({ event }: { event: SyncEventView }) {
  const { t } = useI18n();
  const projectionTone = event.projectionStatus === "PROJECTED"
    ? "ok"
    : event.projectionStatus === "RECEIVED" || event.projectionStatus === "ERROR"
      ? "warning"
      : "muted";
  return (
    <article className="event-row">
      <div>
        <div className="event-row-heading">
          <strong>{event.entityType}</strong>
          <StatusPill status={event.projectionStatus} tone={projectionTone} />
        </div>
        <span>{event.operation} · {formatDate(event.receivedAt)}</span>
        <div className="event-summary">{eventSummary(event)}</div>
        <div className="event-projection-meta">
          <span>{t("eventSchemaVersion")}: {event.schemaVersion}</span>
          {event.projectedAt && <span>{t("eventProjection")}: {formatDate(event.projectedAt)}</span>}
        </div>
        {event.projectionError && (
          <div className="event-projection-error">
            <strong>{t("eventProjectionError")}</strong>
            <span>{event.projectionError}</span>
          </div>
        )}
      </div>
      <code>{event.entityId}</code>
      <details>
        <summary>{t("payload")}</summary>
        <pre>{JSON.stringify(event.payload, null, 2)}</pre>
      </details>
    </article>
  );
}

export function OperationalIncidentsTable({
  rows,
  companyNames,
  canManage,
  busyTargetId,
  onCancel
}: {
  rows: OperationalIncident[];
  companyNames: Map<string, string>;
  canManage: boolean;
  busyTargetId: string | null;
  onCancel: (incident: OperationalIncident) => void;
}) {
  const { t } = useI18n();
  const paging = usePagination(rows);
  if (rows.length === 0) return <EmptyState text={t("noOperationalIncidents")} />;
  return (
    <>
      <div className="table-wrap operational-incident-table">
      <table>
        <thead>
          <tr>
            <th>{t("company")}</th>
            <th>{t("incidentProcess")}</th>
            <th>{t("status")}</th>
            <th>{t("incidentInactivity")}</th>
            <th>{t("incidentProgress")}</th>
            <th>{t("incidentSnapshots")}</th>
            <th>{t("incidentChunks")}</th>
            <th>{t("incidentLastActivity")}</th>
            <th aria-label={t("operations")} />
          </tr>
        </thead>
        <tbody>
          {paging.rows.map((incident) => (
            <tr key={`${incident.incidentType}-${incident.targetId}`} className={busyTargetId === incident.targetId ? "is-busy" : undefined}>
              <td>
                <strong>{companyNames.get(incident.companyId) ?? incident.companyId}</strong>
                <small>{incident.companyId}</small>
              </td>
              <td>
                <strong>{incident.incidentType === "MEMBER_CATEGORY_BOOTSTRAP_STALLED" ? t("memberCategoryBootstrap") : incident.incidentType}</strong>
                <small>{incident.targetId}</small>
                {incident.completedBaselineId && <small>{t("incidentBaseline")}: {incident.completedBaselineId}</small>}
              </td>
              <td>
                <StatusPill status={incident.status} tone={incident.status === "CONFLICT" ? "warning" : "muted"} />
                {incident.conflictSummary && <small title={incident.conflictSummary}>{t("incidentConflict")}: {incident.conflictSummary}</small>}
              </td>
              <td>
                <StatusPill
                  status={incident.inactive ? t("incidentInactive") : t("incidentRecent")}
                  tone={incident.inactive ? "warning" : "ok"}
                />
              </td>
              <td>{incident.completedStoreCount} / {incident.expectedStoreCount}</td>
              <td>{incident.snapshotCount}</td>
              <td>{incident.chunkCount}</td>
              <td>
                <strong>{formatDate(incident.lastActivityAt)}</strong>
                <small>{t("created")}: {formatDate(incident.createdAt)}</small>
              </td>
              <td className="table-actions">
                {incident.cancellable && canManage ? (
                  <button
                    className="small-button danger"
                    type="button"
                    onClick={() => onCancel(incident)}
                    disabled={busyTargetId !== null}
                  >
                    {t("cancelIncident")}
                  </button>
                ) : "-"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      <PaginationControls {...paging} />
    </>
  );
}

export function StockTable({ rows }: { rows: StockSnapshot[] }) {
  const { t } = useI18n();
  if (rows.length === 0) return <EmptyState text={t("noStockForFilter")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("product")}</th>
            <th>{t("warehouse")}</th>
            <th>{t("quantity")}</th>
            <th>{t("store")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.companyId}-${row.storeId}-${row.productId}-${row.warehouseId}`}>
              <td>{row.productId}</td>
              <td>{row.warehouseId}</td>
              <td>{row.quantity}</td>
              <td><small>{row.storeId}</small></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
