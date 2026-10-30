import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import type { SaleCustomer } from "./SaleScreen";
import { SaleCustomerAddressDialog } from "./SaleCustomerAddressDialog";
import "./SalesDocumentCustomerDialog.css";
import { useTableArrowRepeat } from "./useTableArrowRepeat";
import { ensureTableRowVisible } from "./ensureTableRowVisible";

type SalesDocumentCustomerDialogProps = {
  locale: LocaleCode;
  customers: SaleCustomer[];
  selectedCustomerId?: string;
  loading?: boolean;
  onSelect: (customer: SaleCustomer) => void;
  onClose: () => void;
};

export function SalesDocumentCustomerDialog({
  locale,
  customers,
  selectedCustomerId = "",
  loading = false,
  onSelect,
  onClose,
}: SalesDocumentCustomerDialogProps) {
  const t = createTranslator(locale);
  const id = useId();
  const [query, setQuery] = useState("");
  const [highlightedId, setHighlightedId] = useState(selectedCustomerId);
  const [customerAddressChoice, setCustomerAddressChoice] = useState<SaleCustomer | null>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const tableRef = useRef<HTMLDivElement>(null);
  const focusMovedOptionRef = useRef<boolean | null>(null);
  const results = useMemo(() => {
    if (loading) return [];
    const normalized = query.trim().toLocaleLowerCase();
    return customers.filter((customer) => !normalized || [
      customer.clientId, customer.fiscalName, customer.documentNumber,
    ].some((value) => value?.toLocaleLowerCase().includes(normalized))).slice(0, 100);
  }, [customers, loading, query]);
  const selected = results.find((customer) => customer.id === highlightedId) ?? results[0];
  const optionId = (customerId: string) => `${id}-customer-${encodeURIComponent(customerId)}`;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const deactivate = activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document);
    inputRef.current?.focus();
    return deactivate;
  }, []);

  useLayoutEffect(() => {
    if (focusMovedOptionRef.current === null || !selected) return;
    const option = document.getElementById(optionId(selected.id));
    const shouldFocus = focusMovedOptionRef.current;
    focusMovedOptionRef.current = null;
    if (shouldFocus) option?.focus({ preventScroll: true });
    if (option && tableRef.current) ensureTableRowVisible(tableRef.current, option);
  }, [highlightedId]);

  const handleArrowRepeat = useTableArrowRepeat((direction) => {
    const focused = document.activeElement;
    if (loading || results.length < 2 || (focused !== inputRef.current
        && focused !== dialogRef.current
        && !dialogRef.current?.querySelector('[role="option"]:focus'))) return false;
    const focusedOption = focused instanceof HTMLElement && dialogRef.current?.contains(focused)
      ? focused.closest<HTMLElement>('[role="option"]') : null;
    const currentIndex = results.findIndex((customer) => customer.id === (focusedOption?.dataset.customerId ?? selected?.id));
    const nextIndex = Math.max(0, Math.min(results.length - 1, currentIndex + (direction === "ArrowUp" ? -1 : 1)));
    if (nextIndex === currentIndex) return false;
    focusMovedOptionRef.current = document.activeElement?.getAttribute("role") === "option";
    setHighlightedId(results[nextIndex].id);
    return true;
  }, !loading && results.length > 0 && !customerAddressChoice);

  function chooseCustomer(customer: SaleCustomer) {
    const addresses = customer.addresses ?? [];
    if (addresses.length > 1) {
      setCustomerAddressChoice(customer);
    } else if (addresses.length === 1) {
      onSelect({ ...customer, customerAddressId: addresses[0].id, customerContactSnapshot: addresses[0] });
    } else {
      onSelect(customer);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (customerAddressChoice) {
      event.stopPropagation();
      return;
    }
    if (event.nativeEvent.isComposing || (event.repeat && event.key !== "ArrowUp" && event.key !== "ArrowDown")) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    const target = event.target instanceof HTMLElement ? event.target : null;
    const acceptsSelectionShortcuts = target === inputRef.current
      || target === event.currentTarget
      || Boolean(target?.closest('[role="option"]'));
    if (!acceptsSelectionShortcuts || event.ctrlKey || event.altKey || event.metaKey) return;
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      event.stopPropagation();
      handleArrowRepeat(event);
    } else if (event.key === "Insert" || event.key === "Enter") {
      event.preventDefault();
      event.stopPropagation();
      if (selected) chooseCustomer(selected);
    }
  }

  return (
    <>
    <div className="sale-action-overlay sales-document-customer-overlay" role="presentation"
      aria-hidden={Boolean(customerAddressChoice)} inert={Boolean(customerAddressChoice)}
      style={customerAddressChoice ? { visibility: "hidden" } : undefined}>
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-business-dialog sales-document-customer-selector"
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        onKeyDown={handleKeyDown}
      >
        <header className="sales-document-customer-heading">
          <h2 id={`${id}-title`}>{t("salesDocument.selectCustomer")}</h2>
          <WindowCloseButton type="button" aria-label={t("common.close")} onClick={onClose}>{"\u00d7"}</WindowCloseButton>
        </header>

        <label className="sales-document-customer-search" htmlFor={`${id}-query`}>
          <span>{t("sale.customer.search")}</span>
          <input
            ref={inputRef}
            id={`${id}-query`}
            autoComplete="off"
            aria-label={t("sale.customer.search")}
            aria-controls={`${id}-results`}
            aria-activedescendant={selected ? optionId(selected.id) : undefined}
            value={query}
            placeholder={t("salesDocument.customerSearch")}
            onChange={(event) => {
              setQuery(event.target.value);
              setHighlightedId("");
            }}
          />
        </label>

        <div ref={tableRef} className="sales-document-customer-table">
          <div className="sales-document-customer-columns header" role="row" aria-hidden="true">
            <span>{t("sale.customer.column.code")}</span>
            <span>{t("party.field.fiscalName")}</span>
            <span>{t("sale.customer.column.document")}</span>
          </div>
          <div
            id={`${id}-results`}
            className="sales-document-customer-options"
            role="listbox"
            aria-label={t("salesDocument.selectCustomer")}
            aria-busy={loading}
          >
            {results.map((customer) => (
              <div
                id={optionId(customer.id)}
                data-customer-id={customer.id}
                key={customer.id}
                role="option"
                tabIndex={-1}
                aria-selected={customer.id === selected?.id}
                aria-label={`${t("sale.customer.column.code")}: ${customer.clientId ?? "\u2014"}; ${t("party.field.fiscalName")}: ${customer.fiscalName ?? customer.clientId ?? customer.id}; ${t("sale.customer.column.document")}: ${customer.documentNumber ?? "\u2014"}`}
                className="sales-document-customer-option"
                onFocus={() => setHighlightedId(customer.id)}
                onClick={(event) => {
                  setHighlightedId(customer.id);
                  event.currentTarget.focus();
                }}
                onDoubleClick={() => chooseCustomer(customer)}
              >
                <span>{customer.clientId ?? "\u2014"}</span>
                <strong>{customer.fiscalName ?? customer.clientId ?? customer.id}</strong>
                <span>{customer.documentNumber ?? "\u2014"}</span>
              </div>
            ))}
            {(loading || results.length === 0) && (
              <p className="sales-document-customer-status" role="status">
                {t(loading ? "sale.customer.loading" : "sale.customer.empty")}
              </p>
            )}
          </div>
        </div>

        <footer className="sale-business-dialog-actions sales-document-customer-actions erp-dialog-actions-row">
          <DialogDismissButton type="button" className="sales-document-customer-cancel erp-dialog-action-cancel erp-dialog-dismiss" onClick={onClose}>
            <span>{t("sale.dialog.cancel")}</span><kbd aria-hidden="true">Esc</kbd>
          </DialogDismissButton>
          <div className="sales-document-customer-help erp-dialog-actions-group">
            {!loading && <span className="sales-document-customer-count" role="status">
              {results.length} {t(results.length === 1 ? "sale.searchDialog.result" : "sale.searchDialog.results")}
            </span>}
          </div>
          <button
            type="button"
            className="sales-document-customer-confirm erp-dialog-action-confirm"
            disabled={!selected || loading}
            aria-keyshortcuts="Insert Enter"
            onClick={() => selected && chooseCustomer(selected)}
          >
            <span>{t("sale.customer.select")}</span><kbd aria-hidden="true">Insert / Enter</kbd>
          </button>
        </footer>
      </section>
    </div>
    {customerAddressChoice && <SaleCustomerAddressDialog
      locale={locale}
      customer={customerAddressChoice}
      addresses={customerAddressChoice.addresses ?? []}
      onSelect={(address) => onSelect({ ...customerAddressChoice, customerAddressId: address.id, customerContactSnapshot: address })}
      onCancel={() => setCustomerAddressChoice(null)}
    />}
    </>
  );
}
