import { useEffect, useMemo, useState, type CSSProperties, type FormEvent, type KeyboardEvent } from "react";
import {
  TableLayoutHeaderCell,
  tableLayoutGridTemplate,
  useTableLayoutPreference,
  visibleTableColumns,
  type TableColumnDefinition,
  type UserSession
} from "@tpverp/app-common";
import {
  controlAlertStatuses,
  loadControlAlert,
  loadControlAlertGroups,
  loadControlAlerts,
  loadControlRuleCatalog,
  loadControlRules,
  loadRelatedDocument,
  saveControlRule,
  setControlRuleActive,
  transitionControlAlert,
  type ControlAlert,
  type ControlAlertFilters,
  type ControlAlertStatus,
  type ControlAlertTransition,
  type ControlAlertType,
  type ControlRule,
  type ControlRuleAlertGroup,
  type ControlRuleCatalogItem,
  type ControlRuleDraft,
  type RelatedDocument
} from "./controlAlertsApi";

type Translator = (key: string) => string;

type ControlAlertsScreenProps = {
  session: UserSession;
  t: Translator;
};

type DateRange = { from: string; to: string };
type RuleTile = ControlRuleCatalogItem & {
  rule: ControlRule | null;
  group: ControlRuleAlertGroup | null;
  active: boolean;
};

export type ControlAlertColumnKey = "occurredAt" | "username" | "terminal" | "document" | "detail" | "status";

export const controlAlertsTableKey = "gestion.controlAlerts.byRule";
export const controlAlertColumnDefinitions = [
  { key: "occurredAt", defaultWidth: 158 },
  { key: "username", defaultWidth: 145 },
  { key: "terminal", defaultWidth: 120 },
  { key: "document", defaultWidth: 135 },
  { key: "detail", defaultWidth: 300 },
  { key: "status", defaultWidth: 112 }
] as const satisfies readonly TableColumnDefinition<ControlAlertColumnKey>[];

export function canManageControlAlerts(session: UserSession): boolean {
  return session.permissions.includes("ADMIN") || session.permissions.includes("CONTROL_ALERTS_MANAGE");
}

export function canManageControlRules(session: UserSession): boolean {
  return session.permissions.includes("ADMIN") || session.permissions.includes("CONTROL_RULES_MANAGE");
}

export function canOpenRelatedSale(session: UserSession, alert: ControlAlert | null): boolean {
  return Boolean(alert?.documentId) && (
    session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_VENTAS")
  );
}

