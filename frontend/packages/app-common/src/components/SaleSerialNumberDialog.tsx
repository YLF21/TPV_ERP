import { useEffect, useMemo, useRef, useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = {
  locale: LocaleCode;
  productName: string;
  quantity: number;
  initialSerialNumbers: string[];
  onCancel: () => void;
  onConfirm: (serialNumbers: string[]) => void;
};

export function SaleSerialNumberDialog({
  locale,
  productName,
  quantity,
  initialSerialNumbers,
  onCancel,
  onConfirm,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
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

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-serial-number-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-serial-number-title"
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            onCancel();
          } else if (event.key === "Enter" && valid) {
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
                value={value}
                onChange={(event) => {
                  const nextValue = event.currentTarget.value;
                  setValues((current) => current.map(
                    (candidate, candidateIndex) => candidateIndex === index
                    ? nextValue
                    : candidate,
                  ));
                }}
              />
            </label>
          ))}
        </div>
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
