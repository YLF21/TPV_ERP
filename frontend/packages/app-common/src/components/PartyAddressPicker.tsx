import { useEffect, useLayoutEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import type { LocaleCode } from "../types";
import "./PartyAddressPicker.css";

// Same INE display names used by the SaaS province catalogue. Existing values are
// deliberately left untouched until the user chooses a result.
const PROVINCES = [
  "A Coruña", "Albacete", "Alicante/Alacant", "Almería", "Araba/Álava", "Asturias", "Ávila", "Badajoz",
  "Barcelona", "Bizkaia", "Burgos", "Cáceres", "Cádiz", "Cantabria", "Castellón/Castelló", "Ceuta",
  "Ciudad Real", "Córdoba", "Cuenca", "Gipuzkoa", "Girona", "Granada", "Guadalajara", "Huelva",
  "Huesca", "Illes Balears", "Jaén", "La Rioja", "Las Palmas", "León", "Lleida", "Lugo", "Madrid",
  "Málaga", "Melilla", "Murcia", "Navarra", "Ourense", "Palencia", "Pontevedra", "Salamanca",
  "Santa Cruz de Tenerife", "Segovia", "Sevilla", "Soria", "Tarragona", "Teruel", "Toledo",
  "Valencia/València", "Valladolid", "Zamora", "Zaragoza",
] as const;

const PROVINCE_ALIASES: Record<string, string> = {
  "A Coruña": "La Coruña Coruña", "Alicante/Alacant": "Alicante Alacant", "Araba/Álava": "Alava Araba",
  "Bizkaia": "Vizcaya", "Castellón/Castelló": "Castellon Castello", "Girona": "Gerona",
  "Gipuzkoa": "Guipuzcoa", "Illes Balears": "Islas Baleares", "La Rioja": "Rioja",
  "Las Palmas": "Palmas, Las", "Lleida": "Lerida", "Ourense": "Orense", "Valencia/València": "Valencia Valencia",
};

const copy = {
  es: { search: "Buscar", custom: "Usar", clear: "Sin provincia", empty: "Sin coincidencias" },
  en: { search: "Search", custom: "Use", clear: "No province", empty: "No matches" },
  zh: { search: "搜索", custom: "使用", clear: "无省份", empty: "无匹配结果" },
} as const;

type Option = { value: string; label: string; search: string };

function searchKey(value: string) {
  return value.normalize("NFD").replace(/\p{M}/gu, "").toLocaleLowerCase().trim();
}

// ISO 3166-1 alpha-2 codes. This excludes CLDR region aliases and groupings
// (for example UK, EU, IC and SU) that Intl.DisplayNames also recognizes.
const ISO2_CODES = (
  "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ " +
  "CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR " +
  "GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM " +
  "JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP " +
  "MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY " +
  "QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN " +
  "TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW"
).split(" ");

function countryOptions(locale: string): Option[] {
  const names = new Intl.DisplayNames([locale], { type: "region" });
  return ISO2_CODES.map((code) => ({ value: code, label: `${names.of(code) ?? code} (${code})`, search: code }))
    .sort((a, b) => a.value === "ES" ? -1 : b.value === "ES" ? 1 : a.label.localeCompare(b.label, locale));
}

type Props = {
  kind: "province" | "country";
  value: string;
  label: string;
  locale: LocaleCode;
  country?: string;
  invalid?: boolean;
  onChange: (value: string) => void;
};

export function PartyAddressPicker({ kind, value, label, locale, country, invalid = false, onChange }: Props) {
  const words = copy[locale];
  const listId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState<string | null>(null);
  const [active, setActive] = useState(0);
  const options = useMemo<Option[]>(() => {
    const catalogue = kind === "country" ? countryOptions(locale) : [
      { value: "", label: words.clear, search: words.clear },
      ...(country?.trim().toUpperCase() === "ES" ? PROVINCES.map((name) => ({
        value: name, label: name, search: PROVINCE_ALIASES[name] ?? "",
      })) : []),
    ];
    return value && !catalogue.some(option => option.value === value)
      ? [{ value, label: value, search: value }, ...catalogue] : catalogue;
  }, [kind, locale, country, value, words.clear]);
  const selected = options.find((option) => option.value === value);
  const display = value === "" ? "" : selected?.label ?? value;
  const filtered = query === null || query.trim() === "" ? options : options.filter((option) =>
    searchKey(`${option.label} ${option.search}`).includes(searchKey(query)));
  const custom = query?.trim() ?? "";
  const canUseCustom = custom !== "" && kind === "province"
    && !options.some((option) => searchKey(option.label) === searchKey(custom) || option.value.toLowerCase() === custom.toLowerCase());
  const visibleOptions = canUseCustom
    ? [...filtered, { value: custom, label: `${words.custom} “${custom}”`, search: "" }]
    : filtered;

  function openOptions() {
    setQuery("");
    setActive(Math.max(0, options.findIndex(option => option.value === value)));
    setOpen(true);
  }

  function choose(option: Option) {
    onChange(option.value);
    inputRef.current?.focus();
    setQuery(null);
    setOpen(false);
  }

  useEffect(() => {
    if (!open) return;
    function closeOnOutside(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target) && !listRef.current?.contains(event.target)) {
        setOpen(false);
        setQuery(null);
      }
    }
    document.addEventListener("pointerdown", closeOnOutside, true);
    return () => document.removeEventListener("pointerdown", closeOnOutside, true);
  }, [open]);

  useLayoutEffect(() => {
    if (!open) return;
    function position() {
      const input = rootRef.current?.getBoundingClientRect();
      const list = listRef.current;
      if (!input || !list) return;
      const below = window.innerHeight - input.bottom - 8;
      const above = input.top - 8;
      const useAbove = below < 180 && above > below;
      const width = Math.min(input.width, window.innerWidth - 16);
      list.style.left = `${Math.max(8, Math.min(input.left, window.innerWidth - width - 8))}px`;
      list.style.width = `${width}px`;
      list.style.maxHeight = `${Math.min(240, Math.max(80, useAbove ? above : below) - 4)}px`;
      list.style.top = useAbove ? "auto" : `${input.bottom + 3}px`;
      list.style.bottom = useAbove ? `${window.innerHeight - input.top + 3}px` : "auto";
    }
    position();
    window.addEventListener("resize", position);
    window.addEventListener("scroll", position, true);
    return () => { window.removeEventListener("resize", position); window.removeEventListener("scroll", position, true); };
  }, [open, visibleOptions.length]);

  useEffect(() => {
    if (!open) return;
    listRef.current?.querySelector<HTMLElement>(`[data-active="true"]`)?.scrollIntoView?.({ block: "nearest", behavior: "auto" });
  }, [open, active, visibleOptions.length]);

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape" && open) { event.preventDefault(); event.stopPropagation(); setOpen(false); setQuery(null); return; }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) { openOptions(); return; }
      setActive((index) => (index + (event.key === "ArrowDown" ? 1 : -1) + visibleOptions.length) % Math.max(visibleOptions.length, 1));
      return;
    }
    if (event.key === "Enter" && open) {
      event.preventDefault();
      if (visibleOptions[active]) choose(visibleOptions[active]);
    }
  }

  return <div className="party-address-picker" ref={rootRef} onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null) && !listRef.current?.contains(event.relatedTarget as Node | null)) {
      setOpen(false);
      setQuery(null);
    }
  }}>
    <input
      ref={inputRef}
      role="combobox"
      aria-label={label}
      aria-invalid={invalid}
      aria-expanded={open}
      aria-controls={listId}
      aria-activedescendant={open && visibleOptions[active] ? `${listId}-${active}` : undefined}
      aria-autocomplete="list"
      autoComplete="off"
      placeholder={`${words.search} ${label.toLocaleLowerCase(locale)}`}
      value={query ?? display}
      onFocus={openOptions}
      onChange={(event) => { setQuery(event.target.value); setActive(0); setOpen(true); }}
      onKeyDown={onKeyDown}
    />
    <button type="button" className="party-address-picker__trigger" aria-label={`${words.search} ${label}`} aria-expanded={open}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => {
        if (open) { setOpen(false); setQuery(null); }
        else { inputRef.current?.focus(); openOptions(); }
      }}>
      <span aria-hidden="true" />
    </button>
    {open && createPortal(<div className="party-address-picker__list" id={listId} ref={listRef} role="listbox" aria-label={label}>
      {visibleOptions.length ? visibleOptions.map((option, index) => <button
        key={`${option.value}-${index}`}
        id={`${listId}-${index}`}
        type="button"
        role="option"
        aria-selected={option.value === value}
        data-active={index === active ? "true" : undefined}
        onMouseDown={(event) => event.preventDefault()}
        tabIndex={-1}
        onPointerMove={() => setActive(index)}
        onClick={() => choose(option)}>{option.label}</button>) : <div className="party-address-picker__empty">{words.empty}</div>}
    </div>, document.body)}
  </div>;
}
