import type { LocaleCode } from "../types";

const es = {
  navigation: "Alertas de control", eyebrow: "Supervisi\u00f3n operativa", title: "Alertas de control", refresh: "Actualizar", rules: "Reglas",
  search: "Buscar", filterStatus: "Estado", filterType: "Tipo", all: "Todos", apply: "Aplicar filtros", list: "Listado de alertas", detail: "Detalle de la alerta",
  resize: "Redimensionar columna", loadError: "No se pudieron cargar las alertas", retry: "Reintentar", detailError: "No se pudo cargar el detalle o realizar la acci\u00f3n",
  empty: "No hay alertas para los filtros seleccionados", select: "Selecciona una alerta para consultar su detalle", results: "{count} alertas", previous: "Anterior", next: "Siguiente",
  noDocument: "Sin documento", summary: "Motivo registrado", reviewed: "Revisada", closed: "Cerrada", comment: "Comentario", openDocument: "Abrir venta relacionada",
  documentError: "No se pudo cargar la venta relacionada", actionComment: "Comentario de revisi\u00f3n", rule: "Regla aplicada", history: "Historial",
  alertsGroup: "Alertas", updateGroup: "Actualizaci\u00f3n", refreshing: "Actualizando\u2026", lastUpdated: "Actualizado {time}",
  filterPriority: "Prioridad", filterAssignee: "Responsable", filterOverdue: "Solo vencidas", priorityLabel: "Prioridad", assigneeLabel: "Responsable", dueLabel: "Vencimiento",
  unassigned: "Sin asignar", noDueDate: "Sin vencimiento", workTitle: "Gesti\u00f3n operativa", workComment: "Motivo del cambio", saveWork: "Guardar asignaci\u00f3n", workHistory: "Historial operativo",
  summaryManualDiscount: "Umbral: {threshold} %. Descuento global: {global} %. L\u00edneas afectadas: {lines}.", summaryInactiveProducts: "{count} productos desactivados: {products}.",
  summaryTicketCancelled: "Ticket {document}. Motivo: {reason}.", summarySaleCleared: "Venta en curso borrada con {count} l\u00edneas e importe {total}.",
  backToRules: "Volver a reglas", dateFilter: "Periodo de supervisi\u00f3n", from: "Desde", to: "Hasta", quickRanges: "Periodos r\u00e1pidos", today: "Hoy", lastSevenDays: "7 d\u00edas", currentMonth: "Este mes",
  ruleBlocks: "Reglas de control", total: "Alertas del periodo", newCount: "Nuevas", openList: "Ver alertas",
  summaryConsecutiveDeletions: "{count} l\u00edneas eliminadas de forma consecutiva.", summaryProductDiscount: "Descuento manual aplicado a {count} l\u00edneas de producto.", summaryManualPrice: "Cambio manual de precio registrado.",
  summaryManualNegative: "Devoluci\u00f3n manual registrada con {count} l\u00edneas negativas.", summaryCashDrawer: "Apertura manual del caj\u00f3n autorizada por {authorizer}.",
  summaryRefundPolicyOverride: "Devoluci\u00f3n monetaria excepcional de {amount} mediante {method}. Autorizada por {authorizer}.",
  summaryProductModified: "Producto {product} modificado desde APP VENTA. Autorizado por {authorizer}.",
  summaryParkedSaleDeleted: "Ventas guardadas eliminadas: {count}. Autorización: {authorizer}.",
  autoRefresh: "Actualización automática", autoRefreshOff: "Desactivada", newNotice: "Hay {count} alertas nuevas desde la última actualización.", dismissNotice: "Cerrar aviso", pendingIndicator: "Resumen de alertas pendientes",
  analyticsEyebrow: "Resumen del periodo", analyticsTitle: "Panel analítico", overdueAfter: "Pendiente durante", pendingCount: "Pendientes", overdueCount: "Fuera de plazo",
  byStatus: "Por estado", byType: "Por tipo", byUser: "Por usuario", byTerminal: "Por terminal", analyticsEmpty: "Sin datos", analyticsUnavailable: "No disponible",
  analyticsLimited: "Vista parcial: el servidor todavía no ofrece el detalle analítico por usuario y terminal."
};