export function ControlAlertsScreen({ session, t }: ControlAlertsScreenProps) {
  const token = session.accessToken;
  const initialRange = useMemo(todayRange, []);
  const [draftRange, setDraftRange] = useState<DateRange>(initialRange);
  const [range, setRange] = useState<DateRange>(initialRange);
  const [groups, setGroups] = useState<ControlRuleAlertGroup[]>([]);
  const [catalog, setCatalog] = useState<ControlRuleCatalogItem[]>([]);
  const [rules, setRules] = useState<ControlRule[]>([]);
  const [groupsLoading, setGroupsLoading] = useState(true);
  const [groupsError, setGroupsError] = useState(false);
  const [activeRuleId, setActiveRuleId] = useState<string | null>(null);
  const [editorType, setEditorType] = useState<ControlAlertType | null | undefined>(undefined);
  const canManageRules = canManageControlRules(session);

  async function refreshGroups() {
    const instants = dateRangeToInstants(range);
    setGroupsLoading(true);
    setGroupsError(false);
    try {
      setGroups(await loadControlAlertGroups(instants.from, instants.to, token));
    } catch {
      setGroups([]);
      setGroupsError(true);
    } finally {
      setGroupsLoading(false);
    }
  }

  async function refreshRules() {
    if (!canManageRules) return;
    try {
      const [nextCatalog, nextRules] = await Promise.all([
        loadControlRuleCatalog(token),
        loadControlRules(token)
      ]);
      setCatalog(nextCatalog);
      setRules(nextRules);
    } catch {
      setCatalog([]);
      setRules([]);
    }
  }

  useEffect(() => {
    void refreshGroups();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range, token]);

  useEffect(() => {
    void refreshRules();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canManageRules, token]);

  const tiles = useMemo(() => buildRuleTiles(groups, catalog, rules), [groups, catalog, rules]);
  const activeTile = activeRuleId
    ? tiles.find((tile) => tile.ruleId === activeRuleId || tile.group?.ruleId === activeRuleId) ?? null
    : null;

  function applyRange(event?: FormEvent) {
    event?.preventDefault();
    if (!draftRange.from || !draftRange.to || draftRange.from > draftRange.to) return;
    setRange(draftRange);
  }

  function usePreset(next: DateRange) {
    setDraftRange(next);
    setRange(next);
  }

  function openRule(tile: RuleTile) {
    const ruleId = tile.ruleId ?? tile.group?.ruleId;
    if (ruleId) setActiveRuleId(ruleId);
  }

  async function toggleRule(tile: RuleTile) {
    if (!tile.rule || !tile.supported || !canManageRules) return;
    try {
      const updated = await setControlRuleActive(tile.rule, !tile.rule.active, token);
      setRules((current) => current.map((rule) => rule.id === updated.id ? updated : rule));
      setGroups((current) => current.map((group) => group.ruleId === updated.id ? { ...group, active: updated.active } : group));
    } catch {
      await refreshRules();
    }
  }

  return (
    <>
      <section className="gestion-workspace gestion-control-workspace">
        <header className="gestion-dashboard-toolbar gestion-control-toolbar">
          <div>
            <span className="gestion-eyebrow">{t("gestion.controlAlerts.eyebrow")}</span>
            <h2>{activeTile ? ruleDisplayName(activeTile.type, activeTile.name, t) : t("gestion.controlAlerts.title")}</h2>
          </div>
          <div className="gestion-dashboard-actions">
            {activeTile && <button type="button" onClick={() => setActiveRuleId(null)}>{t("gestion.controlAlerts.backToRules")}</button>}
            {!activeTile && <button type="button" onClick={() => void refreshGroups()}>{t("gestion.controlAlerts.refresh")}</button>}
            {!activeTile && canManageRules && (
              <button type="button" className="primary" onClick={() => setEditorType(null)}>
                {t("gestion.controlRules.add")}
              </button>
            )}
          </div>
        </header>

        <DateRangeToolbar
          draft={draftRange}
          applied={range}
          t={t}
          onDraftChange={setDraftRange}
          onApply={applyRange}
          onPreset={usePreset}
        />

        {activeTile ? (
          <AlertsByRuleView session={session} t={t} tile={activeTile} range={range} />
        ) : (
          <RuleOverview
            tiles={tiles}
            loading={groupsLoading}
            error={groupsError}
            canManage={canManageRules}
            t={t}
            onOpen={openRule}
            onEdit={(tile) => setEditorType(tile.type)}
            onToggle={(tile) => void toggleRule(tile)}
          />
        )}
      </section>

      {editorType !== undefined && (
        <RuleConfigurationDialog
          token={token}
          t={t}
          initialType={editorType}
          catalog={catalog}
          rules={rules}
          onClose={() => setEditorType(undefined)}
          onSaved={async () => {
            await Promise.all([refreshRules(), refreshGroups()]);
            setEditorType(undefined);
          }}
        />
      )}
    </>
  );
}

function DateRangeToolbar({
  draft,
  applied,
  t,
  onDraftChange,
  onApply,
  onPreset
}: {
  draft: DateRange;
  applied: DateRange;
  t: Translator;
  onDraftChange: (range: DateRange) => void;
  onApply: (event: FormEvent) => void;
  onPreset: (range: DateRange) => void;
}) {
  return (
    <form className="gestion-control-date-toolbar" onSubmit={onApply}>
      <div className="gestion-control-date-heading">
        <span>{t("gestion.controlAlerts.dateFilter")}</span>
        <strong>{formatRangeLabel(applied)}</strong>
      </div>
      <label>
        <span>{t("gestion.controlAlerts.from")}</span>
        <input type="date" required value={draft.from} max={draft.to} onChange={(event) => onDraftChange({ ...draft, from: event.target.value })} />
      </label>
      <label>
        <span>{t("gestion.controlAlerts.to")}</span>
        <input type="date" required value={draft.to} min={draft.from} onChange={(event) => onDraftChange({ ...draft, to: event.target.value })} />
      </label>
      <div className="gestion-control-date-presets" aria-label={t("gestion.controlAlerts.quickRanges")}>
        <button type="button" onClick={() => onPreset(todayRange())}>{t("gestion.controlAlerts.today")}</button>
        <button type="button" onClick={() => onPreset(lastSevenDaysRange())}>{t("gestion.controlAlerts.lastSevenDays")}</button>
        <button type="button" onClick={() => onPreset(currentMonthRange())}>{t("gestion.controlAlerts.currentMonth")}</button>
      </div>
      <button type="submit" className="primary" disabled={!draft.from || !draft.to || draft.from > draft.to}>{t("gestion.controlAlerts.apply")}</button>
    </form>
  );
}

