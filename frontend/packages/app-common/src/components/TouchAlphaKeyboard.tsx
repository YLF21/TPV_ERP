import { useLayoutEffect, useRef, useState } from "react";
import { ArrowFatUp, Backspace } from "@phosphor-icons/react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import "./TouchAlphaKeyboard.css";

type TextControl = HTMLInputElement | HTMLTextAreaElement;
type EditKey = string | "BACKSPACE" | "CLEAR";

export function editTouchText(
  value: string,
  key: EditKey,
  selectionStart = value.length,
  selectionEnd = selectionStart,
  maxLength = Infinity,
) {
  const start = Math.max(0, Math.min(selectionStart, value.length));
  const end = Math.max(start, Math.min(selectionEnd, value.length));
  if (key === "CLEAR") return { value: "", cursor: 0 };
  if (key === "BACKSPACE") {
    const previous = start === end
      ? start - (Array.from(value.slice(0, start)).at(-1)?.length ?? 0)
      : start;
    return { value: value.slice(0, previous) + value.slice(end), cursor: previous };
  }
  const available = Math.max(0, maxLength - (value.length - (end - start)));
  const insertion = key.slice(0, available);
  return {
    value: value.slice(0, start) + insertion + value.slice(end),
    cursor: start + insertion.length,
  };
}

/** Controlled keyboard: it never stores or renders the entered value (including passwords). */
export function TouchAlphaKeyboard({
  locale,
  value,
  onChange,
  inputRef,
  maxLength,
  disabled = false,
}: {
  locale: LocaleCode;
  value: string;
  onChange: (value: string) => void;
  inputRef?: { readonly current: TextControl | null };
  maxLength?: number;
  disabled?: boolean;
}) {
  const t = createTranslator(locale);
  const [uppercase, setUppercase] = useState(true);
  const [extraSymbols, setExtraSymbols] = useState(false);
  const pendingSelection = useRef<{ input: TextControl; cursor: number } | null>(null);

  useLayoutEffect(() => {
    const pending = pendingSelection.current;
    if (!pending) return;
    pendingSelection.current = null;
    if (!pending.input.isConnected || pending.input.disabled) return;
    pending.input.focus({ preventScroll: true });
    // Native email inputs accept text but do not support selection ranges.
    if (pending.input.selectionStart !== null) {
      pending.input.setSelectionRange(pending.cursor, pending.cursor);
    }
  });

  function press(key: EditKey) {
    const input = inputRef?.current;
    if (disabled || input?.disabled || input?.readOnly) return;
    const limit = maxLength ?? (input && input.maxLength >= 0 ? input.maxLength : undefined);
    const currentValue = input?.value ?? value;
    const edited = editTouchText(
      currentValue,
      key,
      input?.selectionStart ?? currentValue.length,
      input?.selectionEnd ?? currentValue.length,
      limit,
    );
    if (input) {
      input.focus({ preventScroll: true });
      if (edited.value === currentValue) {
        if (input.selectionStart !== null) input.setSelectionRange(edited.cursor, edited.cursor);
        pendingSelection.current = null;
      } else {
        pendingSelection.current = { input, cursor: edited.cursor };
      }
    }
    onChange(edited.value);
  }

  const rows = extraSymbols
    ? ["-_/@#+!?()$%", "&*=:;'\"\\[]{}", "<>|~`^ÁÉÍÓÚÜ"]
    : ["QWERTYUIOP", "ASDFGHJKLÑ", "ZXCVBNM"];

  function characterKeys(row: string) {
    return Array.from(row).map((character) => {
      const label = uppercase ? character : character.toLocaleLowerCase(locale);
      return (
        <button
          type="button"
          key={character}
          disabled={disabled}
          onPointerDown={(event) => event.preventDefault()}
          onClick={() => press(label)}
        >{label}</button>
      );
    });
  }

  const caseButton = (
    <button
      key="case"
      type="button"
      className="touch-alpha-keyboard-case"
      aria-label={t("sale.touch.keyboard.case")}
      aria-pressed={uppercase}
      disabled={disabled}
      onPointerDown={(event) => event.preventDefault()}
      onClick={() => setUppercase((current) => !current)}
    ><ArrowFatUp size={22} aria-hidden="true" /> Aa</button>
  );
  const backspaceButton = (
    <button
      key="backspace"
      type="button"
      className="touch-alpha-keyboard-backspace"
      aria-label={t("sale.touch.keyboard.backspace")}
      disabled={disabled}
      onPointerDown={(event) => event.preventDefault()}
      onClick={() => press("BACKSPACE")}
    ><Backspace size={26} aria-hidden="true" /></button>
  );

  return (
    <div className={`touch-alpha-keyboard${extraSymbols ? " touch-alpha-keyboard-extra-symbols" : ""}`} role="group" aria-label={t("sale.touch.keyboard.title")}>
      <div className="touch-alpha-keyboard-text-pad">
        {rows.map((row, index) => (
          <div className={`touch-alpha-keyboard-row touch-alpha-keyboard-row-${index + 1}`} key={index}>
            {!extraSymbols && index === 2 && caseButton}
            {characterKeys(row)}
            {!extraSymbols && index === 2 && backspaceButton}
          </div>
        ))}
        <div className="touch-alpha-keyboard-row touch-alpha-keyboard-utilities">
          {extraSymbols && caseButton}
          <button type="button" aria-label={t("sale.touch.keyboard.symbols")} aria-pressed={extraSymbols} disabled={disabled} onPointerDown={(event) => event.preventDefault()} onClick={() => setExtraSymbols((current) => !current)}>#+=</button>
          <button type="button" className="space" disabled={disabled} onPointerDown={(event) => event.preventDefault()} onClick={() => press(" ")}>
            {t("sale.touch.keyboard.space")}
          </button>
          <button type="button" className="touch-alpha-keyboard-period" disabled={disabled} onPointerDown={(event) => event.preventDefault()} onClick={() => press(".")}>.</button>
          <button type="button" className="touch-alpha-keyboard-clear" disabled={disabled} onPointerDown={(event) => event.preventDefault()} onClick={() => press("CLEAR")}>
            {t("sale.touch.keyboard.clear")}
          </button>
          {extraSymbols && backspaceButton}
        </div>
      </div>
      <div className="touch-alpha-keyboard-number-pad">
        {Array.from("7894561230,").map((character) => (
          <button
            type="button"
            key={character}
            className={character === "0" ? "touch-alpha-keyboard-zero" : undefined}
            disabled={disabled}
            onPointerDown={(event) => event.preventDefault()}
            onClick={() => press(character)}
          >{character}</button>
        ))}
      </div>
    </div>
  );
}