const en = {
  navigation: "Control alerts", eyebrow: "Operational supervision", title: "Control alerts", refresh: "Refresh", rules: "Rules",
  search: "Search", filterStatus: "Status", filterType: "Type", all: "All", apply: "Apply filters", list: "Alert list", detail: "Alert details",
  resize: "Resize column", loadError: "Alerts could not be loaded", retry: "Retry", detailError: "The details or action could not be completed",
  empty: "No alerts match the selected filters", select: "Select an alert to view its details", results: "{count} alerts", previous: "Previous", next: "Next",
  noDocument: "No document", summary: "Recorded reason", reviewed: "Reviewed", closed: "Closed", comment: "Comment", openDocument: "Open related sale",
  documentError: "The related sale could not be loaded", actionComment: "Review comment", rule: "Applied rule", history: "History",
  alertsGroup: "Alerts", updateGroup: "Refresh", refreshing: "Refreshing\u2026", lastUpdated: "Updated {time}",
  filterPriority: "Priority", filterAssignee: "Assignee", filterOverdue: "Overdue only", priorityLabel: "Priority", assigneeLabel: "Assignee", dueLabel: "Due date",
  unassigned: "Unassigned", noDueDate: "No due date", workTitle: "Operational management", workComment: "Reason for change", saveWork: "Save assignment", workHistory: "Work history",
  summaryManualDiscount: "Threshold: {threshold}%. Global discount: {global}%. Affected lines: {lines}.", summaryInactiveProducts: "{count} inactive products: {products}.",
  summaryTicketCancelled: "Ticket {document}. Reason: {reason}.", summarySaleCleared: "Open sale cleared with {count} lines and amount {total}.",
  backToRules: "Back to rules", dateFilter: "Supervision period", from: "From", to: "To", quickRanges: "Quick periods", today: "Today", lastSevenDays: "7 days", currentMonth: "This month",
  ruleBlocks: "Control rules", total: "Period alerts", newCount: "New", openList: "View alerts",
  summaryConsecutiveDeletions: "{count} lines deleted consecutively.", summaryProductDiscount: "Manual discount applied to {count} product lines.", summaryManualPrice: "Manual price change recorded.",
  summaryManualNegative: "Manual return recorded with {count} negative lines.", summaryCashDrawer: "Manual cash drawer opening authorized by {authorizer}.",
  summaryRefundPolicyOverride: "Exceptional monetary refund of {amount} using {method}. Authorized by {authorizer}.",
  summaryProductModified: "Product {product} modified from APP VENTA. Authorized by {authorizer}.",
  summaryParkedSaleDeleted: "Parked sales deleted: {count}. Authorization: {authorizer}.",
  autoRefresh: "Automatic refresh", autoRefreshOff: "Off", newNotice: "{count} new alerts arrived since the last refresh.", dismissNotice: "Dismiss notification", pendingIndicator: "Pending alerts summary",
  analyticsEyebrow: "Period summary", analyticsTitle: "Analytics panel", overdueAfter: "Pending for", pendingCount: "Pending", overdueCount: "Overdue",
  byStatus: "By status", byType: "By type", byUser: "By user", byTerminal: "By terminal", analyticsEmpty: "No data", analyticsUnavailable: "Unavailable",
  analyticsLimited: "Partial view: the server does not yet provide analytics by user and terminal."
};

