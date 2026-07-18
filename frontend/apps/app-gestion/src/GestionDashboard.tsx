import { useEffect, useRef, useState, type ReactNode } from "react";
import type { UserSession } from "@tpverp/app-common";
import { GestionShell, type GestionNavigationItem } from "./GestionShell";
import {
  changeDashboardWidgetHeight,
  dashboardWidgetDefaults,
  loadActivePromotions,
  loadControlAlertsSummary,
  loadDashboardPreference,
  loadSalesToday,
  loadTopProducts,
  moveDashboardWidget,
  reorderDashboardWidgets,
  resizeDashboardWidget,
  saveDashboardPreference,
  type ActivePromotionData,
  type ControlAlertsSummaryData,
  type DashboardWidgetKey,
  type DashboardWidgetLayout,
  type SalesTodayData,
  type TopProductData
} from "./dashboardModel";

type Translator = (key: string) => string;

type GestionDashboardProps = {
  session: UserSession;
  t: Translator;
  navigation: GestionNavigationItem[];
  onOpenSales: () => void;
  onOpenStock: () => void;
  onOpenPromotions: () => void;
  onOpenControlAlerts: () => void;
};

type SaveState = "idle" | "pending" | "saving" | "saved" | "error";

export function GestionDashboard({
  session,
  t,
  navigation,
  onOpenSales,
  onOpenStock,
  onOpenPromotions,
  onOpenControlAlerts
}: GestionDashboardProps) {
  const [widgets, setWidgets] = useState<DashboardWidgetLayout[]>([]);
  const [availableWidgets, setAvailableWidgets] = useState<DashboardWidgetKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [customizing, setCustomizing] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [draggedKey, setDraggedKey] = useState<DashboardWidgetKey | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const saveRevisionRef = useRef(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError(false);
    void loadDashboardPreference(session.accessToken)
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
  }, [session.accessToken]);

  useEffect(() => () => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
  }, []);

  const updateWidgets = (next: DashboardWidgetLayout[]) => {
    setWidgets(next);
    setSaveState("pending");
    const revision = ++saveRevisionRef.current;
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = setTimeout(() => {
      setSaveState("saving");
      void saveDashboardPreference(next, session.accessToken)
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

  const configuredKeys = new Set(widgets.map((widget) => widget.key));
  const addableWidgets = availableWidgets.filter((key) => !configuredKeys.has(key));

  return (
    <GestionShell session={session} t={t} activeKey="dashboard" navigation={navigation}>
      <section className="gestion-workspace">
        <header className="gestion-dashboard-toolbar">
          <div>
            <span className="gestion-eyebrow">{t("gestion.dashboard.eyebrow")}</span>
            <h2>{t("gestion.dashboard")}</h2>
          </div>
          <div className="gestion-dashboard-actions">
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

        {!loading && !loadError && widgets.length > 0 && (
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
                  <SalesTodayWidget token={session.accessToken} refreshKey={refreshKey} t={t} onOpen={onOpenSales} />
                )}
                {widget.key === "sales.top-products" && (
                  <TopProductsWidget token={session.accessToken} refreshKey={refreshKey} t={t} onOpen={onOpenStock} />
                )}
                {widget.key === "promotions.active" && (
                  <ActivePromotionsWidget token={session.accessToken} refreshKey={refreshKey} t={t} onOpen={onOpenPromotions} />
                )}
                {widget.key === "control.alerts" && (
                  <ControlAlertsWidget token={session.accessToken} refreshKey={refreshKey} t={t} onOpen={onOpenControlAlerts} />
                )}
              </DashboardWidgetFrame>
            ))}
          </section>
        )}
      </section>
    </GestionShell>
  );
}

