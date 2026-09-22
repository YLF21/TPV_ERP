import { useI18n } from "../../i18n";

const labels = {
  title: ["Fallos recibidos", "Received failures", "已接收故障"],
  scope: ["Alertas de control y fallos de sincronización comunicados por las tiendas, y fallos registrados en el servicio central.", "Control alerts and synchronization failures reported by stores, and failures recorded by the central service.", "门店上报的控制警报与同步故障，以及中央服务记录的故障。"],
  search: ["Buscar nombre, código o referencia", "Search name, code or reference", "搜索名称、编号或引用"],
  installation: ["ID de instalación", "Installation ID", "安装ID"],
  storeSearch: ["Buscar tienda por nombre o código", "Find store by name or code", "按名称或编号查找门店"],
  apply: ["Aplicar filtros", "Apply filters", "应用筛选"],
  clear: ["Limpiar filtros", "Clear filters", "清除筛选"],
  invalidDates: ["La fecha final debe ser igual o posterior a la inicial.", "The end date must be on or after the start date.", "结束日期不得早于开始日期。"],
  invalidStore: ["Selecciona una tienda de las sugerencias o deja el filtro vacío.", "Select a suggested store or leave the filter empty.", "请选择建议的门店，或将筛选留空。"],
  invalidInstallation: ["El ID de instalación debe ser un UUID válido.", "The installation ID must be a valid UUID.", "安装ID必须为有效的UUID。"],
  close: ["Cerrar detalle", "Close details", "关闭详情"],
  reference: ["Referencia del origen", "Source reference", "来源引用"],
  code: ["Código del fallo", "Failure code", "故障代码"],
  sync: ["Abrir sincronización", "Open synchronization", "打开同步"],
  support: ["Abrir soporte", "Open support", "打开支持"],
  dateScope: ["Fechas según última detección. Hasta incluye el día seleccionado.", "Dates refer to the last detection. The end date includes the selected day.", "日期按最后检测时间筛选，结束日期包含所选当天。"],
  centralScope: ["Los registros centrales sin tienda siguen visibles.", "Central records without a store remain visible.", "未关联门店的中央记录仍会显示。"],
  LOCAL_CONTROL: ["Control de tienda", "Store control", "门店控制"],
  LOCAL_SYNC: ["Envío desde tienda", "Store delivery", "门店发送"],
  SYNC_PROJECTION: ["Proyección de sincronización", "Synchronization projection", "同步投影"],
  CENTRAL_SECURITY: ["Seguridad central", "Central security", "中央安全"],
  CENTRAL_INTEGRATION: ["Integración central", "Central integration", "中央集成"],
  BOOTSTRAP: ["Inicialización central", "Central initialization", "中央初始化"],
  OPEN: ["Abierto", "Open", "待处理"],
  REVIEWED: ["Revisado", "Reviewed", "已查看"],
  RESOLVED: ["Resuelto", "Resolved", "已解决"],
  DISMISSED: ["Descartado", "Dismissed", "已忽略"],
  ACKNOWLEDGED: ["Reconocido", "Acknowledged", "已确认"],
  INFO: ["Información", "Information", "信息"],
  WARNING: ["Advertencia", "Warning", "警告"],
  DANGER: ["Error", "Error", "错误"],
  detail_LOCAL_CONTROL: ["Alerta de control comunicada por la tienda. Consulta la referencia de origen en APP GESTIÓN.", "Control alert reported by the store. Look up the source reference in APP GESTIÓN.", "门店上报的控制警报。请在APP GESTIÓN中查询来源引用。"],
  detail_LOCAL_SYNC: ["Un evento de la cola local no pudo entregarse. Consulta la referencia de origen en la tienda.", "A local queue event could not be delivered. Look up the source reference at the store.", "本地队列事件发送失败。请在门店查询来源引用。"],
  detail_SYNC_PROJECTION: ["Un evento recibido no pudo proyectarse. Consulta el evento en sincronización.", "A received event could not be projected. Look up the event in synchronization.", "收到的事件投影失败。请在同步页面查询该事件。"],
  detail_CENTRAL_SECURITY: ["Fallo de entrega de una notificación de seguridad central.", "A central security notification failed delivery.", "中央安全通知发送失败。"],
  detail_CENTRAL_INTEGRATION: ["Fallo de entrega de una integración central.", "A central integration failed delivery.", "中央集成发送失败。"],
  detail_BOOTSTRAP: ["Inicialización de categorías de socios pendiente por inactividad o conflicto.", "Member category initialization is pending due to inactivity or conflict.", "会员类别初始化因无活动或冲突而挂起。"],
} as const;

export const FAILURE_SOURCES = ["LOCAL_CONTROL", "LOCAL_SYNC", "SYNC_PROJECTION", "CENTRAL_SECURITY", "CENTRAL_INTEGRATION", "BOOTSTRAP"] as const;
export const FAILURE_STATUSES = ["OPEN", "REVIEWED", "RESOLVED", "DISMISSED", "ACKNOWLEDGED"] as const;
export function useFailureLabels() {
  const { language } = useI18n();
  return (key: string) => labels[key as keyof typeof labels]?.[language === "es" ? 0 : language === "en" ? 1 : 2] ?? key;
}
