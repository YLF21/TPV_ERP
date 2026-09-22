import { useI18n } from "../../i18n";
import { SPANISH_PROVINCES, provinceSelection } from "./spanish-provinces.mjs";

const labels = {
  choose: ["Selecciona provincia", "Select a province", "选择省份"],
  historical: ["valor actual", "current value", "当前值"],
} as const;

export function ProvinceSelect({ value, onChange, disabled = false }: {
  value: string; onChange: (value: string) => void; disabled?: boolean;
}) {
  const { t, language } = useI18n();
  const index = language === "zh" ? 2 : language === "en" ? 1 : 0;
  const selected = provinceSelection(value);
  return <label>
    {t("province")}
    <select className="control-input" aria-label={t("province")} value={selected.value} onChange={event => onChange(event.target.value)} disabled={disabled} required>
      <option value="">{labels.choose[index]}</option>
      {selected.historicalValue !== null && <option value={selected.historicalValue}>{selected.historicalValue} ({labels.historical[index]})</option>}
      {SPANISH_PROVINCES.map(province => <option key={province.code} value={province.name}>{province.name}</option>)}
    </select>
  </label>;
}
