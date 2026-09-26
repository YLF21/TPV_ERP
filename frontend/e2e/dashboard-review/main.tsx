import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { createTranslator, type LocaleCode, type UserSession } from "@tpverp/app-common";
import { ChartBar, BellRinging, Package, Receipt, Users, Warehouse, Gear } from "@phosphor-icons/react";
import { GestionDashboard } from "../../apps/app-gestion/src/GestionDashboard";
import { dashboardPreset, defaultDashboardOptions, type DashboardWidgetKey } from "../../apps/app-gestion/src/dashboardModel";
import { GestionShell } from "../../apps/app-gestion/src/GestionShell";
import "../../packages/app-common/src/styles/tpv.css";
import "../../apps/app-gestion/src/gestion.css";

const parameters = new URLSearchParams(location.search);
const locale = (parameters.get("locale") || "es") as LocaleCode;
const t = createTranslator(locale);
const username = parameters.get("user") || "SUPERVISOR";
const session: UserSession = { username, displayName: username, accessToken: "isolated-dashboard-review", permissions: parameters.has("restricted")
  ? ["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"] : ["APP_GESTION_ACCESS", "GESTION_VENTAS", "GESTION_PRODUCTO", "CONTROL_ALERTS_READ", "CUSTOMER_RECEIVABLES_READ"] };
const today = "2026-09-25";
const availableWidgets: DashboardWidgetKey[] = parameters.has("restricted") ? ["control.alerts"] : ["sales.today", "sales.operations", "sales.average", "sales.units", "sales.trend", "sales.families", "sales.top-products", "control.alerts", "promotions.active", "sales.hourly", "sales.corrections", "sales.payments", "finance.receivables"];
const defaults = { widgets: dashboardPreset("BALANCED", availableWidgets), options: { ...defaultDashboardOptions } };
let preferences = JSON.parse(localStorage.getItem(`dashboard-review:${username}`) || "null") || defaults;
let failNextRead = parameters.has("load-error"), failNextSave = false;
const errors: string[] = [];
window.addEventListener("error", event => errors.push(event.message));
window.addEventListener("unhandledrejection", event => errors.push(String(event.reason)));
function shift(day: string, offset: number) { const date = new Date(`${day}T12:00:00Z`); date.setUTCDate(date.getUTCDate() + offset); return date.toISOString().slice(0, 10); }
function overview(params: URLSearchParams) {
  const from = params.get("from") || "2026-09-01", to = params.get("to") || today;
  const length = Math.round((Date.parse(to) - Date.parse(from)) / 86400000) + 1;
  const previousFrom = shift(from, -length), previousTo = shift(from, -1);
  const factor = parameters.has("empty") ? 0 : params.get("warehouseId") ? 0.42 : 1;
  function series(start: string, previous: boolean) {
    return Array.from({ length }, (_, index) => ({ date: shift(start, index),
      netSales: Math.round(factor * (parameters.has("negative") && index % 3 === 0 ? -360 : 2250 + index * 39 + (index % 5) * 175) * (previous ? 0.79 : 1) * 100) / 100,
      operationCount: factor ? Math.round(factor * (64 + index % 7 * 4) * (previous ? 0.85 : 1)) : 0 }));
  }
  function metric(rows: ReturnType<typeof series>) { const netSales = Math.round(rows.reduce((sum, row) => sum + row.netSales, 0) * 100) / 100; const operationCount = rows.reduce((sum, row) => sum + row.operationCount, 0); return { netSales, operationCount, averageAmount: operationCount ? Math.round(netSales / operationCount * 100) / 100 : 0 }; }
  const daily = series(from, false), previousDaily = series(previousFrom, true);
  const current = { ...metric(daily), netUnits: Math.round(factor * 3800) }, previous = { ...metric(previousDaily), netUnits: Math.round(factor * 2900) };
  const families = factor ? ["Alimentación", "Bebidas", "Limpieza", "Congelados", "Frescos"].map((name, i) => ({
    key: `family-${i}`, name, currentSales: Math.round(current.netSales * [0.40, 0.25, 0.15, 0.1, 0.12][i] * 100) / 100,
    previousSales: Math.round(previous.netSales * [0.35, 0.3, 0.15, 0.1, 0.12][i] * 100) / 100,
    currentUnits: Math.round(current.netUnits * [0.4, 0.2, 0.2, 0.1, 0.1][i]), previousUnits: Math.round(previous.netUnits * [0.4, 0.2, 0.2, 0.1, 0.1][i])
  })) : [];
  if (factor) families.push({ key: "ADJUSTMENTS", name: "", currentSales: Math.round((current.netSales - families.reduce((sum, f) => sum + f.currentSales, 0)) * 100) / 100,
    previousSales: Math.round((previous.netSales - families.reduce((sum, f) => sum + f.previousSales, 0)) * 100) / 100, currentUnits: 0, previousUnits: 0 });
  const topProducts = factor ? ["Agua mineral 1,5 L", "Leche entera 1 L", "Arroz largo 1 kg", "Aceite de oliva virgen 1 L", "Pan de molde integral", "Café molido natural 250 g"].map((name, index) => ({
    productId: `product-${index}`, code: `000${index + 1}`, name, netQuantity: Math.round(factor * (480 - index * 63)),
    familyName: index === 0 ? "Bebidas" : "Alimentación", netAmount: Math.round(factor * (index === 3 ? 2450 : 800 - index * 54) * 100) / 100
  })) : [];
  const hourly = daily.flatMap((row, index) => [
    { date: row.date, hour: 9, sales: Math.round(row.netSales * 0.2 * 100) / 100, units: factor ? 40 + index : 0, operations: Math.floor(row.operationCount * 0.2) },
    { date: row.date, hour: 13, sales: Math.round(row.netSales * 0.5 * 100) / 100, units: factor ? 110 + index : 0, operations: Math.floor(row.operationCount * 0.5) },
    { date: row.date, hour: 18, sales: Math.round(row.netSales * 0.3 * 100) / 100, units: factor ? 70 + index : 0, operations: row.operationCount - Math.floor(row.operationCount * 0.2) - Math.floor(row.operationCount * 0.5) }
  ]);
  current.netUnits = hourly.reduce((sum, row) => sum + row.units, 0);
  families.forEach((family, index) => { family.currentUnits = family.key === "ADJUSTMENTS" ? 0 : Math.round(current.netUnits * [0.4, 0.2, 0.2, 0.1, 0.1][index]); });
  return { from, to, previousFrom, previousTo, storeTimezone: "Atlantic/Canary", currency: "EUR", current, previous, daily, previousDaily, hourly,
    corrections: factor ? [{ kind: "RETURNS", operations: 5, amount: -125 }, { kind: "ECONOMIC", operations: 2, amount: -30 }, { kind: "INCREASES", operations: 1, amount: 10 }] : [],
    payments: factor ? [{ method: "EFECTIVO", collected: 1800, refunded: 125, net: 1675 }, { method: "TARJETA", collected: 2450, refunded: 30, net: 2420 }] : [],
    families, topProducts, topProductsByAmount: [...topProducts].sort((a, b) => b.netAmount - a.netAmount) };
}

