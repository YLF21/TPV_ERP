import { useI18n } from "../../i18n";

const labels = {
  requests: ["Solicitudes", "Requests", "申请"],
  requestDetail: ["Solicitud de acceso", "Access request", "访问申请"],
  userDetail: ["Cuenta de cliente", "Client account", "客户账户"],
  openAccess: ["Acceso", "Access", "访问权限"],
  name: ["Nombre completo", "Full name", "姓名"],
  taxId: ["Identificación fiscal", "Tax ID", "税务识别号"],
  city: ["Localidad", "City", "城市"],
  submittedAt: ["Presentada", "Submitted", "提交时间"],
  reviewedAt: ["Revisada", "Reviewed", "审核时间"],
  reviewedBy: ["Revisada por", "Reviewed by", "审核人"],
  reason: ["Motivo de la decisión", "Decision reason", "审核原因"],
  requestedStores: ["Tiendas indicadas en la solicitud", "Stores named in the request", "申请中填写的门店"],
  requestedStoresHelp: ["Datos declarados por el solicitante.", "Details supplied by the applicant.", "由申请人填写的信息。"],
  selectedStores: ["Tiendas seleccionadas", "Selected stores", "已选门店"],
  chooseStores: ["Asignar tiendas", "Assign stores", "分配门店"],
  searchStores: ["Buscar tiendas", "Search stores", "搜索门店"],
  searchPeople: ["Buscar por nombre, correo o usuario", "Search by name, email or username", "按姓名、邮箱或用户名搜索"],
  noStores: ["No se encontraron tiendas.", "No stores found.", "未找到门店。"],
  noRequests: ["No hay solicitudes para los filtros seleccionados.", "No requests match the selected filters.", "没有符合筛选条件的申请。"],
  noUsers: ["No se encontraron usuarios cliente.", "No client users found.", "未找到客户用户。"],
  allStatuses: ["Todos los estados", "All statuses", "所有状态"],
  PENDING: ["Pendiente", "Pending", "待审核"],
  APPROVED: ["Aprobada", "Approved", "已批准"],
  REJECTED: ["Rechazada", "Rejected", "已拒绝"],
  approve: ["Aprobar", "Approve", "批准"],
  reject: ["Rechazar", "Reject", "拒绝"],
  approveWithoutStores: ["La cuenta quedará aprobada sin acceso a tiendas.", "The account will be approved without store access.", "批准后的账户将无门店访问权限。"],
  approveWithStores: ["La cuenta tendrá acceso a las tiendas seleccionadas.", "The account will have access to the selected stores.", "账户将可以访问所选门店。"],
  rejectReason: ["Motivo del rechazo", "Rejection reason", "拒绝原因"],
  submitRejection: ["Confirmar rechazo", "Confirm rejection", "确认拒绝"],
  alreadyReviewed: ["Esta solicitud cambió durante la revisión. Consulta su estado actualizado.", "This request changed during review. See its current status.", "此申请在审核期间发生变化，请查看当前状态。"],
  approvalSuccess: ["Solicitud aprobada", "Request approved", "申请已批准"],
  rejectionSuccess: ["Solicitud rechazada", "Request rejected", "申请已拒绝"],
  reviewFailed: ["No se pudo guardar la decisión.", "The decision could not be saved.", "无法保存审核决定。"],
  clearStores: ["Quitar todas las tiendas", "Remove all stores", "移除所有门店"],
  removeStore: ["Quitar tienda", "Remove store", "移除门店"],
  noCompanies: ["Sin empresas vinculadas", "No linked companies", "未关联企业"],
  directoryHint: ["Abre una cuenta para gestionar sus tiendas, su contraseña o su estado.", "Open an account to manage its stores, password or status.", "打开账户以管理门店、密码或账户状态。"],
  requestHint: ["Abre una solicitud para revisar los datos y decidir qué acceso conceder.", "Open a request to review its details and decide which access to grant.", "打开申请以审核信息并决定授予哪些访问权限。"],
} as const;

export function useClientUserLabels() {
  const { language } = useI18n();
  const index = language === "en" ? 1 : language === "zh" ? 2 : 0;
  return (key: keyof typeof labels) => labels[key][index];
}