const zh = {
  navigation: "\u63a7\u5236\u8b66\u62a5", eyebrow: "\u8fd0\u8425\u76d1\u7763", title: "\u63a7\u5236\u8b66\u62a5", refresh: "\u5237\u65b0", rules: "\u89c4\u5219",
  search: "\u641c\u7d22", filterStatus: "\u72b6\u6001", filterType: "\u7c7b\u578b", all: "\u5168\u90e8", apply: "\u5e94\u7528\u7b5b\u9009", list: "\u8b66\u62a5\u5217\u8868", detail: "\u8b66\u62a5\u8be6\u60c5",
  resize: "\u8c03\u6574\u5217\u5bbd", loadError: "\u65e0\u6cd5\u52a0\u8f7d\u8b66\u62a5", retry: "\u91cd\u8bd5", detailError: "\u65e0\u6cd5\u52a0\u8f7d\u8be6\u60c5\u6216\u5b8c\u6210\u64cd\u4f5c",
  empty: "\u6ca1\u6709\u7b26\u5408\u5f53\u524d\u7b5b\u9009\u7684\u8b66\u62a5", select: "\u8bf7\u9009\u62e9\u8b66\u62a5\u67e5\u770b\u8be6\u60c5", results: "{count} \u6761\u8b66\u62a5", previous: "\u4e0a\u4e00\u9875", next: "\u4e0b\u4e00\u9875",
  noDocument: "\u65e0\u5355\u636e", summary: "\u8bb0\u5f55\u539f\u56e0", reviewed: "\u5df2\u5ba1\u6838", closed: "\u5df2\u5173\u95ed", comment: "\u5907\u6ce8", openDocument: "\u6253\u5f00\u76f8\u5173\u9500\u552e",
  documentError: "\u65e0\u6cd5\u52a0\u8f7d\u76f8\u5173\u9500\u552e", actionComment: "\u5ba1\u6838\u5907\u6ce8", rule: "\u5e94\u7528\u89c4\u5219", history: "\u5386\u53f2",
  alertsGroup: "\u8b66\u62a5", updateGroup: "\u66f4\u65b0", refreshing: "\u6b63\u5728\u66f4\u65b0\u2026", lastUpdated: "\u5df2\u66f4\u65b0 {time}",
  filterPriority: "\u4f18\u5148\u7ea7", filterAssignee: "\u8d1f\u8d23\u4eba", filterOverdue: "\u4ec5\u663e\u793a\u903e\u671f", priorityLabel: "\u4f18\u5148\u7ea7", assigneeLabel: "\u8d1f\u8d23\u4eba", dueLabel: "\u622a\u6b62\u65f6\u95f4",
  unassigned: "\u672a\u5206\u914d", noDueDate: "\u65e0\u622a\u6b62\u65f6\u95f4", workTitle: "\u8fd0\u8425\u5904\u7406", workComment: "\u66f4\u6539\u539f\u56e0", saveWork: "\u4fdd\u5b58\u5206\u914d", workHistory: "\u5904\u7406\u5386\u53f2",
  summaryManualDiscount: "\u9608\u503c: {threshold}%. \u5168\u5c40\u6298\u6263: {global}%. \u53d7\u5f71\u54cd\u884c: {lines}.", summaryInactiveProducts: "{count} \u4e2a\u5df2\u505c\u7528\u5546\u54c1: {products}.",
  summaryTicketCancelled: "\u5c0f\u7968 {document}. \u539f\u56e0: {reason}.", summarySaleCleared: "\u5df2\u6e05\u9664\u5305\u542b {count} \u884c\u3001\u91d1\u989d {total} \u7684\u672a\u5b8c\u6210\u9500\u552e.",
  backToRules: "\u8fd4\u56de\u89c4\u5219", dateFilter: "\u76d1\u7763\u671f\u95f4", from: "\u5f00\u59cb", to: "\u7ed3\u675f", quickRanges: "\u5feb\u901f\u671f\u95f4", today: "\u4eca\u5929", lastSevenDays: "7\u5929", currentMonth: "\u672c\u6708",
  ruleBlocks: "\u63a7\u5236\u89c4\u5219", total: "\u671f\u95f4\u8b66\u62a5", newCount: "\u65b0\u5efa", openList: "\u67e5\u770b\u8b66\u62a5",
  summaryConsecutiveDeletions: "\u8fde\u7eed\u5220\u9664 {count} \u884c\u3002", summaryProductDiscount: "\u5bf9 {count} \u4e2a\u5546\u54c1\u884c\u5e94\u7528\u4e86\u624b\u52a8\u6298\u6263\u3002", summaryManualPrice: "\u5df2\u8bb0\u5f55\u624b\u52a8\u4ef7\u683c\u66f4\u6539\u3002",
  summaryManualNegative: "\u5df2\u8bb0\u5f55\u5305\u542b {count} \u4e2a\u8d1f\u6570\u884c\u7684\u624b\u52a8\u9000\u8d27\u3002", summaryCashDrawer: "\u7531 {authorizer} \u6388\u6743\u624b\u52a8\u6253\u5f00\u94b1\u7bb1\u3002",
  summaryRefundPolicyOverride: "\u4f8b\u5916\u9000\u6b3e {amount}\uff0c\u65b9\u5f0f\uff1a{method}\uff0c\u6388\u6743\u4eba\uff1a{authorizer}\u3002",
  summaryProductModified: "\u5546\u54c1 {product} \u5df2\u4ece APP VENTA \u4fee\u6539\uff0c\u6388\u6743\u4eba\uff1a{authorizer}\u3002",
  summaryParkedSaleDeleted: "\u5df2\u5220\u9664\u7684\u6682\u5b58\u9500\u552e\uff1a{count}\u3002\u6388\u6743\u4eba\uff1a{authorizer}\u3002",
  autoRefresh: "\u81ea\u52a8\u5237\u65b0", autoRefreshOff: "\u5173\u95ed", newNotice: "\u81ea\u4e0a\u6b21\u5237\u65b0\u4ee5\u6765\u6709 {count} \u6761\u65b0\u8b66\u62a5\u3002", dismissNotice: "\u5173\u95ed\u901a\u77e5", pendingIndicator: "\u5f85\u5904\u7406\u8b66\u62a5\u6458\u8981",
  analyticsEyebrow: "\u671f\u95f4\u6458\u8981", analyticsTitle: "\u5206\u6790\u9762\u677f", overdueAfter: "\u5f85\u5904\u7406\u65f6\u957f", pendingCount: "\u5f85\u5904\u7406", overdueCount: "\u5df2\u903e\u671f",
  byStatus: "\u6309\u72b6\u6001", byType: "\u6309\u7c7b\u578b", byUser: "\u6309\u7528\u6237", byTerminal: "\u6309\u7ec8\u7aef", analyticsEmpty: "\u65e0\u6570\u636e", analyticsUnavailable: "\u4e0d\u53ef\u7528",
  analyticsLimited: "\u90e8\u5206\u89c6\u56fe\uff1a\u670d\u52a1\u5668\u5c1a\u672a\u63d0\u4f9b\u6309\u7528\u6237\u548c\u7ec8\u7aef\u7684\u5206\u6790\u3002"
};

