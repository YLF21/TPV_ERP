import { useMemo } from "react";
import { useI18n, localeFor } from "../../i18n";

// Broad European scope: Council of Europe states plus Belarus, Russia,
// Kazakhstan, Vatican City and Kosovo. This is an address catalogue, not an EU list.
// Country codes: https://unstats.un.org/unsd/methodology/m49/overview
// Scope: https://www.coe.int/en/web/portal/members-states
// XK is a user-assigned code: https://op.europa.eu/en/web/eu-vocabularies/countries-and-territories
const COUNTRY_CODES = [
  "AD", "AL", "AM", "AT", "AZ", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE",
  "DK", "EE", "ES", "FI", "FR", "GB", "GE", "GR", "HR", "HU", "IE", "IS", "IT",
  "KZ", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT", "NL", "NO", "PL",
  "PT", "RO", "RS", "RU", "SE", "SI", "SK", "SM", "TR", "UA", "VA", "XK",
] as const;
const countryCodes = new Set<string>(COUNTRY_CODES);
const labels = {
  choose: ["Selecciona país", "Select a country", "选择国家"],
  historical: ["valor actual", "current value", "当前值"],
} as const;

export function EuropeanCountrySelect({ value, onChange, disabled = false }: {
  value: string; onChange: (value: string) => void; disabled?: boolean;
}) {
  const { t, language } = useI18n();
  const index = language === "zh" ? 2 : language === "en" ? 1 : 0;
  const { countries, names } = useMemo(() => {
    const locale = localeFor(language);
    const names = new Intl.DisplayNames([locale], { type: "region" });
    const countries = COUNTRY_CODES.map(code => ({ code, name: names.of(code) ?? code }))
      .sort((a, b) => a.code === "ES" ? -1 : b.code === "ES" ? 1 : a.name.localeCompare(b.name, locale));
    return { countries, names };
  }, [language]);
  const normalized = value.trim().toUpperCase();
  const known = countryCodes.has(normalized);
  const selected = known ? normalized : value;
  const historicalName = /^[A-Z]{2}$/.test(normalized) ? names.of(normalized) ?? value : value;
  return <label>{t("country")}
    <select className="control-input" aria-label={t("country")} value={selected} onChange={event => onChange(event.target.value)} disabled={disabled} required>
      {countries.map(country => <option key={country.code} value={country.code}>{country.name}</option>)}
      {!known && value !== "" && <option value={value}>{historicalName} ({labels.historical[index]})</option>}
      {value === "" && <option value="" disabled hidden>{labels.choose[index]}</option>}
    </select>
  </label>;
}
