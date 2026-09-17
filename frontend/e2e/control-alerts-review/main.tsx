import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { createTranslator, type LocaleCode, type UserSession } from "@tpverp/app-common";
import { ChartBar, BellRinging, Package, Receipt, Users, Warehouse, Gear } from "@phosphor-icons/react";
import { ControlAlertsScreen } from "../../apps/app-gestion/src/ControlAlertsScreen";
import { GestionShell } from "../../apps/app-gestion/src/GestionShell";
import { defaultControlAlertView, type ControlAlert, type ControlRule } from "../../apps/app-gestion/src/controlAlertsApi";
import "../../packages/app-common/src/styles/tpv.css";
import "../../apps/app-gestion/src/gestion.css";

const parameters = new URLSearchParams(location.search);
const locale = (parameters.get("locale") || "es") as LocaleCode;
const t = createTranslator(locale);
const username = parameters.get("user") || "SUPERVISOR";
const session: UserSession = { username, displayName: username, accessToken: "isolated-review-only", permissions: parameters.has("reader") ? ["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"] : ["ADMIN"] };
const today = new Date();
function instant(days: number, hour: number, minute: number) {
  const date = new Date(today); date.setUTCDate(date.getUTCDate() - days); date.setUTCHours(hour, minute, 0, 0); return date.toISOString();
}
const rules: ControlRule[] = [
  { id: "rule-price", type: "MANUAL_PRICE_CHANGED", name: "Cambio manual de precio", active: true, configuration: {}, ruleVersion: 1, version: 0 },
  { id: "rule-discount", type: "MANUAL_DISCOUNT_OVER_PERCENT", name: "Descuento manual superior al porcentaje", active: true, configuration: { thresholdPercent: 10 }, ruleVersion: 1, version: 0 },
  { id: "rule-clear", type: "SALE_SCREEN_CLEARED", name: "Pantalla de venta vaciada", active: true, configuration: {}, ruleVersion: 1, version: 0 }
];
const rows: ControlAlert[] = parameters.has("empty") ? [] : Array.from({ length: 8 }, (_, index) => {
  const rule = rules[index % 3];
  return {
    id: `alert-${index}`, type: rule.type, ruleId: rule.id, ruleName: rule.name, ruleVersion: 1,
    status: index === 4 ? "REVIEWED" : index === 6 ? "CLOSED" : "NEW", priority: index === 0 ? "HIGH" : "MEDIUM",
    occurredAt: instant(index < 5 ? 0 : 1, index < 5 ? 11 - index : 18 - index, 42 - index * 3),
    documentId: rule.type === "SALE_SCREEN_CLEARED" ? null : `doc-${index}`, documentNumber: rule.type === "SALE_SCREEN_CLEARED" ? null : `T-2026-${String(1821 - index).padStart(6, "0")}`,
    terminalId: "terminal-demo", terminalName: "CAJA 1", userId: index % 2 ? "user-luis" : "user-maria", userName: index % 2 ? "LUIS" : "MARÍA", version: 0,
    reviewComment: index === 4 ? "Revisado con el responsable de caja" : null,
    data: rule.type === "MANUAL_PRICE_CHANGED" ? { currency: "EUR", changedLines: [{ position: 1, productId: "product-demo", name: "Artículo de ejemplo", code: "00123", originalPrice: 10, appliedPrice: 12, changePercent: -20 }] }
      : rule.type === "MANUAL_DISCOUNT_OVER_PERCENT" ? { thresholdPercent: 10, globalDiscountPercent: 15, matchingLines: [] }
        : { lineCount: 4, total: 32.5, lines: [{ productId: "product-demo", code: "00123", name: "Artículo de ejemplo", quantity: 4, unitPrice: 8.125, total: 32.5 }] },
    history: [], workHistory: []
  };
});