const localeValues = { es, en, zh } as const;

const fixed = {
  es: {
    actions: ["Marcar revisada", "Cerrar alerta", "Descartar alerta"], columns: ["Fecha y hora", "Empleado", "Terminal", "Venta / ticket", "Detalle", "Estado"],
    statuses: ["Nueva", "Revisada", "Cerrada", "Descartada"], types: ["Eliminaci\u00f3n completa de carrito", "Eliminaci\u00f3n de l\u00edneas consecutivas", "Bajada manual de precio superior al porcentaje", "Cambio manual de precio", "Descuento manual superior al porcentaje", "Descuento manual aplicado a producto", "Anulaci\u00f3n de ticket", "Venta de producto desactivado", "Devoluci\u00f3n manual sin ticket", "Devoluci\u00f3n monetaria contra la pol\u00edtica", "Apertura manual del caj\u00f3n", "Modificaci\u00f3n de producto desde venta", "Eliminaci\u00f3n de venta guardada"],
    rule: ["Reglas de alertas", "Reglas configuradas", "Nueva regla", "No hay reglas configuradas", "Modificar regla", "Crear regla", "Tipo de control", "Porcentaje l\u00edmite", "Este tipo no necesita par\u00e1metros adicionales.", "Activa", "Inactiva", "Activar", "Desactivar", "No se pudo guardar o actualizar la regla"],
    document: ["Estado", "Fecha", "Cliente", "Producto", "Cantidad", "Precio", "Descuento", "Total", "Pagos", "Subtotal", "Impuestos"]
  },
  en: {
    actions: ["Mark reviewed", "Close alert", "Dismiss alert"], columns: ["Date and time", "Employee", "Terminal", "Sale / ticket", "Details", "Status"],
    statuses: ["New", "Reviewed", "Closed", "Dismissed"], types: ["Full cart deletion", "Consecutive line deletion", "Manual price reduction above percentage", "Manual price change", "Manual discount above percentage", "Manual product discount", "Ticket cancelled", "Inactive product sold", "Manual return without ticket", "Monetary refund against policy", "Manual cash drawer opening", "Product modified from sale", "Parked sale deletion"],
    rule: ["Alert rules", "Configured rules", "New rule", "No rules configured", "Edit rule", "Create rule", "Control type", "Percentage limit", "This type requires no additional parameters.", "Active", "Inactive", "Activate", "Deactivate", "The rule could not be saved or updated"],
    document: ["Status", "Date", "Customer", "Product", "Quantity", "Price", "Discount", "Total", "Payments", "Subtotal", "Tax"]
  },
  zh: {
    actions: ["\u6807\u8bb0\u5df2\u5ba1\u6838", "\u5173\u95ed\u8b66\u62a5", "\u5ffd\u7565\u8b66\u62a5"], columns: ["\u65e5\u671f\u548c\u65f6\u95f4", "\u5458\u5de5", "\u7ec8\u7aef", "\u9500\u552e / \u5c0f\u7968", "\u8be6\u60c5", "\u72b6\u6001"],
    statuses: ["\u65b0\u5efa", "\u5df2\u5ba1\u6838", "\u5df2\u5173\u95ed", "\u5df2\u5ffd\u7565"], types: ["\u5220\u9664\u6574\u4e2a\u8d2d\u7269\u8f66", "\u8fde\u7eed\u5220\u9664\u884c", "手动降价超过百分比", "\u624b\u52a8\u6539\u4ef7", "\u624b\u52a8\u6298\u6263\u8d85\u8fc7\u767e\u5206\u6bd4", "\u624b\u52a8\u5546\u54c1\u6298\u6263", "\u5c0f\u7968\u5df2\u53d6\u6d88", "\u552e\u51fa\u5df2\u505c\u7528\u5546\u54c1", "\u65e0\u5c0f\u7968\u624b\u52a8\u9000\u8d27", "\u8fdd\u53cd\u653f\u7b56\u7684\u91d1\u989d\u9000\u6b3e", "\u624b\u52a8\u6253\u5f00\u94b1\u7bb1", "\u4ece\u9500\u552e\u4fee\u6539\u5546\u54c1", "\u5220\u9664\u6682\u5b58\u9500\u552e"],
    rule: ["\u8b66\u62a5\u89c4\u5219", "\u5df2\u914d\u7f6e\u89c4\u5219", "\u65b0\u5efa\u89c4\u5219", "\u5c1a\u672a\u914d\u7f6e\u89c4\u5219", "\u7f16\u8f91\u89c4\u5219", "\u521b\u5efa\u89c4\u5219", "\u63a7\u5236\u7c7b\u578b", "\u767e\u5206\u6bd4\u9650\u5236", "\u6b64\u7c7b\u578b\u65e0\u9700\u5176\u4ed6\u53c2\u6570\u3002", "\u542f\u7528", "\u505c\u7528", "\u542f\u7528", "\u505c\u7528", "\u65e0\u6cd5\u4fdd\u5b58\u6216\u66f4\u65b0\u89c4\u5219"],
    document: ["\u72b6\u6001", "\u65e5\u671f", "\u5ba2\u6237", "\u5546\u54c1", "\u6570\u91cf", "\u4ef7\u683c", "\u6298\u6263", "\u5408\u8ba1", "\u652f\u4ed8", "\u5c0f\u8ba1", "\u7a0e\u989d"]
  }
} as const;

