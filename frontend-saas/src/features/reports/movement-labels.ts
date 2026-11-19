import { useI18n } from "../../i18n";

const labels = {
  ledger: ["Detalle de movimientos", "Movement ledger", "库存变动明细"],
  viewLedger: ["Ver detalle", "View details", "查看明细"],
  summary: ["Movimientos por grupo", "Movements by group", "按分组统计库存变动"],
  type: ["Tipo de movimiento", "Movement type", "变动类型"],
  warehouse: ["Almacén", "Warehouse", "仓库"],
  product: ["Producto", "Product", "商品"],
  allTypes: ["Todos los tipos", "All types", "全部类型"],
  allWarehouses: ["Todos los almacenes", "All warehouses", "全部仓库"],
  allProducts: ["Todos los productos", "All products", "全部商品"],
  quantity: ["Cantidad", "Quantity", "数量"],
  occurredAt: ["Fecha del movimiento (UTC)", "Movement time (UTC)", "变动时间 (UTC)"],
  receivedAt: ["Recepción (UTC)", "Received (UTC)", "接收时间 (UTC)"],
  unique: ["Movimientos únicos", "Unique movements", "独立变动记录"],
  malformed: ["Recepciones no interpretables", "Unreadable receipts", "无法读取的接收记录"],
  conflicts: ["Movimientos con datos contradictorios", "Movements with conflicting data", "数据冲突的变动记录"],
  duplicate: ["Recepciones duplicadas", "Duplicate receipts", "重复接收记录"],
  scope: ["Los movimientos se agrupan por la fecha en que ocurrieron (UTC). Los controles de cobertura se calculan en el ámbito de empresa, tienda y provincia.", "Movements are grouped by occurrence date (UTC). Coverage checks use the company, store and province scope.", "库存变动按发生日期 (UTC) 分组。覆盖检查按企业、门店和省份范围计算。"],
  noRows: ["No hay movimientos para estos filtros.", "No movements match these filters.", "没有符合筛选条件的库存变动。"],
  AJUSTE: ["Ajuste", "Adjustment", "调整"],
  TRANSFERENCIA_SALIDA: ["Salida por transferencia", "Transfer out", "调拨出库"],
  TRANSFERENCIA_ENTRADA: ["Entrada por transferencia", "Transfer in", "调拨入库"],
  SALIDA_ALMACEN: ["Salida de almacén", "Warehouse out", "出库"],
  ENTRADA_ALMACEN: ["Entrada de almacén", "Warehouse in", "入库"],
  ALBARAN_ENTRADA: ["Albarán de entrada", "Inbound delivery note", "入库单"],
  FACTURA_ENTRADA: ["Factura de entrada", "Purchase invoice", "采购发票"],
  ALBARAN_VENTA: ["Albarán de venta", "Sales delivery note", "销售送货单"],
  TICKET: ["Ticket", "Receipt", "收据"],
  FACTURA_VENTA: ["Factura de venta", "Sales invoice", "销售发票"],
  ANULACION: ["Anulación", "Void", "作废"],
} as const;

export const movementTypes = ["AJUSTE", "TRANSFERENCIA_SALIDA", "TRANSFERENCIA_ENTRADA", "SALIDA_ALMACEN", "ENTRADA_ALMACEN",
  "ALBARAN_ENTRADA", "FACTURA_ENTRADA", "ALBARAN_VENTA", "TICKET", "FACTURA_VENTA", "ANULACION"] as const;

export function useMovementLabels() {
  const { language } = useI18n();
  const index = language === "zh" ? 2 : language === "en" ? 1 : 0;
  return (key: string) => Object.hasOwn(labels, key) ? labels[key as keyof typeof labels][index] : key;
}