function filtered(params: URLSearchParams, facets = false) {
  const from = params.get("from"), to = params.get("to"), search = (params.get("search") || "").toLocaleLowerCase();
  return rows.filter(row => (!from || row.occurredAt >= from) && (!to || row.occurredAt < to)
    && (!params.get("status") || row.status === params.get("status"))
    && (!params.get("priority") || row.priority === params.get("priority"))
    && (!params.get("assigneeId") || row.assigneeId === params.get("assigneeId"))
    && (!params.get("overdue") || Boolean(row.dueAt && row.dueAt < new Date().toISOString() && ["NEW", "REVIEWED"].includes(row.status)))
    && (facets || !params.get("type") || row.type === params.get("type"))
    && (facets || !params.get("ruleId") || row.ruleId === params.get("ruleId"))
    && (!search || [row.userName, row.documentNumber, row.ruleName].join(" ").toLocaleLowerCase().includes(search)));
}
let preferences = JSON.parse(localStorage.getItem(`control-alerts-review:${username}`) || "null") || defaultControlAlertView;
let tablePreference: unknown = null;
let failNextRequest = false;
const requestFailures: string[] = [];
window.addEventListener("error", event => { requestFailures.push(event.message); });
window.addEventListener("unhandledrejection", event => { requestFailures.push(String(event.reason)); });

// Every request is intercepted before mounting. Unknown endpoints fail locally.
window.fetch = async (input, init) => {
  const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href);
  const path = url.pathname.replace(/^\/api\/v1/, ""), method = init?.method || "GET";
  const body = init?.body ? JSON.parse(String(init.body)) : {};
  if (failNextRequest && !path.includes("table-preferences")) { failNextRequest = false; return Response.json({ message: "Error de prueba" }, { status: 503 }); }
  let result: unknown;
  if (path === "/control/alerts/view-preference") {
    if (method === "PUT") { preferences = body; localStorage.setItem(`control-alerts-review:${username}`, JSON.stringify(body)); }
    result = { ...preferences, storeTimezone: "Atlantic/Canary", storeLocale: "es-ES" };
  } else if (path.includes("/ui/table-preferences/")) {
    if (method === "PUT") tablePreference = body;
    result = tablePreference || { app: "gestion", tableKey: decodeURIComponent(path.split("/").at(-1)!), columns: [] };
  } else if (path === "/control/alerts/groups") result = rules.map(rule => {
    const group = filtered(url.searchParams, true).filter(row => row.ruleId === rule.id);
    return { ruleId: rule.id, type: rule.type, ruleName: rule.name, active: rule.active, supported: true, total: group.length,
      newCount: group.filter(row => row.status === "NEW").length, reviewedCount: group.filter(row => row.status === "REVIEWED").length,
      closedCount: group.filter(row => row.status === "CLOSED").length, dismissedCount: group.filter(row => row.status === "DISMISSED").length,
      configuration: rule.configuration, parameterKind: rule.type === "MANUAL_DISCOUNT_OVER_PERCENT" ? "PERCENTAGE" : "NONE" };
  });
  else if (path === "/control/alerts") {
    const list = filtered(url.searchParams).sort((a, b) => (a.occurredAt.localeCompare(b.occurredAt) || a.id.localeCompare(b.id)) * (url.searchParams.get("sortDirection") === "asc" ? 1 : -1));
    const size = Number(url.searchParams.get("size") || 25), page = Number(url.searchParams.get("page") || 0);
    result = { content: list.slice(page * size, (page + 1) * size), number: page, size, totalElements: list.length, totalPages: Math.ceil(list.length / size) };
  } else if (path === "/control/alerts/assignees") result = [{ id: "user-manager", userName: "SUPERVISOR", name: "Supervisor" }];
  else if (path === "/control/alerts/analytics") result = { total: rows.length, overdueCount: 0, byStatus: [], byType: [], byUser: [], byTerminal: [] };
  else if (path === "/control/rules/catalog") result = rules.map(rule => ({ type: rule.type, name: rule.name, parameterKind: rule.type === "MANUAL_DISCOUNT_OVER_PERCENT" ? "PERCENTAGE" : "NONE", defaultConfiguration: rule.configuration, supported: true, configured: true, ruleId: rule.id }));
  else if (path === "/control/rules") result = rules;
  else if (path.startsWith("/control/rules/")) {
    const rule = rules.find(rule => path.endsWith(rule.id));
    if (!rule) return Response.json({}, { status: 404 });
    Object.assign(rule, body, { version: rule.version + 1 }); result = rule;
  } else if (path.startsWith("/control/alerts/alert-")) {
    const row = rows.find(row => path.split("/")[3] === row.id);
    if (!row) return Response.json({}, { status: 404 });
    const action = path.split("/")[4];
    if (action === "document") result = { id: row.documentId, number: row.documentNumber, type: "TICKET", status: "CONFIRMED", date: row.occurredAt.slice(0, 10), globalDiscount: 0, lines: [{ position: 1, lineType: "PRODUCT", productId: "product-demo", code: "00123", name: "Artículo de ejemplo", quantity: 1, unitPrice: 12, discount: 0, taxesIncluded: true, taxRegime: "IVA", taxPercent: 21, base: 9.92, tax: 2.08, total: 12 }], baseTotal: 9.92, taxTotal: 2.08, total: 12, currency: "EUR", payments: [] };
    else {
      if (method !== "GET" && body.version !== row.version) return Response.json({ code: "version_conflict" }, { status: 409 });
      if (action === "work") Object.assign(row, body, { version: row.version + 1 });
      else if (action) {
        const next = action === "reopen" ? "NEW" : action === "review" ? "REVIEWED" : action === "close" ? "CLOSED" : "DISMISSED";
        row.history!.push({ previousStatus: row.status, newStatus: next, changedBy: username, changedByName: username, changedAt: new Date().toISOString(), comment: body.comment });
        if (body.comment?.trim()) row.reviewComment = body.comment.trim();
        row.status = next; row.version++;
      }
      result = { alert: row, history: row.history, workHistory: row.workHistory };
    }
  } else { requestFailures.push(`${method} ${path}`); return Response.json({ message: "Ruta no simulada" }, { status: 400 }); }
  return Response.json(result);
};

