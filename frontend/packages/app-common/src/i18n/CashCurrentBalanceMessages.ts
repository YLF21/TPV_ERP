import type { LocaleCode } from "../types";

const es = {
  "gestion.cashCurrentBalances.navigation": "Efectivo en caja",
  "gestion.cashCurrentBalances.eyebrow": "Control de efectivo",
  "gestion.cashCurrentBalances.title": "Efectivo en caja",
  "gestion.cashCurrentBalances.subtitle": "Efectivo que debe existir en cada terminal según el fondo y todos los movimientos registrados.",
  "gestion.cashCurrentBalances.refresh": "Actualizar ahora",
  "gestion.cashCurrentBalances.summary": "Resumen de efectivo en caja",
  "gestion.cashCurrentBalances.totalExpected": "Total que debe haber",
  "gestion.cashCurrentBalances.openTerminals": "Cajas abiertas",
  "gestion.cashCurrentBalances.activeTerminals": "Terminales activas",
  "gestion.cashCurrentBalances.updatedAt": "Actualizado a las",
  "gestion.cashCurrentBalances.staleWarning": "No se pudo actualizar el saldo. Se conservan los últimos importes recibidos.",
  "gestion.cashCurrentBalances.resize": "Cambiar ancho de",
  "gestion.cashCurrentBalances.column.terminal": "Terminal",
  "gestion.cashCurrentBalances.column.status": "Estado",
  "gestion.cashCurrentBalances.column.user": "Usuario de apertura",
  "gestion.cashCurrentBalances.column.openedAt": "Apertura",
  "gestion.cashCurrentBalances.column.expectedCash": "Efectivo que debe haber",
  "gestion.cashCurrentBalances.column.lastActivity": "Último movimiento",
  "gestion.cashCurrentBalances.status.open": "Caja abierta",
  "gestion.cashCurrentBalances.status.closed": "Caja cerrada",
  "gestion.cashCurrentBalances.status.neverOpened": "Sin sesión",
  "gestion.cashCurrentBalances.loading": "Calculando el efectivo de las terminales…",
  "gestion.cashCurrentBalances.loadError": "No se pudo cargar el efectivo actual de las terminales.",
  "gestion.cashCurrentBalances.retry": "Reintentar",
  "gestion.cashCurrentBalances.empty": "No existen terminales activas para esta tienda.",
  "gestion.cashCurrentBalances.terminalCount": "Terminales visibles: {count}",
  "gestion.cashCurrentBalances.definition": "Fondo + entradas + cobros − devoluciones − retiradas"
};

const en = {
  "gestion.cashCurrentBalances.navigation": "Cash in drawers",
  "gestion.cashCurrentBalances.eyebrow": "Cash control",
  "gestion.cashCurrentBalances.title": "Cash in drawers",
  "gestion.cashCurrentBalances.subtitle": "Cash that should be present at each terminal according to its fund and all recorded movements.",
  "gestion.cashCurrentBalances.refresh": "Refresh now",
  "gestion.cashCurrentBalances.summary": "Cash balance summary",
  "gestion.cashCurrentBalances.totalExpected": "Total expected cash",
  "gestion.cashCurrentBalances.openTerminals": "Open drawers",
  "gestion.cashCurrentBalances.activeTerminals": "Active terminals",
  "gestion.cashCurrentBalances.updatedAt": "Updated at",
  "gestion.cashCurrentBalances.staleWarning": "The balance could not be refreshed. The last received amounts are still shown.",
  "gestion.cashCurrentBalances.resize": "Resize",
  "gestion.cashCurrentBalances.column.terminal": "Terminal",
  "gestion.cashCurrentBalances.column.status": "Status",
  "gestion.cashCurrentBalances.column.user": "Opening user",
  "gestion.cashCurrentBalances.column.openedAt": "Opened at",
  "gestion.cashCurrentBalances.column.expectedCash": "Cash that should be present",
  "gestion.cashCurrentBalances.column.lastActivity": "Last movement",
  "gestion.cashCurrentBalances.status.open": "Drawer open",
  "gestion.cashCurrentBalances.status.closed": "Drawer closed",
  "gestion.cashCurrentBalances.status.neverOpened": "No session",
  "gestion.cashCurrentBalances.loading": "Calculating terminal cash…",
  "gestion.cashCurrentBalances.loadError": "Current terminal cash could not be loaded.",
  "gestion.cashCurrentBalances.retry": "Retry",
  "gestion.cashCurrentBalances.empty": "There are no active terminals for this store.",
  "gestion.cashCurrentBalances.terminalCount": "Visible terminals: {count}",
  "gestion.cashCurrentBalances.definition": "Fund + entries + cash payments − refunds − withdrawals"
};

const zh = {
  "gestion.cashCurrentBalances.navigation": "钱箱现金",
  "gestion.cashCurrentBalances.eyebrow": "现金控制",
  "gestion.cashCurrentBalances.title": "钱箱现金",
  "gestion.cashCurrentBalances.subtitle": "根据备用金和所有已登记流水计算每个终端应有的现金。",
  "gestion.cashCurrentBalances.refresh": "立即刷新",
  "gestion.cashCurrentBalances.summary": "钱箱现金汇总",
  "gestion.cashCurrentBalances.totalExpected": "应有现金总额",
  "gestion.cashCurrentBalances.openTerminals": "已开钱箱",
  "gestion.cashCurrentBalances.activeTerminals": "活动终端",
  "gestion.cashCurrentBalances.updatedAt": "更新时间",
  "gestion.cashCurrentBalances.staleWarning": "无法更新余额，当前仍显示最近一次收到的金额。",
  "gestion.cashCurrentBalances.resize": "调整列宽",
  "gestion.cashCurrentBalances.column.terminal": "终端",
  "gestion.cashCurrentBalances.column.status": "状态",
  "gestion.cashCurrentBalances.column.user": "开箱用户",
  "gestion.cashCurrentBalances.column.openedAt": "开箱时间",
  "gestion.cashCurrentBalances.column.expectedCash": "钱箱应有现金",
  "gestion.cashCurrentBalances.column.lastActivity": "最近流水",
  "gestion.cashCurrentBalances.status.open": "钱箱已开",
  "gestion.cashCurrentBalances.status.closed": "钱箱已关",
  "gestion.cashCurrentBalances.status.neverOpened": "尚无会话",
  "gestion.cashCurrentBalances.loading": "正在计算终端现金…",
  "gestion.cashCurrentBalances.loadError": "无法加载终端当前现金。",
  "gestion.cashCurrentBalances.retry": "重试",
  "gestion.cashCurrentBalances.empty": "该门店没有活动终端。",
  "gestion.cashCurrentBalances.terminalCount": "可见终端：{count}",
  "gestion.cashCurrentBalances.definition": "备用金 + 入款 + 现金收款 − 退款 − 取款"
};

export function cashCurrentBalanceMessages(locale: LocaleCode) {
  if (locale === "en") return en;
  if (locale === "zh") return zh;
  return es;
}
