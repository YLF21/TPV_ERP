import { useEffect, useRef, useState } from "react";
import { ArrowClockwise, ArrowDown, ArrowUp, ChartBar, ChartLine, Check, FloppyDisk, Info, SlidersHorizontal, Table, User } from "@phosphor-icons/react";
import { type LocaleCode, type UserSession } from "@tpverp/app-common";
import { ErpFilterChips, type ErpFilterChip } from "../../../packages/app-common/src/components/ErpFilterChips";
import {
  dashboardPeriodRange, dashboardPreset, dashboardWidgetDefaults, defaultDashboardOptions,
  loadActivePromotions, loadControlAlertsSummary, loadDashboardPreference, loadDashboardWarehouses, loadSalesOverview, loadDashboardReceivables,
  loadSalesToday, loadTopProducts, moveDashboardWidget, reorderDashboardWidgets, saveDashboardPreference, validDashboardRange,
  type ActivePromotionData, type ControlAlertsSummaryData, type DashboardDateRange, type DashboardOptions,
  type DashboardPreference, type DashboardPreset, type DashboardScope, type DashboardWarehouse, type DashboardWidgetKey,
  type DashboardWidgetLayout, type SalesOverviewData, type SalesOverviewScope, type SalesTodayData, type TopProductData, type ReceivablesSummary, type HourlySalesScope, type HourlySalesData
} from "./dashboardModel";
import { ActivePromotionsWidget, ControlAlertsWidget, DashboardWidgetFrame, formatDashboardRange, SalesMetricWidget, SalesTrendWidget, TopProductsWidget, type DashboardDataState } from "./GestionDashboardWidgets";
import { FamilySalesWidget } from "./GestionDashboardAnalysis";
import { CorrectionsWidget, PaymentsWidget, ReceivablesWidget } from "./GestionDashboardActivity";
import { HourlySalesWidget } from "./HourlySalesWidget";
import "./gestion-dashboard.css";

type Translator = (key: string) => string;
type GestionDashboardProps = {
  session: UserSession; locale?: LocaleCode; t: Translator; onOpenSales: () => void; onOpenStock: () => void;
  onOpenPromotions: () => void; onOpenControlAlerts: () => void; dataSource?: DashboardDataSource;
};
export type DashboardDataSource = {
  loadPreference: (token?: string) => Promise<DashboardPreference>;
  savePreference: (widgets: DashboardWidgetLayout[], token?: string, options?: DashboardOptions) => Promise<DashboardPreference>;
  loadSalesOverview: (token: string | undefined, scope: SalesOverviewScope, signal?: AbortSignal) => Promise<SalesOverviewData>;
  loadSalesToday: (token?: string, scope?: DashboardScope) => Promise<SalesTodayData>;
  loadTopProducts: (token?: string, scope?: DashboardScope) => Promise<TopProductData[]>;
  loadActivePromotions: (token?: string, scope?: DashboardScope) => Promise<ActivePromotionData[]>;
  loadControlAlertsSummary: (token?: string) => Promise<ControlAlertsSummaryData>;
  loadWarehouses: (token?: string) => Promise<DashboardWarehouse[]>;
  loadReceivables?: (token?: string, warehouseId?: string, signal?: AbortSignal) => Promise<ReceivablesSummary>;
  loadHourlySales?: (token: string | undefined, scope: HourlySalesScope, signal?: AbortSignal) => Promise<HourlySalesData>;
};
const defaultDataSource: DashboardDataSource = {
  loadPreference: loadDashboardPreference, savePreference: saveDashboardPreference, loadSalesOverview, loadSalesToday,
  loadTopProducts, loadActivePromotions, loadControlAlertsSummary, loadWarehouses: loadDashboardWarehouses, loadReceivables: loadDashboardReceivables
};
const periods: DashboardOptions["defaultPeriod"][] = ["TODAY", "LAST_7_DAYS", "LAST_30_DAYS", "MONTH"];