function Comparison() {
  const [scale, setScale] = useState(() => (window.innerWidth / 2 - 12) / 1487);
  window.onresize = () => setScale((window.innerWidth / 2 - 12) / 1487);
  return <div style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr)", gap: 12, padding: 6, background: "#dee5ed" }}>
    <section style={{ minWidth: 0 }}><h2>Referencia seleccionada</h2><img src="/reference.png" style={{ width: "100%" }} /></section>
    <section style={{ minWidth: 0 }}><h2>Implementación · mismo ancho</h2><div style={{ height: 1100 * scale, overflow: "hidden" }}><iframe title="Implementación real" src="/?compare-child" style={{ width: 1487, height: 1058, maxWidth: "none", border: 0, transform: `scale(${scale})`, transformOrigin: "top left" }}/></div></section>
  </div>;
}

function Review() {
  const [errors, setErrors] = useState<string[]>([]);
  return <div className="app-frame">
    <div style={{ background: "#10243a", color: "white", padding: "5px 14px", display: "flex", alignItems: "center", gap: 16, fontSize: 12 }}>
      <span>REVISIÓN AISLADA · Datos ficticios · Usuario: {username}</span>
      <button onClick={() => { failNextRequest = true; }}>Simular un fallo de API</button>
      <button onClick={() => setErrors([...requestFailures])}>Comprobar errores ({errors.length})</button>
      <span>{errors.join(" · ")}</span>
    </div>
    <GestionShell session={session} t={t} activeKey="alerts" navigation={[
      { key: "summary", label: t("gestion.dashboard"), icon: ChartBar }, { key: "alerts", label: t("gestion.controlAlerts.title"), icon: BellRinging },
      { key: "sales", label: t("gestion.sales"), icon: Receipt }, { key: "products", label: t("gestion.products"), icon: Package },
      { key: "customers", label: t("gestion.customers"), icon: Users }, { key: "stock", label: t("gestion.stock"), icon: Warehouse }, { key: "settings", label: t("gestion.navigation.group.configuration"), icon: Gear }
    ]}><ControlAlertsScreen key={username} session={session} t={t} locale={locale}/></GestionShell>
  </div>;
}
const root = createRoot(document.getElementById("root")!);
root.render(<StrictMode>{parameters.has("compare") ? <Comparison/> : <Review/>}</StrictMode>);
import.meta.hot?.dispose(() => root.unmount());
