import { useId, useState, type ReactNode } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { TouchKeyboardToggle } from "./TouchKeyboardToggle";
import { TouchKeyboardClearButton } from "./TouchKeyboardClearButton";
import "./TouchKeyboardPanel.css";

export function TouchKeyboardPanel({ locale, title, disabled = false, collapsible = true, hideClearButton = false, onClear, children }: {
  locale: LocaleCode;
  title: string;
  disabled?: boolean;
  collapsible?: boolean;
  hideClearButton?: boolean;
  onClear: () => void;
  children: (expanded: boolean) => ReactNode;
}) {
  const t = createTranslator(locale);
  const [open, setOpen] = useState(true);
  const id = useId();
  if (!collapsible) return <>
    {!hideClearButton && <div className="touch-keyboard-panel-heading" onPointerDown={(event) => event.preventDefault()}>
      <TouchKeyboardClearButton locale={locale} disabled={disabled} onClick={onClear} />
    </div>}
    {children(true)}
  </>;

  return <div className="touch-keyboard-panel">
    <div className="touch-keyboard-panel-heading" onPointerDown={(event) => event.preventDefault()}>
      {open && <strong>{title}</strong>}
      {open && <TouchKeyboardClearButton locale={locale} disabled={disabled} onClick={onClear} />}
      <TouchKeyboardToggle expanded={open} controls={id} disabled={disabled}
        openLabel={t("sale.touch.keyboard.open")} closeLabel={t("sale.touch.keyboard.close")}
        onClick={() => setOpen((current) => !current)} />
    </div>
    <div id={id} hidden={!open}>{children(open)}</div>
  </div>;
}
