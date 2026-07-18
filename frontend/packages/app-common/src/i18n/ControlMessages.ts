import type { LocaleCode } from "../types";

const es = {
  navigation: "Alertas de control", eyebrow: "Supervisi\u00f3n operativa", title: "Alertas de control", refresh: "Actualizar", rules: "Reglas",
  search: "Buscar", filterStatus: "Estado", filterType: "Tipo", all: "Todos", apply: "Aplicar filtros", list: "Listado de alertas", detail: "Detalle de la alerta",
  resize: "Redimensionar columna", loadError: "No se pudieron cargar las alertas", detailError: "No se pudo cargar el detalle o realizar la acci\u00f3n",
  empty: "No hay alertas para los filtros seleccionados", select: "Selecciona una alerta para consultar su detalle", results: "{count} alertas", previous: "Anterior", next: "Siguiente",
  noDocument: "Sin documento", summary: "Motivo registrado", reviewed: "Revisada", closed: "Cerrada", comment: "Comentario", openDocument: "Abrir venta relacionada",
  documentError: "No se pudo cargar la venta relacionada", actionComment: "Comentario de revisi\u00f3n", rule: "Regla aplicada", history: "Historial",
  summaryManualDiscount: "Umbral: {threshold} %. Descuento global: {global} %. L\u00edneas afectadas: {lines}.", summaryInactiveProducts: "{count} productos desactivados: {products}.",
  summaryTicketCancelled: "Ticket {document}. Motivo: {reason}.", summarySaleCleared: "Venta en curso borrada con {count} l\u00edneas e importe {total}.",
  backToRules: "Volver a reglas", dateFilter: "Periodo de supervisi\u00f3n", from: "Desde", to: "Hasta", quickRanges: "Periodos r\u00e1pidos", today: "Hoy", lastSevenDays: "7 d\u00edas", currentMonth: "Este mes",
  ruleBlocks: "Reglas de control", total: "Alertas del periodo", newCount: "Nuevas", openList: "Ver alertas",
  summaryConsecutiveDeletions: "{count} l\u00edneas eliminadas de forma consecutiva.", summaryProductDiscount: "Descuento manual aplicado a {count} l\u00edneas de producto.", summaryManualPrice: "Cambio manual de precio registrado."
};

const en = {
  navigation: "Control alerts", eyebrow: "Operational supervision", title: "Control alerts", refresh: "Refresh", rules: "Rules",
  search: "Search", filterStatus: "Status", filterType: "Type", all: "All", apply: "Apply filters", list: "Alert list", detail: "Alert details",
  resize: "Resize column", loadError: "Alerts could not be loaded", detailError: "The details or action could not be completed",
  empty: "No alerts match the selected filters", select: "Select an alert to view its details", results: "{count} alerts", previous: "Previous", next: "Next",
  noDocument: "No document", summary: "Recorded reason", reviewed: "Reviewed", closed: "Closed", comment: "Comment", openDocument: "Open related sale",
  documentError: "The related sale could not be loaded", actionComment: "Review comment", rule: "Applied rule", history: "History",
  summaryManualDiscount: "Threshold: {threshold}%. Global discount: {global}%. Affected lines: {lines}.", summaryInactiveProducts: "{count} inactive products: {products}.",
  summaryTicketCancelled: "Ticket {document}. Reason: {reason}.", summarySaleCleared: "Open sale cleared with {count} lines and amount {total}.",
  backToRules: "Back to rules", dateFilter: "Supervision period", from: "From", to: "To", quickRanges: "Quick periods", today: "Today", lastSevenDays: "7 days", currentMonth: "This month",
  ruleBlocks: "Control rules", total: "Period alerts", newCount: "New", openList: "View alerts",
  summaryConsecutiveDeletions: "{count} lines deleted consecutively.", summaryProductDiscount: "Manual discount applied to {count} product lines.", summaryManualPrice: "Manual price change recorded."
};

