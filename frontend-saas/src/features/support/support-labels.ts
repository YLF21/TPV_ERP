import { useI18n } from "../../i18n";
const labels = {
  queue: ["Cola de trabajo", "Work queue", "工作队列"], all: ["Todas las incidencias", "All incidents", "全部事件"],
  mine: ["Mis incidencias", "My incidents", "我的事件"], unassigned: ["Sin asignar", "Unassigned", "未分配"], visits: ["Visitas pendientes", "Pending visits", "待上门"], due: ["Esperas por revisar", "Waiting cases due", "待复查"],
  owner: ["Responsable", "Owner", "负责人"], visit: ["Visita", "Visit", "上门"], review: ["Próxima revisión", "Next review", "下次复查"],
  back: ["Volver al fallo original", "Back to original failure", "返回原始故障"], context: ["Incidencia vinculada al fallo seleccionado", "Incident linked to the selected failure", "所选故障关联事件"],
  missing: ["El ticket solicitado no está disponible para esta empresa. Actualiza o selecciona otra incidencia.", "The requested ticket is unavailable for this company. Refresh or choose another incident.", "该公司的所选工单不可用，请刷新或选择其他事件。"],
  clearQueue: ["Limpiar filtros", "Clear filters", "清除筛选"], queueScope: ["Los filtros se aplican a la empresa seleccionada.", "Filters apply to the selected company.", "筛选适用于所选公司。"],
  closedPending: ["Atención cerrada · recuperación técnica pendiente", "Support closed · technical recovery pending", "协助已关闭 · 技术恢复待确认"],
  lastSignal: ["Última información técnica", "Latest technical update", "最新技术信息"],
  showComments: ["Ver comentarios", "View comments", "查看评论"], hideComments: ["Ocultar comentarios", "Hide comments", "隐藏评论"]
} as const;
export function useSupportLabels() { const {language}=useI18n();return (key:keyof typeof labels)=>labels[key][language==='zh'?2:language==='en'?1:0]; }
export type SupportTarget = {companyId:string;ticketId?:string;failureKey?:string};