const timelineMessages: Record<LocaleCode, Record<string, string>> = {
  es: {
    reviewComment: "Comentario de revisión", openDetailHint: "Doble clic o Enter para abrir el detalle",
    "action.REOPEN": "Reabrir alerta", reopened: "Reabierta",
    assigneeUnavailable: "Este responsable ya no está disponible para asignación. Selecciona otro usuario o «Sin asignar».",
    assigneesLoadError: "No se pudo cargar la lista de responsables. Reintenta para filtrar por responsable o guardar cambios de gestión.",
    unknownUser: "Usuario no disponible", unknownTerminal: "Terminal no disponible", unknownCustomer: "Cliente no disponible",
    workHelp: "El responsable organiza el seguimiento, la prioridad indica la urgencia y el vencimiento fija la fecha límite. Estas opciones no modifican la venta ni la regla aplicada.",
    workOptions: "Vencimiento y motivo de asignación", applyDates: "Aplicar", chronology: "Cronología de operaciones", chronologyList: "Listado por fecha y hora", personalize: "Personalizar vista", configureRules: "Configurar reglas",
    periodTypes: "Indicadores del periodo y filtros · Todos los tipos", indicatorsError: "No se pudieron actualizar los indicadores.", rulesLoadError: "No se pudieron cargar o actualizar las reglas. Reintenta antes de configurarlas.",
    searchPlaceholder: "Documento o usuario", moreFilters: "Más filtros", sortOrder: "Orden cronológico", newestFirst: "Más recientes primero", oldestFirst: "Más antiguas primero",
    time: "Hora", operation: "Operación", documentUser: "Documento / usuario", columns: "Columnas", yesterday: "Ayer", previousResults: "Actualizando filtros; se muestran los resultados anteriores.",
    versionConflict: "Otro usuario ha modificado esta alerta. Se ha solicitado la versión actual; revisa los cambios antes de continuar.",
    preferenceError: "No se pudieron cargar o guardar las preferencias de vista del usuario.", preferenceScope: "Estas opciones se guardan para tu usuario. El periodo inicial se aplica al abrir la pantalla.",
    defaultPeriod: "Periodo inicial", restoreDefault: "Restaurar predeterminada", "preference.showIndicators": "Mostrar indicadores por tipo", "preference.showDetail": "Mostrar detalle lateral", "preference.groupByDay": "Agrupar alertas por día", "preference.compact": "Filas compactas",
    evidence: "Evidencia registrada", priceModified: "Precio modificado", originalPrice: "Anterior", appliedPrice: "Aplicado", priceReduction: "Variación",
    "evidence.deletedAt": "Eliminado en el terminal", "evidence.receivedAt": "Recibido en el servidor",
    line: "Línea", productReference: "Referencia del producto", ruleVersion: "Versión", generated: "Alerta generada",
    "evidence.globalDiscountPercent": "Descuento global", "evidence.thresholdPercent": "Umbral aplicado", "evidence.total": "Importe registrado", "evidence.lineCount": "Líneas", "evidence.minimumCount": "Mínimo de eliminaciones", "evidence.deletionCount": "Acciones de eliminación", "evidence.expectedCash": "Efectivo esperado", "evidence.declaredFund": "Efectivo declarado", "evidence.discrepancy": "Descuadre", "evidence.tolerance": "Tolerancia", "evidence.authorizerName": "Autorizado por",
    "type.CASH_SESSION_DISCREPANCY": "Descuadre de caja", summaryCashDiscrepancy: "Descuadre registrado: {amount}.", summaryPriceChanges: "{count} cambios de precio registrados."
  },
  en: {
    reviewComment: "Review comment", openDetailHint: "Double-click or press Enter to open details",
    "action.REOPEN": "Reopen alert", reopened: "Reopened",
    assigneeUnavailable: "This assignee is no longer available for assignment. Select another user or “Unassigned”.",
    assigneesLoadError: "The assignee list could not be loaded. Retry to filter by assignee or save management changes.",
    unknownUser: "User unavailable", unknownTerminal: "Terminal unavailable", unknownCustomer: "Customer unavailable",
    workHelp: "The assignee coordinates the review, priority indicates urgency, and the due date sets the deadline. These options do not change the sale or the applied rule.",
    workOptions: "Due date and assignment reason", applyDates: "Apply", chronology: "Operations timeline", chronologyList: "Alerts by date and time", personalize: "Customize view", configureRules: "Configure rules",
    periodTypes: "Indicators for the period and filters · All types", indicatorsError: "Indicators could not be refreshed.", rulesLoadError: "Rules could not be loaded or updated. Retry before configuring them.",
    searchPlaceholder: "Document or user", moreFilters: "More filters", sortOrder: "Timeline order", newestFirst: "Newest first", oldestFirst: "Oldest first",
    time: "Time", operation: "Operation", documentUser: "Document / user", columns: "Columns", yesterday: "Yesterday", previousResults: "Updating filters; previous results remain visible.",
    versionConflict: "Another user changed this alert. The latest version was requested; review the changes before continuing.",
    preferenceError: "Your view preferences could not be loaded or saved.", preferenceScope: "These options are saved for your user. The initial period applies when opening this screen.",
    defaultPeriod: "Initial period", restoreDefault: "Restore defaults", "preference.showIndicators": "Show indicators by type", "preference.showDetail": "Show side details", "preference.groupByDay": "Group alerts by day", "preference.compact": "Compact rows",
    evidence: "Recorded evidence", priceModified: "Price changed", originalPrice: "Previous", appliedPrice: "Applied", priceReduction: "Change",
    "evidence.deletedAt": "Deleted at the terminal", "evidence.receivedAt": "Received by the server",
    line: "Line", productReference: "Product reference", ruleVersion: "Version", generated: "Alert created",
    "evidence.globalDiscountPercent": "Global discount", "evidence.thresholdPercent": "Applied threshold", "evidence.total": "Recorded amount", "evidence.lineCount": "Lines", "evidence.minimumCount": "Minimum deletions", "evidence.deletionCount": "Deletion actions", "evidence.expectedCash": "Expected cash", "evidence.declaredFund": "Declared cash", "evidence.discrepancy": "Discrepancy", "evidence.tolerance": "Tolerance", "evidence.authorizerName": "Authorized by",
    "type.CASH_SESSION_DISCREPANCY": "Cash discrepancy", summaryCashDiscrepancy: "Recorded cash discrepancy: {amount}.", summaryPriceChanges: "{count} recorded price changes."
  },
  zh: {
    reviewComment: "审核备注", openDetailHint: "双击或按 Enter 键打开详情",
    "action.REOPEN": "重新打开警报", reopened: "已重新打开",
    assigneeUnavailable: "此负责人已无法分配。请选择其他用户或“未分配”。",
    assigneesLoadError: "无法加载负责人列表。请重试后再按负责人筛选或保存管理更改。",
    unknownUser: "用户信息不可用", unknownTerminal: "终端信息不可用", unknownCustomer: "客户信息不可用",
    workHelp: "负责人跟进审核，优先级表示紧急程度，截止时间设定处理期限。这些选项不会更改销售记录或已应用的规则。",
    workOptions: "截止时间和分配原因", applyDates: "应用", chronology: "操作时间线", chronologyList: "按日期和时间排列", personalize: "自定义视图", configureRules: "配置规则",
    periodTypes: "所选期间和筛选条件的指标 · 全部类型", indicatorsError: "无法更新指标。", rulesLoadError: "无法加载或更新规则。请重试后再配置。",
    searchPlaceholder: "单据或用户", moreFilters: "更多筛选", sortOrder: "时间顺序", newestFirst: "最新优先", oldestFirst: "最早优先",
    time: "时间", operation: "操作", documentUser: "单据 / 用户", columns: "列设置", yesterday: "昨天", previousResults: "正在更新筛选，暂时显示之前的结果。",
    versionConflict: "其他用户已修改此警报。已请求最新版本，请检查更改后再继续。",
    preferenceError: "无法加载或保存当前用户的视图偏好。", preferenceScope: "这些选项按用户保存。初始期间将在打开此页面时应用。",
    defaultPeriod: "初始期间", restoreDefault: "恢复默认", "preference.showIndicators": "显示类型指标", "preference.showDetail": "显示侧边详情", "preference.groupByDay": "按日期分组", "preference.compact": "紧凑行距",
    evidence: "已记录的凭据", priceModified: "价格已修改", originalPrice: "原价", appliedPrice: "实际价格", priceReduction: "变动",
    "evidence.deletedAt": "终端删除时间", "evidence.receivedAt": "服务器接收时间",
    line: "行", productReference: "商品编号", ruleVersion: "版本", generated: "警报已生成",
    "evidence.globalDiscountPercent": "全局折扣", "evidence.thresholdPercent": "应用阈值", "evidence.total": "记录金额", "evidence.lineCount": "行数", "evidence.minimumCount": "最少删除次数", "evidence.deletionCount": "删除操作次数", "evidence.expectedCash": "应有现金", "evidence.declaredFund": "申报现金", "evidence.discrepancy": "差额", "evidence.tolerance": "容差", "evidence.authorizerName": "授权人",
    "type.CASH_SESSION_DISCREPANCY": "现金差额", summaryCashDiscrepancy: "已记录的现金差额：{amount}。", summaryPriceChanges: "已记录 {count} 次价格更改。"
  }
};

