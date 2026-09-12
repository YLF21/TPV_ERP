import { useEffect, useMemo, useRef, useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import { TouchAlphaKeyboard } from "./TouchAlphaKeyboard";

type Props = {
  locale: LocaleCode;
  interfaceMode?: SaleInterfaceMode;
  productName: string;
  quantity: number;
  initialSerialNumbers: string[];
  onCancel: () => void;
  onConfirm: (serialNumbers: string[]) => void;
};

export function SaleSerialNumberDialog({
  locale,
  interfaceMode,
  productName,
  quantity,
  initialSerialNumbers,
  onCancel,
  onConfirm,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const activeInputRef = useRef<HTMLInputElement | null>(null);
  const activeIndexRef = useRef(0);
  const [activeIndex, setActiveIndex] = useState(0);
  const unitCount = Number.isInteger(Math.abs(quantity)) ? Math.abs(quantity) : 0;
  const [values, setValues] = useState(() => Array.from(
    { length: unitCount },
    (_, index) => initialSerialNumbers[index] ?? "",
  ));
  const [acknowledgeTrim, setAcknowledgeTrim] = useState(false);
  const hasTrimmedSerials = initialSerialNumbers.length > unitCount;
  const normalized = values.map((value) => value.trim().toLocaleUpperCase());
  const complete = unitCount > 0 && values.every((value) => value.trim().length > 0);
  const unique = new Set(normalized).size === normalized.length;
  const valid = complete && unique && (!hasTrimmedSerials || acknowledgeTrim);
  const validation = useMemo(() => {
    if (unitCount === 0) return t("sale.serialNumber.wholeUnits");
    if (!complete) return t("sale.serialNumber.complete");
    if (!unique) return t("sale.serialNumber.duplicate");
    return "";
  }, [complete, t, unique, unitCount]);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  function updateValue(index: number, value: string) {
    setValues((current) => current.map((candidate, candidateIndex) => candidateIndex === index ? value : candidate));
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className={`sale-action-dialog sale-serial-number-dialog${interfaceMode === "TOUCH" ? " sale-touch-keyboard-dialog" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-serial-number-title"
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            onCancel();
          } else if (event.key === "Enter" && valid && event.target instanceof HTMLInputElement) {
            event.preventDefault();
            onConfirm(values.map((value) => value.trim()));
          }
        }}
      >
        <header>
          <div>
            <h2 id="sale-serial-number-title">{t("sale.serialNumber.title")}</h2>
            <p>{productName}</p>
          </div>
          <button type="button" aria-label={t("common.close")} onClick={onCancel}>×</button>
        </header>
        <p>{t("sale.serialNumber.description")}</p>
        <div className="sale-serial-number-fields">
          {values.map((value, index) => (
            <label key={index}>
              <span>{t("sale.serialNumber.unit")} {index + 1}</span>
              <input
                autoFocus={index === 0}
                maxLength={128}
                autoComplete="off"
                inputMode={interfaceMode === "TOUCH" ? "none" : undefined}
                value={value}
                onFocus={(event) => {
                  activeInputRef.current = event.currentTarget;
                  activeIndexRef.current = index;
                  setActiveIndex(index);
                }}
                onChange={(event) => updateValue(index, event.currentTarget.value)}
              />
            </label>
          ))}
        </div>
        {interfaceMode === "TOUCH" && unitCount > 0 && (
          <TouchAlphaKeyboard locale={locale} value={values[activeIndex] ?? ""} onChange={(value) => updateValue(activeIndexRef.current, value)} inputRef={activeInputRef} maxLength={128} />
        )}
        {hasTrimmedSerials && (
          <label>
            <input
              type="checkbox"
              checked={acknowledgeTrim}
              onChange={(event) => setAcknowledgeTrim(event.currentTarget.checked)}
            />
            {t("sale.serialNumber.trimConfirm")}
          </label>
        )}
        {validation && <p className="sale-action-error" role="alert">{validation}</p>}
        <footer className="sale-action-buttons">
          <button type="button" onClick={onCancel}>{t("common.cancel")}</button>
          <button
            type="button"
            className="primary"
            disabled={!valid}
            onClick={() => onConfirm(values.map((value) => value.trim()))}
          >
            {t("common.accept")}
          </button>
        </footer>
      </section>
    </div>
  );
}