const zh = {
  navigation: "\u63a7\u5236\u8b66\u62a5", eyebrow: "\u8fd0\u8425\u76d1\u7763", title: "\u63a7\u5236\u8b66\u62a5", refresh: "\u5237\u65b0", rules: "\u89c4\u5219",
  search: "\u641c\u7d22", filterStatus: "\u72b6\u6001", filterType: "\u7c7b\u578b", all: "\u5168\u90e8", apply: "\u5e94\u7528\u7b5b\u9009", list: "\u8b66\u62a5\u5217\u8868", detail: "\u8b66\u62a5\u8be6\u60c5",
  resize: "\u8c03\u6574\u5217\u5bbd", loadError: "\u65e0\u6cd5\u52a0\u8f7d\u8b66\u62a5", detailError: "\u65e0\u6cd5\u52a0\u8f7d\u8be6\u60c5\u6216\u5b8c\u6210\u64cd\u4f5c",
  empty: "\u6ca1\u6709\u7b26\u5408\u5f53\u524d\u7b5b\u9009\u7684\u8b66\u62a5", select: "\u8bf7\u9009\u62e9\u8b66\u62a5\u67e5\u770b\u8be6\u60c5", results: "{count} \u6761\u8b66\u62a5", previous: "\u4e0a\u4e00\u9875", next: "\u4e0b\u4e00\u9875",
  noDocument: "\u65e0\u5355\u636e", summary: "\u8bb0\u5f55\u539f\u56e0", reviewed: "\u5df2\u5ba1\u6838", closed: "\u5df2\u5173\u95ed", comment: "\u5907\u6ce8", openDocument: "\u6253\u5f00\u76f8\u5173\u9500\u552e",
  documentError: "\u65e0\u6cd5\u52a0\u8f7d\u76f8\u5173\u9500\u552e", actionComment: "\u5ba1\u6838\u5907\u6ce8", rule: "\u5e94\u7528\u89c4\u5219", history: "\u5386\u53f2",
  summaryManualDiscount: "\u9608\u503c: {threshold}%. \u5168\u5c40\u6298\u6263: {global}%. \u53d7\u5f71\u54cd\u884c: {lines}.", summaryInactiveProducts: "{count} \u4e2a\u5df2\u505c\u7528\u5546\u54c1: {products}.",
  summaryTicketCancelled: "\u5c0f\u7968 {document}. \u539f\u56e0: {reason}.", summarySaleCleared: "\u5df2\u6e05\u9664\u5305\u542b {count} \u884c\u3001\u91d1\u989d {total} \u7684\u672a\u5b8c\u6210\u9500\u552e.",
  backToRules: "\u8fd4\u56de\u89c4\u5219", dateFilter: "\u76d1\u7763\u671f\u95f4", from: "\u5f00\u59cb", to: "\u7ed3\u675f", quickRanges: "\u5feb\u901f\u671f\u95f4", today: "\u4eca\u5929", lastSevenDays: "7\u5929", currentMonth: "\u672c\u6708",
  ruleBlocks: "\u63a7\u5236\u89c4\u5219", total: "\u671f\u95f4\u8b66\u62a5", newCount: "\u65b0\u5efa", openList: "\u67e5\u770b\u8b66\u62a5",
  summaryConsecutiveDeletions: "\u8fde\u7eed\u5220\u9664 {count} \u884c\u3002", summaryProductDiscount: "\u5bf9 {count} \u4e2a\u5546\u54c1\u884c\u5e94\u7528\u4e86\u624b\u52a8\u6298\u6263\u3002", summaryManualPrice: "\u5df2\u8bb0\u5f55\u624b\u52a8\u4ef7\u683c\u66f4\u6539\u3002"
};

const localeValues = { es, en, zh } as const;