export function GestionDashboard(props: GestionDashboardProps) {
  return <DashboardWorkspace key={`${props.session.username}:${props.session.accessToken}`} {...props} />;
}

function DashboardWorkspace({ session, locale = "es", t, onOpenSales, onOpenStock, onOpenPromotions, onOpenControlAlerts, dataSource = defaultDataSource }: GestionDashboardProps) {
  const [preference, setPreference] = useState<DashboardPreference>();
  const [draft, setDraft] = useState<{ widgets: DashboardWidgetLayout[]; options: DashboardOptions }>();
  const [preferenceLoading, setPreferenceLoading] = useState(true);
  const [preferenceError, setPreferenceError] = useState(false);
  const [preferenceRetry, setPreferenceRetry] = useState(0);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [viewOptions, setViewOptions] = useState<DashboardOptions>();
  const savingRef = useRef(false);
  const [selectedKey, setSelectedKey] = useState<DashboardWidgetKey>("sales.trend");
  const [draggedKey, setDraggedKey] = useState<DashboardWidgetKey | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [range, setRange] = useState<DashboardDateRange>({ from: "", to: "" });
  const [rangeDraft, setRangeDraft] = useState(range);
  const [period, setPeriod] = useState<DashboardOptions["defaultPeriod"] | "CUSTOM">("MONTH");
  const [appliedPeriod, setAppliedPeriod] = useState<DashboardOptions["defaultPeriod"] | "CUSTOM">("MONTH");
  const [rangeError, setRangeError] = useState(false);
  const [warehouseId, setWarehouseId] = useState("");
  const [warehouses, setWarehouses] = useState<DashboardWarehouse[]>([]);
  const [warehouseError, setWarehouseError] = useState(false);
  const periodRef = useRef<HTMLSelectElement>(null);
  const widgets = draft?.widgets ?? preference?.widgets ?? [];
  const options = draft?.options ?? viewOptions ?? preference?.options ?? defaultDashboardOptions;
  const availableWidgets = preference?.availableWidgets ?? [];
  const customizing = !!draft;
  const saving = saveState === "saving";
  const selected = widgets.find((widget) => widget.key === selectedKey);
  const hasSales = widgets.some((widget) => widget.key.startsWith("sales."));
  const hasReceivables = widgets.some(widget => widget.key === "finance.receivables");
  const validRange = validDashboardRange(range);
  const scopeKey = `${range.from}/${range.to}/${warehouseId}`;
  const sales = useDashboardData((signal) => dataSource.loadSalesOverview(session.accessToken, { ...range, warehouseId: warehouseId || undefined }, signal),
    [dataSource, session.accessToken, scopeKey, refresh], !!preference && validRange && hasSales, scopeKey);
  const promotions = useDashboardData(() => dataSource.loadActivePromotions(session.accessToken, { date: range.to }),
    [dataSource, session.accessToken, range.to, refresh], !!preference && validRange && widgets.some((widget) => widget.key === "promotions.active"), range.to);
  const alerts = useDashboardData(() => dataSource.loadControlAlertsSummary(session.accessToken),
    [dataSource, session.accessToken, refresh], !!preference && widgets.some((widget) => widget.key === "control.alerts"), "alerts");
  const receivables = useDashboardData(signal => (dataSource.loadReceivables ?? loadDashboardReceivables)(session.accessToken, warehouseId || undefined, signal),
    [dataSource, session.accessToken, warehouseId, refresh], !!preference && hasReceivables, warehouseId);

  useEffect(() => {
    let active = true;
    setPreferenceLoading(true); setPreferenceError(false);
    void dataSource.loadPreference(session.accessToken).then((value) => {
      if (!active) return;
      const normalized = { ...value, options: { ...defaultDashboardOptions, ...value.options } };
      setPreference(normalized);
      // Older preview sources may omit store context. The real API always supplies it.
      const businessDate = value.businessDate ?? storeBusinessDate(value.storeTimezone);
      const nextRange = dashboardPeriodRange(normalized.options.defaultPeriod, businessDate);
      setRange(nextRange); setRangeDraft(nextRange); setPeriod(normalized.options.defaultPeriod);
      setAppliedPeriod(normalized.options.defaultPeriod);
    }).catch(() => { if (active) setPreferenceError(true); })
      .finally(() => { if (active) setPreferenceLoading(false); });
    return () => { active = false; };
  }, [dataSource, session.accessToken, preferenceRetry]);

  useEffect(() => {
    if (!preference || !availableWidgets.some((key) => key.startsWith("sales."))) return;
    let active = true;
    setWarehouseError(false);
    void dataSource.loadWarehouses(session.accessToken).then((values) => {
      if (active) setWarehouses(values.filter((warehouse) => warehouse.active));
    }).catch(() => { if (active) setWarehouseError(true); });
    return () => { active = false; };
  }, [dataSource, session.accessToken, preference, refresh]);

  const updateDraft = (nextWidgets: DashboardWidgetLayout[]) => {
    if (!draft || saving) return;
    setDraft({ ...draft, widgets: nextWidgets }); setSaveState("idle");
  };
  const saveViewOptions = async (next: DashboardOptions) => {
    if (!preference || savingRef.current) return;
    savingRef.current = true; setViewOptions(next); setSaveState("saving");
    try {
      const saved = await dataSource.savePreference(preference.widgets, session.accessToken, next);
      setPreference({ ...preference, ...saved, options: { ...defaultDashboardOptions, ...(saved.options ?? next) } });
      setViewOptions(undefined); setSaveState("saved");
    } catch { setSaveState("error"); }
    finally { savingRef.current = false; }
  };
  const updateOptions = (patch: Partial<DashboardOptions>) => {
    if (savingRef.current || saving) return;
    if (draft) { setDraft({ ...draft, options: { ...draft.options, ...patch } }); setSaveState("idle"); }
    else void saveViewOptions({ ...options, ...patch });
  };
  const openCustomization = () => {
    if (!preference || savingRef.current) return;
    setDraft({ widgets: preference.widgets.map((widget) => ({ ...widget })), options: { ...options } });
    setViewOptions(undefined);
    setSelectedKey(preference.widgets.some((widget) => widget.key === "sales.trend") ? "sales.trend" : preference.widgets[0]?.key ?? "sales.today");
    setSaveState("idle");
  };
  const save = async () => {
    if (!draft || savingRef.current || saving || !preference || preferenceError) return;
    savingRef.current = true;
    setSaveState("saving");
    try {
      const saved = await dataSource.savePreference(draft.widgets, session.accessToken, draft.options);
      setPreference({ ...preference, ...saved, options: saved.options ?? draft.options });
      setDraft(undefined); setSaveState("saved");
    } catch { setSaveState("error"); }
    finally { savingRef.current = false; }
  };
  const applyPeriod = (next: DashboardOptions["defaultPeriod"] | "CUSTOM") => {
    setPeriod(next);
    if (next === "CUSTOM") return;
    const nextRange = dashboardPeriodRange(next, storeBusinessDate(preference?.storeTimezone));
    setRange(nextRange); setRangeDraft(nextRange); setRangeError(false);
    setAppliedPeriod(next);
  };
  const refreshData = () => {
    // Relative periods follow the current store day, even when the workspace stays open overnight.
    if (period !== "CUSTOM") applyPeriod(period);
    setRefresh((value) => value + 1);
  };
  const selectPreset = (preset: DashboardPreset) => {
    updateDraft(dashboardPreset(preset, availableWidgets));
    setSelectedKey(preset === "PRODUCTS" ? "sales.top-products" : "sales.trend");
  };
  const showDataError = sales.error || promotions.error || alerts.error || receivables.error;
  const receivedTimes = [sales, promotions, alerts, receivables].filter(state => state.data && state.updatedAt).map(state => state.updatedAt!);
  const updatedAt = receivedTimes.length ? Math.min(...receivedTimes) : undefined;
  const displayKeys: Partial<Record<DashboardWidgetKey, "trendDisplay" | "familyDisplay" | "productDisplay" | "alertDisplay" | "promotionDisplay" | "hourlyDisplay" | "correctionDisplay" | "paymentDisplay" | "receivableDisplay">> = {
    "sales.trend": "trendDisplay", "sales.families": "familyDisplay", "sales.top-products": "productDisplay",
    "control.alerts": "alertDisplay", "promotions.active": "promotionDisplay", "sales.hourly": "hourlyDisplay",
    "sales.corrections": "correctionDisplay", "sales.payments": "paymentDisplay", "finance.receivables": "receivableDisplay"
  };
  const viewControls = (key: DashboardWidgetKey) => {
    const option = displayKeys[key];
    if (!option) return undefined;
    const choices = key === "sales.trend" ? ["LINE", "BAR", "TABLE"] as const : ["BAR", "TABLE"] as const;
    return <>
      <div className="gd-segmented gd-view-switch" role="group" aria-label={`${t("gestion.dashboard.visualization")} ${t(`gestion.widget.${key}`)}`}>
        {choices.map(display => <button type="button" key={display} disabled={saving}
          aria-pressed={(options[option] ?? defaultDashboardOptions[option]) === display} onClick={() => updateOptions({ [option]: display })}>
          {display === "LINE" ? <ChartLine size={14} /> : display === "BAR" ? <ChartBar size={14} /> : <Table size={14} />}{t(key === "sales.hourly" && display === "BAR" ? "gestion.dashboard.hourlyGraph" : `gestion.dashboard.display.${display}`)}</button>)}
      </div>
      {key === "sales.top-products" && <label className="gd-ranking-sort"><span>{t("gestion.dashboard.sortBy")}</span>
        <select aria-label={t("gestion.dashboard.sortBy")} disabled={saving} value={options.productSort ?? "QUANTITY"}
          onChange={event => updateOptions({ productSort: event.target.value as "AMOUNT" | "QUANTITY" })}>
          <option value="QUANTITY">{t("gestion.dashboard.netUnits")}</option><option value="AMOUNT">{t("gestion.widget.sales.today")}</option>
        </select></label>}
    </>;
  };
  const defaultPeriod = preference?.options.defaultPeriod ?? defaultDashboardOptions.defaultPeriod;
  const filterChips: ErpFilterChip[] = [];
  if (validRange && appliedPeriod !== defaultPeriod) {
    filterChips.push({ key: "period", label: t("gestion.dashboard.period"),
      value: formatDashboardRange(range.from, range.to, locale), onRemove: () => applyPeriod(defaultPeriod) });
  }
  if (warehouseId) filterChips.push({ key: "warehouse", label: t("gestion.dashboard.warehouse"),
    value: warehouses.find(warehouse => warehouse.id === warehouseId)?.name ?? warehouseId,
    onRemove: () => setWarehouseId("") });
  return <section className={`gestion-workspace gd-workspace ${customizing ? "is-customizing" : ""} ${options.density === "COMPACT" ? "is-compact" : ""}`}>
    <header className="gd-toolbar"><div><h2>{t(customizing ? "gestion.dashboard.customizeTitle" : "gestion.dashboard")}</h2>
      <p>{t(customizing ? "gestion.dashboard.customizeSubtitle" : "gestion.dashboard.subtitle")}</p></div>
      <div className="gd-toolbar-actions">
        {saveState === "saved" && <span className="gd-saved" role="status"><Check size={16} aria-hidden="true" />{t("gestion.dashboard.saved")}</span>}
        <button type="button" onClick={refreshData} disabled={preferenceLoading || saving}><ArrowClockwise size={17} aria-hidden="true" />{t("gestion.dashboard.refresh")}</button>
        {!customizing && <button type="button" className="gd-primary" disabled={!preference || preferenceLoading || preferenceError || saving} onClick={openCustomization}><SlidersHorizontal size={17} aria-hidden="true" />{t("gestion.dashboard.customize")}</button>}
      </div>
    </header>
    {preferenceLoading && <div className="gd-message" role="status">{t("common.loading")}</div>}
    {preferenceError && <div className="gd-message error" role="alert">{t("gestion.dashboard.loadError")}<button type="button" onClick={() => setPreferenceRetry((value) => value + 1)}>{t("gestion.dashboard.retry")}</button></div>}
    {!customizing && saveState === "error" && viewOptions && <div className="gd-message error" role="alert">{t("gestion.dashboard.viewSaveError")}
      <button type="button" onClick={() => void saveViewOptions(viewOptions)}>{t("gestion.dashboard.retry")}</button></div>}
    {preference && !preferenceLoading && !preferenceError && <div className="gd-layout">
      <div className="gd-main">
        {customizing && <div className="gd-user-notice"><Info size={19} aria-hidden="true" />{t("gestion.dashboard.userNotice")}</div>}
        {(hasSales || widgets.some(widget => widget.key === "promotions.active")) && <><div className="gd-period-presets" role="group" aria-label={t("gestion.dashboard.quickPeriods")}>
          {periods.map(value => <button type="button" key={value} aria-pressed={appliedPeriod === value} onClick={() => applyPeriod(value)}>{t(`gestion.dashboard.period.${value}`)}</button>)}
          {hasSales && !customizing && <label className="gd-checkbox"><input type="checkbox" checked={options.showComparison} disabled={saving} onChange={event => updateOptions({ showComparison: event.target.checked })} />{t("gestion.dashboard.showComparison")}</label>}
        </div><section className="gd-filters" aria-label={t("gestion.dashboard.period")}>
          <label><span>{t("gestion.dashboard.period")}</span><select ref={periodRef} value={period} onChange={(event) => applyPeriod(event.target.value as typeof period)}>
            {periods.map((value) => <option value={value} key={value}>{t(`gestion.dashboard.period.${value}`)}</option>)}<option value="CUSTOM">{t("gestion.dashboard.period.CUSTOM")}</option>
          </select></label>
          <label><span>{t("gestion.dashboard.from")}</span><input type="date" value={rangeDraft.from} onChange={(event) => { setRangeDraft({ ...rangeDraft, from: event.target.value }); setPeriod("CUSTOM"); }} /></label>
          <span className="gd-date-separator" aria-hidden="true">–</span>
          <label><span>{t("gestion.dashboard.to")}</span><input type="date" value={rangeDraft.to} onChange={(event) => { setRangeDraft({ ...rangeDraft, to: event.target.value }); setPeriod("CUSTOM"); }} /></label>
          {(rangeDraft.from !== range.from || rangeDraft.to !== range.to) && <button type="button" className="gd-date-apply" onClick={() => {
            if (!validDashboardRange(rangeDraft)) { setRangeError(true); return; }
            setRange(rangeDraft); setRangeError(false);
            setAppliedPeriod("CUSTOM");
          }}>{t("gestion.dashboard.apply")}</button>}
          {hasSales && <label className="gd-warehouse"><span>{t("gestion.dashboard.warehouse")}</span><select value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>
            <option value="">{t("gestion.dashboard.allWarehouses")}</option>{warehouses.map((warehouse) => <option value={warehouse.id} key={warehouse.id}>{warehouse.name}</option>)}
          </select></label>}
        </section></>}
        {!hasSales && hasReceivables && availableWidgets.some(key => key.startsWith("sales.")) && <section className="gd-filters"><label><span>{t("gestion.dashboard.warehouse")}</span>
          <select value={warehouseId} onChange={event => setWarehouseId(event.target.value)}><option value="">{t("gestion.dashboard.allWarehouses")}</option>
            {warehouses.map(warehouse => <option key={warehouse.id} value={warehouse.id}>{warehouse.name}</option>)}</select></label></section>}
        {hasSales && <ErpFilterChips translate={t} chips={filterChips} focusRef={periodRef}
          onClear={() => { setWarehouseId(""); applyPeriod(defaultPeriod); }} />}
        {rangeError && <p className="gd-inline-error" role="alert">{t("gestion.dashboard.invalidRange")}</p>}
        {warehouseError && <p className="gd-inline-error" role="alert">{t("gestion.dashboard.warehouseError")}<button type="button" onClick={refreshData}>{t("gestion.dashboard.retry")}</button></p>}
        {hasSales && <div className="gd-scope-note"><span title={t("gestion.dashboard.salesScopeDetail")}><Info size={14} aria-hidden="true" />{t("gestion.dashboard.salesScope")}</span>
          <span>{formatDashboardRange(range.from, range.to, locale)}</span>
          {sales.data && options.showComparison && <span>{t("gestion.dashboard.comparedWith")} {formatDashboardRange(sales.data.previousFrom, sales.data.previousTo, locale)}</span>}
          {sales.loading && <span role="status">{t("common.loading")}</span>}
        </div>}
        {hasSales && range.from <= storeBusinessDate(preference.storeTimezone) && range.to >= storeBusinessDate(preference.storeTimezone) && <p className="gd-partial-period">{t("gestion.dashboard.partialDay")}</p>}
        {updatedAt && <p className="gd-updated-at">{t("gestion.dashboard.updatedAt")} {new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { dateStyle: "short", timeStyle: "short", timeZone: preference.storeTimezone }).format(updatedAt)}</p>}
        {showDataError && <div className="gd-message error" role="alert">{t("gestion.dashboard.dataError")}<button type="button" onClick={refreshData}>{t("gestion.dashboard.retry")}</button></div>}
        {widgets.length === 0 && <div className="gd-empty"><strong>{t("gestion.dashboard.empty")}</strong><p>{t("gestion.dashboard.emptyHint")}</p></div>}
        <section className="gd-grid" aria-label={t("gestion.dashboard")}>
          {widgets.map((widget) => <DashboardWidgetFrame key={widget.key} widget={widget} customizing={customizing} selected={selectedKey === widget.key} disabled={saving} t={t}
            onSelect={() => setSelectedKey(widget.key)} onDragStart={() => setDraggedKey(widget.key)} onDrop={() => {
              if (draggedKey && customizing && !saving) updateDraft(reorderDashboardWidgets(widgets, draggedKey, widget.key));
              setDraggedKey(null);
            }} onRemove={() => updateDraft(widgets.filter((value) => value.key !== widget.key))} toolbar={!customizing ? viewControls(widget.key) : undefined}>
            {widget.key === "sales.today" && <SalesMetricWidget state={sales} metric="netSales" t={t} locale={locale} onOpen={onOpenSales} showComparison={options.showComparison} />}
            {widget.key === "sales.operations" && <SalesMetricWidget state={sales} metric="operationCount" t={t} locale={locale} onOpen={onOpenSales} showComparison={options.showComparison} />}
            {widget.key === "sales.average" && <SalesMetricWidget state={sales} metric="averageAmount" t={t} locale={locale} onOpen={onOpenSales} showComparison={options.showComparison} />}
            {widget.key === "sales.units" && <SalesMetricWidget state={sales} metric="netUnits" t={t} locale={locale} onOpen={onOpenSales} showComparison={options.showComparison} />}
            {widget.key === "sales.trend" && <SalesTrendWidget state={sales} t={t} locale={locale} options={options} />}
            {widget.key === "sales.families" && <FamilySalesWidget state={sales} t={t} locale={locale} display={options.familyDisplay ?? "BAR"} comparison={options.showComparison} />}
            {widget.key === "sales.hourly" && <HourlySalesWidget state={sales} t={t} locale={locale} display={options.hourlyDisplay ?? "BAR"}
              token={session.accessToken} warehouseId={warehouseId || undefined} refresh={refresh} load={dataSource.loadHourlySales} />}
            {widget.key === "sales.corrections" && <CorrectionsWidget state={sales} t={t} locale={locale} display={options.correctionDisplay ?? "TABLE"} />}
            {widget.key === "sales.payments" && <PaymentsWidget state={sales} t={t} locale={locale} display={options.paymentDisplay ?? "TABLE"} />}
            {widget.key === "finance.receivables" && <ReceivablesWidget state={receivables} t={t} locale={locale} display={options.receivableDisplay ?? "TABLE"} />}
            {widget.key === "sales.top-products" && <TopProductsWidget state={sales} t={t} locale={locale} onOpen={onOpenStock} display={options.productDisplay} sort={options.productSort} />}
            {widget.key === "promotions.active" && <ActivePromotionsWidget state={promotions} t={t} locale={locale} onOpen={onOpenPromotions} date={range.to} display={options.promotionDisplay} />}
            {widget.key === "control.alerts" && <ControlAlertsWidget state={alerts} t={t} locale={locale} onOpen={onOpenControlAlerts} timeZone={preference.storeTimezone} display={options.alertDisplay} />}
          </DashboardWidgetFrame>)}
        </section>
      </div>
      {draft && <aside className="gd-customization" aria-label={t("gestion.dashboard.myConfiguration")}>
        <header><h3>{t("gestion.dashboard.myConfiguration")}</h3><User size={20} aria-hidden="true" /></header>
        <fieldset disabled={saving}>
          <div className="gd-config-section"><h4>{t("gestion.dashboard.layoutPreset")}</h4><div className="gd-presets">
            {(["BALANCED", "SALES", "PRODUCTS"] as DashboardPreset[]).map((preset) => <button type="button" key={preset} onClick={() => selectPreset(preset)}
              aria-pressed={matchesPreset(widgets, dashboardPreset(preset, availableWidgets))}>
              <span className={`gd-preset-thumbnail ${preset.toLowerCase()}`} aria-hidden="true"><i /><i /><i /><i /><i /></span>{t(`gestion.dashboard.preset.${preset}`)}
            </button>)}
          </div></div>
          <div className="gd-config-section"><h4>{t("gestion.dashboard.visibleWidgets")}</h4><div className="gd-widget-checks">
            {availableWidgets.map((key) => <label key={key}><input type="checkbox" checked={widgets.some((widget) => widget.key === key)} onChange={(event) => {
              updateDraft(event.target.checked ? [...widgets, { ...dashboardWidgetDefaults[key] }] : widgets.filter((widget) => widget.key !== key));
              if (event.target.checked) setSelectedKey(key);
            }} /><span>{t(`gestion.widget.${key}`)}</span></label>)}
          </div></div>
          <div className="gd-config-section gd-selected-settings"><h4>{t("gestion.dashboard.selectedWidget")}</h4>
            {selected ? <><strong className="gd-selected-title">{t(`gestion.widget.${selected.key}`)}</strong>
              {viewControls(selected.key)}
              <label className="gd-config-label">{t("gestion.dashboard.width")}</label><div className="gd-segmented gd-widths">{([3, 4, 6, 8, 12] as const).map((width) =>
                <button type="button" key={width} aria-pressed={selected.width === width} onClick={() => updateDraft(widgets.map((widget) => widget.key === selected.key ? { ...widget, width } : widget))}>
                  {width === 12 ? t("gestion.dashboard.full") : width === 3 ? "1/4" : width === 4 ? "1/3" : width === 6 ? "1/2" : "2/3"}
                </button>)}</div>
              <div className="gd-config-position"><label>{t("gestion.dashboard.height")}<select value={selected.height} onChange={(event) => updateDraft(widgets.map((widget) => widget.key === selected.key ? { ...widget, height: Number(event.target.value) as 1 | 2 | 3 } : widget))}>
                <option value={1}>{t("gestion.dashboard.height.small")}</option><option value={2}>{t("gestion.dashboard.height.medium")}</option><option value={3}>{t("gestion.dashboard.height.large")}</option>
              </select></label><div><span>{t("gestion.dashboard.move")}</span><button type="button" className="gd-icon-button" disabled={widgets[0].key === selected.key} aria-label={t("gestion.dashboard.moveUp")} onClick={() => updateDraft(moveDashboardWidget(widgets, selected.key, -1))}><ArrowUp size={17} /></button>
                <button type="button" className="gd-icon-button" disabled={widgets[widgets.length - 1].key === selected.key} aria-label={t("gestion.dashboard.moveDown")} onClick={() => updateDraft(moveDashboardWidget(widgets, selected.key, 1))}><ArrowDown size={17} /></button></div></div>
            </> : <p className="gd-widget-note">{t("gestion.dashboard.selectHint")}</p>}
          </div>
          <details className="gd-config-section gd-more-options"><summary>{t("gestion.dashboard.generalOptions")}</summary>
            <label>{t("gestion.dashboard.defaultPeriod")}<select value={options.defaultPeriod} onChange={(event) => updateOptions({ defaultPeriod: event.target.value as DashboardOptions["defaultPeriod"] })}>
              {periods.map((value) => <option value={value} key={value}>{t(`gestion.dashboard.period.${value}`)}</option>)}</select></label>
            <label>{t("gestion.dashboard.density")}<select value={options.density} onChange={(event) => updateOptions({ density: event.target.value as DashboardOptions["density"] })}>
              <option value="COMFORTABLE">{t("gestion.dashboard.density.COMFORTABLE")}</option><option value="COMPACT">{t("gestion.dashboard.density.COMPACT")}</option></select></label>
            <label className="gd-checkbox"><input type="checkbox" checked={options.showComparison} onChange={(event) => updateOptions({ showComparison: event.target.checked })} />{t("gestion.dashboard.showComparison")}</label>
          </details>
        </fieldset>
        <footer className="gd-config-footer">
          {saveState === "error" && <p className="gd-inline-error" role="alert">{t("gestion.dashboard.saveError")}</p>}
          <button type="button" className="gd-primary" disabled={saving} onClick={() => void save()}><FloppyDisk size={17} aria-hidden="true" />{t(saving ? "gestion.dashboard.saving" : "gestion.dashboard.saveConfiguration")}</button>
          <button type="button" disabled={saving} onClick={() => { setDraft(undefined); setSaveState("idle"); }}>{t("common.cancel")}</button>
          <button type="button" className="gd-link" disabled={saving} onClick={() => { setDraft({ widgets: dashboardPreset("BALANCED", availableWidgets), options: { ...defaultDashboardOptions } }); setSaveState("idle"); }}>{t("gestion.dashboard.restoreDefault")}</button>
        </footer>
      </aside>}
    </div>}
  </section>;
}

