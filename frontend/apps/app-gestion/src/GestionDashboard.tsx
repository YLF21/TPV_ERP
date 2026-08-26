import { useEffect, useRef, useState } from "react";
import { OperationalStatusCard, type LocaleCode, type UserSession } from "@tpverp/app-common";
import {
  changeDashboardWidgetHeight,
  dashboardWidgetDefaults,
  loadActivePromotions,
  loadControlAlertsSummary,
  loadDashboardWarehouses,
  loadDashboardPreference,
  loadSalesToday,
  loadTopProducts,
  moveDashboardWidget,
  reorderDashboardWidgets,
  resizeDashboardWidget,
  saveDashboardPreference,
  type ActivePromotionData,
  type ControlAlertsSummaryData,
  type DashboardPreference,
  type DashboardScope,
  type DashboardWarehouse,
  type DashboardWidgetKey,
  type DashboardWidgetLayout,
  type SalesTodayData,
  type TopProductData
} from "./dashboardModel";
import {
  ActivePromotionsWidget,
  ControlAlertsWidget,
  DashboardOverviewLayout,
  DashboardWidgetFrame,
  SalesTodayWidget,
  TopProductsWidget,
  type DashboardDataState
} from "./GestionDashboardWidgets";

type Translator = (key: string) => string;

type GestionDashboardProps = {
  session: UserSession;
  locale?: LocaleCode;
  t: Translator;
  onOpenSales: () => void;
  onOpenStock: () => void;
  onOpenPromotions: () => void;
  onOpenControlAlerts: () => void;
  dataSource?: DashboardDataSource;
};

export type DashboardDataSource = {
  loadPreference: (token?: string) => Promise<DashboardPreference>;
  savePreference: (widgets: DashboardWidgetLayout[], token?: string) => Promise<DashboardPreference>;
  loadSalesToday: (token?: string, scope?: DashboardScope) => Promise<SalesTodayData>;
  loadTopProducts: (token?: string, scope?: DashboardScope) => Promise<TopProductData[]>;
  loadActivePromotions: (token?: string, scope?: DashboardScope) => Promise<ActivePromotionData[]>;
  loadControlAlertsSummary: (token?: string) => Promise<ControlAlertsSummaryData>;
  loadWarehouses: (token?: string) => Promise<DashboardWarehouse[]>;
};

const defaultDashboardDataSource: DashboardDataSource = {
  loadPreference: loadDashboardPreference,
  savePreference: saveDashboardPreference,
  loadSalesToday,
  loadTopProducts,
  loadActivePromotions,
  loadControlAlertsSummary,
  loadWarehouses: loadDashboardWarehouses
};

