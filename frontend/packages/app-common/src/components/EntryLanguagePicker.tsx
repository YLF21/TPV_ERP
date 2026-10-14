import { useEffect, useId, useRef, useState } from "react";
import languageIcon from "../assets/language.png";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { useOutsidePointerDown } from "./useOutsidePointerDown";

const options: Array<{ code: LocaleCode; label: string }> = [
  { code: "es", label: "Español" },
  { code: "en", label: "English" },
  { code: "zh", label: "中文" },
];

export function EntryLanguagePicker({ locale, onLocaleChange }: {
  locale: LocaleCode;
  onLocaleChange: (locale: LocaleCode) => void;
}) {
  const t = createTranslator(locale);
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const menuId = useId();

  useOutsidePointerDown(open, rootRef, () => setOpen(false));

  useEffect(() => {
    if (open) optionRefs.current[options.findIndex(option => option.code === locale)]?.focus();
  }, [open]);

  function choose(next: LocaleCode) {
    onLocaleChange(next);
    setOpen(false);
    triggerRef.current?.focus();
  }

  return <div ref={rootRef} style={{ display: "contents" }}
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <button ref={triggerRef} type="button" className="language-button"
      aria-label={t("login.language")} title={t("login.language")}
      aria-expanded={open} aria-haspopup="menu" aria-controls={open ? menuId : undefined}
      onClick={() => setOpen(value => !value)}
      onKeyDown={event => {
        if (event.key === "ArrowDown") { event.preventDefault(); setOpen(true); }
        if (event.key === "Escape") { event.preventDefault(); setOpen(false); }
      }}>
      <img alt="" src={languageIcon} />
    </button>
    {open && <section id={menuId} className="language-picker" role="menu"
      aria-label={t("login.language")}
      onKeyDown={event => {
        if (event.key === "Escape") {
          event.preventDefault(); setOpen(false); triggerRef.current?.focus();
        } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          event.preventDefault();
          const current = optionRefs.current.indexOf(document.activeElement as HTMLButtonElement);
          const direction = event.key === "ArrowDown" ? 1 : -1;
          optionRefs.current[(current + direction + options.length) % options.length]?.focus();
        } else if (event.key === "Home" || event.key === "End") {
          event.preventDefault();
          optionRefs.current[event.key === "Home" ? 0 : options.length - 1]?.focus();
        }
      }}>
      {options.map((option, index) => <button type="button" role="menuitemradio"
        aria-label={`${option.label} ${option.code.toUpperCase()}`}
        aria-checked={option.code === locale} className={option.code === locale ? "selected" : ""}
        style={option.code === locale ? { background: "#0065FE", color: "#fff" } : undefined}
        ref={node => { optionRefs.current[index] = node; }} key={option.code}
        onClick={() => choose(option.code)}>
        <span>{option.label}</span><strong>{option.code.toUpperCase()}</strong>
      </button>)}
    </section>}
  </div>;
}
