import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import { retryPrintSucceeded } from "../sale/printRetry";
import {
  printCustomerReceivablePaymentReceipt,
  type CustomerReceivablePaymentReceiptSnapshot
} from "../sale/ticketPrinting";
import type { LocaleCode, TerminalContext, UserSession } from "../types";
import { CustomerReceivablePaymentDialog, type CustomerReceivable } from "./CustomerReceivablePaymentDialog";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type ReceivablesView = "OPEN" | "HISTORY" | "ACCOUNT";
type PaymentMethod = { id: string; name?: string; nombre?: string; active?: boolean };
export type CustomerReceivablePaymentHistory = {
  paymentId: string;
  requestId?: string | null;
  documentId: string;
  documentType: "ALBARAN_VENTA" | "FACTURA_VENTA";
  documentNumber: string;
  customerId: string;
  customerName: string;
  issueDate: string;
  collectedAt: string;
  paymentMethodId: string;
  paymentMethodName: string;
  amount: number | string;
  reference?: string | null;
};
type CustomerCreditAccountEntry = {
  id: string;
  kind: "SALE" | "PAYMENT";
  occurredAt: string;
  documentId: string;
  documentNumber: string;
  documentType: "ALBARAN_VENTA" | "FACTURA_VENTA";
  paymentId?: string | null;
  paymentMethod?: string | null;
  reference?: string | null;
  debit: number | string;
  credit: number | string;
  balance: number | string;
};
type CustomerCreditAccount = {
  customerId: string;
  customerCode: string;
  customerName: string;
  creditEnabled: boolean;
  creditLimit?: number | string | null;
  paymentTermDays: number;
  creditBlocked: boolean;
  blockOnOverdue: boolean;
  outstandingDebt: number | string;
  overdueDebt: number | string;
  availableCredit?: number | string | null;
  storeOutstandingDebt: number | string;
  storeOverdueDebt: number | string;
  openDocumentCount: number;
  overdueDocumentCount: number;
  entries: CustomerCreditAccountEntry[];
};
type Props = {
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  initialCustomerId?: string;
  request?: Request;
  printReceipt?: typeof printCustomerReceivablePaymentReceipt;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
};
const money = (value: number | string, locale: LocaleCode) => Number(value).toLocaleString(locale === "zh" ? "zh-CN" : locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dateTime = (value: string, locale: LocaleCode) => {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString(locale === "zh" ? "zh-CN" : locale);
};

export function effectiveReceivableStatus(row: Pick<CustomerReceivable, "status" | "paidTotal" | "pendingTotal">): CustomerReceivable["status"] {
  if (Number(row.pendingTotal) <= 0) return "PAGADO";
  if (Number(row.paidTotal) > 0) return "PARCIAL";
  return "PENDIENTE";
}

export function CustomerReceivablesScreen({ locale, session, terminalContext, initialCustomerId, request = apiRequest, printReceipt = printCustomerReceivablePaymentReceipt, onBack, onLocaleChange, onLogout }: Props) {
  const t = createTranslator(locale);
  const [view, setView] = useState<ReceivablesView>("OPEN");
  const [rows, setRows] = useState<CustomerReceivable[]>([]);
  const [historyRows, setHistoryRows] = useState<CustomerReceivablePaymentHistory[]>([]);
  const [account, setAccount] = useState<CustomerCreditAccount | null>(null);
  const [methods, setMethods] = useState<PaymentMethod[]>([]);
  const [search, setSearch] = useState(""); const [status, setStatus] = useState(""); const [documentType, setDocumentType] = useState("");
  const [overdue, setOverdue] = useState(false); const [dueFrom, setDueFrom] = useState(""); const [dueTo, setDueTo] = useState("");
  const [historySearch, setHistorySearch] = useState(""); const [paymentMethodId, setPaymentMethodId] = useState("");
  const [collectedFrom, setCollectedFrom] = useState(""); const [collectedTo, setCollectedTo] = useState("");
  const [loading, setLoading] = useState(false); const [error, setError] = useState(""); const [selected, setSelected] = useState<CustomerReceivable | null>(null);
  const [selectedHistory, setSelectedHistory] = useState<CustomerReceivablePaymentHistory | null>(null);
  const [receipt, setReceipt] = useState<CustomerReceivablePaymentReceiptSnapshot | null>(null);
  const [detailLoading, setDetailLoading] = useState(false); const [detailError, setDetailError] = useState(""); const [printing, setPrinting] = useState(false);
  const [retryPrint, setRetryPrint] = useState<(() => Promise<unknown>) | null>(null);
  const retryFailedPrint = async () => {
    if (!retryPrint) return;
    if (await retryPrintSucceeded(retryPrint)) setRetryPrint(null);
  };
  const loadGeneration = useRef(0);
  const detailGeneration = useRef(0);
  const canPay = hasPermission(session, "CUSTOMER_RECEIVABLES_PAY");
  const load = useCallback(async () => {
    const generation = ++loadGeneration.current;
    const query = new URLSearchParams();
    if (view === "OPEN") {
      if (initialCustomerId) query.set("customerId", initialCustomerId); if (search.trim()) query.set("search", search.trim()); if (status) query.set("status", status);
      if (documentType) query.set("documentType", documentType); if (overdue) query.set("overdue", "true"); if (dueFrom) query.set("dueFrom", dueFrom); if (dueTo) query.set("dueTo", dueTo);
    } else {
      if (initialCustomerId) query.set("customerId", initialCustomerId); if (historySearch.trim()) query.set("search", historySearch.trim());
      if (paymentMethodId) query.set("paymentMethodId", paymentMethodId); if (collectedFrom) query.set("collectedFrom", collectedFrom); if (collectedTo) query.set("collectedTo", collectedTo);
    }
    setLoading(true); setError("");
    try {
      if (view === "OPEN") {
        const result = await request<CustomerReceivable[]>(`/customer-receivables${query.size ? `?${query}` : ""}`, { token: session.accessToken });
        if (generation === loadGeneration.current) setRows(result);
      } else if (view === "HISTORY") {
        const result = await request<CustomerReceivablePaymentHistory[]>(`/customer-receivables/payment-history${query.size ? `?${query}` : ""}`, { token: session.accessToken });
        if (generation === loadGeneration.current) setHistoryRows(result);
      } else if (initialCustomerId) {
        const result = await request<CustomerCreditAccount>(`/customer-credit-accounts/${initialCustomerId}`, { token: session.accessToken });
        if (generation === loadGeneration.current) setAccount(result);
      }
    } catch (failure) {
      if (generation === loadGeneration.current) setError(failure instanceof Error ? failure.message : t(view === "OPEN" ? "receivables.error.load" : view === "HISTORY" ? "receivables.history.error" : "receivables.account.error"));
    } finally { if (generation === loadGeneration.current) setLoading(false); }
  }, [collectedFrom, collectedTo, documentType, dueFrom, dueTo, historySearch, initialCustomerId, overdue, paymentMethodId, request, search, session.accessToken, status, view]);
  useEffect(() => { void load(); return () => { loadGeneration.current += 1; }; }, [load]);
  useEffect(() => {
    if (view !== "HISTORY" || methods.length > 0) return;
    let mounted = true;
    request<PaymentMethod[]>("/payment-methods", { token: session.accessToken })
      .then((result) => { if (mounted) setMethods(result.filter((method) => method.active !== false)); })
      .catch(() => { if (mounted) setMethods([]); });
    return () => { mounted = false; };
  }, [methods.length, request, session.accessToken, view]);
  useEffect(() => () => { detailGeneration.current += 1; }, []);

  const consultHistory = async (row: CustomerReceivablePaymentHistory) => {
    const generation = ++detailGeneration.current;
    setSelectedHistory(row); setReceipt(null); setDetailError(""); setDetailLoading(true);
    try {
      const result = await request<CustomerReceivablePaymentReceiptSnapshot>(`/customer-receivables/${row.documentId}/payments/${row.paymentId}/print`, { token: session.accessToken });
      if (generation === detailGeneration.current) setReceipt(result);
    } catch (failure) {
      if (generation === detailGeneration.current) setDetailError(failure instanceof Error ? failure.message : t("receivables.history.detailError"));
    } finally { if (generation === detailGeneration.current) setDetailLoading(false); }
  };
  const closeHistory = () => { detailGeneration.current += 1; setSelectedHistory(null); setReceipt(null); setDetailError(""); setDetailLoading(false); };
  const reprintReceipt = async () => {
    if (!receipt || printing) return;
    const operation = () => printReceipt(receipt, terminalContext, undefined, locale);
    setPrinting(true);
    try {
      const result = await operation();
      setRetryPrint(result.status === "PRINTED" || result.status === "SKIPPED" ? null : () => operation);
    } catch { setRetryPrint(() => operation); }
    finally { setPrinting(false); }
  };

  const columns = ["document", "customer", "issueDate", "dueDate", "total", "paid", "pending", "status", "actions"];
  const historyColumns = ["document", "customer", "collectedAt", "method", "amount", "reference", "actions"];
  const statusKey = (value: CustomerReceivable["status"]) => value === "PENDIENTE"
    ? "receivables.status.pending" : value === "PARCIAL" ? "receivables.status.partial" : "receivables.status.paid";

  return <main className="customer-receivables-screen">
    <header className="entry-topbar"><strong>{t("receivables.title").toLocaleUpperCase(locale)}</strong></header>
    <SessionTopControls locale={locale} session={session} languageLabel={t("common.language")} shutdownLabel={t("common.close")} changePasswordLabel={t("auth.changePassword")} logoutLabel={t("auth.logout")} shutdownConfirmTitle={t("shutdown.title")} shutdownConfirmText={t("shutdown.message")} noLabel={t("common.no")} yesLabel={t("common.yes")} onLocaleChange={onLocaleChange} onLogout={onLogout} />
    <section className="customer-receivables-panel">
      <header><div><h1>{t(view === "OPEN" ? "receivables.title" : view === "HISTORY" ? "receivables.history.title" : "receivables.account.title")}</h1><p>{t(view === "OPEN" ? "receivables.subtitle" : view === "HISTORY" ? "receivables.history.subtitle" : "receivables.account.subtitle")}</p></div><button type="button" className="receivables-back-button" onClick={onBack}>{t("common.back")}</button></header>
      <nav className="receivables-view-tabs" aria-label={t("receivables.view.label")}>
        <button type="button" aria-pressed={view === "OPEN"} onClick={() => setView("OPEN")}>{t("receivables.view.open")}</button>
        <button type="button" aria-pressed={view === "HISTORY"} onClick={() => setView("HISTORY")}>{t("receivables.view.history")}</button>
        {initialCustomerId && <button type="button" aria-pressed={view === "ACCOUNT"} onClick={() => setView("ACCOUNT")}>{t("receivables.view.account")}</button>}
      </nav>
      {view === "OPEN" ? <>
        <div className="customer-receivables-filters">
          <label>{t("receivables.search")}<input value={search} onChange={(event) => setSearch(event.target.value)} /></label>
          <label>{t("receivables.status")}<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">{t("receivables.all")}</option><option value="PENDIENTE">{t("receivables.status.pending")}</option><option value="PARCIAL">{t("receivables.status.partial")}</option><option value="PAGADO">{t("receivables.status.paid")}</option></select></label>
          <label>{t("receivables.documentType")}<select value={documentType} onChange={(event) => setDocumentType(event.target.value)}><option value="">{t("receivables.all")}</option><option value="ALBARAN_VENTA">{t("receivables.type.deliveryNote")}</option><option value="FACTURA_VENTA">{t("receivables.type.invoice")}</option></select></label>
          <label className="receivables-checkbox"><input aria-label={t("receivables.overdueOnly")} type="checkbox" checked={overdue} onChange={(event) => setOverdue(event.target.checked)} />{t("receivables.overdueOnly")}</label>
          <label>{t("receivables.dueFrom")}<input type="date" value={dueFrom} onChange={(event) => setDueFrom(event.target.value)} /></label>
          <label>{t("receivables.dueTo")}<input type="date" value={dueTo} onChange={(event) => setDueTo(event.target.value)} /></label>
        </div>
      </> : view === "HISTORY" ? <div className="customer-receivables-filters receivables-history-filters">
        <label>{t("receivables.history.search")}<input value={historySearch} onChange={(event) => setHistorySearch(event.target.value)} /></label>
        <label>{t("receivables.history.method")}<select value={paymentMethodId} onChange={(event) => setPaymentMethodId(event.target.value)}><option value="">{t("receivables.all")}</option>{methods.map((method) => <option key={method.id} value={method.id}>{method.name ?? method.nombre ?? method.id}</option>)}</select></label>
        <label>{t("receivables.history.collectedFrom")}<input type="date" value={collectedFrom} onChange={(event) => setCollectedFrom(event.target.value)} /></label>
        <label>{t("receivables.history.collectedTo")}<input type="date" value={collectedTo} onChange={(event) => setCollectedTo(event.target.value)} /></label>
      </div> : null}
      {error && <div className="receivables-error"><p role="alert">{error}</p><button type="button" onClick={() => void load()}>{t("receivables.action.retry")}</button></div>}
      {retryPrint && <div className="receivables-error"><p role="alert">{t("payment.result.printFailed")}</p><button type="button" onClick={() => void retryFailedPrint()}>{t("payment.result.retryPrint")}</button></div>}
      {view === "OPEN" ? <div className="customer-receivables-table" role="table" aria-label={t("receivables.title")}>
        <div role="row" className="receivable-row header">{columns.map((value) => <span role="columnheader" key={value}>{t(`receivables.column.${value}`)}</span>)}</div>
        {loading && <p>{t("common.loading")}</p>}
        {!loading && rows.map((row) => <div role="row" className={`receivable-row${row.overdue ? " overdue" : ""}`} key={row.documentId}>
          <strong role="cell">{row.documentNumber}</strong><span role="cell">{row.customerName}</span><span role="cell">{row.issueDate}</span><span role="cell">{row.dueDate || "-"}</span><span role="cell">{money(row.total, locale)}</span><span role="cell">{money(row.paidTotal, locale)}</span><span role="cell">{money(row.pendingTotal, locale)}</span><span role="cell">{t(statusKey(effectiveReceivableStatus(row)))}</span>
          <span role="cell"><button type="button" aria-label={`${t("receivables.action.collect")} ${row.documentNumber}`} disabled={!canPay || effectiveReceivableStatus(row) === "PAGADO"} onClick={() => setSelected(row)}>{t("receivables.action.collect")}</button></span>
        </div>)}
      </div> : view === "HISTORY" ? <div className="customer-receivables-table" role="table" aria-label={t("receivables.history.title")}>
        <div role="row" className="receivable-history-row header">{historyColumns.map((value) => <span role="columnheader" key={value}>{t(`receivables.column.${value}`)}</span>)}</div>
        {loading && <p>{t("common.loading")}</p>}
        {!loading && historyRows.length === 0 && <p className="receivables-empty">{t("receivables.history.empty")}</p>}
        {!loading && historyRows.map((row) => <div role="row" className="receivable-history-row" key={row.paymentId}>
          <strong role="cell">{row.documentNumber}</strong><span role="cell">{row.customerName}</span><span role="cell">{dateTime(row.collectedAt, locale)}</span><span role="cell">{row.paymentMethodName}</span><span role="cell">{money(row.amount, locale)}</span><span role="cell">{row.reference || "-"}</span>
          <span role="cell"><button type="button" aria-label={`${t("receivables.action.consult")} ${row.documentNumber}`} onClick={() => void consultHistory(row)}>{t("receivables.action.consult")}</button></span>
        </div>)}
      </div> : <div className="receivables-account" aria-label={t("receivables.account.title")}>
        {loading && <p>{t("common.loading")}</p>}
        {!loading && account && <>
          <section className="receivables-account-summary" aria-label={t("receivables.account.summary")}>
            <article><span>{t("receivables.account.outstanding")}</span><strong>{money(account.outstandingDebt, locale)}</strong></article>
            <article className={Number(account.overdueDebt) > 0 ? "warning" : ""}><span>{t("receivables.account.overdue")}</span><strong>{money(account.overdueDebt, locale)}</strong></article>
            <article><span>{t("receivables.account.limit")}</span><strong>{account.creditLimit == null ? t("party.credit.unlimited") : money(account.creditLimit, locale)}</strong></article>
            <article><span>{t("receivables.account.available")}</span><strong>{account.availableCredit == null ? t("party.credit.unlimited") : money(account.availableCredit, locale)}</strong></article>
            <article><span>{t("receivables.account.term")}</span><strong>{account.paymentTermDays}</strong></article>
            <article><span>{t("receivables.account.openDocuments")}</span><strong>{account.openDocumentCount}</strong></article>
          </section>
          {(account.creditBlocked || !account.creditEnabled || (account.blockOnOverdue && Number(account.overdueDebt) > 0)) && <p className="receivables-account-blocked" role="status">{t("receivables.account.blocked")}</p>}
          <div className="customer-receivables-table" role="table" aria-label={t("receivables.account.statement")}>
            <div role="row" className="receivable-account-row header"><span role="columnheader">{t("receivables.account.date")}</span><span role="columnheader">{t("receivables.column.document")}</span><span role="columnheader">{t("receivables.account.concept")}</span><span role="columnheader">{t("receivables.account.debit")}</span><span role="columnheader">{t("receivables.account.credit")}</span><span role="columnheader">{t("receivables.account.balance")}</span></div>
            {account.entries.length === 0 && <p className="receivables-empty">{t("receivables.account.empty")}</p>}
            {account.entries.map((entry) => <div role="row" className="receivable-account-row" key={`${entry.kind}-${entry.id}`}><span role="cell">{dateTime(entry.occurredAt, locale)}</span><strong role="cell">{entry.documentNumber}</strong><span role="cell">{entry.kind === "SALE" ? t("receivables.account.sale") : entry.paymentMethod || t("receivables.account.payment")}</span><span role="cell">{Number(entry.debit) > 0 ? money(entry.debit, locale) : "-"}</span><span role="cell">{Number(entry.credit) > 0 ? money(entry.credit, locale) : "-"}</span><strong role="cell">{money(entry.balance, locale)}</strong></div>)}
          </div>
        </>}
      </div>}
    </section>
    <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    {selected && <CustomerReceivablePaymentDialog locale={locale} receivable={selected} token={session.accessToken} terminalCode={terminalContext.terminalCode} terminalContext={terminalContext} request={request} printReceipt={printReceipt} onCancel={() => setSelected(null)} onPaid={(updated, retry) => { setRows((current) => current.map((row) => row.documentId === updated.documentId ? updated : row)); setRetryPrint(() => retry ?? null); setSelected(null); void load(); }} />}
    {selectedHistory && <div className="sale-action-overlay" role="presentation">
      <section className="customer-receivable-payment-dialog receivable-history-dialog" role="dialog" aria-modal="true" aria-labelledby="receivable-history-title">
        <header><h2 id="receivable-history-title">{t("receivables.history.detailTitle")}</h2><button type="button" aria-label={t("common.close")} disabled={printing} onClick={closeHistory}>×</button></header>
        <dl><div><dt>{t("receivables.column.document")}</dt><dd>{selectedHistory.documentNumber}</dd></div><div><dt>{t("receivables.column.customer")}</dt><dd>{selectedHistory.customerName}</dd></div><div><dt>{t("receivables.column.collectedAt")}</dt><dd>{dateTime(selectedHistory.collectedAt, locale)}</dd></div><div><dt>{t("receivables.column.method")}</dt><dd>{selectedHistory.paymentMethodName}</dd></div><div><dt>{t("receivables.column.amount")}</dt><dd>{money(selectedHistory.amount, locale)}</dd></div><div><dt>{t("receivables.column.reference")}</dt><dd>{selectedHistory.reference || "-"}</dd></div>{receipt && <div><dt>{t("receivables.column.pending")}</dt><dd>{money(receipt.remaining, locale)}</dd></div>}</dl>
        {detailLoading && <p>{t("common.loading")}</p>}
        {detailError && <p className="sale-action-error" role="alert">{detailError}</p>}
        <footer><button type="button" disabled={printing} onClick={closeHistory}>{t("common.close")}</button><button type="button" disabled={!receipt || detailLoading || printing} onClick={() => void reprintReceipt()}>{printing ? t("receivables.history.printing") : t("receivables.action.reprint")}</button></footer>
      </section>
    </div>}
  </main>;
}
