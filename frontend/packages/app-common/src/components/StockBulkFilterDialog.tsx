import { X } from "@phosphor-icons/react";
import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { StockTopSalesFamilyNode } from "./StockScreen";
import {
  countActiveStockBulkFilters,
  emptyStockBulkFilterCriteria
} from "./stockBulkAdvanced";
import type { StockBulkFilterCriteria } from "./stockBulkAdvanced";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { ErpListSelect } from "./ErpListSelect";
import { changeStockBulkOptions, stockBulkOptionValues, type StockBulkOptionKey } from "./stockBulkAdvanced";

export type StockBulkFilterOption = {
  value: string;
  label: string;
};

type StockBulkFilterDialogProps = {
  open: boolean;
  locale: LocaleCode;
  value: StockBulkFilterCriteria;
  families: StockTopSalesFamilyNode[];
  suppliers: StockBulkFilterOption[];
  taxes: StockBulkFilterOption[];
  allowIndependentSubfamily?: boolean;
  autoApply?: boolean;
  onApply: (value: StockBulkFilterCriteria) => void;
  onClose: () => void;
};

export function validateStockBulkFilter(criteria: StockBulkFilterCriteria) {
  if (criteria.minimumPrice !== null && criteria.minimumPrice !== undefined
      && criteria.maximumPrice !== null && criteria.maximumPrice !== undefined
      && criteria.minimumPrice > criteria.maximumPrice) {
    return "stock.bulkEdit.filter.priceRangeError";
  }
  if (criteria.offerFrom && criteria.offerUntil && criteria.offerFrom > criteria.offerUntil) {
    return "stock.bulkEdit.filter.offerRangeError";
  }
  return null;
}