function RuleOverview({ tiles, loading, error, canManage, t, onOpen, onEdit, onToggle }: {
  tiles: RuleTile[];
  loading: boolean;
  error: boolean;
  canManage: boolean;
  t: Translator;
  onOpen: (tile: RuleTile) => void;
  onEdit: (tile: RuleTile) => void;
  onToggle: (tile: RuleTile) => void;
}) {
  if (loading) return <div className="gestion-control-overview-state">{t("common.loading")}</div>;
  if (error) return <div className="gestion-control-overview-state error">{t("gestion.controlAlerts.loadError")}</div>;
  if (tiles.length === 0) return <div className="gestion-control-overview-state">{t("gestion.controlRules.empty")}</div>;

  return (
    <section className="gestion-rule-overview" aria-label={t("gestion.controlAlerts.ruleBlocks")}>
      {tiles.map((tile) => {
        const configured = Boolean(tile.ruleId ?? tile.group?.ruleId);
        const canOpen = configured;
        const parameter = ruleParameterText(tile.parameterKind, tile.group?.configuration ?? tile.rule?.configuration ?? tile.defaultConfiguration, t);
        return (
          <article
            key={tile.type}
            className={`gestion-rule-card ${tile.supported ? "" : "unsupported"} ${configured ? "configured" : "unconfigured"}`}
            tabIndex={canOpen ? 0 : -1}
            onDoubleClick={() => canOpen && onOpen(tile)}
            onKeyDown={(event: KeyboardEvent<HTMLElement>) => {
              if (event.key === "Enter" && event.target === event.currentTarget && canOpen) onOpen(tile);
            }}
          >
            <header>
              <div className="gestion-rule-card-index" aria-hidden="true">{String(tiles.indexOf(tile) + 1).padStart(2, "0")}</div>
              <div>
                <h3>{ruleDisplayName(tile.type, tile.name, t)}</h3>
                <span className={`gestion-rule-state ${!tile.supported ? "unsupported" : tile.active ? "active" : configured ? "inactive" : "unconfigured"}`}>
                  {!tile.supported
                    ? t("gestion.controlRules.unavailable")
                    : tile.active
                      ? t("gestion.controlRules.active")
                      : configured
                        ? t("gestion.controlRules.inactive")
                        : t("gestion.controlRules.unconfigured")}
                </span>
              </div>
            </header>

            {configured ? (
              <div className="gestion-rule-card-counts">
                <div><strong>{tile.group?.total ?? 0}</strong><span>{t("gestion.controlAlerts.total")}</span></div>
                <div className="new"><strong>{tile.group?.newCount ?? 0}</strong><span>{t("gestion.controlAlerts.newCount")}</span></div>
              </div>
            ) : (
              <div className="gestion-rule-card-empty">
                {tile.supported ? t("gestion.controlRules.notAddedDescription") : t("gestion.controlRules.unavailableDescription")}
              </div>
            )}

            <footer>
              <span className="gestion-rule-parameter">{parameter || t("gestion.controlRules.noParameter")}</span>
              <div>
                {canManage && configured && tile.supported && (
                  <button type="button" className="quiet" onClick={(event) => { event.stopPropagation(); onToggle(tile); }}>
                    {t(`gestion.controlRules.${tile.active ? "deactivate" : "activate"}`)}
                  </button>
                )}
                {canManage && tile.supported && (
                  <button type="button" onClick={(event) => { event.stopPropagation(); onEdit(tile); }}>
                    {configured ? t("gestion.controlRules.configure") : t("gestion.controlRules.add")}
                  </button>
                )}
                {configured && (
                  <button type="button" className="primary" onClick={(event) => { event.stopPropagation(); onOpen(tile); }}>
                    {t("gestion.controlAlerts.openList")}
                  </button>
                )}
              </div>
            </footer>
          </article>
        );
      })}
    </section>
  );
}