function useDashboardData<T>(loader: (signal: AbortSignal) => Promise<T>, dependencies: unknown[], enabled: boolean, scope: string): DashboardDataState<T> {
  const [state, setState] = useState<DashboardDataState<T>>({ loading: false, error: false });
  const previousScope = useRef(scope);
  useEffect(() => {
    if (!enabled) { setState({ loading: false, error: false }); return; }
    const controller = new AbortController();
    let active = true;
    const sameScope = previousScope.current === scope;
    previousScope.current = scope;
    setState((previous) => ({ loading: true, error: false, data: sameScope ? previous.data : undefined, updatedAt: sameScope ? previous.updatedAt : undefined }));
    void loader(controller.signal).then((data) => { if (active) setState({ loading: false, error: false, data, updatedAt: Date.now() }); })
      .catch(() => { if (active) setState((previous) => ({ ...previous, loading: false, error: true })); });
    return () => { active = false; controller.abort(); };
    // Each caller supplies the stable request identity, independently of the loader closure.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...dependencies, enabled]);
  return state;
}
function storeBusinessDate(timeZone?: string) {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}
function matchesPreset(widgets: DashboardWidgetLayout[], preset: DashboardWidgetLayout[]) {
  return widgets.length === preset.length && widgets.every((widget, index) => widget.key === preset[index].key && widget.width === preset[index].width && widget.height === preset[index].height);
}
