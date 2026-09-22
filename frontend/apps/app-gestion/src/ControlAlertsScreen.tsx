import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { ArrowClockwise, ArrowRight, BellRinging, CashRegister, FileText, Gear, ListBullets, MinusCircle, Package, Percent, SlidersHorizontal, Tag, Trash, X } from "@phosphor-icons/react";
import { classifyApiFailure, useTableLayoutPreference, TableLayoutHeaderCell, tableLayoutGridTemplate, visibleTableColumns, type LocaleCode, type TableColumnDefinition, type UserSession } from "@tpverp/app-common";
import {
  controlAlertPriorities,
  controlAlertTypes,
  defaultControlAlertView,
  loadControlAlertViewPreference,
  saveControlAlertViewPreference,
  type ControlAlertViewPreference,
  controlAlertStatuses,
  loadControlAlert,
  loadControlAlertAssignees,
  loadControlAlertGroups,
  loadControlAlerts,
  loadControlRuleCatalog,
  loadControlRules,
  loadRelatedDocument,
  saveControlRule,
  setControlRuleActive,
  transitionControlAlert,
  updateControlAlertWork,
  type ControlAlert,
  type ControlAlertAssignee,
  type ControlAlertFilters,
  type ControlAlertPriority,
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
  locale?: LocaleCode;
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

const timelineColumns = [
  { key: "time", defaultWidth: 88 },
  { key: "operation", defaultWidth: 280 },
  { key: "documentUser", defaultWidth: 220 },
  { key: "status", defaultWidth: 115 },
  { key: "reviewComment", defaultWidth: 270 }
] as const satisfies readonly TableColumnDefinition[];

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

export function ControlAlertsScreen(props: ControlAlertsScreenProps) {
  return <ControlAlertsWorkspace key={props.session.username} {...props} />;
}

function ControlAlertsWorkspace({ session, t, locale = "es" }: ControlAlertsScreenProps) {
  const token = session.accessToken;
  const [range, setRange] = useState<DateRange>(lastSevenDaysRange);
  const [draftRange, setDraftRange] = useState<DateRange>(lastSevenDaysRange);
  const [groups, setGroups] = useState<ControlRuleAlertGroup[]>([]);
  const [catalog, setCatalog] = useState<ControlRuleCatalogItem[]>([]);
  const [rules, setRules] = useState<ControlRule[]>([]);
  const [groupsError, setGroupsError] = useState(false);
  const [groupsLoading, setGroupsLoading] = useState(true);
  const [rulesError, setRulesError] = useState(false);
  const [rulesLoading, setRulesLoading] = useState(false);
  const [rulesOpen, setRulesOpen] = useState(false);
  const [editorType, setEditorType] = useState<ControlAlertType | null | undefined>(undefined);
  const [activeType, setActiveType] = useState<"" | ControlAlertType>("");
  const [refreshKey, setRefreshKey] = useState(0);

  const [documentVisible, setDocumentVisible] = useState(() => document.visibilityState !== "hidden");
  const [preferencesOpen, setPreferencesOpen] = useState(false);
  const [view, setView] = useState<ControlAlertViewPreference>(defaultControlAlertView);
  const [viewDraft, setViewDraft] = useState<ControlAlertViewPreference>(defaultControlAlertView);
  const [preferenceReady, setPreferenceReady] = useState(false);
  const [preferenceLoaded, setPreferenceLoaded] = useState(false);
  const [preferenceError, setPreferenceError] = useState(false);
  const [preferenceSaving, setPreferenceSaving] = useState(false);
  const [preferenceReload, setPreferenceReload] = useState(0);
  const [metricsFilters, setMetricsFilters] = useState<Pick<ControlAlertFilters, "search" | "status" | "priority" | "assigneeId" | "overdue">>({ search: "", status: "" });
  const refreshSeconds = view.refreshSeconds;
  const storeTimezone = view.storeTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;
  const canManageRules = canManageControlRules(session);
  const ruleRequest = useRef(0);
  const instants = useMemo(() => dateRangeToInstants(range, storeTimezone), [range, storeTimezone]);

  useEffect(() => {
    const controller = new AbortController();
    setPreferenceError(false);
    void loadControlAlertViewPreference(token, controller.signal).then((preference) => {
      if (controller.signal.aborted) return;
      const next = { ...defaultControlAlertView, ...preference, sortBy: "occurredAt" };
      setPreferenceLoaded(true);
      setView(next);
      setViewDraft(next);
      const dates = rangeForPeriod(next.defaultPeriod, next.storeTimezone);
      setRange(dates);
      setDraftRange(dates);
    }).catch(() => { if (!controller.signal.aborted) setPreferenceError(true); })
      .finally(() => { if (!controller.signal.aborted) setPreferenceReady(true); });
    return () => controller.abort();
  }, [token, preferenceReload]);

  async function saveView(next: ControlAlertViewPreference, closeDialog = true) {
    if (preferenceSaving || !preferenceLoaded) return;
    setPreferenceSaving(true);
    setPreferenceError(false);
    try {
      const saved = await saveControlAlertViewPreference({ ...next, sortBy: "occurredAt" }, token);
      setView({ ...next, ...saved, sortBy: "occurredAt" });
      if (closeDialog) setPreferencesOpen(false);
    } catch { setPreferenceError(true); }
    finally { setPreferenceSaving(false); }
  }

  const changeMetricsFilters = useCallback((next: Pick<ControlAlertFilters, "search" | "status" | "priority" | "assigneeId" | "overdue">) => {
    setMetricsFilters((current) => JSON.stringify(current) === JSON.stringify(next) ? current : next);
  }, []);

  useEffect(() => {
    const update = () => setDocumentVisible(document.visibilityState !== "hidden");
    document.addEventListener("visibilitychange", update);
    return () => document.removeEventListener("visibilitychange", update);
  }, []);

  useEffect(() => {
    if (refreshSeconds <= 0 || !documentVisible) return;
    const timer = window.setInterval(() => setRefreshKey((value) => value + 1), refreshSeconds * 1000);
    return () => window.clearInterval(timer);
  }, [refreshSeconds, documentVisible]);

  useEffect(() => {
    if (!preferenceReady) return;
    const controller = new AbortController();
    setGroupsError(false);
    setGroupsLoading(true);
    void loadControlAlertGroups(instants.from, instants.to, token, controller.signal, metricsFilters)
      .then((items) => { if (!controller.signal.aborted) setGroups(items); })
      .catch(() => { if (!controller.signal.aborted) setGroupsError(true); })
      .finally(() => { if (!controller.signal.aborted) setGroupsLoading(false); });
    return () => controller.abort();
  }, [instants.from, instants.to, token, refreshKey, metricsFilters, preferenceReady]);

  const refreshRules = useCallback(async () => {
    if (!canManageRules) return;
    const request = ++ruleRequest.current;
    setRulesLoading(true);
    setRulesError(false);
    try {
      const [nextCatalog, nextRules] = await Promise.all([loadControlRuleCatalog(token), loadControlRules(token)]);
      if (request !== ruleRequest.current) return;
      setCatalog(nextCatalog);
      setRules(nextRules);
    } catch {
      if (request === ruleRequest.current) setRulesError(true);
    } finally {
      if (request === ruleRequest.current) setRulesLoading(false);
    }
  }, [canManageRules, token]);

  useEffect(() => {
    if (rulesOpen) void refreshRules();
    return () => { ruleRequest.current += 1; };
  }, [rulesOpen, refreshRules]);

  const tiles = useMemo(() => buildRuleTiles(groups, catalog, rules), [groups, catalog, rules]);
  const groupTotal = groups.reduce((sum, group) => sum + group.total, 0);
  const groupTypes = groups.filter((group) => group.active || group.total > 0);

  function changeRange(next: DateRange) {
    setDraftRange(next);
    setRange(next);
  }

  async function toggleRule(tile: RuleTile) {
    if (!tile.rule || !tile.supported || !canManageRules || rulesLoading) return;
    setRulesLoading(true);
    setRulesError(false);
    try {
      const updated = await setControlRuleActive(tile.rule, !tile.rule.active, token);
      setRules((current) => current.map((rule) => rule.id === updated.id ? updated : rule));
      setGroups((current) => current.map((group) => group.ruleId === updated.id ? { ...group, active: updated.active } : group));
    } catch {
      setRulesError(true);
    } finally {
      setRulesLoading(false);
    }
  }

  return (
    <section className={`gestion-workspace gestion-control-workspace gestion-control-timeline ${view.compact ? "compact-view" : ""}`}>
      <header className="gestion-dashboard-toolbar gestion-control-toolbar">
        <div><h2>{t("gestion.controlAlerts.title")}</h2><p className="gestion-control-subtitle">{t("gestion.controlAlerts.chronology")}</p></div>
        <div className="gestion-dashboard-actions">
          <button type="button" disabled={!preferenceLoaded || preferenceSaving} onClick={() => { setViewDraft(view); setPreferencesOpen(true); }}><SlidersHorizontal size={20} />{t("gestion.controlAlerts.personalize")}</button>
          {canManageRules && <button type="button" onClick={() => setRulesOpen(true)}><Gear size={20} />{t("gestion.controlAlerts.configureRules")}</button>}
          <button type="button" onClick={() => setRefreshKey((value) => value + 1)}><ArrowClockwise size={20} />{t("gestion.controlAlerts.refresh")}</button>
        </div>
      </header>
      <DateRangeToolbar draft={draftRange} applied={range} t={t} locale={locale} timeZone={storeTimezone} onDraftChange={setDraftRange}
        onApply={(event) => { event.preventDefault(); if (draftRange.from && draftRange.to && draftRange.from <= draftRange.to) setRange(draftRange); }}
        onPreset={changeRange} />
      {preferenceError && <div className="gestion-control-inline-state error" role="alert">{t("gestion.controlAlerts.preferenceError")}<button type="button" onClick={() => setPreferenceReload((value) => value + 1)}>{t("gestion.controlAlerts.retry")}</button></div>}
      {view.showIndicators && (
        <section className="gestion-control-type-strip" aria-label={t("gestion.controlAlerts.periodTypes")} aria-busy={groupsLoading}>
          <button type="button" aria-pressed={!activeType} className={!activeType ? "selected" : ""} onClick={() => setActiveType("")}>
            <ListBullets size={29} /><span>{t("gestion.controlAlerts.all")}<strong>{groupsLoading ? "…" : groupsError ? "—" : groupTotal}</strong></span>
          </button>
          {groupTypes.map((group) => <button type="button" key={group.ruleId} aria-pressed={activeType === group.type}
            className={`${alertTypeClass(group.type)} ${activeType === group.type ? "selected" : ""}`} onClick={() => setActiveType(activeType === group.type ? "" : group.type)}>
            <AlertTypeIcon type={group.type} size={28} /><span>{ruleDisplayName(group.type, group.ruleName, t)}<strong>{groupsLoading ? "…" : groupsError ? "—" : group.total}</strong></span>
          </button>)}
        </section>
      )}
      {groupsError && <div className="gestion-control-inline-state error" role="alert">{t("gestion.controlAlerts.indicatorsError")} <button type="button" onClick={() => setRefreshKey((value) => value + 1)}>{t("gestion.controlAlerts.retry")}</button></div>}
      <div className="gestion-control-period-note">{t("gestion.controlAlerts.periodTypes")} · {formatRangeLabel(range, locale)} · {storeTimezone}</div>
      {preferenceReady ? <AlertsTimelineView session={session} t={t} locale={locale} range={range} activeType={activeType} onTypeChange={setActiveType}
        defaultRange={rangeForPeriod(view.defaultPeriod, storeTimezone)} onResetRange={() => changeRange(rangeForPeriod(view.defaultPeriod, storeTimezone))}
        refreshKey={refreshKey} view={view} timeZone={storeTimezone} onChanged={() => setRefreshKey((value) => value + 1)}
        onFiltersChange={changeMetricsFilters} onSortChange={(sortDirection) => void saveView({ ...view, sortDirection }, false)} preferenceSaving={preferenceSaving || !preferenceLoaded} /> : <div className="gestion-alert-list-state">{t("common.loading")}</div>}
      {rulesOpen && <div className="gestion-modal-backdrop" role="presentation"><section className="gestion-control-rules-manager" role="dialog" aria-modal="true" aria-labelledby="control-rules-manager-title">
        <header><h2 id="control-rules-manager-title">{t("gestion.controlAlerts.configureRules")}</h2><div>
          <button type="button" disabled={rulesLoading || rulesError} onClick={() => setEditorType(null)}>{t("gestion.controlRules.add")}</button>
          <button type="button" aria-label={t("common.close")} onClick={() => setRulesOpen(false)}><X size={20} /></button>
        </div></header>
        {rulesError && <div className="gestion-control-inline-state error" role="alert">{t("gestion.controlAlerts.rulesLoadError")}<button type="button" onClick={() => void refreshRules()}>{t("gestion.controlAlerts.retry")}</button></div>}
        <RuleOverview tiles={tiles} loading={rulesLoading} error={false} canManage={!rulesError} t={t}
          onOpen={(tile) => { setActiveType(tile.type); setRulesOpen(false); }} onEdit={(tile) => setEditorType(tile.type)} onToggle={(tile) => void toggleRule(tile)} onRetry={() => void refreshRules()} />
      </section></div>}
      {editorType !== undefined && <RuleConfigurationDialog token={token} t={t} initialType={editorType} catalog={catalog} rules={rules}
        onClose={() => setEditorType(undefined)} onSaved={async () => { await refreshRules(); setRefreshKey((value) => value + 1); setEditorType(undefined); }} />}
      {preferencesOpen && <div className="gestion-modal-backdrop" role="presentation"><section className="gestion-control-preferences" role="dialog" aria-modal="true" aria-labelledby="control-preferences-title">
        <header><h2 id="control-preferences-title">{t("gestion.controlAlerts.personalize")}</h2><button type="button" aria-label={t("common.close")} onClick={() => setPreferencesOpen(false)}><X size={20} /></button></header>
        {(["showIndicators", "groupByDay", "compact"] as const).map((key) => <label key={key}><input type="checkbox" checked={viewDraft[key]} onChange={(event) => setViewDraft((current) => ({ ...current, [key]: event.target.checked }))} />{t(`gestion.controlAlerts.preference.${key}`)}</label>)}
        <label>{t("gestion.controlAlerts.autoRefresh")}<select value={viewDraft.refreshSeconds} onChange={(event) => setViewDraft((current) => ({ ...current, refreshSeconds: Number(event.target.value) as ControlAlertViewPreference["refreshSeconds"] }))}><option value={0}>{t("gestion.controlAlerts.autoRefreshOff")}</option><option value={15}>15 s</option><option value={30}>30 s</option><option value={60}>60 s</option></select></label>
        <label>{t("gestion.controlAlerts.defaultPeriod")}<select value={viewDraft.defaultPeriod} onChange={(event) => setViewDraft((current) => ({ ...current, defaultPeriod: event.target.value as ControlAlertViewPreference["defaultPeriod"] }))}><option value="LAST_7_DAYS">{t("gestion.controlAlerts.lastSevenDays")}</option><option value="TODAY">{t("gestion.controlAlerts.today")}</option><option value="CURRENT_MONTH">{t("gestion.controlAlerts.currentMonth")}</option></select></label>
        <p>{t("gestion.controlAlerts.preferenceScope")}</p>
        {preferenceError && <p role="alert" className="gestion-inline-error">{t("gestion.controlAlerts.preferenceError")}</p>}
        <footer><button type="button" disabled={preferenceSaving} onClick={() => setViewDraft({ ...defaultControlAlertView, storeTimezone: view.storeTimezone, storeLocale: view.storeLocale })}>{t("gestion.controlAlerts.restoreDefault")}</button><button type="button" className="primary" disabled={preferenceSaving} onClick={() => void saveView(viewDraft)}>{t(preferenceSaving ? "common.loading" : "common.save")}</button></footer>
      </section></div>}
    </section>
  );
}

function alertTypeClass(type: ControlAlertType) {
  if (type.includes("DISCOUNT")) return "discount";
  if (type.includes("PRICE")) return "price";
  if (type === "SALE_SCREEN_CLEARED" || type === "CONSECUTIVE_LINE_DELETIONS" || type === "PARKED_SALE_DELETED") return "cleared";
  if (type.includes("CASH")) return "cash";
  return "other";
}

function AlertTypeIcon({ type, size = 26 }: { type: ControlAlertType; size?: number }) {
  const Icon = type.includes("DISCOUNT") ? Percent : type.includes("PRICE") ? Tag
    : type === "SALE_SCREEN_CLEARED" ? ListBullets : type === "CONSECUTIVE_LINE_DELETIONS" ? MinusCircle
      : type === "PARKED_SALE_DELETED" ? Trash : type.includes("CASH") ? CashRegister
        : type.includes("PRODUCT") ? Package : type === "TICKET_CANCELLED" ? FileText : BellRinging;
  return <Icon size={size} weight="bold" aria-hidden="true" />;
}

function DateRangeToolbar({
  draft,
  applied,
  t,
  onDraftChange,
  onApply,
  onPreset,
  locale,
  timeZone
}: {
  draft: DateRange;
  applied: DateRange;
  t: Translator;
  onDraftChange: (range: DateRange) => void;
  onApply: (event: FormEvent) => void;
  onPreset: (range: DateRange) => void;
  locale: LocaleCode;
  timeZone: string;
}) {
  return (
    <form className="gestion-control-date-toolbar" onSubmit={onApply} title={formatRangeLabel(applied, locale)}>
      <select aria-label={t("gestion.controlAlerts.quickRanges")} value="" onChange={(event) => {
        const period = event.target.value as ControlAlertViewPreference["defaultPeriod"];
        if (period) onPreset(rangeForPeriod(period, timeZone));
      }}><option value="">{t("gestion.controlAlerts.dateFilter")}</option><option value="TODAY">{t("gestion.controlAlerts.today")}</option><option value="LAST_7_DAYS">{t("gestion.controlAlerts.lastSevenDays")}</option><option value="CURRENT_MONTH">{t("gestion.controlAlerts.currentMonth")}</option></select>
      <label>
        <span>{t("gestion.controlAlerts.from")}</span>
        <input type="date" required value={draft.from} max={draft.to} onChange={(event) => onDraftChange({ ...draft, from: event.target.value })} />
      </label>
      <label>
        <span>{t("gestion.controlAlerts.to")}</span>
        <input type="date" required value={draft.to} min={draft.from} onChange={(event) => onDraftChange({ ...draft, to: event.target.value })} />
      </label>
      <button type="submit" className="primary" disabled={!draft.from || !draft.to || draft.from > draft.to}>{t("gestion.controlAlerts.applyDates")}</button>
    </form>
  );
}

function RuleOverview({ tiles, loading, error, canManage, t, onOpen, onEdit, onToggle, onRetry }: {
  tiles: RuleTile[];
  loading: boolean;
  error: boolean;
  canManage: boolean;
  t: Translator;
  onOpen: (tile: RuleTile) => void;
  onEdit: (tile: RuleTile) => void;
  onToggle: (tile: RuleTile) => void;
  onRetry: () => void;
}) {
  if (loading) return <div className="gestion-control-overview-state">{t("common.loading")}</div>;
  if (error) return (
    <div className="gestion-control-overview-state error" role="alert">
      <div>
        <span>{t("gestion.controlAlerts.loadError")}</span>
        <button type="button" onClick={onRetry}>{t("gestion.controlAlerts.retry")}</button>
      </div>
    </div>
  );
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
              <div className={`gestion-rule-card-index ${alertTypeClass(tile.type)}`} aria-hidden="true"><AlertTypeIcon type={tile.type} /></div>
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

function AlertsTimelineView({ session, t, locale, range, defaultRange, onResetRange, activeType, onTypeChange, refreshKey, view, onChanged, timeZone, onFiltersChange, onSortChange, preferenceSaving }: {
  session: UserSession; t: Translator; locale: LocaleCode; range: DateRange; activeType: "" | ControlAlertType;
  defaultRange: DateRange; onResetRange: () => void;
  onTypeChange: (type: "" | ControlAlertType) => void; refreshKey: number;
  view: ControlAlertViewPreference; onChanged: () => void; timeZone: string;
  onFiltersChange: (filters: Pick<ControlAlertFilters, "search" | "status" | "priority" | "assigneeId" | "overdue">) => void;
  onSortChange: (direction: "asc" | "desc") => void; preferenceSaving: boolean;
}) {
  const token = session.accessToken;
  const instants = useMemo(() => dateRangeToInstants(range, timeZone), [range, timeZone]);
  const [filters, setFilters] = useState<ControlAlertFilters>({ search: "", status: "", page: 0, size: 25, sortBy: "occurredAt", sortDirection: view.sortDirection });
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState<ControlAlert[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const detailOpenRef = useRef(false);
  detailOpenRef.current = detailOpen;
  const selectedIdRef = useRef<string | null>(null);
  selectedIdRef.current = selectedId;
  const [selected, setSelected] = useState<ControlAlert | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [pendingAction, setPendingAction] = useState<ControlAlertTransition | "">("");
  const [workSaving, setWorkSaving] = useState(false);
  const [assignees, setAssignees] = useState<ControlAlertAssignee[]>([]);
  const [assigneesLoaded, setAssigneesLoaded] = useState(false);
  const [assigneesError, setAssigneesError] = useState(false);
  const [assigneesRefresh, setAssigneesRefresh] = useState(0);
  const [transitionComment, setTransitionComment] = useState("");
  const [relatedDocument, setRelatedDocument] = useState<RelatedDocument | null>(null);
  const [documentLoading, setDocumentLoading] = useState(false);
  const [documentError, setDocumentError] = useState(false);
  const [detailRefresh, setDetailRefresh] = useState(0);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [loadedScope, setLoadedScope] = useState("");
  const canManageAlerts = canManageControlAlerts(session);
  const rowVersion = rows.find((row) => row.id === selectedId)?.version;
  const scope = JSON.stringify({ ...filters, type: activeType, from: instants.from, to: instants.to });
  const previousScope = useRef({ from: instants.from, to: instants.to, type: activeType });
  const timelineLayout = useTableLayoutPreference({ app: "gestion", username: session.username, accessToken: token,
    tableKey: "gestion.controlAlerts.timeline", definitions: timelineColumns });
  const timelineColumnsVisible = visibleTableColumns(timelineLayout.layout);
  const tableStyle = { minWidth: timelineColumnsVisible.reduce((width, column) => width + column.width, 0), gridTemplateColumns: timelineColumnsVisible.map((column, index) => {
    const width = tableLayoutGridTemplate([column]);
    return index === timelineColumnsVisible.length - 1 ? `minmax(${width}, 1fr)` : width;
  }).join(" ") };

  useEffect(() => {
    setFilters((current) => current.sortDirection === view.sortDirection ? current : { ...current, sortBy: "occurredAt", sortDirection: view.sortDirection, page: 0 });
  }, [view.sortDirection]);

  useEffect(() => {
    onFiltersChange({ status: filters.status, search: filters.search, priority: filters.priority, assigneeId: filters.assigneeId, overdue: filters.overdue });
  }, [filters.status, filters.search, filters.priority, filters.assigneeId, filters.overdue, onFiltersChange]);


  useEffect(() => {
    const previous = previousScope.current;
    if (previous.from !== instants.from || previous.to !== instants.to || previous.type !== activeType) {
      previousScope.current = { from: instants.from, to: instants.to, type: activeType };
      setFilters((current) => current.page === 0 ? current : { ...current, page: 0 });
    }
  }, [instants.from, instants.to, activeType]);

  useEffect(() => {
    let active = true;
    setAssigneesLoaded(false);
    setAssigneesError(false);
    void loadControlAlertAssignees(token)
      .then((items) => { if (active) { setAssignees(items); setAssigneesLoaded(true); } })
      .catch(() => { if (active) setAssigneesError(true); });
    return () => { active = false; };
  }, [token, refreshKey, assigneesRefresh]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setLoadError(false);
    const requestFilters: ControlAlertFilters = JSON.parse(scope);
    void loadControlAlerts(requestFilters, token, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      if (result.items.length === 0 && requestFilters.page > 0 && result.totalPages <= requestFilters.page) {
        setFilters((current) => ({ ...current, page: Math.max(0, result.totalPages - 1) }));
        return;
      }
      setRows(result.items);
      setTotalElements(result.totalElements);
      setTotalPages(result.totalPages);
      setLoadedScope(scope);
      setSelectedId(detailOpenRef.current || result.items.some((row) => row.id === selectedIdRef.current) ? selectedIdRef.current : result.items[0]?.id ?? null);
      setLastUpdated(new Date());
    }).catch(() => { if (!controller.signal.aborted) setLoadError(true); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [scope, token, refreshKey]);

  useEffect(() => {
    setConflict(false);
    setTransitionComment("");
    setDocumentError(false);
  }, [selectedId]);

  useEffect(() => {
    if (!detailOpen || !selectedId) { setDetailLoading(false); return; }
    const controller = new AbortController();
    setDetailLoading(true);
    setDetailError(false);
    void loadControlAlert(selectedId, token, controller.signal)
      .then((alert) => { if (!controller.signal.aborted) setSelected(alert); })
      .catch(() => { if (!controller.signal.aborted) setDetailError(true); })
      .finally(() => { if (!controller.signal.aborted) setDetailLoading(false); });
    return () => controller.abort();
  }, [detailOpen, selectedId, token, rowVersion, detailRefresh]);

  const applyUpdate = (updated: ControlAlert) => {
    if (selectedIdRef.current === updated.id) setSelected(updated);
    setRows((current) => current.map((row) => row.id === updated.id ? updated : row));
    onChanged();
  };

  function actionError(error: unknown) {
    if (classifyApiFailure(error) === "conflict") {
      setConflict(true);
      setDetailRefresh((current) => current + 1);
    } else setDetailError(true);
  }

  async function runTransition(action: ControlAlertTransition) {
    if (!selected || !canManageAlerts || pendingAction || workSaving) return;
    const id = selected.id;
    setPendingAction(action);
    setDetailError(false);
    setConflict(false);
    try {
      applyUpdate(await transitionControlAlert(id, action, transitionComment, selected.version, token));
      if (selectedIdRef.current === id) setTransitionComment("");
    } catch (error) { if (selectedIdRef.current === id) actionError(error); }
    finally { setPendingAction(""); }
  }

  async function saveWork(work: { priority: ControlAlertPriority; assigneeId: string; dueAt: string; comment: string }) {
    if (!selected || !canManageAlerts || workSaving || pendingAction || !assigneesLoaded
      || (work.assigneeId && !assignees.some((assignee) => assignee.id === work.assigneeId))) return;
    const id = selected.id;
    setWorkSaving(true);
    setDetailError(false);
    setConflict(false);
    try {
      applyUpdate(await updateControlAlertWork(selected, { priority: work.priority, assigneeId: work.assigneeId || null,
        dueAt: work.dueAt ? (work.dueAt === toLocalDateTimeInput(selected.dueAt, timeZone) ? selected.dueAt : controlAlertDueAtToInstant(work.dueAt, timeZone)) : null, comment: work.comment }, token));
    } catch (error) { if (selectedIdRef.current === id) actionError(error); }
    finally { setWorkSaving(false); }
  }

  async function openDocument() {
    if (!selected || !canOpenRelatedSale(session, selected)) return;
    const id = selected.id;
    setDocumentLoading(true);
    setDocumentError(false);
    try { const result = await loadRelatedDocument(id, token); if (selectedIdRef.current === id) setRelatedDocument(result); }
    catch { if (selectedIdRef.current === id) setDocumentError(true); }
    finally { setDocumentLoading(false); }
  }

  const staleRows = Boolean(loadedScope) && scope !== loadedScope;
  const assigneesErrorMessage = assigneesError && <div className="gestion-control-inline-state error" role="alert">
    {t("gestion.controlAlerts.assigneesLoadError")}
    <button type="button" onClick={() => setAssigneesRefresh((value) => value + 1)}>{t("gestion.controlAlerts.retry")}</button>
  </div>;
  function openDetail(alert: ControlAlert, trigger: HTMLElement) {
    trigger.focus({ preventScroll: true });
    setSelectedId(alert.id);
    setDetailOpen(true);
  }
  function closeDetail() {
    setDetailOpen(false);
    setSelectedId((current) => rows.some((row) => row.id === current) ? current : rows[0]?.id ?? null);
  }
  function renderTimelineCell(key: string, alert: ControlAlert) {
    if (key === "time") return <span role="cell" title={formatDateTime(alert.occurredAt, locale, timeZone)}>{view.groupByDay ? new Date(alert.occurredAt).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit", timeZone }) : formatDateTime(alert.occurredAt, locale, timeZone)}</span>;
    if (key === "operation") return <span role="cell" className={`gestion-alert-operation ${alertTypeClass(alert.type)}`}><AlertTypeIcon type={alert.type} /><span><strong>{t(`gestion.controlAlerts.type.${alert.type}`)}</strong><small>{alertSummary(alert, t, locale)}</small></span></span>;
    if (key === "documentUser") return <span role="cell" className="gestion-alert-document-user"><strong>{alert.documentNumber || alertDataText(alert, "documentNumber") || t("gestion.controlAlerts.noDocument")}</strong><small>{alert.userName || t("gestion.controlAlerts.unknownUser")} · {alert.terminalName || alertDataText(alert, "terminalCode") || t("gestion.controlAlerts.unknownTerminal")}</small></span>;
    if (key === "reviewComment") return <span role="cell" className="gestion-alert-review-comment" title={alert.reviewComment || undefined}>{alert.reviewComment || "—"}</span>;
    return <span role="cell"><span className={`gestion-alert-status ${alert.status.toLowerCase()}`}>{t(`gestion.controlAlerts.status.${alert.status}`)}</span></span>;
  }

  return (
    <div className="gestion-control-list-stage">
      <form className="gestion-alert-filters compact" onSubmit={(event) => { event.preventDefault(); setFilters((current) => ({ ...current, search: query, page: 0 })); }}>
        <label><span>{t("gestion.controlAlerts.search")}</span><input value={query} maxLength={160} placeholder={t("gestion.controlAlerts.searchPlaceholder")} onChange={(event) => setQuery(event.target.value)} /></label>
        <label><span>{t("gestion.controlAlerts.filterStatus")}</span><select value={filters.status} onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value as "" | ControlAlertStatus, page: 0 }))}><option value="">{t("gestion.controlAlerts.all")}</option>{controlAlertStatuses.map((status) => <option key={status} value={status}>{t(`gestion.controlAlerts.status.${status}`)}</option>)}</select></label>
        <label><span>{t("gestion.controlAlerts.filterType")}</span><select value={activeType} onChange={(event) => onTypeChange(event.target.value as "" | ControlAlertType)}><option value="">{t("gestion.controlAlerts.all")}</option>{controlAlertTypes.map((type) => <option key={type} value={type}>{t(`gestion.controlAlerts.type.${type}`)}</option>)}</select></label>
        <details className="gestion-alert-extra-filters"><summary>{t("gestion.controlAlerts.moreFilters")}</summary><div>
          <label><span>{t("gestion.controlAlerts.filterPriority")}</span><select value={filters.priority ?? ""} onChange={(event) => setFilters((current) => ({ ...current, priority: event.target.value as "" | ControlAlertPriority, page: 0 }))}><option value="">{t("gestion.controlAlerts.all")}</option>{controlAlertPriorities.map((priority) => <option key={priority} value={priority}>{t(`gestion.controlAlerts.priority.${priority}`)}</option>)}</select></label>
          <label><span>{t("gestion.controlAlerts.filterAssignee")}</span><select disabled={!assigneesLoaded} value={filters.assigneeId ?? ""} onChange={(event) => setFilters((current) => ({ ...current, assigneeId: event.target.value, page: 0 }))}><option value="">{t("gestion.controlAlerts.all")}</option>{assignees.map((assignee) => <option key={assignee.id} value={assignee.id}>{assignee.name} ({assignee.userName})</option>)}</select></label>
          <label className="gestion-alert-overdue-filter"><input type="checkbox" checked={filters.overdue ?? false} onChange={(event) => setFilters((current) => ({ ...current, overdue: event.target.checked, page: 0 }))} /><span>{t("gestion.controlAlerts.filterOverdue")}</span></label>
        </div></details>
        <button type="submit" className="primary">{t("gestion.controlAlerts.apply")}</button>
      </form>
      <ErpFilterChips locale={locale} translate={t} onClear={() => {
        setQuery("");
        setFilters((current) => ({ ...current, search: "", status: "", priority: "", assigneeId: "", overdue: false, page: 0 }));
        onTypeChange("");
        onResetRange();
      }} chips={[
        { key: "period", label: t("gestion.controlAlerts.dateFilter"), value: range.from !== defaultRange.from || range.to !== defaultRange.to ? formatRangeLabel(range, locale) : "", onRemove: onResetRange },
        { key: "search", label: t("gestion.controlAlerts.search"), value: filters.search, onRemove: () => { setQuery(""); setFilters((current) => ({ ...current, search: "", page: 0 })); } },
        { key: "status", label: t("gestion.controlAlerts.filterStatus"), value: filters.status ? t(`gestion.controlAlerts.status.${filters.status}`) : "", onRemove: () => setFilters((current) => ({ ...current, status: "", page: 0 })) },
        { key: "type", label: t("gestion.controlAlerts.filterType"), value: activeType ? t(`gestion.controlAlerts.type.${activeType}`) : "", onRemove: () => onTypeChange("") },
        { key: "priority", label: t("gestion.controlAlerts.filterPriority"), value: filters.priority ? t(`gestion.controlAlerts.priority.${filters.priority}`) : "", onRemove: () => setFilters((current) => ({ ...current, priority: "", page: 0 })) },
        { key: "assignee", label: t("gestion.controlAlerts.filterAssignee"), value: filters.assigneeId ? assignees.find((assignee) => assignee.id === filters.assigneeId)?.name ?? filters.assigneeId : "", onRemove: () => setFilters((current) => ({ ...current, assigneeId: "", page: 0 })) },
        { key: "overdue", label: t("gestion.controlAlerts.filterOverdue"), value: filters.overdue ? t("common.yes") : "", onRemove: () => setFilters((current) => ({ ...current, overdue: false, page: 0 })) }
      ]} />
      {!detailOpen && assigneesErrorMessage}
      <div className="gestion-control-grid without-detail">
        <section className="gestion-alert-list" aria-label={t("gestion.controlAlerts.list")}>
          <header className="gestion-alert-list-heading"><div className="gestion-alert-list-title"><h3>{t("gestion.controlAlerts.chronologyList")}</h3><small id="control-alert-open-hint">{t("gestion.controlAlerts.openDetailHint")}</small></div><div><details className="gestion-alert-columns"><summary aria-label={t("gestion.controlAlerts.columns")}><SlidersHorizontal size={18} /></summary><div>{timelineLayout.layout.map((column) => <label key={column.key}><input type="checkbox" checked={column.visible} disabled={column.visible && timelineColumnsVisible.length === 1} onChange={() => timelineLayout.toggleColumnVisibility(column.key)} />{t(`gestion.controlAlerts.${column.key === "status" ? "filterStatus" : column.key}`)}</label>)}</div></details><select aria-label={t("gestion.controlAlerts.sortOrder")} disabled={preferenceSaving} value={filters.sortDirection} onChange={(event) => onSortChange(event.target.value as "asc" | "desc")}><option value="desc">{t("gestion.controlAlerts.newestFirst")}</option><option value="asc">{t("gestion.controlAlerts.oldestFirst")}</option></select></div></header>
          <div className="gestion-alert-table" role="table" aria-label={t("gestion.controlAlerts.chronologyList")} aria-busy={loading}>
            <div className="gestion-alert-row gestion-alert-row-head" role="row" style={tableStyle}>{timelineColumnsVisible.map((column) => <TableLayoutHeaderCell key={column.key} as="span" column={column} resizeLabel={t("gestion.controlAlerts.resize")} onReorder={timelineLayout.reorderColumns} onMove={timelineLayout.moveColumn} onResize={timelineLayout.resizeColumn}>{t(`gestion.controlAlerts.${column.key === "status" ? "filterStatus" : column.key}`)}</TableLayoutHeaderCell>)}</div>
            {(loadError || staleRows) && <div className="gestion-control-inline-state" role={loadError ? "alert" : "status"}>{t(loadError ? "gestion.controlAlerts.loadError" : "gestion.controlAlerts.previousResults")} {loadError && <button type="button" onClick={onChanged}>{t("gestion.controlAlerts.retry")}</button>}</div>}
            {rows.map((alert, index) => {
              const day = zonedDate(new Date(alert.occurredAt), timeZone);
              const newDay = index === 0 || zonedDate(new Date(rows[index - 1].occurredAt), timeZone) !== day;
              return <Fragment key={alert.id}>
                {view.groupByDay && newDay && <div className="gestion-alert-day" role="row"><span role="cell">{formatDayHeading(alert.occurredAt, t, locale, timeZone)}</span></div>}
                <button type="button" role="row" className={`gestion-alert-row ${selectedId === alert.id ? "selected" : ""}`} aria-selected={selectedId === alert.id} aria-describedby="control-alert-open-hint" aria-haspopup="dialog" data-control-alert-row style={tableStyle}
                  onClick={() => setSelectedId(alert.id)} onDoubleClick={(event) => openDetail(alert, event.currentTarget)} onKeyDown={(event) => {
                    if (event.key === "Enter") { event.preventDefault(); openDetail(alert, event.currentTarget); return; }
                    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
                    event.preventDefault();
                    const next = rows[index + (event.key === "ArrowDown" ? 1 : -1)];
                    if (next) { setSelectedId(next.id); const siblings = event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>("button.gestion-alert-row"); siblings?.[index + (event.key === "ArrowDown" ? 1 : -1)]?.focus(); }
                  }}>
                  {timelineColumnsVisible.map((column) => <Fragment key={column.key}>{renderTimelineCell(column.key, alert)}</Fragment>)}
                </button>
              </Fragment>;
            })}
            {loading && rows.length === 0 && <div className="gestion-alert-list-state">{t("common.loading")}</div>}
            {!loading && !loadError && rows.length === 0 && <div className="gestion-alert-list-state">{t("gestion.controlAlerts.empty")}</div>}
          </div>
          <footer className="gestion-alert-pagination"><span>{t("gestion.controlAlerts.results").replace("{count}", String(totalElements))}{lastUpdated && <small>{t("gestion.controlAlerts.lastUpdated").replace("{time}", lastUpdated.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" }))}</small>}</span><div>
            <button type="button" disabled={filters.page === 0 || loading} onClick={() => setFilters((current) => ({ ...current, page: current.page - 1 }))}>{t("gestion.controlAlerts.previous")}</button>
            <span>{`${Math.min(filters.page + 1, Math.max(totalPages, 1))} / ${Math.max(totalPages, 1)}`}</span>
            <button type="button" disabled={filters.page + 1 >= totalPages || loading} onClick={() => setFilters((current) => ({ ...current, page: current.page + 1 }))}>{t("gestion.controlAlerts.next")}</button>
          </div></footer>
        </section>
      </div>
      {detailOpen && <ControlAlertDialog id="control-alert-detail" title={t("gestion.controlAlerts.detail")} closeLabel={t("common.close")}
        onClose={closeDetail} inactive={Boolean(relatedDocument)} closeDisabled={Boolean(pendingAction) || workSaving || documentLoading}>
          {conflict && <div className="gestion-control-inline-state" role="alert">{t("gestion.controlAlerts.versionConflict")}</div>}
          {detailError && <div className="gestion-control-inline-state error" role="alert">{t("gestion.controlAlerts.detailError")}<button type="button" onClick={() => setDetailRefresh((value) => value + 1)}>{t("gestion.controlAlerts.retry")}</button></div>}
          {assigneesErrorMessage}
          <AlertDetailPanel session={session} t={t} locale={locale} timeZone={timeZone} selected={selected?.id === selectedId ? selected : null} loading={detailLoading && selected?.id !== selectedId} error={false}
            pendingAction={pendingAction} comment={transitionComment} documentLoading={documentLoading} documentError={documentError} assigneesLoaded={assigneesLoaded}
            assignees={assignees} workSaving={workSaving || detailLoading} onCommentChange={setTransitionComment}
            onTransition={(action) => void runTransition(action)} onOpenDocument={() => void openDocument()} onSaveWork={(work) => void saveWork(work)} />
      </ControlAlertDialog>}
      {relatedDocument && <RelatedDocumentDialog document={relatedDocument} t={t} onClose={() => setRelatedDocument(null)} />}
    </div>
  );
}

function formatDayHeading(value: string, t: Translator, locale: LocaleCode, timeZone: string) {
  const date = new Date(value);
  const day = zonedDate(date, timeZone);
  const today = zonedDate(new Date(), timeZone);
  const yesterday = new Date(`${today}T12:00:00Z`);
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  const prefix = day === today ? t("gestion.controlAlerts.today") : day === yesterday.toISOString().slice(0, 10) ? t("gestion.controlAlerts.yesterday") : "";
  return `${prefix ? `${prefix} · ` : ""}${date.toLocaleDateString(locale, { day: "2-digit", month: "2-digit", year: "numeric", timeZone })}`;
}

// Follow the workspace dialog focus/portal pattern, with only the top dialog active.
function ControlAlertDialog({ id, title, closeLabel, children, onClose, inactive = false, closeDisabled = false, className = "" }: {
  id: string; title: string; closeLabel: string; children: ReactNode; onClose: () => void;
  inactive?: boolean; closeDisabled?: boolean; className?: string;
}) {
  const dialogRef = useRef<HTMLElement>(null);
  const stateRef = useRef({ onClose, inactive, closeDisabled });
  stateRef.current = { onClose, inactive, closeDisabled };
  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const dialog = dialogRef.current;
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])') ?? [])
      .filter((element) => !element.closest('[inert], [hidden]') && !Array.from(dialog?.querySelectorAll('details:not([open])') ?? []).some((details) => details.contains(element) && element !== details.querySelector('summary')));
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      const dialogs = document.querySelectorAll('[data-control-dialog]');
      if (stateRef.current.inactive || dialogs[dialogs.length - 1] !== dialog) return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopImmediatePropagation();
        if (!stateRef.current.closeDisabled) stateRef.current.onClose();
      } else if (event.key === "Tab") {
        const controls = focusable();
        const index = controls.indexOf(document.activeElement as HTMLElement);
        event.preventDefault();
        if (controls.length) controls[index < 0 ? (event.shiftKey ? controls.length - 1 : 0) : (index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length].focus();
        else dialog?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    const frame = window.requestAnimationFrame(() => {
      if (stateRef.current.inactive || dialog?.contains(document.activeElement)) return;
      (focusable()[0] ?? dialog)?.focus({ preventScroll: true });
    });
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("keydown", onKeyDown);
      const restore = previousFocus?.isConnected ? previousFocus : document.querySelector<HTMLElement>('[data-control-alert-row][aria-selected="true"]');
      restore?.focus({ preventScroll: true });
    };
  }, []);
  return createPortal(<div className="gestion-modal-backdrop gestion-control-dialog-backdrop gestion-classic-tables erp-classic-tables" role="presentation" onMouseDown={(event) => {
    if (event.target === event.currentTarget && !inactive && !closeDisabled) onClose();
  }}>
    <section ref={dialogRef} className={`gestion-control-dialog gestion-control-dialog-theme ${className}`} role="dialog" aria-modal={!inactive || undefined}
      aria-hidden={inactive || undefined} inert={inactive || undefined} aria-labelledby={`${id}-title`} data-control-dialog tabIndex={-1}>
      <header><h2 id={`${id}-title`}>{title}</h2><button type="button" disabled={closeDisabled} aria-label={closeLabel} onClick={onClose}><X size={20} /></button></header>
      <div className="gestion-control-dialog-body">{children}</div>
    </section>
  </div>, document.body);
}

