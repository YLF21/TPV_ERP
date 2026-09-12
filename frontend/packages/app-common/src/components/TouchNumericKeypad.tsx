import { useLayoutEffect, useRef } from "react";
import { Backspace } from "@phosphor-icons/react";
import { editTouchText } from "./TouchAlphaKeyboard";
import "./TouchNumericKeypad.css";

export type TouchNumericKey = "CLEAR" | "BACKSPACE" | "DECIMAL" | "SIGN" | `${number}`;

export function editTouchNumber(value: string, key: TouchNumericKey, start: number, end: number,
  allowDecimal: boolean, maximumFractionDigits: number, allowNegative: boolean) {
  const normalized = value.replace(",", ".");
  const cursor = Math.max(0, Math.min(start, value.length));
  if (key === "SIGN") {
    if (!allowNegative) return { value, cursor };
    const next = pressTouchNumericKey(normalized, key, allowDecimal, maximumFractionDigits, allowNegative);
    return { value: next, cursor: Math.max(0, cursor + (normalized.startsWith("-") ? -1 : 1)) };
  }
  if (key === "DECIMAL" && (!allowDecimal || maximumFractionDigits === 0)) return { value, cursor };
  const edited = editTouchText(normalized, key === "DECIMAL" ? "." : key, start, end);
  if (edited.value === "." || edited.value === "-.") {
    edited.value = edited.value === "." ? "0." : "-0.";
    edited.cursor += 1;
  }
  if (!/^-?\d*(?:\.\d*)?$/.test(edited.value) || (!allowNegative && edited.value.startsWith("-")) ||
    (!allowDecimal && edited.value.includes(".")) ||
    (edited.value.split(".")[1]?.length ?? 0) > maximumFractionDigits) return { value, cursor };
  return edited;
}

export function pressTouchNumericKey(
  value: string,
  key: TouchNumericKey,
  allowDecimal: boolean,
  maximumFractionDigits = 2,
  allowNegative = false,
) {
  if (key === "CLEAR") return "";
  if (key === "BACKSPACE") return value.slice(0, -1);
  value = value.replace(",", ".");
  if (key === "SIGN") return !allowNegative ? value : value.startsWith("-") ? value.slice(1) : `-${value}`;
  if (key === "DECIMAL") {
    if (!allowDecimal || maximumFractionDigits === 0 || value.includes(".")) return value;
    return value === "" ? "0." : value === "-" ? "-0." : `${value}.`;
  }
  if (!/^\d$/.test(key)) return value;
  const fraction = value.split(".")[1];
  if (fraction !== undefined && fraction.length >= maximumFractionDigits) return value;
  if (value === "0" && key !== "0") return key;
  if (value === "-0" && key !== "0") return `-${key}`;
  return `${value}${key}`;
}

