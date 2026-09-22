import { useId, useRef, useState } from "react";
import { useI18n } from "../../i18n";
import type { CompanySummary } from "../../lib/types";
import { Input } from "../../shared/ui";
import { SPANISH_PROVINCES } from "../../shared/provinces/spanish-provinces.mjs";
import { companyDateRange, companyProvinceFilterValue, emptyCompanyFilters, type CompanyFilters as Filters } from "./company-filters.mjs";
import { useCompanyLabels } from "./labels";

const labels = {
  filters: ["Filtros de empresas", "Company filters", "企业筛选"],
  query: ["Empresa / NIF", "Company / Tax ID", "企业 / 税号"],
  all: ["Todos", "All", "全部"],
  allProvinces: ["Todas las provincias", "All provinces", "所有省份"],
  anyDate: ["Cualquier fecha", "Any date", "不限日期"],
  more: ["Más filtros", "More filters", "更多筛选"],
  less: ["Menos filtros", "Fewer filters", "收起筛选"],
  from: ["Desde", "From", "从"],
  to: ["Hasta", "To", "至"],
  owner: ["Propietario (nombre o DNI/NIE)", "Owner (name or DNI/NIE)", "所有者（姓名或身份证号）"],
  remove: ["Quitar filtro", "Remove filter", "移除筛选"],
  clear: ["Limpiar todos", "Clear all", "清除全部"],
  selected: ["Filtros aplicados", "Applied filters", "已应用筛选"],
  invalidDates: ["La fecha inicial debe ser anterior o igual a la final.", "The start date must be on or before the end date.", "开始日期不能晚于结束日期。"],
  results: ["empresas", "companies", "家企业"],
} as const;

