import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext, UserSession } from "../types";
import { CustomerReceivablePaymentDialog, type CustomerReceivable } from "./CustomerReceivablePaymentDialog";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type SaleCustomerSummary = {
  id: string;
  clientId?: string | null;
  fiscalName?: string | null;
};

type Request = <T>(path: string, options?: {
  method?: string;
  token?: string;
  body?: unknown;
}) => Promise<T>;

type Props = {
  locale: LocaleCode;
  interfaceMode?: "KEYBOARD" | "TOUCH";
  session: UserSession;
  terminalContext: TerminalContext;
  customer: SaleCustomerSummary;
  request?: Request;
  onClose: () => void;
};

const amount = (value: number | string, locale: LocaleCode) => Number(value).toLocaleString(
  locale === "zh" ? "zh-CN" : locale,
  { minimumFractionDigits: 2, maximumFractionDigits: 2 },
);

const date = (value: string | null | undefined, locale: LocaleCode) => {
  if (!value) return "";
  const parsed = new Date(`${value}T00:00:00`);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString(locale === "zh" ? "zh-CN" : locale);
};

const sortOpenReceivables = (left: CustomerReceivable, right: CustomerReceivable) =>
  Number(right.overdue) - Number(left.overdue)
  || String(left.dueDate ?? "9999-12-31").localeCompare(String(right.dueDate ?? "9999-12-31"))
  || left.documentNumber.localeCompare(right.documentNumber);

export function SaleCustomerReceivablesDialog({
  locale,
  interfaceMode = "KEYBOARD",
  session,
  terminalContext,
  customer,
  request = apiRequest,
  onClose,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const [rows, setRows] = useState<CustomerReceivable[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [payment, setPayment] = useState<CustomerReceivable | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const openRows = useMemo(() => rows
    .filter((row) => row.status !== "PAGADO" && Number(row.pendingTotal) > 0)
    .sort(sortOpenReceivables), [rows]);
  const totalPending = openRows.reduce((total, row) => total + Number(row.pendingTotal), 0);
  const totalOverdue = openRows.filter((row) => row.overdue)
    .reduce((total, row) => total + Number(row.pendingTotal), 0);
  const selected = openRows.find((row) => row.documentId === selectedId) ?? null;

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const loaded = await request<CustomerReceivable[]>(`/customer-receivables?customerId=${customer.id}`, {
        token: session.accessToken,
      });
      setRows(loaded);
      const available = loaded
        .filter((row) => row.status !== "PAGADO" && Number(row.pendingTotal) > 0)
        .sort(sortOpenReceivables);
      setSelectedId((current) => available.some((row) => row.documentId === current)
        ? current : available[0]?.documentId ?? "");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t("receivables.error.load"));
    } finally {
      setLoading(false);
    }
  }, [customer.id, request, session.accessToken]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      event.stopPropagation();
      if (openRows.length === 0) return;
      const current = openRows.findIndex((row) => row.documentId === selectedId);
      const delta = event.key === "ArrowDown" ? 1 : -1;
      const next = current < 0 ? 0 : (current + delta + openRows.length) % openRows.length;
      setSelectedId(openRows[next].documentId);
      return;
    }
    if (event.key === "Enter" && selected) {
      event.preventDefault();
      event.stopPropagation();
      setPayment(selected);
    }
  }

  return <div className="filter-overlay sale-customer-receivables-overlay" role="dialog" aria-modal="true" aria-labelledby="sale-customer-receivables-title">
    <section ref={dialogRef} className="filter-dialog sale-customer-receivables-dialog" onKeyDown={handleKeyDown}>
      <header className="filter-header">
        <div>
          <h2 id="sale-customer-receivables-title">{t("sale.customer.receivables.title")}</h2>
          <span>{customer.clientId} · {customer.fiscalName}</span>
        </div>
        <button type="button" aria-label={t("common.close")} onClick={onClose}>×</button>
      </header>
      <div className="sale-customer-receivables-summary">
        <div><span>{t("sale.customer.receivables.pending")}</span><strong className={totalPending > 0 ? "debt" : ""}>{amount(totalPending, locale)} €</strong></div>
        <div><span>{t("sale.customer.receivables.overdue")}</span><strong className={totalOverdue > 0 ? "overdue-debt" : ""}>{amount(totalOverdue, locale)} €</strong></div>
        <div><span>{t("sale.customer.receivables.documents")}</span><strong>{openRows.length}</strong></div>
      </div>
      <div className="sale-customer-receivables-table" role="table" aria-label={t("sale.customer.receivables.title")}>
        <div className="sale-customer-receivables-table-header" role="row">
          <span role="columnheader">{t("receivables.column.document")}</span>
          <span role="columnheader">{t("receivables.column.issueDate")}</span>
          <span role="columnheader">{t("receivables.column.dueDate")}</span>
          <span role="columnheader">{t("receivables.column.total")}</span>
          <span role="columnheader">{t("receivables.column.paid")}</span>
          <span role="columnheader">{t("receivables.column.pending")}</span>
        </div>
        <div className="sale-customer-receivables-table-body" role="rowgroup">
          {loading && <p className="sale-search-status">{t("common.loading")}</p>}
          {error && <p className="sale-action-error" role="alert">{error}</p>}
          {!loading && !error && openRows.map((row) => <button
            type="button"
            role="row"
            key={row.documentId}
            className={`sale-customer-receivables-row ${row.documentId === selectedId ? "selected " : ""}${row.overdue ? "overdue" : ""}`.trim()}
            aria-current={row.documentId === selectedId}
            onClick={() => setSelectedId(row.documentId)}
            onDoubleClick={() => setPayment(row)}
          >
            <strong role="cell">{row.documentNumber}</strong>
            <span role="cell">{date(row.issueDate, locale)}</span>
            <span role="cell">{date(row.dueDate, locale)}</span>
            <span role="cell" className="money">{amount(row.total, locale)} €</span>
            <span role="cell" className="money">{amount(row.paidTotal, locale)} €</span>
            <strong role="cell" className={`money ${row.overdue ? "overdue-debt" : "debt"}`}>{amount(row.pendingTotal, locale)} €</strong>
          </button>)}
          {!loading && !error && openRows.length === 0 && <p className="sale-customer-receivables-empty">{t("sale.customer.receivables.empty")}</p>}
        </div>
      </div>
      <footer className="sale-customer-receivables-footer">
        <p><kbd>↑</kbd><kbd>↓</kbd> {t("sale.customer.receivables.navigateHint")} · <kbd>Enter</kbd> {t("sale.customer.receivables.collectHint")}</p>
        <div className="sale-action-buttons">
          <button type="button" onClick={onClose}>{t("common.close")}</button>
          <button type="button" disabled={!selected} onClick={() => selected && setPayment(selected)}>{t("receivables.action.collect")}</button>
        </div>
      </footer>
    </section>
    {payment && <CustomerReceivablePaymentDialog
      locale={locale}
      interfaceMode={interfaceMode}
      receivable={payment}
      token={session.accessToken}
      terminalCode={terminalContext.terminalCode}
      terminalContext={terminalContext}
      request={request}
      onCancel={() => setPayment(null)}
      onPaid={(updated) => {
        setRows((current) => current.map((row) => row.documentId === updated.documentId ? updated : row));
        setPayment(null);
        void load();
      }}
    />}
  </div>;
}