function AlertDetailPanel({ session, t, locale, timeZone, selected, loading, error, pendingAction, comment, documentLoading, documentError, assignees, assigneesLoaded, workSaving, onCommentChange, onTransition, onOpenDocument, onSaveWork }: {
  session: UserSession;
  t: Translator;
  locale: LocaleCode;
  timeZone: string;
  selected: ControlAlert | null;
  loading: boolean;
  error: boolean;
  pendingAction: ControlAlertTransition | "";
  comment: string;
  documentLoading: boolean;
  documentError: boolean;
  assignees: ControlAlertAssignee[];
  assigneesLoaded: boolean;
  workSaving: boolean;
  onCommentChange: (value: string) => void;
  onTransition: (action: ControlAlertTransition) => void;
  onOpenDocument: () => void;
  onSaveWork: (work: { priority: ControlAlertPriority; assigneeId: string; dueAt: string; comment: string }) => void;
}) {
  const canManage = canManageControlAlerts(session);
  const editable = canManage && selected?.status !== "CLOSED" && selected?.status !== "DISMISSED";
  const [workPriority, setWorkPriority] = useState<ControlAlertPriority>("MEDIUM");
  const [workAssigneeId, setWorkAssigneeId] = useState("");
  const [workDueAt, setWorkDueAt] = useState("");
  const [workComment, setWorkComment] = useState("");

  useEffect(() => {
    setWorkPriority(selected?.priority ?? "MEDIUM");
    setWorkAssigneeId(selected?.assigneeId ?? "");
    setWorkDueAt(toLocalDateTimeInput(selected?.dueAt, timeZone));
    setWorkComment("");
  }, [selected?.assigneeId, selected?.dueAt, selected?.id, selected?.priority, timeZone]);

  const assigneeName = (id?: string | null, name?: string | null) => name || assignees.find((item) => item.id === id)?.name || t(id ? "gestion.controlAlerts.unknownUser" : "gestion.controlAlerts.unassigned");
  const unavailableAssignee = assigneesLoaded && Boolean(workAssigneeId) && !assignees.some((item) => item.id === workAssigneeId);
  const workChanged = Boolean(selected) && (
    workPriority !== selected?.priority
    || workAssigneeId !== (selected?.assigneeId ?? "")
    || workDueAt !== toLocalDateTimeInput(selected?.dueAt, timeZone)
  );
  return (
    <div className="gestion-alert-detail">
      {loading && <div className="gestion-alert-detail-state">{t("common.loading")}</div>}
      {!loading && error && <div className="gestion-alert-detail-state error">{t("gestion.controlAlerts.detailError")}</div>}
      {!loading && !error && !selected && <div className="gestion-alert-detail-state">{t("gestion.controlAlerts.select")}</div>}
      {!loading && selected && (
        <>
          <header>
            <div><span className={`gestion-alert-status ${selected.status.toLowerCase()}`}>{t(`gestion.controlAlerts.status.${selected.status}`)}</span><h3>{t(`gestion.controlAlerts.type.${selected.type}`)}</h3></div>
            <span className="gestion-alert-reference">{selected.documentNumber || alertDataText(selected, "documentNumber") || t("gestion.controlAlerts.noDocument")}</span>
          </header>
          <dl className="gestion-alert-detail-data">
            <div><dt>{t("gestion.controlAlerts.column.occurredAt")}</dt><dd>{formatDateTime(selected.occurredAt, locale, timeZone)}</dd></div>
            <div><dt>{t("gestion.controlAlerts.column.username")}</dt><dd>{selected.userName || t("gestion.controlAlerts.unknownUser")}</dd></div>
            <div><dt>{t("gestion.controlAlerts.column.terminal")}</dt><dd>{selected.terminalName || alertDataText(selected, "terminalCode") || t("gestion.controlAlerts.unknownTerminal")}</dd></div>
            <div><dt>{t("gestion.controlAlerts.column.document")}</dt><dd>{selected.documentNumber || alertDataText(selected, "documentNumber") || t("gestion.controlAlerts.noDocument")}</dd></div>
            {!editable && <><div><dt>{t("gestion.controlAlerts.priorityLabel")}</dt><dd><span className={`gestion-alert-priority ${selected.priority.toLowerCase()}`}>{t(`gestion.controlAlerts.priority.${selected.priority}`)}</span></dd></div>
            <div><dt>{t("gestion.controlAlerts.assigneeLabel")}</dt><dd>{assigneeName(selected.assigneeId, selected.assigneeName)}</dd></div>
            <div><dt>{t("gestion.controlAlerts.dueLabel")}</dt><dd>{selected.dueAt ? formatDateTime(selected.dueAt, locale, timeZone) : t("gestion.controlAlerts.noDueDate")}</dd></div></>}
          </dl>
          {!selected.type.includes("PRICE") && <p className="gestion-alert-detail-summary">{alertSummary(selected, t, locale)}</p>}
          <AlertEvidence alert={selected} t={t} locale={locale} timeZone={timeZone} />
          <p className="gestion-alert-applied-rule"><strong>{t("gestion.controlAlerts.rule")}: </strong>{ruleDisplayName(selected.type, selected.ruleName || selected.type, t)}{selected.ruleVersion != null && ` · ${t("gestion.controlAlerts.ruleVersion")} ${selected.ruleVersion}`}</p>
          {canOpenRelatedSale(session, selected) && <button type="button" className="gestion-alert-document-button" disabled={documentLoading} onClick={onOpenDocument}>{documentLoading ? t("common.loading") : t("gestion.controlAlerts.openDocument")}</button>}
          {documentError && <p className="gestion-inline-error">{t("gestion.controlAlerts.documentError")}</p>}
          {canManage && selected.status !== "CLOSED" && selected.status !== "DISMISSED" && (
            <section className="gestion-alert-work-editor">
              <h4>{t("gestion.controlAlerts.workTitle")}</h4>
              <p className="gestion-alert-work-help">{t("gestion.controlAlerts.workHelp")}</p>
              <div>
                <label><span>{t("gestion.controlAlerts.priorityLabel")}</span><select value={workPriority} onChange={(event) => setWorkPriority(event.target.value as ControlAlertPriority)}>{controlAlertPriorities.map((priority) => <option key={priority} value={priority}>{t(`gestion.controlAlerts.priority.${priority}`)}</option>)}</select></label>
                <label><span>{t("gestion.controlAlerts.assigneeLabel")}</span><select disabled={!assigneesLoaded} value={workAssigneeId} onChange={(event) => setWorkAssigneeId(event.target.value)}><option value="">{t("gestion.controlAlerts.unassigned")}</option>{workAssigneeId && !assignees.some((assignee) => assignee.id === workAssigneeId) && <option value={workAssigneeId} disabled>{assigneeName(workAssigneeId, selected.assigneeName)}</option>}{assignees.map((assignee) => <option key={assignee.id} value={assignee.id}>{assignee.name} ({assignee.userName})</option>)}</select></label>
              </div>
              {unavailableAssignee && <p className="gestion-alert-work-help">{t("gestion.controlAlerts.assigneeUnavailable")}</p>}
              <details className="gestion-alert-work-options"><summary>{t("gestion.controlAlerts.workOptions")}{selected.dueAt ? ` · ${formatDateTime(selected.dueAt, locale, timeZone)}` : ""}</summary>
                <label><span>{t("gestion.controlAlerts.dueLabel")}</span><input type="datetime-local" value={workDueAt} onChange={(event) => setWorkDueAt(event.target.value)} /></label>
                <label><span>{t("gestion.controlAlerts.workComment")}</span><textarea value={workComment} maxLength={500} onChange={(event) => setWorkComment(event.target.value)} /></label>
              </details>
              {(workChanged || workSaving) && <button type="button" className="primary" disabled={workSaving || pendingAction !== "" || !workChanged || !assigneesLoaded || unavailableAssignee} onClick={() => onSaveWork({ priority: workPriority, assigneeId: workAssigneeId, dueAt: workDueAt, comment: workComment })}>{workSaving ? t("common.loading") : t("gestion.controlAlerts.saveWork")}</button>}
            </section>
          )}
          {canManage && (
            <section className="gestion-alert-actions">
              <label><span>{t("gestion.controlAlerts.actionComment")}</span><textarea value={comment} maxLength={500} onChange={(event) => onCommentChange(event.target.value)} /></label>
              <div>
                {selected.status !== "NEW" && <button type="button" disabled={pendingAction !== "" || workSaving} onClick={() => onTransition("REOPEN")}>{t("gestion.controlAlerts.action.REOPEN")}</button>}
                <button type="button" disabled={pendingAction !== "" || workSaving || selected.status !== "NEW"} onClick={() => onTransition("REVIEW")}>{t("gestion.controlAlerts.action.REVIEW")}</button>
                <button type="button" disabled={pendingAction !== "" || workSaving || selected.status === "CLOSED" || selected.status === "DISMISSED"} onClick={() => onTransition("CLOSE")}>{t("gestion.controlAlerts.action.CLOSE")}</button>
                <button type="button" disabled={pendingAction !== "" || workSaving || selected.status === "CLOSED" || selected.status === "DISMISSED"} onClick={() => onTransition("DISMISS")}>{t("gestion.controlAlerts.action.DISMISS")}</button>
              </div>
            </section>
          )}
          <section className="gestion-alert-generated"><h4>{t("gestion.controlAlerts.history")}</h4><p>{formatDateTime(selected.occurredAt, locale, timeZone)} · {t("gestion.controlAlerts.generated")}</p></section>
          {selected.history && selected.history.length > 0 && (
            <section className="gestion-alert-history">
              <ol>{selected.history.map((entry, index) => (
                <li key={`${entry.changedAt}:${index}`}><span>{formatDateTime(entry.changedAt, locale, timeZone)}</span><strong>{t(entry.newStatus === "NEW" && entry.previousStatus ? "gestion.controlAlerts.reopened" : `gestion.controlAlerts.status.${entry.newStatus}`)}</strong><small>{entry.changedByName || t("gestion.controlAlerts.unknownUser")}</small>{entry.comment && <p>{entry.comment}</p>}</li>
              ))}</ol>
            </section>
          )}
          {selected.workHistory && selected.workHistory.length > 0 && (
            <section className="gestion-alert-history gestion-alert-work-history">
              <h4>{t("gestion.controlAlerts.workHistory")}</h4>
              <ol>{selected.workHistory.map((entry, index) => (
                <li key={`${entry.changedAt}:${index}`}><span>{formatDateTime(entry.changedAt, locale, timeZone)}</span><strong>{t(`gestion.controlAlerts.priority.${entry.newPriority}`)}</strong><small>{entry.changedByName || t("gestion.controlAlerts.unknownUser")}</small><p>{t("gestion.controlAlerts.assigneeLabel")}: {assigneeName(entry.newAssigneeId, entry.newAssigneeName)}</p>{entry.comment && <p>{entry.comment}</p>}</li>
              ))}</ol>
            </section>
          )}

        </>
      )}
    </div>
  );
}

