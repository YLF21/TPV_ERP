import type { LocaleCode } from "../types";

const catalog = {
  es: {
    infoTab: "Precio por cantidad", perPack: "Unidades por lote", fromQuantity: "Desde",
    unitCount: (n: number) => `${n.toLocaleString("es-ES")} ${n === 1 ? "unidad" : "unidades"}`,
    looseUnitHint: "Las unidades sueltas se cobran por separado.",
    blocked: "El bloqueo de descuentos impide aplicar esta regla en caja.",
    title: "Precios por cantidad", button: "Configurar precios por cantidad", close: "Cerrar", none: "Sin precios por cantidad",
    pack: "Precio por lotes", threshold: "Precio unitario por umbral", packHelp: "Cada lote tiene un precio total. Se elige la combinación más económica de lotes y unidades sueltas.",
    thresholdHelp: "Desde la cantidad indicada, el precio se aplica a todas las unidades.", quantity: "Cantidad", price: "Precio", packPrice: "Precio del lote", unitPrice: "Precio por unidad",
    add: "Añadir tramo", remove: "Eliminar tramo", simulate: "Simular compra (1 a 10.000 unidades)", total: "Total", loose: "Unidades sueltas", packs: "Lotes aplicados",
    save: "Guardar", saveTitle: "Guardar los tramos conservando el estado actual", apply: "Aplicar", doNotApply: "No aplicar",
    applied: "Aplicado", notApplied: "No aplicado", unknownState: "Estado desconocido",
    cancel: "Cancelar", clear: "Quitar precios por cantidad", empty: "Añade al menos un tramo.", tooMany: "Se admiten hasta 100 tramos.", invalidQuantity: "La cantidad debe ser un entero positivo.",
    missingFields: "Hay campos sin rellenar.", invalidPrice: "El precio no es válido.", duplicate: "Las cantidades no se pueden repetir.", invalidBase: "Introduce un PVP válido para simular.", invalidSimulationQuantity: "Introduce una cantidad entre 1 y 10.000 unidades.",
    discardTitle: "Cambios sin confirmar", discardMessage: "Hay cambios en los precios por cantidad. ¿Quieres salir sin confirmarlos?", keepEditing: "Seguir editando", discard: "Descartar cambios",
    summaryPack: "Lotes", summaryThreshold: "Umbrales", summaryTier: "tramo", summaryTiers: "tramos", genericHint: "Los cambios se incorporan a la lista en edición."
  },
  en: {
    infoTab: "Quantity pricing", perPack: "Units per pack", fromQuantity: "From",
    unitCount: (n: number) => `${n.toLocaleString("en-GB")} ${n === 1 ? "unit" : "units"}`,
    looseUnitHint: "Loose units are priced separately.",
    blocked: "The discount lock prevents this rule from applying at checkout.",
    title: "Quantity pricing", button: "Set quantity pricing", close: "Close", none: "No quantity pricing",
    pack: "Pack pricing", threshold: "Unit price by threshold", packHelp: "Each pack has a total price. The lowest cost combination of packs and loose units is used.",
    thresholdHelp: "From the specified quantity, the unit price applies to every unit.", quantity: "Quantity", price: "Price", packPrice: "Pack price", unitPrice: "Unit price",
    add: "Add tier", remove: "Remove tier", simulate: "Simulate purchase (1 to 10,000 units)", total: "Total", loose: "Loose units", packs: "Applied packs",
    save: "Save", saveTitle: "Save tiers and keep the current activation status", apply: "Apply", doNotApply: "Do not apply",
    applied: "Applied", notApplied: "Not applied", unknownState: "Status unknown",
    cancel: "Cancel", clear: "Remove quantity pricing", empty: "Add at least one tier.", tooMany: "Up to 100 tiers are allowed.", invalidQuantity: "Quantity must be a positive integer.",
    missingFields: "Some fields are empty.", invalidPrice: "Enter a valid price.", duplicate: "Quantities must be unique.", invalidBase: "Enter a valid sale price to simulate.", invalidSimulationQuantity: "Enter a quantity between 1 and 10,000 units.",
    discardTitle: "Unconfirmed changes", discardMessage: "Quantity pricing has changed. Leave without confirming?", keepEditing: "Keep editing", discard: "Discard changes",
    summaryPack: "Packs", summaryThreshold: "Thresholds", summaryTier: "tier", summaryTiers: "tiers", genericHint: "Changes are added to the list you are editing."
  },
  zh: {
    infoTab: "数量定价", perPack: "每包件数", fromQuantity: "起始数量",
    unitCount: (n: number) => `${n.toLocaleString("zh-CN")} 件`,
    looseUnitHint: "散件单独计价。",
    blocked: "已禁止折扣，结账时不会应用此规则。",
    title: "数量定价", button: "设置数量定价", close: "关闭", none: "未设置数量定价",
    pack: "组合包定价", threshold: "数量门槛单价", packHelp: "每个组合包有总价。系统选择组合包与散件的最低总价组合。",
    thresholdHelp: "达到指定数量后，该单价适用于所有商品。", quantity: "数量", price: "价格", packPrice: "组合包价格", unitPrice: "单价",
    add: "添加阶梯", remove: "删除阶梯", simulate: "模拟购买（1 至 10,000 件）", total: "总价", loose: "散件数量", packs: "已用组合包",
    save: "保存", saveTitle: "保存阶梯并保持当前启用状态", apply: "启用", doNotApply: "停用",
    applied: "已启用", notApplied: "未启用", unknownState: "状态未知",
    cancel: "取消", clear: "移除数量定价", empty: "请至少添加一个阶梯。", tooMany: "最多允许 100 个阶梯。", invalidQuantity: "数量必须是正整数。",
    missingFields: "有字段尚未填写。", invalidPrice: "请输入有效价格。", duplicate: "数量不能重复。", invalidBase: "请输入有效售价以模拟。", invalidSimulationQuantity: "请输入 1 至 10,000 件的数量。",
    discardTitle: "未确认的更改", discardMessage: "数量定价已更改。确定不确认直接退出吗？", keepEditing: "继续编辑", discard: "放弃更改",
    summaryPack: "组合包", summaryThreshold: "数量门槛", summaryTier: "个阶梯", summaryTiers: "个阶梯", genericHint: "更改将加入正在编辑的列表。"
  }
} as const;

export function quantityPricingMessages(locale: LocaleCode) { return catalog[locale]; }