// Unknown endpoints fail locally; no request can reach a real server.
window.fetch = async (input, init) => {
  const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href);
  const path = url.pathname.replace(/^\/api\/v1/, ""), method = init?.method || "GET";
  const body = init?.body ? JSON.parse(String(init.body)) : {};
  if (method === "PUT" && failNextSave) { failNextSave = false; return Response.json({ message: "Fallo de guardado simulado" }, { status: 503 }); }
  if (method === "GET" && failNextRead && path.startsWith("/gestion/dashboard")) { failNextRead = false; return Response.json({ message: "Fallo de lectura simulado" }, { status: 503 }); }
  let result: unknown;
  if (path === "/gestion/dashboard/preference") {
    if (method === "PUT") { preferences = body; localStorage.setItem(`dashboard-review:${username}`, JSON.stringify(body)); }
    result = { ...preferences, widgets: preferences.widgets.filter((widget: { key: DashboardWidgetKey }) => availableWidgets.includes(widget.key)), availableWidgets, businessDate: today, storeTimezone: "Atlantic/Canary" };
  } else if (path === "/gestion/dashboard/data/sales-overview") result = overview(url.searchParams);
  else if (path === "/gestion/dashboard/data/sales-hourly") {
    const from = url.searchParams.get("from") || url.searchParams.get("day") || today;
    const to = url.searchParams.get("to") || from;
    const comparisonFrom = url.searchParams.get("comparisonFrom") || url.searchParams.get("comparisonDay");
    const comparisonTo = url.searchParams.get("comparisonTo") || comparisonFrom;
    const factor = parameters.has("empty") ? 0 : url.searchParams.get("warehouseId") ? 0.42 : 1;
    const hours = (start: string, end: string) => {
      const days = Math.round((Date.parse(end) - Date.parse(start)) / 86400000) + 1;
      return [12, 28, 46, 72, 110, 156, 128, 84, 62, 95, 138, 104, 57, 22].map((units, index) => {
        const count = Math.round(units * factor * (end === today ? 1 : .8));
        return { date: start, hour: index + 8, units: days * (parameters.has("negative") && index === 2 ? -count : count),
          sales: days * (parameters.has("negative") && index === 2 ? -1 : 1) * count * 8.5, operations: days * Math.ceil(count / 4) };
      });
    };
    result = { day: from, comparisonDay: comparisonFrom, from, to, comparisonFrom, comparisonTo, storeTimezone: "Atlantic/Canary", currency: "EUR", current: hours(from, to), previous: comparisonFrom && comparisonTo ? hours(comparisonFrom, comparisonTo) : [] };

  }
  else if (path === "/gestion/dashboard/data/receivables-summary") result = { asOf: today, currency: "EUR", balances: parameters.has("empty") ? [] : [
    { kind: "OVERDUE", documents: 3, amount: 480 }, { kind: "NOT_DUE", documents: 5, amount: 920 }, { kind: "NO_DUE_DATE", documents: 2, amount: 150 }
  ] };
  else if (path === "/warehouses") result = [{ id: "warehouse-main", name: "Almacén general", active: true }, { id: "warehouse-secondary", name: "Almacén secundario", active: true }];
  else if (path === "/gestion/dashboard/data/active-promotions") result = parameters.has("empty") ? [] : [
    { id: "promo-1", name: "Promoción de septiembre", type: "QUANTITY_DISCOUNT", startDate: "2026-09-01", endDate: "2026-09-30", minimumQuantity: 3, discountPercent: 15 },
    { id: "promo-2", name: "Bebidas · Segunda unidad", type: "SECOND_UNIT_PERCENT", startDate: "2026-09-01", endDate: "2026-09-30", discountPercent: 50 }
  ];
  else if (path === "/control/alerts/summary") result = { newCount: parameters.has("empty") ? 0 : 8, reviewedCount: parameters.has("empty") ? 0 : 2, recentAlerts: parameters.has("empty") ? [] : ["MANUAL_DISCOUNT_OVER_PERCENT", "MANUAL_PRICE_CHANGED", "SALE_SCREEN_CLEARED"].map((type, index) => ({ id: `alert-${index}`, type, status: index === 2 ? "CLOSED" : index === 1 ? "REVIEWED" : "NEW", occurredAt: `${today}T10:${42 - index * 11}:00Z`, documentNumber: `T-2026-00${1821 - index}`, userName: "MARÍA" })) };
  else { errors.push(`${method} ${path}`); return Response.json({ message: "Ruta no simulada" }, { status: 400 }); }
  return Response.json(result);
};