function AlertsByRuleView({ session, t, tile, range }: { session: UserSession; t: Translator; tile: RuleTile; range: DateRange }) {
  const token = session.accessToken;
  const instants = useMemo(() => dateRangeToInstants(range), [range]);
  const [filters, setFilters] = useState<ControlAlertFilters>({
    search: "", status: "", ruleId: tile.ruleId ?? tile.group?.ruleId, from: instants.from, to: instants.to, page: 0, size: 25
  });
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState<ControlAlert[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<ControlAlert | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [pendingAction, setPendingAction] = useState<ControlAlertTransition | "">("");
  const [transitionComment, setTransitionComment] = useState("");
  const [document, setDocument] = useState<RelatedDocument | null>(null);
  const [documentLoading, setDocumentLoading] = useState(false);
  const [documentError, setDocumentError] = useState(false);
  const tableLayout = useTableLayoutPreference({
    app: "gestion", username: session.username, accessToken: token, tableKey: controlAlertsTableKey, definitions: controlAlertColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const tableStyle = { gridTemplateColumns: tableLayoutGridTemplate(tableLayout.layout) } as CSSProperties;
  const canManageAlerts = canManageControlAlerts(session);

  useEffect(() => {
    const ruleId = tile.ruleId ?? tile.group?.ruleId;
    setFilters((current) => current.ruleId === ruleId && current.from === instants.from && current.to === instants.to
      ? current
      : { ...current, ruleId, from: instants.from, to: instants.to, page: 0 });
  }, [instants.from, instants.to, tile.group?.ruleId, tile.ruleId]);

  async function refresh(preferredId = selectedId) {
    setLoading(true);
    setLoadError(false);
    try {
      const result = await loadControlAlerts(filters, token);
      setRows(result.items);
      setTotalElements(result.totalElements);
      setTotalPages(result.totalPages);
      setSelectedId(result.items.some((row) => row.id === preferredId) ? preferredId : result.items[0]?.id ?? null);
    } catch {
      setRows([]);
      setTotalElements(0);
      setTotalPages(0);
      setSelectedId(null);
      setSelected(null);
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, token]);

  useEffect(() => {
    if (!selectedId) {
      setSelected(null);
      return;
    }
    let active = true;
    setDetailLoading(true);
    setDetailError(false);
    void loadControlAlert(selectedId, token)
      .then((alert) => { if (active) setSelected(alert); })
      .catch(() => { if (active) { setSelected(null); setDetailError(true); } })
      .finally(() => { if (active) setDetailLoading(false); });
    return () => { active = false; };
  }, [selectedId, token]);

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    setFilters((current) => ({ ...current, search: query, page: 0 }));
  }

  async function runTransition(action: ControlAlertTransition) {
    if (!selected || !canManageAlerts) return;
    setPendingAction(action);
    setDetailError(false);
    try {
      const updated = await transitionControlAlert(selected.id, action, transitionComment, selected.version, token);
      setSelected(updated);
      setRows((current) => current.map((row) => row.id === updated.id ? updated : row));
      setTransitionComment("");
    } catch {
      setDetailError(true);
    } finally {
      setPendingAction("");
    }
  }

  async function openDocument() {
    if (!selected || !canOpenRelatedSale(session, selected)) return;
    setDocumentLoading(true);
    setDocumentError(false);
    try {
      setDocument(await loadRelatedDocument(selected.id, token));
    } catch {
      setDocumentError(true);
    } finally {
      setDocumentLoading(false);
    }
  }

  function renderCell(column: ControlAlertColumnKey, alert: ControlAlert) {
    if (column === "occurredAt") return formatDateTime(alert.occurredAt);
    if (column === "username") return alert.userName || "—";
    if (column === "terminal") return alertDataText(alert, "terminalCode") || alert.terminalId || "—";
    if (column === "document") return alert.documentNumber || alertDataText(alert, "documentNumber") || alert.documentId || t("gestion.controlAlerts.noDocument");
    if (column === "detail") return alertSummary(alert, t);
    return <span className={`gestion-alert-status ${alert.status.toLowerCase()}`}>{t(`gestion.controlAlerts.status.${alert.status}`)}</span>;
  }

  return (
    <div className="gestion-control-list-stage">
      <form className="gestion-alert-filters compact" onSubmit={submitSearch}>
        <label>
          <span>{t("gestion.controlAlerts.search")}</span>
          <input value={query} onChange={(event) => setQuery(event.target.value)} />
        </label>
        <label>
          <span>{t("gestion.controlAlerts.filterStatus")}</span>
          <select value={filters.status} onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value as "" | ControlAlertStatus, page: 0 }))}>
            <option value="">{t("gestion.controlAlerts.all")}</option>
            {controlAlertStatuses.map((status) => <option key={status} value={status}>{t(`gestion.controlAlerts.status.${status}`)}</option>)}
          </select>
        </label>
        <button type="submit" className="primary">{t("gestion.controlAlerts.apply")}</button>
      </form>

      <div className="gestion-control-grid">
        <section className="gestion-alert-list" aria-label={t("gestion.controlAlerts.list")}>
          <div className="gestion-alert-table" role="table" aria-rowcount={totalElements}>
            <div className="gestion-alert-row gestion-alert-row-head" role="row" style={tableStyle}>
              {visibleColumns.map((column) => (
                <TableLayoutHeaderCell
                  as="span" column={column} key={column.key}
                  resizeLabel={`${t("gestion.controlAlerts.resize")} ${t(`gestion.controlAlerts.column.${column.key}`)}`}
                  onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}
                >
                  {t(`gestion.controlAlerts.column.${column.key}`)}
                </TableLayoutHeaderCell>
              ))}
            </div>
            {rows.map((alert) => (
              <button
                type="button" role="row" key={alert.id}
                className={`gestion-alert-row ${selectedId === alert.id ? "selected" : ""}`}
                style={tableStyle} aria-selected={selectedId === alert.id} onClick={() => setSelectedId(alert.id)}
              >
                {visibleColumns.map((column) => <span role="cell" data-column-key={column.key} key={column.key}>{renderCell(column.key, alert)}</span>)}
              </button>
            ))}
            {loading && <div className="gestion-alert-list-state">{t("common.loading")}</div>}
            {!loading && loadError && <div className="gestion-alert-list-state error">{t("gestion.controlAlerts.loadError")}</div>}
            {!loading && !loadError && rows.length === 0 && <div className="gestion-alert-list-state">{t("gestion.controlAlerts.empty")}</div>}
          </div>
          <footer className="gestion-alert-pagination">
            <span>{t("gestion.controlAlerts.results").replace("{count}", String(totalElements))}</span>
            <div>
              <button type="button" disabled={filters.page === 0 || loading} onClick={() => setFilters((current) => ({ ...current, page: current.page - 1 }))}>{t("gestion.controlAlerts.previous")}</button>
              <span>{`${Math.min(filters.page + 1, Math.max(totalPages, 1))} / ${Math.max(totalPages, 1)}`}</span>
              <button type="button" disabled={filters.page + 1 >= totalPages || loading} onClick={() => setFilters((current) => ({ ...current, page: current.page + 1 }))}>{t("gestion.controlAlerts.next")}</button>
            </div>
          </footer>
        </section>

        <AlertDetailPanel
          session={session} t={t} selected={selected} loading={detailLoading} error={detailError}
          pendingAction={pendingAction} comment={transitionComment} documentLoading={documentLoading} documentError={documentError}
          onCommentChange={setTransitionComment} onTransition={(action) => void runTransition(action)} onOpenDocument={() => void openDocument()}
        />
      </div>
      {document && <RelatedDocumentDialog document={document} t={t} onClose={() => setDocument(null)} />}
    </div>
  );
}

