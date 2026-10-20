import { useEffect, useRef, useState } from "react";
import { ErpSelect } from "./ErpSelect";
import "./SerialBaudRateSelect.css";

const COMMON_BAUD_RATES = [300, 600, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200] as const;
const OPTIONS = COMMON_BAUD_RATES.map(rate => ({ value: String(rate), label: String(rate) }));

function positiveSafeInteger(text: string): number | null {
  if (!/^[0-9]+$/.test(text)) return null;
  const number = Number(text);
  return Number.isSafeInteger(number) && number > 0 ? number : null;
}

export function SerialBaudRateSelect({ label, value, onChange }: {
  label: string;
  value: number;
  onChange: (value: number) => void;
}) {
  const [text, setText] = useState(String(value));
  const lastNotifiedValue = useRef(value);

  useEffect(() => {
    setText(String(value));
    lastNotifiedValue.current = value;
  }, [value]);

  function updateText(nextText: string) {
    setText(nextText);
    const parsed = positiveSafeInteger(nextText);
    if (parsed !== null && parsed !== lastNotifiedValue.current) {
      lastNotifiedValue.current = parsed;
      onChange(parsed);
    }
  }

  return <div className="hardware-serial-baud-rate" onBlur={event => {
    if (event.currentTarget.contains(event.relatedTarget as Node | null)) return;
    if (positiveSafeInteger(text) === null) setText(String(value));
  }}>
    <ErpSelect aria-label={label} value={text} options={OPTIONS}
      onChange={updateText} editable={{ text, onChange: updateText, label }} />
  </div>;
}