const fixed = {
  es: {
    actions: ["Marcar revisada", "Cerrar alerta", "Descartar alerta"], columns: ["Fecha y hora", "Empleado", "Terminal", "Venta / ticket", "Detalle", "Estado"],
    statuses: ["Nueva", "Revisada", "Cerrada", "Descartada"], types: ["Eliminaci\u00f3n completa de carrito", "Eliminaci\u00f3n de l\u00edneas consecutivas", "Cambio manual de precio superior al porcentaje", "Cambio manual de precio", "Descuento manual superior al porcentaje", "Descuento manual aplicado a producto", "Anulaci\u00f3n de ticket", "Venta de producto desactivado"],
    rule: ["Reglas de alertas", "Reglas configuradas", "Nueva regla", "No hay reglas configuradas", "Modificar regla", "Crear regla", "Tipo de control", "Porcentaje l\u00edmite", "Este tipo no necesita par\u00e1metros adicionales.", "Activa", "Inactiva", "Activar", "Desactivar", "No se pudo guardar o actualizar la regla"],
    document: ["Estado", "Fecha", "Cliente", "Producto", "Cantidad", "Precio", "Descuento", "Total", "Pagos", "Subtotal", "Impuestos"]
  },
  en: {
    actions: ["Mark reviewed", "Close alert", "Dismiss alert"], columns: ["Date and time", "Employee", "Terminal", "Sale / ticket", "Details", "Status"],
    statuses: ["New", "Reviewed", "Closed", "Dismissed"], types: ["Full cart deletion", "Consecutive line deletion", "Manual price change above percentage", "Manual price change", "Manual discount above percentage", "Manual product discount", "Ticket cancelled", "Inactive product sold"],
    rule: ["Alert rules", "Configured rules", "New rule", "No rules configured", "Edit rule", "Create rule", "Control type", "Percentage limit", "This type requires no additional parameters.", "Active", "Inactive", "Activate", "Deactivate", "The rule could not be saved or updated"],
    document: ["Status", "Date", "Customer", "Product", "Quantity", "Price", "Discount", "Total", "Payments", "Subtotal", "Tax"]
  },
  zh: {
    actions: ["\u6807\u8bb0\u5df2\u5ba1\u6838", "\u5173\u95ed\u8b66\u62a5", "\u5ffd\u7565\u8b66\u62a5"], columns: ["\u65e5\u671f\u548c\u65f6\u95f4", "\u5458\u5de5", "\u7ec8\u7aef", "\u9500\u552e / \u5c0f\u7968", "\u8be6\u60c5", "\u72b6\u6001"],
    statuses: ["\u65b0\u5efa", "\u5df2\u5ba1\u6838", "\u5df2\u5173\u95ed", "\u5df2\u5ffd\u7565"], types: ["\u5220\u9664\u6574\u4e2a\u8d2d\u7269\u8f66", "\u8fde\u7eed\u5220\u9664\u884c", "\u624b\u52a8\u6539\u4ef7\u8d85\u8fc7\u767e\u5206\u6bd4", "\u624b\u52a8\u6539\u4ef7", "\u624b\u52a8\u6298\u6263\u8d85\u8fc7\u767e\u5206\u6bd4", "\u624b\u52a8\u5546\u54c1\u6298\u6263", "\u5c0f\u7968\u5df2\u53d6\u6d88", "\u552e\u51fa\u5df2\u505c\u7528\u5546\u54c1"],
    rule: ["\u8b66\u62a5\u89c4\u5219", "\u5df2\u914d\u7f6e\u89c4\u5219", "\u65b0\u5efa\u89c4\u5219", "\u5c1a\u672a\u914d\u7f6e\u89c4\u5219", "\u7f16\u8f91\u89c4\u5219", "\u521b\u5efa\u89c4\u5219", "\u63a7\u5236\u7c7b\u578b", "\u767e\u5206\u6bd4\u9650\u5236", "\u6b64\u7c7b\u578b\u65e0\u9700\u5176\u4ed6\u53c2\u6570\u3002", "\u542f\u7528", "\u505c\u7528", "\u542f\u7528", "\u505c\u7528", "\u65e0\u6cd5\u4fdd\u5b58\u6216\u66f4\u65b0\u89c4\u5219"],
    document: ["\u72b6\u6001", "\u65e5\u671f", "\u5ba2\u6237", "\u5546\u54c1", "\u6570\u91cf", "\u4ef7\u683c", "\u6298\u6263", "\u5408\u8ba1", "\u652f\u4ed8", "\u5c0f\u8ba1", "\u7a0e\u989d"]
  }
} as const;

