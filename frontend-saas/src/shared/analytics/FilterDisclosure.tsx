import { useId, useState, type ReactNode } from "react";
import { useI18n } from "../../i18n";
import { formatPickerDate } from "../lib";
import { SPANISH_PROVINCES } from "../provinces/spanish-provinces.mjs";
import "./analytics.css";

/** Keep the applied scope readable even when the editing controls are folded away. */
export function FilterDisclosure({ filters, options, dirty = false, children }: {
  filters: Record<string, string>;
  options?: { companies: { id: string; name: string }[]; stores: { id: string; name: string }[] } | null;
  dirty?: boolean;
  children: ReactNode;
}) {
  const { language } = useI18n();
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  const [expanded, setExpanded] = useState(true);
  const open = expanded || dirty;
  const id = useId();
  const period = filters.from && filters.to
    ? `${formatPickerDate(filters.from, true)} – ${formatPickerDate(filters.to, true)} · UTC` : "";
  const scope = [
    filters.companyName || options?.companies.find(item => item.id === filters.companyId)?.name || (filters.companyId ? l("Empresa seleccionada", "Selected company", "所选企业") : l("Todas las empresas", "All companies", "全部企业")),
    filters.storeName || options?.stores.find(item => item.id === filters.storeId)?.name || (filters.storeId ? l("Tienda seleccionada", "Selected store", "所选门店") : l("Todas las tiendas", "All stores", "全部门店")),
    filters.province ? SPANISH_PROVINCES.find(item => item.code === filters.province)?.name || l("Sin provincia", "No province", "未分配省份") : "",
    filters.productName || filters.productCode || (filters.productId ? l("Producto seleccionado", "Selected product", "所选商品") : ""),
    filters.warehouseName || (filters.warehouseId ? l("Almacén seleccionado", "Selected warehouse", "所选仓库") : ""),
    filters.search ? `${l("Búsqueda", "Search", "搜索")}: ${filters.search}` : "",
    filters.currency || "",
    filters.typeLabel || filters.type || "",
    filters.active === "true" ? l("Artículos activos", "Active products", "启用商品") : filters.active === "false" ? l("Artículos inactivos", "Inactive products", "停用商品") : "",
    filters.changedOnly === "true" ? l("Solo cambios", "Changes only", "仅显示变更") : "",
  ].filter(Boolean).join(" · ");
  const comparison = filters.comparison === "CUSTOM" && filters.comparisonFrom && filters.comparisonTo
    ? `${l("Comparación", "Comparison", "对比")}: ${formatPickerDate(filters.comparisonFrom, true)} – ${formatPickerDate(filters.comparisonTo, true)} · UTC`
    : filters.comparison === "PREVIOUS_PERIOD" ? l("Comparación: período anterior", "Comparison: previous period", "对比：上一期间")
    : filters.comparison === "PREVIOUS_YEAR" ? l("Comparación: año anterior", "Comparison: previous year", "对比：去年同期") : "";

  return <div className="analytics-filter-disclosure">
    <div className="analytics-filter-overview">
      <div className="analytics-applied-scope" aria-label={l("Filtros aplicados", "Applied filters", "已应用筛选")}>
        {period && <strong>{period}</strong>}<span>{scope}</span>{comparison && <span>{comparison}</span>}
      </div>
      <button type="button" className="small-button" aria-expanded={open} aria-controls={id} disabled={dirty}
        title={dirty ? l("Aplica o restablece los cambios pendientes antes de ocultar los filtros.", "Apply or reset pending changes before hiding filters.", "隐藏筛选前，请应用或重置待处理更改。") : undefined}
        onClick={() => setExpanded(!open)}>{open ? l("Ocultar filtros", "Hide filters", "隐藏筛选") : l("Mostrar filtros", "Show filters", "显示筛选")}</button>
    </div>
    <div id={id} hidden={!open}>{children}</div>
  </div>;
}