function DashboardWidgetFrame({
  widget,
  index,
  count,
  customizing,
  t,
  onDragStart,
  onDrop,
  onMove,
  onResize,
  onHeight,
  onRemove,
  children
}: {
  widget: DashboardWidgetLayout;
  index: number;
  count: number;
  customizing: boolean;
  t: Translator;
  onDragStart: () => void;
  onDrop: () => void;
  onMove: (direction: -1 | 1) => void;
  onResize: (direction: -1 | 1) => void;
  onHeight: (direction: -1 | 1) => void;
  onRemove: () => void;
  children: ReactNode;
}) {
  return (
    <article
      className="gestion-widget"
      style={{ gridColumn: `span ${widget.width}`, gridRow: `span ${widget.height}` }}
      draggable={customizing}
      onDragStart={onDragStart}
      onDragOver={(event) => {
        if (customizing) event.preventDefault();
      }}
      onDrop={onDrop}
    >
      <header className="gestion-widget-header">
        <strong>{t(`gestion.widget.${widget.key}`)}</strong>
        {customizing && (
          <div className="gestion-widget-controls">
            <button type="button" disabled={index === 0} aria-label={t("gestion.dashboard.moveLeft")} title={t("gestion.dashboard.moveLeft")} onClick={() => onMove(-1)}>←</button>
            <button type="button" disabled={index === count - 1} aria-label={t("gestion.dashboard.moveRight")} title={t("gestion.dashboard.moveRight")} onClick={() => onMove(1)}>→</button>
            <button type="button" aria-label={t("gestion.dashboard.narrower")} title={t("gestion.dashboard.narrower")} onClick={() => onResize(-1)}>−</button>
            <span>{`${widget.width}/12`}</span>
            <button type="button" aria-label={t("gestion.dashboard.wider")} title={t("gestion.dashboard.wider")} onClick={() => onResize(1)}>+</button>
            <button type="button" aria-label={t("gestion.dashboard.shorter")} title={t("gestion.dashboard.shorter")} onClick={() => onHeight(-1)}>▴</button>
            <button type="button" aria-label={t("gestion.dashboard.taller")} title={t("gestion.dashboard.taller")} onClick={() => onHeight(1)}>▾</button>
            <button type="button" className="remove" aria-label={t("gestion.dashboard.remove")} title={t("gestion.dashboard.remove")} onClick={onRemove}>×</button>
          </div>
        )}
      </header>
      <div className="gestion-widget-body">{children}</div>
    </article>
  );
}

function SalesTodayWidget({ token, refreshKey, t, onOpen }: WidgetProps) {
  const state = useDashboardData(() => loadSalesToday(token), [token, refreshKey]);
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const data = state.data as SalesTodayData;
  const comparison = data.changePercent == null
    ? t("gestion.widget.noComparison")
    : `${data.changePercent >= 0 ? "+" : ""}${formatNumber(data.changePercent)} %`;
  return (
    <div className="gestion-sales-today">
      <div className="gestion-main-metric">
        <span>{t("gestion.widget.issued")}</span>
        <strong>{formatCurrency(data.issuedTotal)}</strong>
        <small className={data.changePercent != null && data.changePercent < 0 ? "negative" : "positive"}>
          {`${comparison} ${t("gestion.widget.vsYesterday")}`}
        </small>
      </div>
      <dl>
        <div><dt>{t("gestion.widget.collected")}</dt><dd>{formatCurrency(data.collectedTotal)}</dd></div>
        <div><dt>{t("gestion.widget.yesterday")}</dt><dd>{formatCurrency(data.previousIssuedTotal)}</dd></div>
      </dl>
      <WidgetFooter label={t("gestion.widget.openSales")} onOpen={onOpen} />
    </div>
  );
}

function TopProductsWidget({ token, refreshKey, t, onOpen }: WidgetProps) {
  const state = useDashboardData(() => loadTopProducts(token), [token, refreshKey]);
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const rows = state.data as TopProductData[];
  return (
    <div className="gestion-widget-table-wrap">
      {rows.length === 0 ? <WidgetMessage text={t("gestion.widget.noSales")} /> : (
        <table className="gestion-widget-table">
          <thead><tr><th>#</th><th>{t("gestion.widget.product")}</th><th>{t("gestion.widget.units")}</th><th>{t("gestion.widget.amount")}</th></tr></thead>
          <tbody>{rows.map((row, index) => (
            <tr key={row.productId}><td>{index + 1}</td><td>{row.name}</td><td>{formatNumber(row.soldQuantity)}</td><td>{formatCurrency(row.netAmount)}</td></tr>
          ))}</tbody>
        </table>
      )}
      <WidgetFooter label={t("gestion.widget.openStock")} onOpen={onOpen} />
    </div>
  );
}