function priceVariation(original: unknown, applied: unknown, currency: string | undefined, locale: LocaleCode) {
  const difference = Number(applied) - Number(original);
  if (!Number.isFinite(difference)) return "—";
  return new Intl.NumberFormat(locale, { ...(currency ? { style: "currency", currency } : {}), maximumFractionDigits: 3, signDisplay: "exceptZero" }).format(difference);
}

function AlertEvidence({ alert, t, locale, timeZone }: { alert: ControlAlert; t: Translator; locale: LocaleCode; timeZone?: string }) {
  const data = alert.data ?? {};
  const currency = typeof data.currency === "string" ? data.currency : undefined;
  const number = (value: unknown) => {
    if (value === null || value === undefined || value === "") return "—";
    const parsed = Number(value);
    return Number.isFinite(parsed) ? new Intl.NumberFormat(locale, { maximumFractionDigits: 6 }).format(parsed) : "—";
  };
  const amount = (value: unknown) => currency && value != null && Number.isFinite(Number(value))
    ? new Intl.NumberFormat(locale, { style: "currency", currency, maximumFractionDigits: 3 }).format(Number(value)) : number(value);
  const changed = Array.isArray(data.changedLines) ? data.changedLines.filter(isRecord) : [];
  const details = [data.discountedLines, data.matchingLines, data.lines, data.products, data.negativeLines]
    .find((value) => Array.isArray(value) && value.length > 0);
  const scalarKeys = ["globalDiscountPercent", "thresholdPercent", "total", "lineCount", "minimumCount", "deletionCount", "expectedCash", "declaredFund", "discrepancy", "tolerance", "authorizerName"];
  const scalars = scalarKeys.filter((key) => data[key] != null);
  if (changed.length === 0 && !details && scalars.length === 0) return null;
  return <section className="gestion-alert-evidence" aria-label={t("gestion.controlAlerts.evidence")}>
    {changed.map((line, index) => <article className="gestion-alert-price-evidence" key={String(line.position ?? index)}>
      <h4>{t("gestion.controlAlerts.priceModified")}</h4>
      <p>{t("gestion.controlAlerts.line")} {number(line.position)}{typeof (line.name || line.productName) === "string" ? ` · ${line.name || line.productName}` : ""}{typeof line.code === "string" && line.code ? ` · ${line.code}` : ""}</p>
      <div><span>{t("gestion.controlAlerts.originalPrice")}<strong>{amount(line.originalPrice)}</strong></span><ArrowRight size={28} aria-hidden="true" /><span>{t("gestion.controlAlerts.appliedPrice")}<strong>{amount(line.appliedPrice)}</strong></span></div>
      <p>{t("gestion.controlAlerts.priceReduction")} <strong>{priceVariation(line.originalPrice, line.appliedPrice, currency, locale)} · {new Intl.NumberFormat(locale, { maximumFractionDigits: 3, signDisplay: "exceptZero" }).format(-Number(line.changePercent))} %</strong></p>
    </article>)}
    {scalars.length > 0 && <dl className="gestion-alert-evidence-values">{scalars.map((key) => <div key={key}><dt>{t(`gestion.controlAlerts.evidence.${key}`)}</dt><dd>{key === "authorizerName" ? String(data[key]) : number(data[key])}{key.endsWith("Percent") ? " %" : ""}</dd></div>)}</dl>}
    {Array.isArray(details) && <ul className="gestion-alert-evidence-lines">{details.filter(isRecord).map((line, index) => <li key={String(line.position ?? line.productId ?? index)}>
      <strong>{String(line.name || line.productName || line.code || `${t("gestion.controlAlerts.line")} ${line.position ?? index + 1}`)}</strong>
      {typeof line.code === "string" && line.code && <small>{t("gestion.controlAlerts.productReference")}: {line.code}</small>}
      {line.discountPercent != null && <span>{t("gestion.controlDocument.discount")}: {number(line.discountPercent)} %</span>}
      {line.quantity != null && <span>{t("gestion.controlDocument.quantity")}: {number(line.quantity)}</span>}
      {line.total != null && <span>{t("gestion.controlDocument.total")}: {amount(line.total)}</span>}
      {(["deletedAt", "receivedAt"] as const).map((key) => typeof line[key] === "string" && Number.isFinite(Date.parse(line[key]))
        ? <span key={key}>{t(`gestion.controlAlerts.evidence.${key}`)}: <time dateTime={line[key]}>{formatDateTime(line[key], locale, timeZone)}</time></span>
        : null)}
    </li>)}</ul>}
  </section>;
}

