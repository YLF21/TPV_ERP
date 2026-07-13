import { useEffect, useMemo, useState } from "react";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { enterNavigationIntent } from "./keyboardNavigation";

type StockBulkDecimalDialogProps = {
  open: boolean;
  locale: LocaleCode;
  fieldLabel: string;
  selectedCount: number;
  onApply: (ending: number) => void;
  onClose: () => void;
};

export function normalizeStockBulkDecimalInput(value: string) {
  const normalized = value.trim().replace(".", ",");
  const cents = normalized.startsWith("0,") ? normalized.slice(2) : normalized;
  return /^\d{2}$/.test(cents) ? cents : null;
}

export function StockBulkDecimalDialog({
  open,
  locale,
  fieldLabel,
  selectedCount,
  onApply,
  onClose
}: StockBulkDecimalDialogProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [value, setValue] = useState("");

  useEffect(() => {
    if (open) setValue("");
  }, [open]);

  if (!open) return null;
  const cents = normalizeStockBulkDecimalInput(value);

  return (
    <div className="filter-overlay" role="presentation">
      <section aria-labelledby="stock-bulk-decimal-title" aria-modal="true" className="filter-dialog stock-bulk-decimal-dialog" role="dialog">
        <header className="filter-header">
          <div>
            <h2 id="stock-bulk-decimal-title">{t("stock.bulkEdit.decimalAdjust")}</h2>
            <p>{fieldLabel} · {t("stock.bulkEdit.decimal.selected").replace("{count}", String(selectedCount))}</p>
          </div>
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </header>
        <label className="stock-bulk-decimal-field">
          <span>{t("stock.bulkEdit.decimal.ending")}</span>
          <span className="stock-bulk-decimal-input">
            <strong>0,</strong>
            <input
              autoFocus
              aria-invalid={!cents}
              inputMode="numeric"
              maxLength={2}
              pattern="[0-9]{2}"
              value={value}
              onChange={(event) => setValue(event.target.value.replace(/\D/g, "").slice(0, 2))}
              onKeyDown={(event) => {
                const intent = enterNavigationIntent(event.key, {
                  shiftKey: event.shiftKey,
                  ctrlKey: event.ctrlKey,
                  altKey: event.altKey,
                  metaKey: event.metaKey,
                  isComposing: event.nativeEvent.isComposing
                });
                if (intent === "next" && cents && selectedCount > 0) {
                  event.preventDefault();
                  onApply(Number(cents));
                }
              }}
            />
          </span>
          <small>{t("stock.bulkEdit.decimal.help")}</small>
        </label>
        {!cents && <p className="stock-bulk-dialog-error" role="alert">{t("stock.bulkEdit.decimal.invalid")}</p>}
        <footer className="filter-actions">
          <button type="button" className="secondary" onClick={onClose}>{t("common.cancel")}</button>
          <button type="button" disabled={!cents || selectedCount === 0} onClick={() => onApply(Number(cents))}>{t("stock.filter.apply")}</button>
        </footer>
      </section>
    </div>
  );
}