function ActivePromotionsWidget({ token, refreshKey, t, onOpen }: WidgetProps) {
  const state = useDashboardData(() => loadActivePromotions(token), [token, refreshKey]);
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const rows = state.data as ActivePromotionData[];
  return (
    <div className="gestion-widget-table-wrap">
      <div className="gestion-promotion-content">
        <div className="gestion-promotion-count"><strong>{rows.length}</strong><span>{t("gestion.widget.activeCount")}</span></div>
        {rows.length === 0 ? <WidgetMessage text={t("gestion.widget.noPromotions")} /> : (
          <table className="gestion-widget-table compact">
            <tbody>{rows.slice(0, 6).map((row) => (
              <tr key={row.id}><td><strong>{row.name}</strong><small>{t(`promotion.type.${row.type}`)}</small></td><td>{row.endDate ?? t("promotion.noEndDate")}</td></tr>
            ))}</tbody>
          </table>
        )}
      </div>
      <WidgetFooter label={t("gestion.widget.openPromotions")} onOpen={onOpen} />
    </div>
  );
}

function ControlAlertsWidget({ token, refreshKey, t, onOpen }: WidgetProps) {
  const state = useDashboardData(() => loadControlAlertsSummary(token), [token, refreshKey]);
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const data = state.data as ControlAlertsSummaryData;
  return (
    <div className="gestion-control-alert-widget">
      <div className="gestion-control-alert-counts">
        <div><strong>{data.newCount}</strong><span>{t("gestion.widget.controlAlerts.new")}</span></div>
        <div><strong>{data.reviewedCount}</strong><span>{t("gestion.widget.controlAlerts.reviewed")}</span></div>
      </div>
      <div className="gestion-control-alert-recent">
        {data.recentAlerts.length === 0 ? <WidgetMessage text={t("gestion.widget.controlAlerts.empty")} /> : (
          <table className="gestion-widget-table compact">
            <tbody>{data.recentAlerts.slice(0, 5).map((alert) => (
              <tr key={alert.id}>
                <td><strong>{t(`gestion.controlAlerts.type.${alert.type}`)}</strong><small>{alert.documentNumber || alert.userName || "—"}</small></td>
                <td>{formatDashboardDateTime(alert.occurredAt)}</td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </div>
      <WidgetFooter label={t("gestion.widget.controlAlerts.open")} onOpen={onOpen} />
    </div>
  );
}

type WidgetProps = {
  token?: string;
  refreshKey: number;
  t: Translator;
  onOpen: () => void;
};

function useDashboardData<T>(loader: () => Promise<T>, dependencies: unknown[]) {
  const [state, setState] = useState<{ loading: boolean; error: boolean; data?: T }>({ loading: true, error: false });
  useEffect(() => {
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
  }, dependencies);
  return state;
}

function WidgetMessage({ text, error = false }: { text: string; error?: boolean }) {
  return <div className={`gestion-widget-message ${error ? "error" : ""}`}>{text}</div>;
}

function WidgetFooter({ label, onOpen }: { label: string; onOpen: () => void }) {
  return <footer className="gestion-widget-footer"><button type="button" onClick={onOpen}>{label} →</button></footer>;
}

function formatCurrency(value: number) {
  return new Intl.NumberFormat("es-ES", { style: "currency", currency: "EUR" }).format(value ?? 0);
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("es-ES", { maximumFractionDigits: 3 }).format(value ?? 0);
}

function formatDashboardDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("es-ES", { dateStyle: "short", timeStyle: "short" }).format(date);
}