function AlertDetailPanel({ session, t, selected, loading, error, pendingAction, comment, documentLoading, documentError, onCommentChange, onTransition, onOpenDocument }: {
  session: UserSession;
  t: Translator;
  selected: ControlAlert | null;
  loading: boolean;
  error: boolean;
  pendingAction: ControlAlertTransition | "";
  comment: string;
  documentLoading: boolean;
  documentError: boolean;
  onCommentChange: (value: string) => void;
  onTransition: (action: ControlAlertTransition) => void;
  onOpenDocument: () => void;
}) {
  const canManage = canManageControlAlerts(session);
  return (
    <aside className="gestion-alert-detail" aria-label={t("gestion.controlAlerts.detail")}>
      {loading && <div className="gestion-alert-detail-state">{t("common.loading")}</div>}
      {!loading && error && <div className="gestion-alert-detail-state error">{t("gestion.controlAlerts.detailError")}</div>}
      {!loading && !error && !selected && <div className="gestion-alert-detail-state">{t("gestion.controlAlerts.select")}</div>}
      {!loading && selected && (
        <>
          <header>
            <div><span className={`gestion-alert-status ${selected.status.toLowerCase()}`}>{t(`gestion.controlAlerts.status.${selected.status}`)}</span><h3>{t(`gestion.controlAlerts.type.${selected.type}`)}</h3></div>
            <span className="gestion-alert-reference">{selected.documentNumber || alertDataText(selected, "documentNumber") || selected.documentId || selected.id}</span>
          </header>
          <dl className="gestion-alert-detail-data">
            <div><dt>{t("gestion.controlAlerts.column.occurredAt")}</dt><dd>{formatDateTime(selected.occurredAt)}</dd></div>
            <div><dt>{t("gestion.controlAlerts.column.username")}</dt><dd>{selected.userName || "—"}</dd></div>
            <div><dt>{t("gestion.controlAlerts.column.terminal")}</dt><dd>{alertDataText(selected, "terminalCode") || selected.terminalId || "—"}</dd></div>
            <div><dt>{t("gestion.controlAlerts.column.document")}</dt><dd>{selected.documentNumber || selected.documentId || "—"}</dd></div>
            <div className="wide"><dt>{t("gestion.controlAlerts.summary")}</dt><dd>{alertSummary(selected, t)}</dd></div>
          </dl>
          {selected.history && selected.history.length > 0 && (
            <section className="gestion-alert-history">
              <h4>{t("gestion.controlAlerts.history")}</h4>
              <ol>{selected.history.map((entry, index) => (
                <li key={`${entry.changedAt}:${index}`}><span>{formatDateTime(entry.changedAt)}</span><strong>{t(`gestion.controlAlerts.status.${entry.newStatus}`)}</strong><small>{entry.changedBy || "—"}</small>{entry.comment && <p>{entry.comment}</p>}</li>
              ))}</ol>
            </section>
          )}
          {canOpenRelatedSale(session, selected) && <button type="button" className="gestion-alert-document-button" disabled={documentLoading} onClick={onOpenDocument}>{documentLoading ? t("common.loading") : t("gestion.controlAlerts.openDocument")}</button>}
          {documentError && <p className="gestion-inline-error">{t("gestion.controlAlerts.documentError")}</p>}
          {canManage && (
            <section className="gestion-alert-actions">
              <label><span>{t("gestion.controlAlerts.actionComment")}</span><textarea value={comment} maxLength={500} onChange={(event) => onCommentChange(event.target.value)} /></label>
              <div>
                <button type="button" disabled={pendingAction !== "" || selected.status !== "NEW"} onClick={() => onTransition("REVIEW")}>{t("gestion.controlAlerts.action.REVIEW")}</button>
                <button type="button" disabled={pendingAction !== "" || selected.status === "CLOSED" || selected.status === "DISMISSED"} onClick={() => onTransition("CLOSE")}>{t("gestion.controlAlerts.action.CLOSE")}</button>
                <button type="button" disabled={pendingAction !== "" || selected.status === "CLOSED" || selected.status === "DISMISSED"} onClick={() => onTransition("DISMISS")}>{t("gestion.controlAlerts.action.DISMISS")}</button>
              </div>
            </section>
          )}
        </>
      )}
    </aside>
  );
}

