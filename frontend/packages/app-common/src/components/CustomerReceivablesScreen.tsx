import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext, UserSession } from "../types";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { CustomerReceivablePaymentDialog, type CustomerReceivable } from "./CustomerReceivablePaymentDialog";
import { retryPrintSucceeded } from "../sale/printRetry";

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type Props = { locale: LocaleCode; session: UserSession; terminalContext: TerminalContext; initialCustomerId?: string; request?: Request; onBack: () => void; onLocaleChange: (locale: LocaleCode) => void; onLogout?: () => void };
const money = (value: number | string, locale: LocaleCode) => Number(value).toLocaleString(locale === "zh" ? "zh-CN" : locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function CustomerReceivablesScreen({ locale, session, terminalContext, initialCustomerId, request = apiRequest, onBack, onLocaleChange, onLogout }: Props) {
  const t = createTranslator(locale);
  const [rows, setRows] = useState<CustomerReceivable[]>([]);
  const [search, setSearch] = useState(""); const [status, setStatus] = useState(""); const [documentType, setDocumentType] = useState("");
  const [overdue, setOverdue] = useState(false); const [dueFrom, setDueFrom] = useState(""); const [dueTo, setDueTo] = useState("");
  const [loading, setLoading] = useState(false); const [error, setError] = useState(""); const [selected, setSelected] = useState<CustomerReceivable | null>(null);
  const [retryPrint, setRetryPrint] = useState<(() => Promise<unknown>) | null>(null);
  const retryFailedPrint = async () => {
    if (!retryPrint) return;
    if (await retryPrintSucceeded(retryPrint)) setRetryPrint(null);
  };
  const loadGeneration = useRef(0);
  const canPay = hasPermission(session, "CUSTOMER_RECEIVABLES_PAY");
  const load = useCallback(async () => {
    const generation = ++loadGeneration.current;
    const query = new URLSearchParams();
    if (initialCustomerId) query.set("customerId", initialCustomerId); if (search.trim()) query.set("search", search.trim()); if (status) query.set("status", status);
    if (documentType) query.set("documentType", documentType); if (overdue) query.set("overdue", "true"); if (dueFrom) query.set("dueFrom", dueFrom); if (dueTo) query.set("dueTo", dueTo);
    setLoading(true); setError("");
    try { const result = await request<CustomerReceivable[]>(`/customer-receivables${query.size ? `?${query}` : ""}`, { token: session.accessToken }); if (generation === loadGeneration.current) setRows(result); }
    catch (failure) { if (generation === loadGeneration.current) setError(failure instanceof Error ? failure.message : t("receivables.error.load")); }
    finally { if (generation === loadGeneration.current) setLoading(false); }
  }, [documentType, dueFrom, dueTo, initialCustomerId, overdue, request, search, session.accessToken, status]);
  useEffect(() => { void load(); return () => { loadGeneration.current += 1; }; }, [load]);
  const columns = ["document", "customer", "issueDate", "dueDate", "total", "paid", "pending", "status", "actions"];
  const statusKey = (value: CustomerReceivable["status"]) => value === "PENDIENTE"
    ? "receivables.status.pending" : value === "PARCIAL" ? "receivables.status.partial" : "receivables.status.paid";

  return <main className="customer-receivables-screen">
    <header className="entry-topbar"><strong>{t("receivables.title").toLocaleUpperCase(locale)}</strong></header>
    <SessionTopControls locale={locale} session={session} languageLabel={t("common.language")} shutdownLabel={t("common.close")} changePasswordLabel={t("auth.changePassword")} logoutLabel={t("auth.logout")} shutdownConfirmTitle={t("shutdown.title")} shutdownConfirmText={t("shutdown.message")} noLabel={t("common.no")} yesLabel={t("common.yes")} onLocaleChange={onLocaleChange} onLogout={onLogout} />
    <section className="customer-receivables-panel">
      <header><div><h1>{t("receivables.title")}</h1><p>{t("receivables.subtitle")}</p></div><button type="button" onClick={onBack}>{t("common.back")}</button></header>
      <div className="customer-receivables-filters">
        <label>{t("receivables.search")}<input value={search} onChange={(event) => setSearch(event.target.value)} /></label>
        <label>{t("receivables.status")}<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">{t("receivables.all")}</option><option value="PENDIENTE">{t("receivables.status.pending")}</option><option value="PARCIAL">{t("receivables.status.partial")}</option><option value="PAGADO">{t("receivables.status.paid")}</option></select></label>
        <label>{t("receivables.documentType")}<select value={documentType} onChange={(event) => setDocumentType(event.target.value)}><option value="">{t("receivables.all")}</option><option value="ALBARAN_VENTA">{t("receivables.type.deliveryNote")}</option><option value="FACTURA_VENTA">{t("receivables.type.invoice")}</option></select></label>
        <label className="receivables-checkbox"><input aria-label={t("receivables.overdueOnly")} type="checkbox" checked={overdue} onChange={(event) => setOverdue(event.target.checked)} />{t("receivables.overdueOnly")}</label>
        <label>{t("receivables.dueFrom")}<input type="date" value={dueFrom} onChange={(event) => setDueFrom(event.target.value)} /></label>
        <label>{t("receivables.dueTo")}<input type="date" value={dueTo} onChange={(event) => setDueTo(event.target.value)} /></label>
      </div>
      {error && <div className="receivables-error"><p role="alert">{error}</p><button type="button" onClick={() => void load()}>{t("receivables.action.retry")}</button></div>}
      {retryPrint && <div className="receivables-error"><p role="alert">{t("payment.result.printFailed")}</p><button type="button" onClick={() => void retryFailedPrint()}>{t("payment.result.retryPrint")}</button></div>}
      <div className="customer-receivables-table" role="table" aria-label={t("receivables.title")}>
        <div role="row" className="receivable-row header">{columns.map((value) => <span role="columnheader" key={value}>{t(`receivables.column.${value}`)}</span>)}</div>
        {loading && <p>{t("common.loading")}</p>}
        {!loading && rows.map((row) => <div role="row" className={`receivable-row${row.overdue ? " overdue" : ""}`} key={row.documentId}>
          <strong role="cell">{row.documentNumber}</strong><span role="cell">{row.customerName}</span><span role="cell">{row.issueDate}</span><span role="cell">{row.dueDate || "-"}</span><span role="cell">{money(row.total, locale)}</span><span role="cell">{money(row.paidTotal, locale)}</span><span role="cell">{money(row.pendingTotal, locale)}</span><span role="cell">{t(statusKey(row.status))}</span>
          <span role="cell"><button type="button" aria-label={`${t("receivables.action.collect")} ${row.documentNumber}`} disabled={!canPay || Number(row.pendingTotal) <= 0 || row.status === "PAGADO"} onClick={() => setSelected(row)}>{t("receivables.action.collect")}</button></span>
        </div>)}
      </div>
    </section>
    <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    {selected && <CustomerReceivablePaymentDialog locale={locale} receivable={selected} token={session.accessToken} terminalCode={terminalContext.terminalCode} terminalContext={terminalContext} request={request} onCancel={() => setSelected(null)} onPaid={(updated, retry) => { setRows((current) => current.map((row) => row.documentId === updated.documentId ? updated : row)); setRetryPrint(() => retry ?? null); setSelected(null); void load(); }} />}
  </main>;
}
