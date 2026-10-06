import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import "./TouchKeyboardToggle.css";

export function TouchKeyboardClearButton({ locale, disabled = false, onClick }: {
  locale: LocaleCode;
  disabled?: boolean;
  onClick: () => void;
}) {
  const t = createTranslator(locale);
  return <button type="button" className="touch-keyboard-clear" disabled={disabled}
    onPointerDown={(event) => event.preventDefault()} onClick={onClick}
    onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") event.stopPropagation(); }}>
    {t("sale.touch.keyboard.clearAll")}
  </button>;
}
