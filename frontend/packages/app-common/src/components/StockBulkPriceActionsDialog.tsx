import { Plus, Trash } from "@phosphor-icons/react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { ErpSelect } from "./ErpSelect";
import { WindowCloseButton } from "./WindowCloseButton";
import {
  newStockBulkPriceAction,
  stockBulkPriceActionTypes,
  stockBulkRulePriceFields,
  stockBulkRulePriceUseModes,
  validateStockBulkPriceActions,
  type StockBulkPriceAction,
  type StockBulkPriceActionChanges,
  type StockBulkPriceField
} from "./stockBulkPriceActions";

type Props = {
  locale: LocaleCode;
  token: string;
  productIds: string[];
  defaultField: StockBulkPriceField;
  onApplied: (changes: StockBulkPriceActionChanges) => void;
  onClose: () => void;
};

export function StockBulkPriceActionsDialog({ locale, token, productIds, defaultField, onApplied, onClose }: Props) {
  const t = createTranslator(locale);
  const [actions, setActions] = useState<StockBulkPriceAction[]>(() => [newStockBulkPriceAction(defaultField)]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const requestRef = useRef<AbortController | null>(null);
  useEffect(() => () => requestRef.current?.abort(), []);

  function updateAction(index: number, action: StockBulkPriceAction) {
    setActions(current => current.map((item, position) => position === index ? action : item));
    setMessage("");
  }

  async function apply(event: FormEvent) {
    event.preventDefault();
    if (requestRef.current || !token || productIds.length === 0) return;
    const invalid = validateStockBulkPriceActions(actions);
    if (invalid) {
      setMessage(t(invalid));
      return;
    }
    const request = new AbortController();
    requestRef.current = request;
    setBusy(true);
    setMessage("");
    try {
      const changes = await apiRequest<StockBulkPriceActionChanges>("/product-price-rules/preview-actions", {
        method: "POST",
        token,
        signal: request.signal,
        body: { productIds, actions }
      });
      if (request.signal.aborted) return;
      if (changes.products.length === 0) {
        setMessage(t("stock.bulkEdit.noChanges"));
        return;
      }
      onApplied(changes);
    } catch (error) {
      if (!request.signal.aborted) {
        setMessage(error instanceof Error ? error.message : t("stock.bulkEdit.rules.applyError"));
      }
    } finally {
      requestRef.current = null;
      if (!request.signal.aborted) setBusy(false);
    }
  }

  return (
    <div className="filter-overlay" role="presentation">
      <form className="filter-dialog bulk-compact-dialog stock-bulk-price-actions-dialog" role="dialog"
        aria-labelledby="stock-bulk-price-actions-title" aria-modal="true" onSubmit={event => void apply(event)}>
        <header className="filter-header">
          <div>
            <h2 id="stock-bulk-price-actions-title">{t("stock.bulkEdit.priceActions")}</h2>
            <p>{t("stock.bulkEdit.decimal.selected").replace("{count}", String(productIds.length))}</p>
          </div>
          <WindowCloseButton type="button" aria-label={t("common.close")} onClick={onClose} />
        </header>
        <div className="stock-bulk-rule-section-title">
          <strong>{t("stock.bulkEdit.rules.actions")}</strong>
          <button type="button" className="stock-bulk-price-action-button" disabled={busy || actions.length >= 100}
            onClick={() => { setActions(current => [...current, newStockBulkPriceAction(defaultField)]); setMessage(""); }}>
            <Plus size={15} aria-hidden="true" />
            {t("stock.bulkEdit.rules.addAction")}
          </button>
        </div>
        {actions.map((action, index) => (
          <div className="stock-bulk-rule-line" key={index}>
            <label className="stock-bulk-filter-field">
              <span>{t("stock.bulkEdit.rules.actionType")}</span>
              <ErpSelect aria-label={t("stock.bulkEdit.rules.actionType")} value={action.type} disabled={busy}
                options={stockBulkPriceActionTypes.map(value => ({ value, label: t(`stock.bulkEdit.rules.action.${value}`) }))}
                onChange={value => updateAction(index, newStockBulkPriceAction(defaultField, value as StockBulkPriceAction["type"]))} />
            </label>
            {"field" in action && (
              <label className="stock-bulk-filter-field">
                <span>{t("stock.bulkEdit.rules.field")}</span>
                <ErpSelect aria-label={t("stock.bulkEdit.rules.field")} value={action.field} disabled={busy}
                  options={stockBulkRulePriceFields.map(value => ({ value, label: t(`stock.bulkEdit.rules.priceField.${value}`) }))}
                  onChange={value => updateAction(index, { ...action, field: value as StockBulkPriceField })} />
              </label>
            )}
            {action.type === "FIXED_PRICE" && (
              <label className="stock-bulk-filter-field">
                <span>{t("stock.bulkEdit.rules.action.FIXED_PRICE")}</span>
                <input autoFocus={index === 0} type="number" min="0" step="0.01" value={action.value} disabled={busy}
                  onChange={event => updateAction(index, { ...action, value: event.target.value })} />
              </label>
            )}
            {action.type === "INVERSE_MARGIN" && (
              <>
                <label className="stock-bulk-filter-field">
                  <span>{t("stock.column.purchasePrice")}</span>
                  <ErpSelect aria-label={t("stock.column.purchasePrice")} value={action.costBasis} disabled={busy}
                    options={["GROSS", "NET"].map(value => ({ value, label: t(`stock.bulkEdit.rules.costBasis.${value}`) }))}
                    onChange={value => updateAction(index, { ...action, costBasis: value as typeof action.costBasis })} />
                </label>
                <label className="stock-bulk-filter-field">
                  <span>{t("stock.bulkEdit.rules.action.INVERSE_MARGIN")} %</span>
                  <input type="number" min="0" max="99.99" step="0.01" value={action.marginPercent} disabled={busy}
                    onChange={event => updateAction(index, { ...action, marginPercent: event.target.value })} />
                </label>
              </>
            )}
            {action.type === "DECIMAL_ENDING" && (
              <label className="stock-bulk-filter-field">
                <span>{t("stock.bulkEdit.decimal.ending")}</span>
                <input type="number" min="0" max="99" step="1" value={action.cents} disabled={busy}
                  onChange={event => updateAction(index, { ...action, cents: event.target.value === "" ? "" : event.target.valueAsNumber })} />
              </label>
            )}
            {action.type === "PRICE_USE_MODE" && (
              <label className="stock-bulk-filter-field">
                <span>{t("product.field.usePrice")}</span>
                <ErpSelect aria-label={t("product.field.usePrice")} value={action.value} disabled={busy}
                  options={stockBulkRulePriceUseModes.map(value => ({ value, label: t(`stock.bulkEdit.rules.priceUse.${value}`) }))}
                  onChange={value => updateAction(index, { ...action, value: value as typeof action.value })} />
              </label>
            )}
            {action.type === "OFFER" && (
              <>
                <label className="stock-bulk-rule-checkbox">
                  <input type="checkbox" checked={action.active} disabled={busy}
                    onChange={event => updateAction(index, {
                      ...action, active: event.target.checked, discountPercent: event.target.checked ? action.discountPercent : null
                    })} />
                  <span>{t("stock.bulkEdit.rules.offerActive")}</span>
                </label>
                <label className="stock-bulk-filter-field">
                  <span>{t("stock.bulkEdit.rules.discountPercent")}</span>
                  <input type="number" min="0" max="100" step="0.01" value={action.discountPercent ?? ""} disabled={busy || !action.active}
                    onChange={event => updateAction(index, { ...action, discountPercent: event.target.value || null })} />
                </label>
                <label className="stock-bulk-filter-field">
                  <span>{t("stock.bulkEdit.rules.offerFrom")}</span>
                  <input type="date" value={action.from ?? ""} disabled={busy}
                    onChange={event => updateAction(index, { ...action, from: event.target.value || null })} />
                </label>
                <label className="stock-bulk-filter-field">
                  <span>{t("stock.bulkEdit.rules.offerUntil")}</span>
                  <input type="date" value={action.until ?? ""} disabled={busy}
                    onChange={event => updateAction(index, { ...action, until: event.target.value || null })} />
                </label>
              </>
            )}
            {actions.length > 1 && (
              <button type="button" className="stock-bulk-price-action-button" disabled={busy}
                onClick={() => { setActions(current => current.filter((_, position) => position !== index)); setMessage(""); }}>
                <Trash size={15} aria-hidden="true" />
                {t("stock.bulkEdit.rules.remove")}
              </button>
            )}
          </div>
        ))}
        {productIds.length === 0 && <p role="status">{t("stock.bulkEdit.rules.noSelectedProducts")}</p>}
        {message && <p className="stock-bulk-dialog-error" role="alert">{message}</p>}
        <footer className="filter-actions erp-dialog-actions-row">
          <button type="button" className="secondary" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="erp-dialog-action-confirm" disabled={busy || !token || productIds.length === 0}>
            {t("stock.filter.apply")}
          </button>
        </footer>
      </form>
    </div>
  );
}