function RuleConfigurationDialog({ token, t, initialType, catalog, rules, onClose, onSaved }: {
  token?: string;
  t: Translator;
  initialType: ControlAlertType | null;
  catalog: ControlRuleCatalogItem[];
  rules: ControlRule[];
  onClose: () => void;
  onSaved: () => void | Promise<void>;
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
    if (!validRuleDraft(item, draft)) { setError(true); return; }
    setSaving(true);
    setError(false);
    try {
      await saveControlRule(draft, existing, token);
      await onSaved();
    } catch {
      setError(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="gestion-modal-backdrop" role="presentation">
      <section className={`gestion-rules-dialog compact ${!item && absent.length === 0 ? "empty" : ""}`} role="dialog" aria-modal="true" aria-labelledby="control-rules-title">
        <header><h2 id="control-rules-title">{existing ? t("gestion.controlRules.edit") : t("gestion.controlRules.add")}</h2><button type="button" aria-label={t("common.close")} onClick={onClose}>×</button></header>
        {!item ? (
          <div className="gestion-rule-catalog-picker">
            {absent.length === 0 ? (
              <div className="gestion-rule-catalog-empty">
                <p>{t("gestion.controlRules.allConfigured")}</p>
                <button type="button" onClick={onClose}>{t("common.close")}</button>
              </div>
            ) : (
              <>
                <p>{t("gestion.controlRules.addDescription")}</p>
                <div>
                  {absent.map((entry) => (
                    <button type="button" key={entry.type} disabled={!entry.supported} onClick={() => choose(entry)}>
                      <strong className="gestion-rule-name-with-icon"><AlertTypeIcon type={entry.type} size={22} />{ruleDisplayName(entry.type, entry.name, t)}</strong>
                      <span>{entry.supported ? ruleParameterText(entry.parameterKind, entry.defaultConfiguration, t) || t("gestion.controlRules.noParameter") : t("gestion.controlRules.unavailable")}</span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        ) : (
          <form className="gestion-rule-form" onSubmit={submit}>
            <div className="gestion-rule-system-name"><span>{t("gestion.controlRules.systemRule")}</span><strong className="gestion-rule-name-with-icon"><AlertTypeIcon type={item.type} size={25} />{ruleDisplayName(item.type, item.name, t)}</strong><small>{t("gestion.controlRules.systemNameLocked")}</small></div>
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
    <ControlAlertDialog id="related-document" title={`${localizedDocumentType(document.type, t)} · ${document.number}`} closeLabel={t("common.close")} onClose={onClose} className="gestion-control-document-modal">
      <div className="gestion-document-dialog">
        <dl><div><dt>{t("gestion.controlDocument.status")}</dt><dd>{localizedDocumentStatus(document.status, t)}</dd></div><div><dt>{t("gestion.controlDocument.date")}</dt><dd>{formatDocumentDate(document.date)}</dd></div><div><dt>{t("gestion.controlDocument.customer")}</dt><dd>{document.customerName || t("gestion.controlAlerts.unknownCustomer")}</dd></div></dl>
        <div className="gestion-document-lines"><table><thead><tr><th>{t("gestion.controlDocument.product")}</th><th>{t("gestion.controlDocument.quantity")}</th><th>{t("gestion.controlDocument.price")}</th><th>{t("gestion.controlDocument.discount")}</th><th>{t("gestion.controlDocument.total")}</th></tr></thead><tbody>{document.lines.map((line) => <tr key={`${line.position}:${line.productId ?? line.code ?? line.name}`}><td>{line.name}</td><td>{formatNumber(line.quantity)}</td><td>{formatCurrency(line.unitPrice, document.currency)}</td><td>{`${formatNumber(line.discount)} %`}</td><td>{formatCurrency(line.total, document.currency)}</td></tr>)}</tbody></table></div>
        <div className="gestion-document-bottom"><section><h3>{t("gestion.controlDocument.payments")}</h3>{document.payments.length === 0 ? <p>—</p> : document.payments.map((payment) => <p key={`${payment.position}:${payment.paymentMethodId ?? payment.paymentMethod}`}><span>{payment.paymentMethod}</span><strong>{formatCurrency(payment.amount, document.currency)}</strong></p>)}</section><dl><div><dt>{t("gestion.controlDocument.subtotal")}</dt><dd>{formatCurrency(document.baseTotal, document.currency)}</dd></div><div><dt>{t("gestion.controlDocument.discount")}</dt><dd>{`${formatNumber(document.globalDiscount)} %`}</dd></div><div><dt>{t("gestion.controlDocument.tax")}</dt><dd>{formatCurrency(document.taxTotal, document.currency)}</dd></div><div className="total"><dt>{t("gestion.controlDocument.total")}</dt><dd>{formatCurrency(document.total, document.currency)}</dd></div></dl></div>
      </div>
    </ControlAlertDialog>
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

export function validRuleDraft(item: ControlRuleCatalogItem, draft: ControlRuleDraft) {
  if (item.parameterKind === "NONE") return Object.keys(draft.configuration).length === 0;
  const key = item.parameterKind === "QUANTITY" ? "minimumCount" : "thresholdPercent";
  const raw = draft.configuration[key];
  if (raw == null || raw === "" || typeof raw === "boolean") return false;
  const value = Number(raw);
  if (!Number.isFinite(value)) return false;
  if (item.parameterKind === "QUANTITY") return Number.isInteger(value) && value >= 2 && value <= 999;
  return value >= 0 && value <= 100 && /^\d+(?:\.\d{1,2})?$/.test(String(value));
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

export function dateRangeToInstants(range: DateRange, timeZone?: string) {
  const nextDay = new Date(`${range.to}T12:00:00Z`);
  nextDay.setUTCDate(nextDay.getUTCDate() + 1);
  const endDate = nextDay.toISOString().slice(0, 10);
  return { from: zonedMidnight(range.from, timeZone), to: zonedMidnight(endDate, timeZone) };
}

function zonedMidnight(value: string, timeZone?: string) {
  return zonedLocalInstant(`${value}T00:00`, timeZone);
}

export function controlAlertDueAtToInstant(value: string, timeZone: string) {
  const instant = zonedLocalInstant(value, timeZone);
  if (toLocalDateTimeInput(instant, timeZone) !== value.slice(0, 16)) throw new RangeError("invalid_store_local_time");
  return instant;
}

function zonedLocalInstant(value: string, timeZone?: string) {
  if (!timeZone) return new Date(value).toISOString();
  const [date, time] = value.split("T");
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  const target = Date.UTC(year, month - 1, day, hour, minute);
  let instant = target;
  const formatter = new Intl.DateTimeFormat("en-GB", { timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
  for (let index = 0; index < 3; index += 1) {
    const parts = Object.fromEntries(formatter.formatToParts(new Date(instant)).map((part) => [part.type, part.value]));
    const local = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour), Number(parts.minute), Number(parts.second));
    instant += target - local;
  }
  return new Date(instant).toISOString();
}

function zonedDate(value: Date, timeZone?: string) {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(value);
  const get = (name: string) => parts.find((part) => part.type === name)?.value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}

function rangeForPeriod(period: ControlAlertViewPreference["defaultPeriod"], timeZone?: string): DateRange {
  const to = zonedDate(new Date(), timeZone);
  if (period === "TODAY") return { from: to, to };
  if (period === "CURRENT_MONTH") return { from: `${to.slice(0, 7)}-01`, to };
  const from = new Date(`${to}T12:00:00Z`);
  from.setUTCDate(from.getUTCDate() - 6);
  return { from: from.toISOString().slice(0, 10), to };
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

function formatRangeLabel(range: DateRange, locale: LocaleCode = "es") {
  const from = new Date(`${range.from}T00:00:00`);
  const to = new Date(`${range.to}T00:00:00`);
  const formatter = new Intl.DateTimeFormat(locale, { day: "2-digit", month: "short", year: "numeric" });
  return range.from === range.to ? formatter.format(from) : `${formatter.format(from)} — ${formatter.format(to)}`;
}

function formatDateTime(value: string, locale: LocaleCode = "es", timeZone?: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "medium", timeZone }).format(date);
}

function toLocalDateTimeInput(value?: string | null, timeZone?: string) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  if (timeZone) {
    const parts = new Intl.DateTimeFormat("en-GB", { timeZone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(date);
    const get = (type: string) => parts.find((part) => part.type === type)?.value;
    return `${zonedDate(date, timeZone)}T${get("hour")}:${get("minute")}`;
  }
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function alertDataText(alert: ControlAlert, key: string): string {
  const value = alert.data?.[key];
  return typeof value === "string" || typeof value === "number" ? String(value) : "";
}

function alertSummary(alert: ControlAlert, t: Translator, locale: LocaleCode = "es"): string {
  const data = alert.data ?? {};
  const number = (value: unknown) => formatUnknownNumber(value, locale);
  if (alert.type === "MANUAL_DISCOUNT_OVER_PERCENT") {
    return interpolate(t("gestion.controlAlerts.summaryManualDiscount"), { threshold: number(data.thresholdPercent), global: number(data.globalDiscountPercent), lines: String(Array.isArray(data.matchingLines) ? data.matchingLines.length : 0) });
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
    return interpolate(t("gestion.controlAlerts.summaryConsecutiveDeletions"), { count: number(data.deletionCount ?? data.lineCount) });
  }
  if (alert.type === "PRODUCT_DISCOUNT_APPLIED") {
    return interpolate(t("gestion.controlAlerts.summaryProductDiscount"), {
      count: number(Array.isArray(data.discountedLines) ? data.discountedLines.length : undefined)
    });
  }
  if (alert.type === "MANUAL_PRICE_CHANGED" || alert.type === "MANUAL_PRICE_CHANGE_OVER_PERCENT") {
    if (Array.isArray(data.changedLines) && data.changedLines.length === 1 && isRecord(data.changedLines[0])) {
      const line = data.changedLines[0];
      if (line.originalPrice != null && line.appliedPrice != null && Number.isFinite(Number(line.originalPrice)) && Number.isFinite(Number(line.appliedPrice))) {
        const formatter = new Intl.NumberFormat(locale, { ...(typeof data.currency === "string" ? { style: "currency", currency: data.currency } : {}), maximumFractionDigits: 3 });
        return `${formatter.format(Number(line.originalPrice))} → ${formatter.format(Number(line.appliedPrice))}`;
      }
    }
    return interpolate(t("gestion.controlAlerts.summaryPriceChanges"), { count: String(Array.isArray(data.changedLines) ? data.changedLines.length : 0) });
  }
  if (alert.type === "MANUAL_NEGATIVE_QUANTITY") {
    return interpolate(t("gestion.controlAlerts.summaryManualNegative"), {
      count: String(Array.isArray(data.negativeLines) ? data.negativeLines.length : 0)
    });
  }
  if (alert.type === "REFUND_POLICY_OVERRIDE") {
    return interpolate(t("gestion.controlAlerts.summaryRefundPolicyOverride"), {
      amount: alertDataText(alert, "amount") || "—",
      method: alertDataText(alert, "method") || "—",
      authorizer: alertDataText(alert, "authorizerName") || "—"
    });
  }
  if (alert.type === "CASH_DRAWER_OPENED") {
    return interpolate(t("gestion.controlAlerts.summaryCashDrawer"), {
      authorizer: alertDataText(alert, "authorizerName") || "—"
    });
  }
  if (alert.type === "CASH_SESSION_DISCREPANCY") return interpolate(t("gestion.controlAlerts.summaryCashDiscrepancy"), { amount: number(data.discrepancy) });
  if (alert.type === "PRODUCT_CATALOG_MODIFIED") {
    return interpolate(t("gestion.controlAlerts.summaryProductModified"), {
      product: alertDataText(alert, "productName") || alertDataText(alert, "productCode") || "—",
      authorizer: alertDataText(alert, "authorizerName") || "—"
    });
  }
  if (alert.type === "PARKED_SALE_DELETED") {
    return interpolate(t("gestion.controlAlerts.summaryParkedSaleDeleted"), {
      count: number(data.deletedCount),
      authorizer: alertDataText(alert, "authorizerName") || alert.userName || "—",
    });
  }
  const lines = Array.isArray(data.lines) ? data.lines : [];
  return interpolate(t("gestion.controlAlerts.summarySaleCleared"), { count: String(typeof data.lineCount === "number" ? data.lineCount : lines.length), total: number(data.total) });
}

function interpolate(template: string, values: Record<string, string>): string {
  return Object.entries(values).reduce((result, [key, value]) => result.replaceAll(`{${key}}`, value), template);
}

function formatUnknownNumber(value: unknown, locale: LocaleCode = "es"): string {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }).format(number) : "—";
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

function formatDocumentDate(value: string) {
  const date = new Date(`${value.slice(0, 10)}T00:00:00`);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("es-ES", { dateStyle: "short" }).format(date);
}

function localizedDocumentType(type: string, t: Translator) {
  const key = `salesReport.activity.documentType.${type.trim().toLocaleUpperCase()}`;
  const translated = t(key);
  return translated === key ? type : translated;
}

function localizedDocumentStatus(status: string, t: Translator) {
  const statusKeys: Record<string, string> = {
    BORRADOR: "salesReport.status.draft",
    PENDIENTE: "salesReport.status.pending",
    PARCIAL: "salesReport.status.partial",
    CONFIRMADA: "salesReport.status.confirmed",
    CONFIRMADO: "salesReport.status.confirmed",
    ANULADA: "salesReport.status.cancelled",
    ANULADO: "salesReport.status.cancelled",
    FACTURADA: "salesReport.status.invoiced",
    FACTURADO: "salesReport.status.invoiced",
    PAGADA: "salesReport.status.paid",
    PAGADO: "salesReport.status.paid"
  };
  const key = statusKeys[status.trim().toLocaleUpperCase()];
  return key ? t(key) : status;
}
