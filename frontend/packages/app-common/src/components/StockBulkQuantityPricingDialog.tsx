import { useMemo } from "react";
import { quantityPricingMessages } from "../i18n/QuantityPricingMessages";
import { QuantityPricingEditor } from "../QuantityPricingEditor";
import type { QuantityPricing } from "../quantityPricing";
import type { LocaleCode } from "../types";
import { ErpConfirmDialog } from "./ErpConfirmDialog";
import { bulkQuantityPricingPreview, type QuantityPricingAction } from "./stockBulkQuantityPricing";
import type { StockBulkEditRowData } from "./stockBulkEdit";

const labels = {
  es: {
    missing: "Solo productos sin regla", replace: "Reemplazar reglas de todos los seleccionados",
    remove: "Quitar reglas", enable: "Activar reglas", disable: "Desactivar reglas",
    selected: "Seleccionados", known: "Con estado conocido", eligible: "Aplicables", changed: "Cambiarán",
    skipped: "Sin cambios", draft: "Los cambios se incorporan a la lista en edición."
  },
  en: {
    missing: "Only products without a rule", replace: "Replace rules for all selected products",
    remove: "Remove rules", enable: "Enable rules", disable: "Disable rules",
    selected: "Selected", known: "Known status", eligible: "Applicable", changed: "Will change",
    skipped: "Unchanged", draft: "Changes are added to the list you are editing."
  },
  zh: {
    missing: "仅无规则的商品", replace: "替换所有选中商品的规则",
    remove: "移除规则", enable: "启用规则", disable: "停用规则",
    selected: "已选择", known: "状态已知", eligible: "适用", changed: "将变更",
    skipped: "不变", draft: "更改将加入正在编辑的列表。"
  }
} as const;

export function StockBulkQuantityPricingDialog({ rows, action, locale, onClose, onConfirm }: {
  rows: StockBulkEditRowData[];
  action: QuantityPricingAction;
  locale: LocaleCode;
  onClose: () => void;
  onConfirm: (changes: Map<string, QuantityPricing>) => void;
}) {
  const words = labels[locale];
  const pricing = quantityPricingMessages(locale);
  const selected = rows.filter(row => row.selected).length;
  const preview = useMemo(() => bulkQuantityPricingPreview(rows, action, null), [rows, action]);
  const changes = preview.filter(row => row.change);
  const scope = `${words.selected} ${selected} · ${words.known} ${preview.length} · ${words.changed} ${changes.length} · ${words.skipped} ${selected - changes.length}`;

  if (action === "missing" || action === "replace") {
    const eligible = preview.filter(row => action === "replace" || row.before === null);
    const hint = `${words[action]} · ${words.selected} ${selected} · ${words.eligible} ${eligible.length} · ${words.skipped} ${selected - eligible.length}. ${words.draft}`;
    return <QuantityPricingEditor value={null} basePrice={eligible[0]?.basePrice ?? 0} locale={locale}
      saveHint={hint} onClose={onClose} onConfirmIntent={(rule, intent) => {
        const changed = bulkQuantityPricingPreview(rows, action, rule, intent === "save").filter(row => row.change);
        onConfirm(new Map(changed.map(row => [row.id, row.after ?? null])));
      }} />;
  }

  return <ErpConfirmDialog title={words[action]} message={`${scope}. ${words.draft}`}
    confirmLabel={words[action]} cancelLabel={pricing.cancel} onCancel={onClose}
    onConfirm={() => onConfirm(new Map(changes.map(row => [row.id, row.after ?? null])))} />;
}