type SaveState = "idle" | "pending" | "saving" | "saved" | "error";
export function GestionDashboard({
  session,
  locale = "es",
  t,
  onOpenSales,
  onOpenStock,
  onOpenPromotions,
  onOpenControlAlerts,
  dataSource = defaultDashboardDataSource
}: GestionDashboardProps) {
  const [widgets, setWidgets] = useState<DashboardWidgetLayout[]>([]);
  const [availableWidgets, setAvailableWidgets] = useState<DashboardWidgetKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [customizing, setCustomizing] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [draggedKey, setDraggedKey] = useState<DashboardWidgetKey | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [scopeDate, setScopeDate] = useState(todayIsoDate);
  const [warehouseId, setWarehouseId] = useState("");
  const [warehouses, setWarehouses] = useState<DashboardWarehouse[]>([]);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const saveRevisionRef = useRef(0);
  const configuredKeys = new Set(widgets.map((widget) => widget.key));
  const scope = { date: scopeDate, warehouseId: warehouseId || undefined };
  const salesState = useDashboardData(() => dataSource.loadSalesToday(session.accessToken, scope), [dataSource, session.accessToken, scopeDate, warehouseId, refreshKey], configuredKeys.has("sales.today"));
  const topProductsState = useDashboardData(() => dataSource.loadTopProducts(session.accessToken, scope), [dataSource, session.accessToken, scopeDate, warehouseId, refreshKey], configuredKeys.has("sales.top-products"));
  const promotionsState = useDashboardData(() => dataSource.loadActivePromotions(session.accessToken, scope), [dataSource, session.accessToken, scopeDate, refreshKey], configuredKeys.has("promotions.active"));
  const alertsState = useDashboardData(() => dataSource.loadControlAlertsSummary(session.accessToken), [dataSource, session.accessToken, refreshKey], configuredKeys.has("control.alerts"));

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError(false);
    void dataSource.loadPreference(session.accessToken)
      .then((preference) => {
        if (!active) return;
        setWidgets(preference.widgets);
        setAvailableWidgets(preference.availableWidgets);
      })
      .catch(() => {
        if (active) setLoadError(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [dataSource, session.accessToken]);

  useEffect(() => () => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
  }, []);

  useEffect(() => {
    let active = true;
    void dataSource.loadWarehouses(session.accessToken)
      .then((values) => {
        if (active) setWarehouses(values.filter((warehouse) => warehouse.active));
      })
      .catch(() => {
        if (active) setWarehouses([]);
      });
    return () => { active = false; };
  }, [dataSource, session.accessToken]);

  const updateWidgets = (next: DashboardWidgetLayout[]) => {
    setWidgets(next);
    setSaveState("pending");
    const revision = ++saveRevisionRef.current;
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = setTimeout(() => {
      setSaveState("saving");
      void dataSource.savePreference(next, session.accessToken)
        .then((preference) => {
          if (revision !== saveRevisionRef.current) return;
          setWidgets(preference.widgets);
          setAvailableWidgets(preference.availableWidgets);
          setSaveState("saved");
        })
        .catch(() => {
          if (revision === saveRevisionRef.current) setSaveState("error");
        });
    }, 350);
  };

  const addableWidgets = availableWidgets.filter((key) => !configuredKeys.has(key));

  return (
    <section className="gestion-workspace">
        <header className="gestion-dashboard-toolbar">
          <div>
            <span className="gestion-eyebrow">{t("gestion.dashboard.eyebrow")}</span>
            <h2>{t("gestion.dashboard")}</h2>
          </div>
          <div className="gestion-dashboard-actions">
            <label className="gestion-dashboard-scope-control">
              <span>{t("gestion.dashboard.date")}</span>
              <input type="date" value={scopeDate} onChange={(event) => setScopeDate(event.target.value)} />
            </label>
            <label className="gestion-dashboard-scope-control">
              <span>{t("gestion.dashboard.warehouse")}</span>
              <select value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>
                <option value="">{t("gestion.dashboard.allWarehouses")}</option>
                {warehouses.map((warehouse) => <option value={warehouse.id} key={warehouse.id}>{warehouse.name}</option>)}
              </select>
            </label>
            <span className={`gestion-save-state ${saveState}`} role="status">
              {t(`gestion.dashboard.save.${saveState}`)}
            </span>
            <button type="button" onClick={() => setRefreshKey((value) => value + 1)}>
              {t("gestion.dashboard.refresh")}
            </button>
            <button
              type="button"
              className={customizing ? "primary selected" : "primary"}
              aria-pressed={customizing}
              onClick={() => setCustomizing((value) => !value)}
            >
              {customizing ? t("gestion.dashboard.finish") : t("gestion.dashboard.customize")}
            </button>
          </div>
        </header>

        {customizing && (
          <section className="gestion-widget-catalog" aria-label={t("gestion.dashboard.catalog") }>
            <div>
              <strong>{t("gestion.dashboard.catalog")}</strong>
              <span>{t("gestion.dashboard.catalogHint")}</span>
            </div>
            <div className="gestion-widget-catalog-actions">
              {addableWidgets.length === 0 && <span>{t("gestion.dashboard.allAdded")}</span>}
              {addableWidgets.map((key) => (
                <button
                  type="button"
                  key={key}
                  onClick={() => updateWidgets([...widgets, dashboardWidgetDefaults[key]])}
                >
                  {`+ ${t(`gestion.widget.${key}`)}`}
                </button>
              ))}
            </div>
          </section>
        )}

        {loading && <div className="gestion-dashboard-message">{t("common.loading")}</div>}
        {loadError && <div className="gestion-dashboard-message error">{t("gestion.dashboard.loadError")}</div>}
        {!loading && !loadError && widgets.length === 0 && (
          <div className="gestion-dashboard-empty">
            <strong>{t("gestion.dashboard.empty")}</strong>
            <p>{t("gestion.dashboard.emptyHint")}</p>
            {!customizing && (
              <button type="button" onClick={() => setCustomizing(true)}>
                {t("gestion.dashboard.customize")}
              </button>
            )}
          </div>
        )}

        {!loading && !loadError && widgets.length > 0 && !customizing && (
          <DashboardOverviewLayout
            widgets={widgets}
            salesState={salesState}
            topProductsState={topProductsState}
            promotionsState={promotionsState}
            alertsState={alertsState}
            t={t}
            onOpenSales={onOpenSales}
            onOpenStock={onOpenStock}
            onOpenPromotions={onOpenPromotions}
            onOpenControlAlerts={onOpenControlAlerts}
          />
        )}

        {!loading && !loadError && widgets.length > 0 && customizing && (
          <section className={`gestion-dashboard-grid ${customizing ? "customizing" : ""}`}>
            {widgets.map((widget, index) => (
              <DashboardWidgetFrame
                key={widget.key}
                widget={widget}
                index={index}
                count={widgets.length}
                customizing={customizing}
                t={t}
                onDragStart={() => setDraggedKey(widget.key)}
                onDrop={() => {
                  if (draggedKey) updateWidgets(reorderDashboardWidgets(widgets, draggedKey, widget.key));
                  setDraggedKey(null);
                }}
                onMove={(direction) => updateWidgets(moveDashboardWidget(widgets, widget.key, direction))}
                onResize={(direction) => updateWidgets(resizeDashboardWidget(widgets, widget.key, direction))}
                onHeight={(direction) => updateWidgets(changeDashboardWidgetHeight(widgets, widget.key, direction))}
                onRemove={() => updateWidgets(widgets.filter((candidate) => candidate.key !== widget.key))}
              >
                {widget.key === "sales.today" && (
                  <SalesTodayWidget state={salesState} t={t} onOpen={onOpenSales} />
                )}
                {widget.key === "sales.top-products" && (
                  <TopProductsWidget state={topProductsState} t={t} onOpen={onOpenStock} />
                )}
                {widget.key === "promotions.active" && (
                  <ActivePromotionsWidget state={promotionsState} t={t} onOpen={onOpenPromotions} />
                )}
                {widget.key === "control.alerts" && (
                  <ControlAlertsWidget state={alertsState} t={t} onOpen={onOpenControlAlerts} />
                )}
              </DashboardWidgetFrame>
            ))}
          </section>
        )}

        {session.permissions.includes("ADMIN") ? (
          <div className="gestion-operational-status">
            <OperationalStatusCard locale={locale} token={session.accessToken} />
          </div>
        ) : null}
    </section>
  );
}

function useDashboardData<T>(loader: () => Promise<T>, dependencies: unknown[], enabled = true) {
  const [state, setState] = useState<{ loading: boolean; error: boolean; data?: T }>({ loading: true, error: false });
  useEffect(() => {
    if (!enabled) {
      setState({ loading: false, error: false });
      return;
    }
    let active = true;
    setState({ loading: true, error: false });
    void loader()
      .then((data) => {
        if (active) setState({ loading: false, error: false, data });
      })
      .catch(() => {
        if (active) setState({ loading: false, error: true });
      });
    return () => {
      active = false;
    };
    // The caller supplies the stable reload identity for its loader.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...dependencies, enabled]);
  return state;
}

function todayIsoDate() {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 10);
}