export function CompanyFilters({ filters, onChange, companies, count }: {
  filters: Filters; onChange: (filters: Filters) => void; companies: CompanySummary[]; count: number;
}) {
  const { t, language } = useI18n();
  const l = useCompanyLabels();
  const f = (key: keyof typeof labels) => labels[key][language === "zh" ? 2 : language === "en" ? 1 : 0];
  const [more, setMore] = useState(false);
  const [dates, setDates] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const id = useId();
  const update = (key: keyof Filters, value: string) => onChange({ ...filters, [key]: value });
  const dateLabel = (date: string) => new Intl.DateTimeFormat(language, { dateStyle: "short" }).format(new Date(`${date}T00:00:00`));
  const dateSummary = filters.dateFrom && filters.dateTo
    ? `${dateLabel(filters.dateFrom)} – ${dateLabel(filters.dateTo)}`
    : filters.dateFrom ? `${f("from")} ${dateLabel(filters.dateFrom)}`
      : filters.dateTo ? `${f("to")} ${dateLabel(filters.dateTo)}` : f("anyDate");
  const invalidDates = companyDateRange(filters.dateFrom, filters.dateTo) === null;
  const typeLabel = (value: string) => value === "SOCIEDAD" ? l("society") : value === "AUTONOMO" ? l("selfEmployed") : value;
  const provinces = new Map(SPANISH_PROVINCES.map(province => [province.code, province.name]));
  for (const company of companies) {
    const value = company.companyAddress?.provincia;
    if (value?.trim()) provinces.set(companyProvinceFilterValue(value), value);
  }
  // Catalogue names remain canonical, while unknown historical values stay selectable.
  for (const province of SPANISH_PROVINCES) provinces.set(province.code, province.name);
  if (filters.province && !provinces.has(filters.province)) provinces.set(filters.province, filters.province.replace(/^raw:/, ""));
  const fields: { key: keyof Filters; label: string }[] = [
    { key: "contactName", label: t("contactName") }, { key: "contactPhone", label: l("phone") },
    { key: "contactEmail", label: t("contactEmail") }, { key: "owner", label: f("owner") },
    { key: "city", label: t("city") }, { key: "postalCode", label: t("postalCode") },
  ];
  const allChips: { key: keyof Filters; label: string; value: string; keys?: (keyof Filters)[] }[] = [
    { key: "query", label: f("query"), value: filters.query.trim() },
    { key: "province", label: t("province"), value: provinces.get(filters.province) ?? "" },
    { key: "taxpayerType", label: t("type"), value: typeLabel(filters.taxpayerType) },
    { key: "dateFrom", label: l("createdAt"), value: filters.dateFrom || filters.dateTo ? dateSummary : "", keys: ["dateFrom", "dateTo"] },
    ...fields.map(field => ({ ...field, value: filters[field.key].trim() })),
  ];
  const chips = allChips.filter(chip => chip.value !== "");
  function remove(keys: (keyof Filters)[], index: number) {
    const next = { ...filters };
    for (const key of keys) next[key] = "";
    onChange(next);
    requestAnimationFrame(() => {
      const remaining = root.current?.querySelectorAll<HTMLButtonElement>(".company-filter-chip button");
      const target = remaining?.[Math.min(index, remaining.length - 1)] ?? root.current?.querySelector<HTMLInputElement>("input");
      target?.focus();
    });
  }
  return <div className="company-filters" role="search" aria-label={f("filters")} ref={root}>
    <div className="company-filter-main">
      <Input label={f("query")} value={filters.query} onChange={value => update("query", value)} />
      <label>{t("province")}<select className="control-input" aria-label={t("province")} value={filters.province} onChange={event => update("province", event.target.value)}>
        <option value="">{f("allProvinces")}</option>
        {[...provinces].sort((a, b) => a[1].localeCompare(b[1], language)).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>
      <label>{t("type")}<select className="control-input" aria-label={t("type")} value={filters.taxpayerType} onChange={event => update("taxpayerType", event.target.value)}>
        <option value="">{f("all")}</option><option value="SOCIEDAD">{l("society")}</option><option value="AUTONOMO">{l("selfEmployed")}</option>
      </select></label>
      <div className="company-date-filter"><span id={`${id}-date-label`}>{l("createdAt")}</span>
        <button className="control-input company-date-toggle" type="button" aria-label={l("createdAt")} aria-describedby={`${id}-date-summary`} aria-expanded={dates} aria-controls={`${id}-dates`} onClick={() => setDates(!dates)}>
          <span id={`${id}-date-summary`}>{dateSummary}</span><span aria-hidden="true">▾</span>
        </button>
      </div>
      <button className="secondary-button company-more-filters" type="button" aria-expanded={more} aria-controls={`${id}-more`} onClick={() => setMore(!more)}>{more ? `− ${f("less")}` : `+ ${f("more")}`}</button>
    </div>
    {dates && <div className="company-filter-dates" id={`${id}-dates`} role="group" aria-labelledby={`${id}-date-label`}>
      <Input type="date" label={f("from")} value={filters.dateFrom} onChange={value => update("dateFrom", value)} />
      <Input type="date" label={f("to")} value={filters.dateTo} onChange={value => update("dateTo", value)} />
    </div>}
    {more && <div className="company-filter-extra" id={`${id}-more`}>
      {fields.map(field => <Input key={field.key} label={field.label} value={filters[field.key]} onChange={value => update(field.key, value)} />)}
    </div>}
    {invalidDates && <p className="company-filter-error" role="alert">{f("invalidDates")}</p>}
    {chips.length > 0 && <div className="company-filter-applied" aria-label={f("selected")} role="group">
      <div className="company-filter-chips">{chips.map((chip, index) => <span className="company-filter-chip" key={chip.key}>
        <span title={`${chip.label}: ${chip.value}`}>{chip.label}: <strong>{chip.value}</strong></span>
        <button type="button" aria-label={`${f("remove")} ${chip.label}`} onClick={() => remove(chip.keys ?? [chip.key], index)}>×</button>
      </span>)}</div>
      <button className="small-button company-filter-clear" type="button" onClick={() => {
        onChange(emptyCompanyFilters()); root.current?.querySelector<HTMLInputElement>("input")?.focus();
      }}>{f("clear")}</button>
    </div>}
    <p className="company-filter-count" role="status" aria-live="polite">{count} {f("results")}</p>
  </div>;
}