export function TouchNumericKeypad({
  value,
  allowDecimal = false,
  allowNegative = false,
  maximumFractionDigits = 2,
  disabled = false,
  signLabel = "±",
  decimalLabel = ",",
  ariaLabel,
  clearLabel,
  backspaceLabel,
  onChange,
  inputRef,
  replaceOnFirstKey = false,
}: {
  value: string;
  allowDecimal?: boolean;
  allowNegative?: boolean;
  maximumFractionDigits?: number;
  disabled?: boolean;
  signLabel?: string;
  decimalLabel?: string;
  ariaLabel: string;
  clearLabel: string;
  backspaceLabel: string;
  onChange: (value: string) => void;
  inputRef?: { readonly current: HTMLInputElement | HTMLTextAreaElement | null };
  /** Number inputs cannot expose a selection range. Opt in when the dialog selects its value on opening. */
  replaceOnFirstKey?: boolean;
}) {
  const keys: { key: TouchNumericKey; area: string }[] = [
    { key: "7", area: "1 / 1" }, { key: "8", area: "1 / 2" }, { key: "9", area: "1 / 3" },
    { key: "BACKSPACE", area: allowNegative ? "1 / 4" : "1 / 4 / 3 / 5" },
    { key: "4", area: "2 / 1" }, { key: "5", area: "2 / 2" }, { key: "6", area: "2 / 3" },
    { key: "CLEAR", area: allowNegative ? "2 / 4" : "3 / 4 / 5 / 5" },
    { key: "1", area: "3 / 1" }, { key: "2", area: "3 / 2" }, { key: "3", area: "3 / 3" },
    { key: "0", area: "4 / 1 / 5 / 3" }, { key: "DECIMAL", area: "4 / 3" },
    ...(allowNegative ? [{ key: "SIGN" as const, area: "3 / 4 / 5 / 5" }] : []),
  ];
  const firstKey = useRef(true);
  const lastField = useRef(inputRef?.current);
  const selection = useRef<{ field: HTMLInputElement | HTMLTextAreaElement; cursor: number } | null>(null);
  useLayoutEffect(() => {
    const field = inputRef?.current;
    if (lastField.current !== field) { lastField.current = field; firstKey.current = true; }
    if (!field) return;
    const markEdited = () => { firstKey.current = false; };
    field.addEventListener("input", markEdited);
    return () => field.removeEventListener("input", markEdited);
  });
  useLayoutEffect(() => {
    const pending = selection.current;
    selection.current = null;
    if (!pending || !pending.field.isConnected || pending.field.matches(":disabled")) return;
    pending.field.focus({ preventScroll: true });
    if (pending.field.selectionStart !== null) pending.field.setSelectionRange(pending.cursor, pending.cursor);
  });

  function press(key: TouchNumericKey) {
    const field = inputRef?.current;
    if (disabled || field?.readOnly || field?.matches(":disabled")) return;
    if (lastField.current !== field) { lastField.current = field; firstKey.current = true; }
    if (!field) { onChange(pressTouchNumericKey(value, key, allowDecimal, maximumFractionDigits, allowNegative)); return; }
    // Native number inputs erase incomplete values such as "2.". The controlled draft retains them.
    const current = (field instanceof HTMLInputElement && field.type === "number" ? value : field.value).replace(",", ".");
    const replace = replaceOnFirstKey && firstKey.current && field.selectionStart === null && key !== "SIGN";
    const start = replace ? 0 : field.selectionStart ?? current.length;
    const end = replace ? current.length : field.selectionEnd ?? current.length;
    const edited = editTouchNumber(current, key, start, end, allowDecimal, maximumFractionDigits, allowNegative);
    if (field.maxLength >= 0 && edited.value.length > field.maxLength) return;
    firstKey.current = false;
    field.focus({ preventScroll: true });
    if (edited.value === current) {
      selection.current = null;
      if (field.selectionStart !== null) field.setSelectionRange(edited.cursor, edited.cursor);
    } else {
      selection.current = { field, cursor: edited.cursor };
    }
    onChange(edited.value);
  }

  return (
    <div className="touch-numeric-keypad touch-numeric-keypad-grid" role="group" aria-label={ariaLabel}>
      {keys.map(({ key, area }) => {
        const utility = key === "CLEAR" || key === "BACKSPACE" || key === "SIGN";
        const accessibleLabel = key === "CLEAR" ? clearLabel : key === "BACKSPACE" ? backspaceLabel
          : key === "DECIMAL" ? decimalLabel : key === "SIGN" ? signLabel : key;
        const label = key === "CLEAR" ? "C" : key === "SIGN" ? "±" : key === "DECIMAL" ? decimalLabel : key;
        return (
          <button
            type="button"
            className={utility ? "utility" : key === "DECIMAL" ? "decimal" : undefined}
            aria-label={accessibleLabel}
            key={key}
            data-numeric-key={key}
            style={{ gridArea: area }}
            disabled={disabled || (key === "DECIMAL" && (!allowDecimal || maximumFractionDigits === 0))}
            onPointerDown={(event) => event.preventDefault()}
            onClick={() => press(key)}
          >
            {key === "BACKSPACE" ? <Backspace aria-hidden="true" focusable="false" weight="bold" /> : label}
          </button>
        );
      })}
    </div>
  );
}
