export type TouchNumericKey = "CLEAR" | "BACKSPACE" | "DECIMAL" | `${number}`;

export function pressTouchNumericKey(
  value: string,
  key: TouchNumericKey,
  allowDecimal: boolean,
  maximumFractionDigits = 2,
) {
  if (key === "CLEAR") return "";
  if (key === "BACKSPACE") return value.slice(0, -1);
  if (key === "DECIMAL") {
    if (!allowDecimal || value.includes(".")) return value;
    return value === "" ? "0." : `${value}.`;
  }
  if (!/^\d$/.test(key)) return value;
  const fraction = value.split(".")[1];
  if (fraction !== undefined && fraction.length >= maximumFractionDigits) return value;
  if (value === "0" && key !== "0") return key;
  return `${value}${key}`;
}

export function TouchNumericKeypad({
  value,
  allowDecimal = false,
  decimalLabel = ",",
  ariaLabel,
  clearLabel,
  backspaceLabel,
  onChange,
}: {
  value: string;
  allowDecimal?: boolean;
  decimalLabel?: string;
  ariaLabel: string;
  clearLabel: string;
  backspaceLabel: string;
  onChange: (value: string) => void;
}) {
  const keys: TouchNumericKey[] = ["7", "8", "9", "4", "5", "6", "1", "2", "3", "CLEAR", "0", "BACKSPACE"];

  return (
    <div className="touch-numeric-keypad" role="group" aria-label={ariaLabel}>
      {keys.map((key) => {
        const label = key === "CLEAR" ? "C" : key === "BACKSPACE" ? "⌫" : key;
        const accessibleLabel = key === "CLEAR" ? clearLabel : key === "BACKSPACE" ? backspaceLabel : key;
        return (
          <button
            type="button"
            className={key === "CLEAR" || key === "BACKSPACE" ? "utility" : undefined}
            aria-label={accessibleLabel}
            key={key}
            onClick={() => onChange(pressTouchNumericKey(value, key, allowDecimal))}
          >
            {label}
          </button>
        );
      })}
      {allowDecimal && (
        <button
          type="button"
          className="decimal"
          aria-label={decimalLabel}
          onClick={() => onChange(pressTouchNumericKey(value, "DECIMAL", true))}
        >
          {decimalLabel}
        </button>
      )}
    </div>
  );
}