function Comparison() {
  const [scale, setScale] = useState(() => (window.innerWidth / 2 - 12) / 1487);
  useEffect(() => {
    const resize = () => setScale((window.innerWidth / 2 - 12) / 1487);
    window.addEventListener("resize", resize); return () => window.removeEventListener("resize", resize);
  }, []);
  return <div style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr)", gap: 12, padding: 6, background: "#e0e7ef" }}>
    <section style={{ minWidth: 0 }}><h2>Referencia aprobada</h2><img src="/reference.png" style={{ width: "100%" }} /></section>
    <section style={{ minWidth: 0 }}><h2>Implementación · mismo tamaño</h2><div style={{ height: 1058 * scale, overflow: "hidden" }}>
      <iframe title="Resumen implementado" src="/?user=COMPARACION&compare-child" style={{ width: 1487, height: 1058, maxWidth: "none", border: 0, transform: `scale(${scale})`, transformOrigin: "top left" }}/></div></section>
  </div>;
}
function Review() {
  const [status, setStatus] = useState("");
  return <div className="app-frame">
    <div style={{ background: "#10243a", color: "white", padding: "5px 14px", display: "flex", alignItems: "center", gap: 12, fontSize: 12 }}>
      <span>REVISIÓN AISLADA · Datos ficticios · {username}</span>
      <button onClick={() => { failNextRead = true; }}>Fallar lectura</button>
      <button onClick={() => { failNextSave = true; }}>Fallar guardado</button>
      <button onClick={() => setStatus(errors.length ? errors.join(" · ") : "0 errores")}>Comprobar errores</button>
      <span role="status">{status}</span>
    </div>
    <GestionShell session={session} t={t} activeKey="summary" navigation={[
      { key: "summary", label: t("gestion.dashboard"), icon: ChartBar }, { key: "alerts", label: t("gestion.controlAlerts.title"), icon: BellRinging },
      { key: "sales", label: t("gestion.sales"), icon: Receipt }, { key: "products", label: t("gestion.products"), icon: Package },
      { key: "customers", label: t("gestion.customers"), icon: Users }, { key: "stock", label: t("gestion.stock"), icon: Warehouse }, { key: "settings", label: t("gestion.navigation.group.configuration"), icon: Gear }
    ]}><GestionDashboard key={username} session={session} t={t} locale={locale} onOpenSales={() => setStatus("Navegación: ventas")} onOpenStock={() => setStatus("Navegación: productos")} onOpenPromotions={() => setStatus("Navegación: promociones")} onOpenControlAlerts={() => setStatus("Navegación: alertas")}/></GestionShell>
  </div>;
}
const root = createRoot(document.getElementById("root")!);
root.render(<StrictMode>{parameters.has("compare") ? <Comparison/> : <Review/>}</StrictMode>);
import.meta.hot?.dispose(() => root.unmount());