export function controlMessages(locale: LocaleCode): Record<string, string> {
  const values = localeValues[locale];
  const data = fixed[locale];
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(values)) result[`gestion.controlAlerts.${key}`] = value;
  ["REVIEW", "CLOSE", "DISMISS"].forEach((key, index) => { result[`gestion.controlAlerts.action.${key}`] = data.actions[index]; });
  ["occurredAt", "username", "terminal", "document", "detail", "status"].forEach((key, index) => { result[`gestion.controlAlerts.column.${key}`] = data.columns[index]; });
  ["NEW", "REVIEWED", "CLOSED", "DISMISSED"].forEach((key, index) => { result[`gestion.controlAlerts.status.${key}`] = data.statuses[index]; });
  ["SALE_SCREEN_CLEARED", "CONSECUTIVE_LINE_DELETIONS", "MANUAL_PRICE_CHANGE_OVER_PERCENT", "MANUAL_PRICE_CHANGED", "MANUAL_DISCOUNT_OVER_PERCENT", "PRODUCT_DISCOUNT_APPLIED", "TICKET_CANCELLED", "INACTIVE_PRODUCT_SOLD"].forEach((key, index) => { result[`gestion.controlAlerts.type.${key}`] = data.types[index]; });
  ["title", "list", "new", "empty", "edit", "create", "type", "threshold", "noConfig", "active", "inactive", "activate", "deactivate", "error"].forEach((key, index) => { result[`gestion.controlRules.${key}`] = data.rule[index]; });
  result["gestion.controlRules.name"] = locale === "es" ? "Nombre" : locale === "en" ? "Name" : "\u540d\u79f0";
  const ruleExtra = locale === "es" ? {
    add: "A\u00f1adir regla", configure: "Configurar", unavailable: "No disponible", unconfigured: "Sin configurar",
    notAddedDescription: "Esta regla del sistema a\u00fan no est\u00e1 configurada para la tienda.", unavailableDescription: "El sistema todav\u00eda no dispone de una operaci\u00f3n real que permita detectar esta regla.",
    noParameter: "Sin par\u00e1metros", addDescription: "Selecciona una regla del sistema que todav\u00eda no est\u00e9 configurada. No se permiten nombres personalizados ni duplicados.", allConfigured: "Todas las reglas disponibles ya est\u00e1n configuradas.",
    systemRule: "Regla del sistema", systemNameLocked: "El nombre est\u00e1 definido por el sistema y no se puede modificar.", activeRule: "Regla activa", minimumCount: "Cantidad m\u00ednima consecutiva", thresholdShort: "Umbral", minimumShort: "M\u00ednimo"
  } : locale === "en" ? {
    add: "Add rule", configure: "Configure", unavailable: "Unavailable", unconfigured: "Not configured",
    notAddedDescription: "This system rule has not yet been configured for the store.", unavailableDescription: "There is not yet a real operation the system can use to detect this rule.",
    noParameter: "No parameters", addDescription: "Select a system rule that has not yet been configured. Custom names and duplicates are not allowed.", allConfigured: "All available rules are already configured.",
    systemRule: "System rule", systemNameLocked: "The name is defined by the system and cannot be changed.", activeRule: "Active rule", minimumCount: "Minimum consecutive quantity", thresholdShort: "Threshold", minimumShort: "Minimum"
  } : {
    add: "\u6dfb\u52a0\u89c4\u5219", configure: "\u914d\u7f6e", unavailable: "\u4e0d\u53ef\u7528", unconfigured: "\u672a\u914d\u7f6e",
    notAddedDescription: "\u8be5\u7cfb\u7edf\u89c4\u5219\u5c1a\u672a\u4e3a\u95e8\u5e97\u914d\u7f6e\u3002", unavailableDescription: "\u7cfb\u7edf\u5c1a\u65e0\u53ef\u7528\u4e8e\u68c0\u6d4b\u6b64\u89c4\u5219\u7684\u5b9e\u9645\u64cd\u4f5c\u3002",
    noParameter: "\u65e0\u53c2\u6570", addDescription: "\u9009\u62e9\u5c1a\u672a\u914d\u7f6e\u7684\u7cfb\u7edf\u89c4\u5219\u3002\u4e0d\u5141\u8bb8\u81ea\u5b9a\u4e49\u540d\u79f0\u6216\u91cd\u590d\u3002", allConfigured: "\u6240\u6709\u53ef\u7528\u89c4\u5219\u5747\u5df2\u914d\u7f6e\u3002",
    systemRule: "\u7cfb\u7edf\u89c4\u5219", systemNameLocked: "\u540d\u79f0\u7531\u7cfb\u7edf\u5b9a\u4e49\uff0c\u65e0\u6cd5\u4fee\u6539\u3002", activeRule: "\u542f\u7528\u89c4\u5219", minimumCount: "\u6700\u5c0f\u8fde\u7eed\u6570\u91cf", thresholdShort: "\u9608\u503c", minimumShort: "\u6700\u5c0f"
  };
  for (const [key, value] of Object.entries(ruleExtra)) result[`gestion.controlRules.${key}`] = value;
  ["status", "date", "customer", "product", "quantity", "price", "discount", "total", "payments", "subtotal", "tax"].forEach((key, index) => { result[`gestion.controlDocument.${key}`] = data.document[index]; });
  const widget = locale === "es"
    ? ["Alertas de control", "Nuevas", "Revisadas", "No hay alertas recientes", "Abrir alertas de control"]
    : locale === "en"
      ? ["Control alerts", "New", "Reviewed", "No recent alerts", "Open control alerts"]
      : ["\u63a7\u5236\u8b66\u62a5", "\u65b0\u5efa", "\u5df2\u5ba1\u6838", "\u6ca1\u6709\u6700\u8fd1\u8b66\u62a5", "\u6253\u5f00\u63a7\u5236\u8b66\u62a5"];
  result["gestion.widget.control.alerts"] = widget[0];
  result["gestion.widget.controlAlerts.new"] = widget[1];
  result["gestion.widget.controlAlerts.reviewed"] = widget[2];
  result["gestion.widget.controlAlerts.empty"] = widget[3];
  result["gestion.widget.controlAlerts.open"] = widget[4];
  return result;
}