function RuleConfigurationDialog({ token, t, initialType, catalog, rules, onClose, onSaved }: {
  token?: string;
  t: Translator;
  initialType: ControlAlertType | null;
  catalog: ControlRuleCatalogItem[];
  rules: ControlRule[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [selectedType, setSelectedType] = useState<ControlAlertType | null>(initialType);
  const item = catalog.find((entry) => entry.type === selectedType) ?? null;
  const existing = rules.find((rule) => rule.type === selectedType) ?? null;
  const [draft, setDraft] = useState<ControlRuleDraft | null>(() => item ? draftFrom(item, existing) : null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(false);
  const absent = catalog.filter((entry) => !entry.configured);

  useEffect(() => {
    setDraft(item ? draftFrom(item, existing) : null);
  }, [existing, item]);

  function choose(next: ControlRuleCatalogItem) {
    if (!next.supported) return;
    setSelectedType(next.type);
    setDraft(draftFrom(next, null));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!draft || !item || !item.supported) return;
    if (!validRuleDraft(item, draft)) return;
    setSaving(true);
    setError(false);
    try {
      await saveControlRule(draft, existing, token);
      onSaved();
    } catch {
      setError(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="gestion-modal-backdrop" role="presentation">
      <section className="gestion-rules-dialog compact" role="dialog" aria-modal="true" aria-labelledby="control-rules-title">
        <header><h2 id="control-rules-title">{existing ? t("gestion.controlRules.edit") : t("gestion.controlRules.add")}</h2><button type="button" aria-label={t("common.close")} onClick={onClose}>×</button></header>
        {!item ? (
          <div className="gestion-rule-catalog-picker">
            <p>{t("gestion.controlRules.addDescription")}</p>
            <div>
              {absent.map((entry) => (
                <button type="button" key={entry.type} disabled={!entry.supported} onClick={() => choose(entry)}>
                  <strong>{ruleDisplayName(entry.type, entry.name, t)}</strong>
                  <span>{entry.supported ? ruleParameterText(entry.parameterKind, entry.defaultConfiguration, t) || t("gestion.controlRules.noParameter") : t("gestion.controlRules.unavailable")}</span>
                </button>
              ))}
            </div>
            {absent.length === 0 && <p>{t("gestion.controlRules.allConfigured")}</p>}
          </div>
        ) : (
          <form className="gestion-rule-form" onSubmit={submit}>
            <div className="gestion-rule-system-name"><span>{t("gestion.controlRules.systemRule")}</span><strong>{ruleDisplayName(item.type, item.name, t)}</strong><small>{t("gestion.controlRules.systemNameLocked")}</small></div>
            {item.parameterKind === "PERCENTAGE" && draft && (
              <label><span>{t("gestion.controlRules.threshold")}</span><input type="number" min="0" max="100" step="0.01" required value={String(draft.configuration.thresholdPercent ?? "")} onChange={(event) => setDraft({ ...draft, configuration: { thresholdPercent: event.target.value === "" ? undefined : Number(event.target.value) } })} /></label>
            )}
            {item.parameterKind === "QUANTITY" && draft && (
              <label><span>{t("gestion.controlRules.minimumCount")}</span><input type="number" min="2" max="999" step="1" required value={String(draft.configuration.minimumCount ?? "")} onChange={(event) => setDraft({ ...draft, configuration: { minimumCount: event.target.value === "" ? undefined : Number(event.target.value) } })} /></label>
            )}
            {item.parameterKind === "NONE" && <p className="gestion-rule-no-config">{t("gestion.controlRules.noConfig")}</p>}
            {draft && <label className="gestion-rule-active-control"><input type="checkbox" checked={draft.active} onChange={(event) => setDraft({ ...draft, active: event.target.checked })} /><span>{t("gestion.controlRules.activeRule")}</span></label>}
            {error && <p className="gestion-inline-error">{t("gestion.controlRules.error")}</p>}
            <div className="gestion-rule-form-actions"><button type="button" onClick={onClose}>{t("common.cancel")}</button><button type="submit" className="primary" disabled={saving}>{saving ? t("common.loading") : t("common.save")}</button></div>
          </form>
        )}
      </section>
    </div>
  );
}

function RelatedDocumentDialog({ document, t, onClose }: { document: RelatedDocument; t: Translator; onClose: () => void }) {
  return (
    <div className="gestion-modal-backdrop" role="presentation">
      <section className="gestion-document-dialog" role="dialog" aria-modal="true" aria-labelledby="related-document-title">
        <header><div><span>{document.type}</span><h2 id="related-document-title">{document.number}</h2></div><button type="button" aria-label={t("common.close")} onClick={onClose}>×</button></header>
        <dl><div><dt>{t("gestion.controlDocument.status")}</dt><dd>{document.status}</dd></div><div><dt>{t("gestion.controlDocument.date")}</dt><dd>{document.date}</dd></div><div><dt>{t("gestion.controlDocument.customer")}</dt><dd>{document.customerId || "—"}</dd></div></dl>
        <div className="gestion-document-lines"><table><thead><tr><th>{t("gestion.controlDocument.product")}</th><th>{t("gestion.controlDocument.quantity")}</th><th>{t("gestion.controlDocument.price")}</th><th>{t("gestion.controlDocument.discount")}</th><th>{t("gestion.controlDocument.total")}</th></tr></thead><tbody>{document.lines.map((line) => <tr key={`${line.position}:${line.productId ?? line.code ?? line.name}`}><td>{line.name}</td><td>{formatNumber(line.quantity)}</td><td>{formatCurrency(line.unitPrice, document.currency)}</td><td>{`${formatNumber(line.discount)} %`}</td><td>{formatCurrency(line.total, document.currency)}</td></tr>)}</tbody></table></div>
        <div className="gestion-document-bottom"><section><h3>{t("gestion.controlDocument.payments")}</h3>{document.payments.length === 0 ? <p>—</p> : document.payments.map((payment) => <p key={`${payment.position}:${payment.paymentMethodId ?? payment.paymentMethod}`}><span>{payment.paymentMethod}</span><strong>{formatCurrency(payment.amount, document.currency)}</strong></p>)}</section><dl><div><dt>{t("gestion.controlDocument.subtotal")}</dt><dd>{formatCurrency(document.baseTotal, document.currency)}</dd></div><div><dt>{t("gestion.controlDocument.discount")}</dt><dd>{`${formatNumber(document.globalDiscount)} %`}</dd></div><div><dt>{t("gestion.controlDocument.tax")}</dt><dd>{formatCurrency(document.taxTotal, document.currency)}</dd></div><div className="total"><dt>{t("gestion.controlDocument.total")}</dt><dd>{formatCurrency(document.total, document.currency)}</dd></div></dl></div>
      </section>
    </div>
  );
}

function buildRuleTiles(groups: ControlRuleAlertGroup[], catalog: ControlRuleCatalogItem[], rules: ControlRule[]): RuleTile[] {
  if (catalog.length === 0) {
    return groups.map((group) => ({
      type: group.type,
      name: group.ruleName,
      parameterKind: group.parameterKind ?? "NONE",
      defaultConfiguration: {},
      supported: group.supported ?? true,
      configured: true,
      ruleId: group.ruleId,
      rule: rules.find((rule) => rule.id === group.ruleId) ?? null,
      group,
      active: group.active
    }));
  }
  return catalog.map((item) => ({
    ...item,
    rule: rules.find((rule) => rule.type === item.type) ?? null,
    group: groups.find((group) => group.type === item.type) ?? null,
    active: groups.find((group) => group.type === item.type)?.active
      ?? rules.find((rule) => rule.type === item.type)?.active
      ?? false
  }));
}

function draftFrom(item: ControlRuleCatalogItem, existing: ControlRule | null): ControlRuleDraft {
  return { type: item.type, active: existing?.active ?? false, configuration: existing?.configuration ?? item.defaultConfiguration };
}

function validRuleDraft(item: ControlRuleCatalogItem, draft: ControlRuleDraft) {
  if (item.parameterKind === "NONE") return Object.keys(draft.configuration).length === 0;
  const key = item.parameterKind === "QUANTITY" ? "minimumCount" : "thresholdPercent";
  const value = Number(draft.configuration[key]);
  if (!Number.isFinite(value)) return false;
  if (item.parameterKind === "QUANTITY") return Number.isInteger(value) && value >= 2 && value <= 999;
  return value >= 0 && value <= 100 && Math.round(value * 100) === value * 100;
}

function ruleParameterText(kind: ControlRuleCatalogItem["parameterKind"], configuration: Record<string, unknown> | undefined, t: Translator) {
  if (kind === "PERCENTAGE") return `${t("gestion.controlRules.thresholdShort")}: ${formatUnknownNumber(configuration?.thresholdPercent)} %`;
  if (kind === "QUANTITY") return `${t("gestion.controlRules.minimumShort")}: ${formatUnknownNumber(configuration?.minimumCount)}`;
  return "";
}

function ruleDisplayName(type: ControlAlertType, fallback: string, t: Translator) {
  const key = `gestion.controlAlerts.type.${type}`;
  const translated = t(key);
  return translated === key ? fallback : translated;
}

function todayRange(): DateRange {
  const value = toIsoDate(new Date());
  return { from: value, to: value };
}

function lastSevenDaysRange(): DateRange {
  const today = startOfDay(new Date());
  const from = new Date(today);
  from.setDate(from.getDate() - 6);
  return { from: toIsoDate(from), to: toIsoDate(today) };
}

function currentMonthRange(): DateRange {
  const today = startOfDay(new Date());
  return { from: toIsoDate(new Date(today.getFullYear(), today.getMonth(), 1)), to: toIsoDate(today) };
}

function dateRangeToInstants(range: DateRange) {
  const from = new Date(`${range.from}T00:00:00`);
  const toExclusive = new Date(`${range.to}T00:00:00`);
  toExclusive.setDate(toExclusive.getDate() + 1);
  return { from: from.toISOString(), to: toExclusive.toISOString() };
}

function startOfDay(value: Date) {
  return new Date(value.getFullYear(), value.getMonth(), value.getDate());
}

function toIsoDate(value: Date) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function formatRangeLabel(range: DateRange) {
  const from = new Date(`${range.from}T00:00:00`);
  const to = new Date(`${range.to}T00:00:00`);
  const formatter = new Intl.DateTimeFormat("es-ES", { day: "2-digit", month: "short", year: "numeric" });
  return range.from === range.to ? formatter.format(from) : `${formatter.format(from)} — ${formatter.format(to)}`;
}

function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("es-ES", { dateStyle: "short", timeStyle: "medium" }).format(date);
}

function alertDataText(alert: ControlAlert, key: string): string {
  const value = alert.data?.[key];
  return typeof value === "string" || typeof value === "number" ? String(value) : "";
}

function alertSummary(alert: ControlAlert, t: Translator): string {
  const data = alert.data ?? {};
  if (alert.type === "MANUAL_DISCOUNT_OVER_PERCENT") {
    return interpolate(t("gestion.controlAlerts.summaryManualDiscount"), { threshold: formatUnknownNumber(data.thresholdPercent), global: formatUnknownNumber(data.globalDiscountPercent), lines: String(Array.isArray(data.matchingLines) ? data.matchingLines.length : 0) });
  }
  if (alert.type === "INACTIVE_PRODUCT_SOLD") {
    const products = Array.isArray(data.products) ? data.products : [];
    const names = products.map((product) => isRecord(product) && typeof product.name === "string" ? product.name : "").filter(Boolean).join(", ");
    return interpolate(t("gestion.controlAlerts.summaryInactiveProducts"), { count: String(products.length), products: names || "—" });
  }
  if (alert.type === "TICKET_CANCELLED") {
    return interpolate(t("gestion.controlAlerts.summaryTicketCancelled"), { document: alert.documentNumber || alertDataText(alert, "documentNumber") || "—", reason: alertDataText(alert, "reason") || "—" });
  }
  if (alert.type === "CONSECUTIVE_LINE_DELETIONS") {
    return interpolate(t("gestion.controlAlerts.summaryConsecutiveDeletions"), { count: formatUnknownNumber(data.deletionCount ?? data.lineCount) });
  }
  if (alert.type === "PRODUCT_DISCOUNT_APPLIED") {
    return interpolate(t("gestion.controlAlerts.summaryProductDiscount"), {
      count: formatUnknownNumber(Array.isArray(data.discountedLines) ? data.discountedLines.length : undefined)
    });
  }
  if (alert.type === "MANUAL_PRICE_CHANGED" || alert.type === "MANUAL_PRICE_CHANGE_OVER_PERCENT") {
    return t("gestion.controlAlerts.summaryManualPrice");
  }
  const lines = Array.isArray(data.lines) ? data.lines : [];
  return interpolate(t("gestion.controlAlerts.summarySaleCleared"), { count: String(typeof data.lineCount === "number" ? data.lineCount : lines.length), total: formatUnknownNumber(data.total) });
}

function interpolate(template: string, values: Record<string, string>): string {
  return Object.entries(values).reduce((result, [key, value]) => result.replaceAll(`{${key}}`, value), template);
}

function formatUnknownNumber(value: unknown): string {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? formatNumber(number) : "—";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function formatCurrency(value: number, currency = "EUR") {
  return new Intl.NumberFormat("es-ES", { style: "currency", currency }).format(value ?? 0);
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("es-ES", { maximumFractionDigits: 3 }).format(value ?? 0);
}
