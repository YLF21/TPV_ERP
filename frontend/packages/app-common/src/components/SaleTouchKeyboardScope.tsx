import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import { TouchAlphaKeyboard } from "./TouchAlphaKeyboard";
import { TouchNumericKeypad } from "./TouchNumericKeypad";
import { TouchKeyboardToggle } from "./TouchKeyboardToggle";
import { TouchKeyboardClearButton } from "./TouchKeyboardClearButton";
import "./SaleTouchKeyboardScope.css";

type Field = HTMLInputElement | HTMLTextAreaElement;
type Target = { field: Field; dialog: HTMLElement; numeric: boolean; decimal: boolean; negative: boolean };
const dialogSelector = '[role="dialog"], .sale-action-dialog, .filter-dialog';
const textTypes = new Set(["text", "search", "tel", "email", "url", "password", "number"]);

function targetFor(element: EventTarget | null): Target | null {
  if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement)) return null;
  if (element instanceof HTMLInputElement && !textTypes.has(element.type)) return null;
  if (element.readOnly || element.matches(":disabled") || element.closest('[data-touch-keyboard="off"], [inert], [hidden], [aria-hidden="true"]')) return null;
  const dialog = element.closest<HTMLElement>(dialogSelector);
  if (!dialog) return null; // Never install a permanent keypad on the sales workspace/scanner.
  // These dialogs already explicitly bind their keyboard to their input(s).
  const existing = Array.from(dialog.querySelectorAll(".touch-alpha-keyboard, .touch-numeric-keypad"))
    .some((keyboard) => !keyboard.closest(".sale-touch-field-keyboard") && keyboard.closest(dialogSelector) === dialog);
  if (existing) return null;
  const numeric = element instanceof HTMLInputElement &&
    (element.type === "number" || element.inputMode === "numeric" || element.inputMode === "decimal");
  const decimal = numeric && (element.inputMode === "decimal" ||
    (element instanceof HTMLInputElement && element.type === "number" && element.step !== "1" && element.step !== ""));
  const negative = numeric && element instanceof HTMLInputElement && (decimal || element.type === "number") &&
    (element.min === "" || Number(element.min) < 0);
  return { field: element, dialog, numeric, decimal, negative };
}

/** Use the native input event so existing React onChange handlers/validation remain authoritative.
 * No React private internals, submit events, API calls, or credential copies are used. */
export function writeTouchField(field: Field, value: string) {
  if (!field.isConnected || field.readOnly || field.matches(":disabled")) return;
  const prototype = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
  setter?.call(field, value);
  field.dispatchEvent(new Event("input", { bubbles: true }));
}

/** Opt-in boundary for sale dialogs, including React portals and nested dialogs.
 * Legacy forms keep their normal controls, events, permission checks and submit buttons. */
