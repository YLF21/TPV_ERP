import type { RefObject } from "react";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { TableSortButton } from "./TableSortButton";
import type { TableSort } from "./tableSorting";
import type { SaleCustomer } from "./SaleScreen";

export type SaleCustomerSortColumn = "code" | "name" | "document" | "member" | "discount" | "debt" | "overdue";

type SaleCustomerListProps = {
  locale: LocaleCode;
  inputRef: RefObject<HTMLInputElement | null>;
  customers: SaleCustomer[];
  query: string;
  onQueryChange(value: string): void;
  loading: boolean;
  error: boolean;
  selectedCustomerId: string;
  onHighlight(id: string): void;
  onActivate(customer: SaleCustomer | null): void;
  sort: TableSort<SaleCustomerSortColumn> | null;
  onSort(column: SaleCustomerSortColumn): void;
  includeNoCustomer?: boolean;
  noCustomerId?: string;
  activeOnly?: boolean;
};

export function SaleCustomerList({
  locale,
  inputRef,
  customers,
  query,
  onQueryChange,
  loading,
  error,
  selectedCustomerId,
  onHighlight,
  onActivate,
  sort,
  onSort,
  includeNoCustomer = false,
  noCustomerId = "__NO_CUSTOMER__",
  activeOnly = false,
}: SaleCustomerListProps) {
  const t = createTranslator(locale);

  return <>
    <div className="sale-customer-toolbar">
      <label>
        <span>{t("sale.customer.search")}</span>
        <input ref={inputRef} aria-label={t("sale.customer.search")} value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder={t("sale.customer.placeholder")} />
      </label>
    </div>
    <div className="sale-customer-table" role="table" aria-label={t("sale.customer.title")}>
      <div className="sale-customer-table-header" role="row">
        {(["code", "name", "document", "member", "discount", "debt", "overdue"] as const).map((column) => (
          <span
            role="columnheader"
            aria-sort={sort?.column === column
              ? sort.direction === "asc" ? "ascending" : "descending"
              : "none"}
            key={column}
          >
            <TableSortButton
              direction={sort?.column === column ? sort.direction : null}
              label={`${t("party.sortBy")} ${t(`sale.customer.column.${column}`)}`}
              onSort={() => onSort(column)}
            >
              {t(`sale.customer.column.${column}`)}
            </TableSortButton>
          </span>
        ))}
      </div>
      <div className="sale-customer-table-body" role="rowgroup">
        {loading && <p className="sale-search-status">{t("sale.customer.loading")}</p>}
        {error && <p className="sale-action-error">{t("sale.customer.loadError")}</p>}
        {!loading && !error && includeNoCustomer && <button
          type="button"
          aria-label={t("sale.customer.none")}
          className={`sale-customer-table-row sale-customer-none-row${selectedCustomerId === noCustomerId ? " selected" : ""}`}
          aria-current={selectedCustomerId === noCustomerId}
          onClick={() => onHighlight(noCustomerId)}
          onDoubleClick={() => onActivate(null)}
        >
          <span role="cell">—</span>
          <strong role="cell">{t("sale.customer.none")}</strong>
          <span role="cell"></span><span role="cell"></span><span role="cell"></span>
          <span role="cell"></span><span role="cell"></span>
        </button>}
        {!loading && !error && customers.map((customer) => (
          <button
            type="button"
            aria-label={[customer.clientId, customer.fiscalName, customer.documentNumber].filter(Boolean).join(" · ") || t("sale.customer.unnamed")}
            className={`sale-customer-table-row${customer.id === selectedCustomerId ? " selected" : ""}`}
            aria-current={customer.id === selectedCustomerId}
            key={customer.id}
            disabled={activeOnly && customer.active !== true}
            onFocus={() => onHighlight(customer.id)}
            onClick={() => onHighlight(customer.id)}
            onDoubleClick={() => onActivate(customer)}
          >
            <strong role="cell">{customer.clientId ?? t("sale.customer.noCode")}</strong>
            <span role="cell" title={customer.fiscalName ?? undefined}>{customer.fiscalName ?? t("sale.customer.unnamed")}</span>
            <span role="cell">{customer.documentNumber ?? ""}</span>
            <span role="cell">{customer.activeMember ? customer.memberCategoryName || t("common.yes") : ""}</span>
            <span role="cell">{customer.memberDiscountPercent == null ? "" : `${Number(customer.memberDiscountPercent).toLocaleString(locale, { maximumFractionDigits: 2 })} %`}</span>
            <strong role="cell" className={Number(customer.outstandingDebt ?? 0) > 0 ? "debt" : ""}>
              {Number(customer.outstandingDebt ?? 0) > 0 ? `${Number(customer.outstandingDebt).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} €` : ""}
            </strong>
            <strong role="cell" className={Number(customer.overdueDebt ?? 0) > 0 ? "overdue-debt" : ""}>
              {Number(customer.overdueDebt ?? 0) > 0 ? `${Number(customer.overdueDebt).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} €` : ""}
            </strong>
          </button>
        ))}
        {!loading && !error && customers.length === 0 && !includeNoCustomer && <p className="sale-customer-empty">{t("sale.customer.empty")}</p>}
      </div>
    </div>
  </>;
}
