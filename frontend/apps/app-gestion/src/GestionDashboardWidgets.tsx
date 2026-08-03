import type { ReactNode } from "react";
import type {
  ActivePromotionData,
  ControlAlertsSummaryData,
  DashboardWidgetLayout,
  SalesTodayData,
  TopProductData
} from "./dashboardModel";

export type DashboardTranslator = (key: string) => string;
export type DashboardDataState<T> = { loading: boolean; error: boolean; data?: T };

type WidgetProps<T> = {
  state: DashboardDataState<T>;
  t: DashboardTranslator;
  onOpen: () => void;
};

export function DashboardOverviewLayout({
  widgets,
  salesState,
  topProductsState,
  promotionsState,
  alertsState,
  t,
  onOpenSales,
  onOpenStock,
  onOpenPromotions,
  onOpenControlAlerts
}: {
  widgets: DashboardWidgetLayout[];
  salesState: DashboardDataState<SalesTodayData>;
  topProductsState: DashboardDataState<TopProductData[]>;
  promotionsState: DashboardDataState<ActivePromotionData[]>;
  alertsState: DashboardDataState<ControlAlertsSummaryData>;
  t: DashboardTranslator;
  onOpenSales: () => void;
  onOpenStock: () => void;
  onOpenPromotions: () => void;
  onOpenControlAlerts: () => void;
}) {
  const visible = new Set(widgets.map((widget) => widget.key));
  const hasActivity = visible.has("promotions.active") || visible.has("control.alerts");
  return (
    <section className="gestion-dashboard-overview" aria-label={t("gestion.dashboard")}>
      <div className="gestion-dashboard-summary-strip">
        {visible.has("sales.today") && (
          <DashboardOverviewPanel title={t("gestion.widget.sales.today")} className="sales-summary">
            <SalesTodayWidget state={salesState} t={t} onOpen={onOpenSales} />
          </DashboardOverviewPanel>
        )}
        {visible.has("promotions.active") && (
          <DashboardOverviewPanel title={t("gestion.widget.promotions.active")} className="dashboard-promotion-summary">
            <PromotionSummaryWidget state={promotionsState} t={t} onOpen={onOpenPromotions} />
          </DashboardOverviewPanel>
        )}
        {visible.has("control.alerts") && (
          <DashboardOverviewPanel title={t("gestion.widget.control.alerts")} className="alert-summary">
            <ControlAlertsSummaryWidget state={alertsState} t={t} onOpen={onOpenControlAlerts} />
          </DashboardOverviewPanel>
        )}
      </div>

      <div className={`gestion-dashboard-main-grid ${!hasActivity ? "single" : ""}`}>
        {visible.has("sales.top-products") && (
          <DashboardOverviewPanel title={t("gestion.widget.sales.top-products")} className="top-products-panel">
            <TopProductsWidget state={topProductsState} t={t} onOpen={onOpenStock} />
          </DashboardOverviewPanel>
        )}
        {hasActivity && (
          <section className="gestion-dashboard-activity" aria-labelledby="gestion-dashboard-activity-title">
            <h3 id="gestion-dashboard-activity-title">{t("gestion.dashboard.activity")}</h3>
            <div className="gestion-dashboard-activity-body">
              {visible.has("promotions.active") && (
                <DashboardOverviewPanel title={t("gestion.widget.promotions.active")} className="activity-promotions">
                  <ActivePromotionsWidget state={promotionsState} t={t} onOpen={onOpenPromotions} />
                </DashboardOverviewPanel>
              )}
              {visible.has("control.alerts") && (
                <DashboardOverviewPanel title={t("gestion.widget.control.alerts")} className="activity-alerts">
                  <ControlAlertsWidget state={alertsState} t={t} onOpen={onOpenControlAlerts} />
                </DashboardOverviewPanel>
              )}
            </div>
          </section>
        )}
      </div>
    </section>
  );
}

function DashboardOverviewPanel({ title, className = "", children }: { title: string; className?: string; children: ReactNode }) {
  return (
    <article className={`gestion-dashboard-panel ${className}`}>
      <header><strong>{title}</strong></header>
      <div className="gestion-dashboard-panel-body">{children}</div>
    </article>
  );
}

export function DashboardWidgetFrame({
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
  t: DashboardTranslator;
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

export function SalesTodayWidget({ state, t, onOpen }: WidgetProps<SalesTodayData>) {
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const data = state.data;
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

export function TopProductsWidget({ state, t, onOpen }: WidgetProps<TopProductData[]>) {
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const rows = state.data;
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

export function ActivePromotionsWidget({ state, t, onOpen }: WidgetProps<ActivePromotionData[]>) {
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const rows = state.data;
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

export function ControlAlertsWidget({ state, t, onOpen }: WidgetProps<ControlAlertsSummaryData>) {
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  const data = state.data;
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

function PromotionSummaryWidget({ state, t, onOpen }: WidgetProps<ActivePromotionData[]>) {
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  return (
    <div className="gestion-dashboard-summary-content">
      <div className="gestion-promotion-count"><strong>{state.data.length}</strong><span>{t("gestion.widget.activeCount")}</span></div>
      <WidgetFooter label={t("gestion.widget.openPromotions")} onOpen={onOpen} />
    </div>
  );
}

function ControlAlertsSummaryWidget({ state, t, onOpen }: WidgetProps<ControlAlertsSummaryData>) {
  if (state.loading) return <WidgetMessage text={t("common.loading")} />;
  if (state.error || !state.data) return <WidgetMessage text={t("gestion.widget.loadError")} error />;
  return (
    <div className="gestion-dashboard-summary-content">
      <div className="gestion-control-alert-counts">
        <div><strong>{state.data.newCount}</strong><span>{t("gestion.widget.controlAlerts.new")}</span></div>
        <div><strong>{state.data.reviewedCount}</strong><span>{t("gestion.widget.controlAlerts.reviewed")}</span></div>
      </div>
      <WidgetFooter label={t("gestion.widget.controlAlerts.open")} onOpen={onOpen} />
    </div>
  );
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