export function controlMessages(locale: LocaleCode): Record<string, string> {
  const values = localeValues[locale];
  const data = fixed[locale];
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(values)) result[`gestion.controlAlerts.${key}`] = value;
  ["REVIEW", "CLOSE", "DISMISS"].forEach((key, index) => { result[`gestion.controlAlerts.action.${key}`] = data.actions[index]; });
  ["occurredAt", "username", "terminal", "document", "detail", "status"].forEach((key, index) => { result[`gestion.controlAlerts.column.${key}`] = data.columns[index]; });
  ["NEW", "REVIEWED", "CLOSED", "DISMISSED"].forEach((key, index) => { result[`gestion.controlAlerts.status.${key}`] = data.statuses[index]; });
  const priorities = locale === "es" ? ["Informativa", "Media", "Alta", "Cr\u00edtica"] : locale === "en" ? ["Informational", "Medium", "High", "Critical"] : ["\u4fe1\u606f", "\u4e2d", "\u9ad8", "\u7d27\u6025"];
  ["INFORMATIONAL", "MEDIUM", "HIGH", "CRITICAL"].forEach((key, index) => { result[`gestion.controlAlerts.priority.${key}`] = priorities[index]; });
  ["SALE_SCREEN_CLEARED", "CONSECUTIVE_LINE_DELETIONS", "MANUAL_PRICE_CHANGE_OVER_PERCENT", "MANUAL_PRICE_CHANGED", "MANUAL_DISCOUNT_OVER_PERCENT", "PRODUCT_DISCOUNT_APPLIED", "TICKET_CANCELLED", "INACTIVE_PRODUCT_SOLD", "MANUAL_NEGATIVE_QUANTITY", "REFUND_POLICY_OVERRIDE", "CASH_DRAWER_OPENED", "PRODUCT_CATALOG_MODIFIED", "PARKED_SALE_DELETED"].forEach((key, index) => { result[`gestion.controlAlerts.type.${key}`] = data.types[index]; });
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
  for (const [key, value] of Object.entries(timelineMessages[locale])) result[`gestion.controlAlerts.${key}`] = value;
  return result;
}