function nullableNumber(value: string) {
  const normalized = value.trim().replace(",", ".");
  if (!normalized) return null;
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

function optionValue(value: string) {
  return value || null;
}

export function StockBulkFilterDialog({
  open,
  locale,
  value,
  families,
  suppliers,
  taxes,
  allowIndependentSubfamily = false,
  autoApply = false,
  onApply,
  onClose
}: StockBulkFilterDialogProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [draft, setDraft] = useState<StockBulkFilterCriteria>(value);
  const dialogRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (open) setDraft({ ...value });
  }, [open]);

  if (!open) return null;

  const selectedFamilies = stockBulkOptionValues(draft, "familyId");
  const subfamilyOptions = families
    .filter(family => selectedFamilies.includes(family.id) || (!selectedFamilies.length && allowIndependentSubfamily))
    .flatMap(family => family.subfamilies.map(subfamily => ({
      value: subfamily.id,
      label: selectedFamilies.length === 1 ? subfamily.name : family.name + " / " + subfamily.name,
    })));
  const error = validateStockBulkFilter(draft);
  const activeCount = countActiveStockBulkFilters(draft);
  const update = (patch: Partial<StockBulkFilterCriteria>) => {
    const next = { ...draft, ...patch };
    setDraft(next);
    if (autoApply && !validateStockBulkFilter(next)) onApply(next);
  };
  const selectOptions = (key: StockBulkOptionKey, values: string[]) => {
    let next = changeStockBulkOptions(draft, key, values);
    if (key === "familyId") {
      const allowedSubfamilies = families.filter(family => values.includes(family.id) || (!values.length && allowIndependentSubfamily))
        .flatMap(family => family.subfamilies.map(subfamily => subfamily.id));
      next = changeStockBulkOptions(next, "subfamilyId",
        stockBulkOptionValues(next, "subfamilyId").filter(id => allowedSubfamilies.includes(id)));
    }
    update(next);
  };
  const controlSelector = ".stock-bulk-filter-grid input:not(:disabled), .stock-bulk-filter-grid .erp-select__trigger:not(:disabled)";
  const moveFromActiveControl = (intent: "next" | "previous") => {
    const current = document.activeElement;
    if (!(current instanceof HTMLElement)) return false;
    return focusRelativeEnterTarget(dialogRef.current, current, intent, controlSelector);
  };
  const handleEnter = (event: KeyboardEvent<HTMLElement>) => {
    const intent = enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey,
      ctrlKey: event.ctrlKey,
      altKey: event.altKey,
      metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing
    });
    if (!intent || !(event.target as HTMLElement).matches("input")) return;
    event.preventDefault();
    const moved = focusRelativeEnterTarget(
      dialogRef.current,
      event.target as HTMLElement,
      intent,
      controlSelector
    );
    if (!moved && intent === "next" && !error && !autoApply) onApply({ ...draft });
  };

  return (
    <div className="filter-overlay" role="presentation">
      <section
        ref={dialogRef}
        aria-labelledby="stock-bulk-filter-title"
        aria-modal="true"
        className="filter-dialog bulk-compact-dialog stock-bulk-filter-dialog"
        role="dialog"
        onKeyDown={handleEnter}
      >
        <header className="filter-header">
          <div>
            <h2 id="stock-bulk-filter-title">{t("stock.bulkEdit.filter.title")}</h2>
            <p>{t("stock.bulkEdit.filter.activeCount").replace("{count}", String(activeCount))}</p>
          </div>
          <WindowCloseButton type="button" aria-label={t("common.close")} onClick={onClose} ><X size={18} weight="bold" aria-hidden="true" /></WindowCloseButton>
        </header>

        <div className="stock-bulk-filter-grid">
          <div className="stock-bulk-filter-field">
            <span>{t("stock.bulkEdit.filter.productType")}</span>
            <ErpListSelect single={!autoApply}
              aria-label={t("stock.bulkEdit.filter.productType")}
              values={stockBulkOptionValues(draft, "productType")}
              options={[
                { value: "", label: t("stock.filter.all") },
                { value: "UNIT", label: t("product.type.unit") },
                { value: "WEIGHT", label: t("product.type.weight") },
                { value: "SERVICE", label: t("product.type.service") }
              ]}
              onChange={values => selectOptions("productType", values)}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.family")}</span>
            <ErpListSelect single={!autoApply}
              aria-label={t("stock.column.family")}
              values={stockBulkOptionValues(draft, "familyId")}
              options={[
                { value: "", label: t("stock.filter.all") },
                ...families.map((family) => ({ value: family.id, label: family.name }))
              ]}
              onChange={values => selectOptions("familyId", values)}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.subfamily")}</span>
            <ErpListSelect single={!autoApply}
              aria-label={t("stock.column.subfamily")}
              disabled={!selectedFamilies.length && !allowIndependentSubfamily}
              values={stockBulkOptionValues(draft, "subfamilyId")}
              options={[
                { value: "", label: t("stock.filter.all") },
                ...subfamilyOptions
              ]}
              onChange={values => selectOptions("subfamilyId", values)}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.supplier")}</span>
            <ErpListSelect single={!autoApply}
              aria-label={t("stock.column.supplier")}
              values={stockBulkOptionValues(draft, "supplierId")}
              options={[{ value: "", label: t("stock.filter.all") }, ...suppliers]}
              onChange={values => selectOptions("supplierId", values)}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.tax")}</span>
            <ErpListSelect single={!autoApply}
              aria-label={t("stock.column.tax")}
              values={stockBulkOptionValues(draft, "taxId")}
              options={[{ value: "", label: t("stock.filter.all") }, ...taxes]}
              onChange={values => selectOptions("taxId", values)}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("product.field.usePrice")}</span>
            <ErpListSelect single={!autoApply}
              aria-label={t("product.field.usePrice")}
              values={stockBulkOptionValues(draft, "priceUseMode")}
              options={[
                { value: "", label: t("stock.filter.all") },
                { value: "NORMAL", label: t("product.discount.salePrice") },
                { value: "MEMBER_PRICE", label: t("product.discount.memberPrice") },
                { value: "OFFER_PRICE", label: t("product.discount.offerPrice") },
                { value: "OFFER_DISCOUNT", label: t("product.discount.offerDiscount") }
              ]}
              onChange={values => selectOptions("priceUseMode", values)}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.bulkEdit.filter.offerActive")}</span>
            <ErpListSelect single={!autoApply}
              aria-label={t("stock.bulkEdit.filter.offerActive")}
              values={stockBulkOptionValues(draft, "offerActive")}
              options={[
                { value: "", label: t("stock.filter.all") },
                { value: "yes", label: t("common.yes") },
                { value: "no", label: t("common.no") }
              ]}
              onChange={values => selectOptions("offerActive", values)}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <label>
            <span>{t("stock.bulkEdit.filter.offerFrom")}</span>
            <input type="date" value={draft.offerFrom ?? ""} onChange={(event) => update({ offerFrom: optionValue(event.target.value) })} />
          </label>
          <label>
            <span>{t("stock.bulkEdit.filter.offerUntil")}</span>
            <input type="date" value={draft.offerUntil ?? ""} onChange={(event) => update({ offerUntil: optionValue(event.target.value) })} />
          </label>
          <label>
            <span>{t("stock.bulkEdit.filter.minimumPrice")}</span>
            <input min="0" step="0.01" type="number" value={draft.minimumPrice ?? ""} onChange={(event) => update({ minimumPrice: nullableNumber(event.target.value) })} />
          </label>
          <label>
            <span>{t("stock.bulkEdit.filter.maximumPrice")}</span>
            <input min="0" step="0.01" type="number" value={draft.maximumPrice ?? ""} onChange={(event) => update({ maximumPrice: nullableNumber(event.target.value) })} />
          </label>
        </div>

        {error && <p className="stock-bulk-dialog-error" role="alert">{t(error)}</p>}
        <footer className="filter-actions erp-dialog-actions-row">
          <DialogDismissButton type="button" className="secondary erp-dialog-action-cancel erp-dialog-dismiss" onClick={onClose}>{t(autoApply ? "common.close" : "common.cancel")}</DialogDismissButton>
          <button type="button" onClick={() => update({ ...emptyStockBulkFilterCriteria })}>
            {t("stock.bulkEdit.filter.clear")}
          </button>
          {!autoApply && <button type="button" className="erp-dialog-action-confirm" disabled={Boolean(error)} onClick={() => onApply({ ...draft })}>
            {t("stock.filter.apply")}
          </button>}
        </footer>
      </section>
    </div>
  );
}
