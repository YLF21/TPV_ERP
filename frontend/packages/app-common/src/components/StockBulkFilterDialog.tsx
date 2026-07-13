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
import { ErpSelect } from "./ErpSelect";

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
  onApply,
  onClose
}: StockBulkFilterDialogProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [draft, setDraft] = useState<StockBulkFilterCriteria>(value);
  const dialogRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (open) setDraft({ ...value });
  }, [open, value]);

  if (!open) return null;

  const selectedFamily = families.find((family) => family.id === draft.familyId);
  const error = validateStockBulkFilter(draft);
  const activeCount = countActiveStockBulkFilters(draft);
  const update = (patch: Partial<StockBulkFilterCriteria>) => setDraft((current) => ({
    ...current,
    ...patch
  }));
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
    if (!moved && intent === "next" && !error) onApply({ ...draft });
  };

  return (
    <div className="filter-overlay" role="presentation">
      <section
        ref={dialogRef}
        aria-labelledby="stock-bulk-filter-title"
        aria-modal="true"
        className="filter-dialog stock-bulk-filter-dialog"
        role="dialog"
        onKeyDown={handleEnter}
      >
        <header className="filter-header">
          <div>
            <h2 id="stock-bulk-filter-title">{t("stock.bulkEdit.filter.title")}</h2>
            <p>{t("stock.bulkEdit.filter.activeCount").replace("{count}", String(activeCount))}</p>
          </div>
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </header>

        <div className="stock-bulk-filter-grid">
          <div className="stock-bulk-filter-field">
            <span>{t("stock.bulkEdit.filter.productType")}</span>
            <ErpSelect
              aria-label={t("stock.bulkEdit.filter.productType")}
              value={draft.productType ?? ""}
              options={[
                { value: "", label: t("stock.filter.all") },
                { value: "UNIT", label: t("product.type.unit") },
                { value: "WEIGHT", label: t("product.type.weight") },
                { value: "SERVICE", label: t("product.type.service") }
              ]}
              onChange={(next) => update({ productType: optionValue(next) })}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.family")}</span>
            <ErpSelect
              aria-label={t("stock.column.family")}
              value={draft.familyId ?? ""}
              options={[
                { value: "", label: t("stock.filter.all") },
                ...families.map((family) => ({ value: family.id, label: family.name }))
              ]}
              onChange={(next) => update({ familyId: optionValue(next), subfamilyId: null })}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.subfamily")}</span>
            <ErpSelect
              aria-label={t("stock.column.subfamily")}
              disabled={!selectedFamily}
              value={draft.subfamilyId ?? ""}
              options={[
                { value: "", label: t("stock.filter.all") },
                ...(selectedFamily?.subfamilies.map((subfamily) => ({ value: subfamily.id, label: subfamily.name })) ?? [])
              ]}
              onChange={(next) => update({ subfamilyId: optionValue(next) })}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.supplier")}</span>
            <ErpSelect
              aria-label={t("stock.column.supplier")}
              value={draft.supplierId ?? ""}
              options={[{ value: "", label: t("stock.filter.all") }, ...suppliers]}
              onChange={(next) => update({ supplierId: optionValue(next) })}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.column.tax")}</span>
            <ErpSelect
              aria-label={t("stock.column.tax")}
              value={draft.taxId ?? ""}
              options={[{ value: "", label: t("stock.filter.all") }, ...taxes]}
              onChange={(next) => update({ taxId: optionValue(next) })}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("product.field.usePrice")}</span>
            <ErpSelect
              aria-label={t("product.field.usePrice")}
              value={draft.priceUseMode ?? ""}
              options={[
                { value: "", label: t("stock.filter.all") },
                { value: "NORMAL", label: t("product.discount.salePrice") },
                { value: "MEMBER_PRICE", label: t("product.discount.memberPrice") },
                { value: "OFFER_PRICE", label: t("product.discount.offerPrice") },
                { value: "OFFER_DISCOUNT", label: t("product.discount.offerDiscount") }
              ]}
              onChange={(next) => update({ priceUseMode: optionValue(next) })}
              onCommit={() => moveFromActiveControl("next")}
              onNavigatePrevious={() => moveFromActiveControl("previous")}
            />
          </div>
          <div className="stock-bulk-filter-field">
            <span>{t("stock.bulkEdit.filter.offerActive")}</span>
            <ErpSelect
              aria-label={t("stock.bulkEdit.filter.offerActive")}
              value={draft.offerActive === null || draft.offerActive === undefined ? "" : draft.offerActive ? "yes" : "no"}
              options={[
                { value: "", label: t("stock.filter.all") },
                { value: "yes", label: t("common.yes") },
                { value: "no", label: t("common.no") }
              ]}
              onChange={(next) => update({ offerActive: next === "" ? null : next === "yes" })}
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
        <footer className="filter-actions">
          <button type="button" onClick={() => setDraft({ ...emptyStockBulkFilterCriteria })}>
            {t("stock.bulkEdit.filter.clear")}
          </button>
          <button type="button" className="secondary" onClick={onClose}>{t("common.cancel")}</button>
          <button type="button" disabled={Boolean(error)} onClick={() => onApply({ ...draft })}>
            {t("stock.filter.apply")}
          </button>
        </footer>
      </section>
    </div>
  );
}
