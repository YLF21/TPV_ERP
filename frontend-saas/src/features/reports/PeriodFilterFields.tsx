import { useI18n } from "../../i18n";
import { DateRangePicker } from "../../shared/DateRangePicker";
import { PeriodShortcuts } from "./PeriodShortcuts";
import { comparisons, groupings, provinces } from "./report-query";
import { useStatisticsLabels } from "./statistics-labels";
import type { StatisticsComparison, StatisticsFilters, StatisticsGrouping, StatisticsOptions } from "./statistics-types";

export function PeriodFilterFields({ draft, options, grouping, comparison = true, onChange }: {
  draft: StatisticsFilters;
  options: StatisticsOptions | null;
  grouping?: boolean;
  comparison?: boolean;
  onChange: (value: Partial<StatisticsFilters>) => void;
}) {
  const { t } = useI18n();
  const l = useStatisticsLabels();
  const stores = options?.stores.filter((store) => !draft.companyId || store.companyId === draft.companyId);

  return <>
    <PeriodShortcuts from={draft.from} to={draft.to} onChange={onChange} />
    <DateRangePicker fromLabel={l("from")} toLabel={l("to")} from={draft.from} to={draft.to} onChange={onChange} required />
    {comparison && <label>
      {l("comparison")}
      <select className="control-input" value={draft.comparison}
        onChange={(event) => onChange({ comparison: event.target.value as StatisticsComparison })}>
        {comparisons.map((comparison) => <option key={comparison} value={comparison}>{l(comparison)}</option>)}
      </select>
    </label>}
    {comparison && draft.comparison === "CUSTOM" && <>
      <DateRangePicker fromLabel={l("comparisonFrom")} toLabel={l("comparisonTo")} from={draft.comparisonFrom} to={draft.comparisonTo}
        onChange={({ from: comparisonFrom, to: comparisonTo }) => onChange({ comparisonFrom, comparisonTo })} required />
    </>}
    <label>
      {l("COMPANY")}
      <select className="control-input" value={draft.companyId} disabled={!options}
        onChange={(event) => onChange({ companyId: event.target.value, storeId: "" })}>
        <option value="">{t("allCompanies")}</option>
        {options?.companies.map((company) => <option key={company.id} value={company.id}>{company.name}</option>)}
      </select>
    </label>
    <label>
      {l("STORE")}
      <select className="control-input" value={draft.storeId} disabled={!stores?.length}
        onChange={(event) => onChange({ storeId: event.target.value })}>
        <option value="">{l("allStores")}</option>
        {stores?.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}
      </select>
    </label>
    <ProvinceFilter value={draft.province} onChange={(province) => onChange({ province })} />
    {grouping && <label>
      {l("groupBy")}
      <select className="control-input" value={draft.groupBy}
        onChange={(event) => onChange({ groupBy: event.target.value as StatisticsGrouping })}>
        {groupings.map((group) => <option key={group} value={group}>{l(group)}</option>)}
      </select>
    </label>}
  </>;
}

export function ProvinceFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const l = useStatisticsLabels();
  return <label>
    {l("PROVINCE")}
    <select className="control-input" value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="">{l("allProvinces")}</option>
      {[...provinces].map(([code, name]) => <option key={code} value={code}>{name}</option>)}
      <option value="UNKNOWN">{l("unknownProvince")}</option>
    </select>
  </label>;
}