export function SaleTouchKeyboardScope({ locale, interfaceMode, children }: {
  locale: LocaleCode;
  interfaceMode: SaleInterfaceMode;
  children: ReactNode;
}) {
  const t = createTranslator(locale);
  const rootRef = useRef<HTMLDivElement>(null);
  const activeRef = useRef<Target | null>(null);
  const inputRef = useRef<Field | null>(null);
  const numericDraft = useRef<{ field: Field; value: string } | null>(null);
  const [active, setActive] = useState<Target | null>(null);
  const [keyboardOpen, setKeyboardOpen] = useState(true);
  const keyboardId = useId();
  const [, refresh] = useState(0);
  const enabled = interfaceMode === "TOUCH";

  function bind(next: Target | null, open: boolean) {
    if (activeRef.current?.field !== next?.field) numericDraft.current = null;
    activeRef.current = next;
    inputRef.current = next?.field ?? null;
    setActive((current) => current?.field === next?.field && current?.dialog === next?.dialog ? current : next);
    setKeyboardOpen(open);
  }

  function activate(element: EventTarget | null) {
    if (!enabled) return;
    if (element instanceof Element && element.closest(".sale-touch-keyboard-scope") &&
        element.closest(".sale-touch-keyboard-scope") !== rootRef.current) return;
    if (element instanceof Element && element.closest(".sale-touch-field-keyboard")) return;
    // Keep the layout still between pointerdown and click on Save/Cancel or another action.
    if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) &&
        element instanceof Element && element.closest(dialogSelector) === activeRef.current?.dialog) return;
    const next = targetFor(element);
    if (activeRef.current?.field === next?.field) {
      if (next) setKeyboardOpen(true);
      return;
    }
    // An excluded field can collapse the keys without removing the launch button.
    if (!next) { setKeyboardOpen(false); return; }
    bind(next, true);
  }

  function closeKeyboard() {
    activeRef.current = null;
    inputRef.current = null;
    numericDraft.current = null;
    setActive(null);
  }

  useEffect(() => {
    if (!enabled) { closeKeyboard(); return; }
    // Discover a window even before an editable field receives focus. Portals are outside
    // the DOM boundary; other keyboard scopes keep ownership of their own descendants.
    const synchronize = () => {
      const dialogs = Array.from(document.querySelectorAll<HTMLElement>(dialogSelector)).filter((dialog) =>
        (dialog.closest(".sale-touch-keyboard-scope") === rootRef.current || !dialog.closest(".sale-touch-keyboard-scope")) &&
        !dialog.closest('[hidden], [inert], [aria-hidden="true"]') &&
        dialog.checkVisibility?.() !== false &&
        getComputedStyle(dialog).display !== "none" && getComputedStyle(dialog).visibility !== "hidden");
      const dialog = dialogs.at(-1);
      const current = activeRef.current;
      if (dialog && current?.dialog === dialog && targetFor(current.field)) return;
      const fields = dialog ? Array.from(dialog.querySelectorAll<Field>("input, textarea"))
        .filter((field) => field.closest(dialogSelector) === dialog) : [];
      const focused = targetFor(document.activeElement);
      const next = focused?.dialog === dialog ? focused : fields.map(targetFor).find((target) => target !== null) ?? null;
      if (next) bind(next, next === focused);
      else if (current && current.dialog === dialog && current.field.isConnected &&
          !dialog?.querySelector(".touch-keyboard-panel")) {
        // During submission retain a disabled launcher; it must never edit a locked field.
        setKeyboardOpen(false);
        refresh((version) => version + 1);
      } else if (current) closeKeyboard();
    };
    synchronize();
    const observer = new MutationObserver(synchronize);
    observer.observe(document.body, { childList: true, subtree: true, attributes: true,
      attributeFilter: ["disabled", "readonly", "hidden", "inert", "aria-hidden"] });
    return () => observer.disconnect();
  }, [enabled]);

  useEffect(() => {
    if (!active || !enabled) return;
    const { dialog } = active;
    dialog.classList.add("sale-touch-keyboard-host");
    dialog.dataset.touchKeyboardKind = active.numeric ? "numeric" : "alpha";
    return () => {
      dialog.classList.remove("sale-touch-keyboard-host");
      delete dialog.dataset.touchKeyboardKind;
    };
  }, [active, enabled]);

  function change(value: string) {
    const target = activeRef.current;
    if (!target || !targetFor(target.field)) return;
    // Keep incomplete numbers only in the keyboard draft. Browsers otherwise erase a trailing
    // decimal point in type=number, turning the following digit into a different quantity.
    if (target.numeric && target.field instanceof HTMLInputElement && target.field.type === "number" &&
        (value === "-" || value.endsWith("."))) {
      numericDraft.current = { field: target.field, value };
      refresh((version) => version + 1);
      return;
    }
    numericDraft.current = null;
    writeTouchField(target.field, value);
    refresh((version) => version + 1);
  }

  return <div ref={rootRef} className="sale-touch-keyboard-scope" data-sale-touch={enabled || undefined}
    onFocusCapture={(event) => activate(event.target)}
    onPointerDownCapture={(event) => {
      if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) activate(event.target);
    }}
    onChangeCapture={() => {
      numericDraft.current = null;
      if (activeRef.current) refresh((version) => version + 1);
    }}>
    {children}
    {enabled && active && createPortal(<div className="sale-touch-field-keyboard" data-kind={active.numeric ? "numeric" : "alpha"}
      onPointerDown={(event) => event.preventDefault()}>
      <div className="sale-touch-field-keyboard-heading">
        {keyboardOpen && <strong>{active.field.getAttribute("aria-label") || active.field.labels?.[0]?.textContent?.trim() ||
          t(active.numeric ? "sale.touch.numericKeypad" : "sale.touch.keyboard.title")}</strong>}
        {keyboardOpen && <TouchKeyboardClearButton locale={locale} disabled={!targetFor(active.field)} onClick={() => change("")} />}
        <TouchKeyboardToggle expanded={keyboardOpen} controls={keyboardId}
          openLabel={t("sale.touch.keyboard.open")} closeLabel={t("sale.touch.keyboard.close")}
          disabled={!targetFor(active.field)}
          onClick={() => {
            if (!keyboardOpen) active.field.focus({ preventScroll: true });
            setKeyboardOpen(!keyboardOpen);
          }} />
      </div>
      <div id={keyboardId} hidden={!keyboardOpen}>{active.numeric
        ? <TouchNumericKeypad locale={locale} collapsible={false} hideClearButton key={active.field.name || active.field.id || "numeric"}
            value={numericDraft.current?.field === active.field ? numericDraft.current.value : active.field.value}
            allowDecimal={active.decimal} allowNegative={active.negative}
            maximumFractionDigits={Infinity} inputRef={inputRef} replaceOnFirstKey disabled={!keyboardOpen}
            ariaLabel={t("sale.touch.numericKeypad")} clearLabel={t("sale.touch.clearNumber")}
            backspaceLabel={t("sale.touch.backspace")} onChange={change} />
        : <TouchAlphaKeyboard locale={locale} collapsible={false} hideClearButton inputRef={inputRef} value={active.field.value} disabled={!keyboardOpen} onChange={change} />}</div>
    </div>, active.dialog)}
  </div>;
}
