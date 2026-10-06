import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { SaleCustomer } from "./SaleScreen";
import { SaleCustomerList, type SaleCustomerSortColumn } from "./SaleCustomerList";
import { nextTableSort, sortTableRows, type TableSort } from "./tableSorting";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";

export function SaleInvoiceCustomerListDialog({ token, locale, selectedCustomerId, onSelect, onClose }: {
  token?: string;
  locale: LocaleCode;
  selectedCustomerId: string;
  onSelect: (customer: SaleCustomer) => void;
  onClose: () => void;
}) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState("");
  const [customers, setCustomers] = useState<SaleCustomer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [highlightedId, setHighlightedId] = useState(selectedCustomerId);
  const [sort, setSort] = useState<TableSort<SaleCustomerSortColumn> | null>(null);
  const results = useMemo(() => sortTableRows(customers, sort, (customer, column) => {
    if (column === "code") return customer.clientId;
    if (column === "name") return customer.fiscalName;
    if (column === "document") return customer.documentNumber;
    if (column === "member") return customer.memberCategoryName ?? customer.activeMember ?? false;
    if (column === "discount") return customer.memberDiscountPercent == null ? null : Number(customer.memberDiscountPercent);
    if (column === "debt") return Number(customer.outstandingDebt ?? 0);
    return Number(customer.overdueDebt ?? 0);
  }, locale), [customers, sort, locale]);
  const available = results.filter((customer) => customer.active === true);
  const selected = available.find((customer) => customer.id === highlightedId) ?? available[0];

  useEffect(() => {
    let current = true;
    setCustomers([]);
    setLoading(true);
    setError(false);
    const timer = globalThis.setTimeout(() => {
      void apiRequest<SaleCustomer[]>(`/customers/sale-options/search?q=${encodeURIComponent(query.trim())}&limit=50`, { token })
        .then((rows) => { if (current) setCustomers(rows); })
        .catch(() => { if (current) setError(true); })
        .finally(() => { if (current) setLoading(false); });
    }, query ? 180 : 0);
    return () => { current = false; globalThis.clearTimeout(timer); };
  }, [query, token]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const deactivate = activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document);
    inputRef.current?.focus();
    return deactivate;
  }, []);

  function choose(customer: SaleCustomer | null | undefined) {
    if (!loading && !error && customer?.active === true) onSelect(customer);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.repeat) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    const target = event.target instanceof HTMLElement ? event.target : null;
    if (target !== inputRef.current && !target?.closest(".sale-customer-table-row")) return;
    if (event.ctrlKey || event.altKey || event.metaKey) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      event.stopPropagation();
      if (!selected) return;
      const index = available.findIndex((customer) => customer.id === selected.id);
      const next = available[(index + (event.key === "ArrowDown" ? 1 : -1) + available.length) % available.length];
      setHighlightedId(next.id);
      queueMicrotask(() => dialogRef.current?.querySelector<HTMLElement>(".sale-customer-table-row.selected")?.scrollIntoView?.({ block: "nearest" }));
    } else if (event.key === "Enter" || event.key === "Insert") {
      event.preventDefault();
      event.stopPropagation();
      choose(selected);
    }
  }

  return <div className="sale-action-overlay" role="presentation"
    onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section ref={dialogRef} className="sale-action-dialog wide sale-customer-selection-dialog sale-invoice-customer-list-dialog"
      role="dialog" aria-modal="true" aria-label={t("sale.customer.title")} onKeyDown={handleKeyDown}>
      <header>
        <h2>{t("sale.customer.title")}</h2>
        <WindowCloseButton aria-label={t("sale.dialog.close")} onClick={onClose} />
      </header>
      <SaleCustomerList locale={locale} inputRef={inputRef} customers={results} query={query}
        onQueryChange={(value) => { setQuery(value); setHighlightedId(""); }} loading={loading} error={error}
        selectedCustomerId={selected?.id ?? ""} onHighlight={setHighlightedId} onActivate={choose}
        sort={sort} onSort={(column) => setSort((current) => nextTableSort(current, column))} activeOnly />
      <footer className="sale-customer-selection-footer erp-dialog-actions-row">
        <DialogDismissButton className="erp-dialog-action-cancel erp-dialog-dismiss" type="button" onClick={onClose}>{t("sale.dialog.close")}</DialogDismissButton>
        <button className="erp-dialog-action-confirm" type="button" disabled={loading || error || !selected} onClick={() => choose(selected)}>
          {t("sale.customer.select")}
        </button>
      </footer>
    </section>
  </div>;
}
