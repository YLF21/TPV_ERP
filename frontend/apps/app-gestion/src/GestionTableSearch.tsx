import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { GestionSearchFrame } from "../../../packages/app-common/src/components/GestionSearchFrame";
import { ErpListFilterPanel } from "../../../packages/app-common/src/components/ErpListFilterPanel";

/** Search for complete in-memory lists. Paged screens keep their server filters. */
export function GestionTableSearch({ value, onChange, t }: {
  value: string;
  onChange: (value: string) => void;
  t: (key: string) => string;
}) {
  return <div className="gestion-table-search-host">
    <ErpListFilterPanel className="gestion-table-search" translate={t} filters={null} search={<label><span>{t("party.searchLabel")}</span>
      <GestionSearchFrame><input className="erp-search-input" type="search" value={value} placeholder={t("party.searchLabel")} onChange={event => onChange(event.target.value)} /></GestionSearchFrame>
    </label>} />
    <ErpFilterChips translate={t} chips={[
      { key: "search", label: t("party.searchLabel"), value, onRemove: () => onChange("") }
    ]} onClear={() => onChange("")} />
  </div>;
}

export function matchesGestionTableSearch(query: string, values: readonly unknown[]) {
  const normalized = query.trim().toLocaleLowerCase();
  return !normalized || values.some(value => value != null && String(value).toLocaleLowerCase().includes(normalized));
}
