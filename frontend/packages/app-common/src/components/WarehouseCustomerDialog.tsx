import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import type { SaleCustomer } from "./SaleScreen";
import type { SaleCustomerSortColumn } from "./SaleCustomerList";
import { SaleCustomerSelectionContent } from "./SaleCustomerSelectionContent";
import { SaleCustomerCreateDialog, canCreateSaleCustomer } from "./SaleCustomerCreateDialog";
import { nextTableSort, sortTableRows, type TableSort } from "./tableSorting";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { WindowCloseButton } from "./WindowCloseButton";
import { useTableArrowRepeat } from "./useTableArrowRepeat";

const noCustomerId = "__NO_CUSTOMER__";

export function WarehouseCustomerDialog({ token, session, locale, selectedCustomerId,
  onSelect, onChanged, onClose }: {
  token?: string;
  session?: UserSession;
  locale: LocaleCode;
  selectedCustomerId: string;
  onSelect: (customer: SaleCustomer | null) => void;
  onChanged: (customer: SaleCustomer) => void;
  onClose: () => void;
}) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState("");
  const [customers, setCustomers] = useState<SaleCustomer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [highlightedId, setHighlightedId] = useState(selectedCustomerId || noCustomerId);
  const [sort, setSort] = useState<TableSort<SaleCustomerSortColumn> | null>(null);
  const [editor, setEditor] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const accessToken = token ?? session?.accessToken;
  const canCreate = Boolean(session && canCreateSaleCustomer(session.permissions));
  const canEdit = Boolean(session && (hasPermission(session, "CUSTOMERS_WRITE")
    || hasPermission(session, "GESTION_CLIENTE_PROVEEDOR")));
  const results = useMemo(() => sortTableRows(customers, sort, (customer, column) => {
    if (column === "code") return customer.clientId;
    if (column === "name") return customer.fiscalName;
    if (column === "document") return customer.documentNumber;
    if (column === "member") return customer.memberCategoryName ?? customer.activeMember ?? false;
    if (column === "discount") return customer.memberDiscountPercent == null ? null : Number(customer.memberDiscountPercent);
    if (column === "debt") return Number(customer.outstandingDebt ?? 0);
    return Number(customer.overdueDebt ?? 0);
  }, locale), [customers, sort, locale]);
  const available = results.filter(customer => customer.active === true);
  const selectionIds = [noCustomerId, ...available.map(customer => customer.id)];
  const selected = loading || error ? undefined : highlightedId === noCustomerId ? null
    : available.find(customer => customer.id === highlightedId);

  useEffect(() => {
    if (editor !== null) return;
    let current = true;
    setLoading(true);
    setError(false);
    const timer = globalThis.setTimeout(() => {
      void apiRequest<SaleCustomer[]>(`/customers/sale-options/search?q=${encodeURIComponent(query.trim())}&limit=50`, { token: accessToken })
        .then(rows => {
          if (!current) return;
          setCustomers(rows);
          setHighlightedId(id => id === noCustomerId || rows.some(customer => customer.id === id && customer.active)
            ? id : rows.find(customer => customer.active)?.id ?? noCustomerId);
        })
        .catch(() => { if (current) setError(true); })
        .finally(() => { if (current) setLoading(false); });
    }, query ? 180 : 0);
    return () => { current = false; globalThis.clearTimeout(timer); };
  }, [query, accessToken, editor, revision]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const deactivate = activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document);
    inputRef.current?.focus();
    return deactivate;
  }, [editor]);

  const handleArrowRepeat = useTableArrowRepeat(direction => {
    const focused = document.activeElement;
    if (loading || error || !dialogRef.current?.contains(focused)) return false;
    const focusedRow = focused instanceof HTMLElement ? focused.closest<HTMLElement>(".sale-customer-table-row") : null;
    const index = selectionIds.indexOf(focusedRow?.dataset.customerId ?? highlightedId);
    const nextIndex = Math.max(0, Math.min(selectionIds.length - 1, index + (direction === "ArrowDown" ? 1 : -1)));
    if (nextIndex === index) return false;
    setHighlightedId(selectionIds[nextIndex]);
    return true;
  }, !loading && !error && editor === null);

  function choose(customer: SaleCustomer | null | undefined) {
    if (!loading && !error && customer !== undefined && (customer === null || customer.active === true)) onSelect(customer);
  }

  function openEditor() { if (canEdit && selected) setEditor(selected.id); }

  async function finishSavedCustomer(id: string) {
    try {
      const saved = await apiRequest<SaleCustomer>(`/customers/sale-options/${id}`, { token: accessToken });
      onChanged(saved);
      if (editor === "new" && saved.active === true) { onSelect(saved); return; }
      setHighlightedId(saved.id);
      setEditor(null);
      setRevision(current => current + 1);
    } catch {
      setEditor(null);
      setError(true);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || (event.repeat && event.key !== "ArrowUp" && event.key !== "ArrowDown")) return;
    if (event.key === "Escape") {
      event.preventDefault(); event.stopPropagation(); onClose();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault(); event.stopPropagation(); handleArrowRepeat(event);
    } else if (event.key === "F5" && canCreate) {
      event.preventDefault(); event.stopPropagation(); setEditor("new");
    } else if (event.ctrlKey && event.key === "F7" && canEdit) {
      event.preventDefault(); event.stopPropagation(); openEditor();
    } else if (event.key === "Insert") {
      event.preventDefault(); event.stopPropagation(); choose(selected);
    }
  }

  if (editor !== null && session) return <SaleCustomerCreateDialog locale={locale} session={session}
    customerId={editor === "new" ? undefined : editor} onCancel={() => setEditor(null)}
    onCreated={customer => void finishSavedCustomer(customer.id)} />;

  return <div className="sale-action-overlay" role="presentation"
    onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <section ref={dialogRef} className="sale-action-dialog wide sale-customer-selection-dialog"
      role="dialog" aria-modal="true" aria-label={t("sale.customer.title")} onKeyDown={handleKeyDown}>
      <header><h2>{t("sale.customer.title")}</h2><WindowCloseButton aria-label={t("sale.dialog.close")} onClick={onClose} /></header>
      <SaleCustomerSelectionContent locale={locale} inputRef={inputRef} customers={results} query={query}
        onQueryChange={value => { setQuery(value); setHighlightedId(""); }} loading={loading} error={error}
        selectedCustomerId={highlightedId} onHighlight={setHighlightedId} onActivate={choose}
        sort={sort} onSort={column => setSort(current => nextTableSort(current, column))}
        includeNoCustomer noCustomerId={noCustomerId} activeOnly selectedCustomer={selected}
        onCreate={canCreate ? () => setEditor("new") : undefined} onEdit={canEdit ? openEditor : undefined}
        onClose={onClose} />
    </section>
  </div>;
}
