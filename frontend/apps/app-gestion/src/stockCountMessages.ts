import type { LocaleCode } from "../../../packages/app-common/src/types";

const copy = {
  es: {
    title: "Hacer inventario", subtitle: "Historial y gestión de inventarios", create: "Crear inventario", consult: "Consultar", cancelDocument: "Anular borrador",
    search: "Número o notas", editor: "Documento de inventario", save: "Guardar borrador (F9)", review: "Revisar diferencias", exit: "Salir (Esc)",
    number: "Número", date: "Fecha", status: "Estado", warehouse: "Almacén", notes: "Notas", articles: "Artículos", user: "Usuario", counted: "Contados",
    product: "Buscar producto", placeholder: "Código, código de barras o nombre", add: "Añadir", family: "Añadir por familia", import: "Importar Excel", print: "Imprimir",
    code: "Código", barcode: "Código de barras", name: "Producto", before: "Stock anterior", quantity: "Cantidad contada", difference: "Diferencia", remove: "Quitar",
    pending: "Pendiente", hint: "Vacío = pendiente. 0 = sin unidades.", draftHint: "Guardar el borrador no modifica el stock.", legend: "Menos: rojo · Coincide: verde · Más: amarillo",
    confirmTitle: "Confirmar inventario", confirmText: "Se sustituirán las existencias de los artículos incluidos en este almacén por las cantidades contadas. Revisa las diferencias antes de continuar.",
    confirm: "Confirmar inventario", back: "Volver a revisar", differences: "Diferencias", needCount: "Cuenta los artículos pendientes o retíralos del documento antes de confirmar.",
    stockChanged: "El stock de algunos artículos ha cambiado. Sus cantidades contadas han quedado pendientes: vuelve a contarlos antes de confirmar.", refreshStock: "Revisar stock de referencia",
    saved: "Borrador guardado", confirmed: "Inventario confirmado", error: "No se pudo completar la operación", invalid: "Revisa las cantidades: deben ser cero o positivas, con un máximo de 3 decimales; los artículos por unidad requieren enteros.",
    closeTitle: "Cambios sin guardar", closeText: "Hay cambios en el documento. Puedes guardar el borrador o descartarlos antes de salir.", discard: "Descartar y salir", stay: "Seguir editando",
    selectFamily: "Seleccionar familia", cancel: "Cancelar", empty: "SIN DATOS", readOnly: "Este documento está cerrado y no se puede editar.", noProducts: "No se encontraron productos", reload: "Recargar documento", reloadText: "Se perderán los cambios sin guardar. ¿Recargar la versión guardada?", cancelText: "¿Anular este borrador? No se modificarán existencias.", all: "Todos", actions: "Acciones", noAccess: "No tienes permiso para gestionar inventarios."
  },
  en: {
    title: "Take inventory", subtitle: "Inventory documents and management", create: "Create inventory", consult: "View", cancelDocument: "Cancel draft",
    search: "Number or notes", editor: "Inventory document", save: "Save draft (F9)", review: "Review differences", exit: "Exit (Esc)",
    number: "Number", date: "Date", status: "Status", warehouse: "Warehouse", notes: "Notes", articles: "Items", user: "User", counted: "Counted",
    product: "Find product", placeholder: "Code, barcode or name", add: "Add", family: "Add by family", import: "Import Excel", print: "Print",
    code: "Code", barcode: "Barcode", name: "Product", before: "Previous stock", quantity: "Counted quantity", difference: "Difference", remove: "Remove",
    pending: "Pending", hint: "Blank = pending. 0 = no units.", draftHint: "Saving a draft does not change stock.", legend: "Less: red · Matches: green · More: yellow",
    confirmTitle: "Confirm inventory", confirmText: "Stock for the included items in this warehouse will be replaced with the counted quantities. Review the differences before continuing.",
    confirm: "Confirm inventory", back: "Review again", differences: "Differences", needCount: "Count pending items or remove them before confirming.",
    stockChanged: "Stock has changed for some items. Their counts have been cleared: count them again before confirming.", refreshStock: "Review reference stock",
    saved: "Draft saved", confirmed: "Inventory confirmed", error: "The operation could not be completed", invalid: "Check quantities: zero or positive, up to 3 decimals; unit products require whole numbers.",
    closeTitle: "Unsaved changes", closeText: "This document has unsaved changes. Save the draft or discard them before leaving.", discard: "Discard and exit", stay: "Keep editing",
    selectFamily: "Select family", cancel: "Cancel", empty: "NO DATA", readOnly: "This document is closed and cannot be edited.", noProducts: "No products found", reload: "Reload document", reloadText: "Unsaved changes will be lost. Reload the saved version?", cancelText: "Cancel this draft? Stock will not change.", all: "All", actions: "Actions", noAccess: "You do not have permission to manage inventory."
  },
  zh: {
    title: "进行盘点", subtitle: "盘点单据列表与管理", create: "新建盘点", consult: "查看", cancelDocument: "作废草稿",
    search: "单号或备注", editor: "盘点单", save: "保存草稿 (F9)", review: "核对差异", exit: "退出 (Esc)",
    number: "单号", date: "日期", status: "状态", warehouse: "仓库", notes: "备注", articles: "商品数", user: "操作用户", counted: "已盘点",
    product: "搜索商品", placeholder: "编码、条码或名称", add: "添加", family: "按分类添加", import: "导入 Excel", print: "打印",
    code: "编码", barcode: "条码", name: "商品", before: "原库存", quantity: "实盘数量", difference: "差异", remove: "移除",
    pending: "待盘点", hint: "留空表示待盘点；0 表示无库存。", draftHint: "保存草稿不会修改库存。", legend: "减少：红色 · 一致：绿色 · 增加：黄色",
    confirmTitle: "确认盘点", confirmText: "本仓库单据中商品的库存将替换为实盘数量。请先核对差异。",
    confirm: "确认盘点", back: "返回核对", differences: "差异数", needCount: "请先盘点待处理商品，或将其从单据移除。",
    stockChanged: "部分商品库存已变化，其实盘数量已清空。确认前请重新盘点。", refreshStock: "核对参考库存",
    saved: "草稿已保存", confirmed: "盘点已确认", error: "操作未完成", invalid: "数量必须为零或正数，最多三位小数；按件商品必须为整数。",
    closeTitle: "未保存的修改", closeText: "单据包含未保存的修改。退出前请保存草稿或放弃修改。", discard: "放弃并退出", stay: "继续编辑",
    selectFamily: "选择分类", cancel: "取消", empty: "暂无数据", readOnly: "此单据已关闭，无法编辑。", noProducts: "未找到商品", reload: "重新加载单据", reloadText: "未保存的修改将丢失。是否重新加载？", cancelText: "是否作废草稿？库存不会改变。", all: "全部", actions: "操作", noAccess: "无权限管理盘点。"
  }
};
export type CountText = keyof typeof copy.es;
export const countText = (locale: LocaleCode) => (key: CountText) => copy[locale][key];
