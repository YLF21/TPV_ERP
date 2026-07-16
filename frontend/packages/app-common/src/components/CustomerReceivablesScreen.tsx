import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { hasPermission } from "../auth/auth";
import type { LocaleCode, TerminalContext, UserSession } from "../types";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { CustomerReceivablePaymentDialog, type CustomerReceivable } from "./CustomerReceivablePaymentDialog";

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type Props = { locale: LocaleCode; session: UserSession; terminalContext: TerminalContext; initialCustomerId?: string; request?: Request; onBack: () => void; onLocaleChange: (locale: LocaleCode) => void; onLogout?: () => void };

const money = (value: number | string, locale: LocaleCode) => Number(value).toLocaleString(locale === "zh" ? "zh-CN" : locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function CustomerReceivablesScreen({ locale, session, terminalContext, initialCustomerId, request = apiRequest, onBack, onLocaleChange, onLogout }: Props) {
  const [rows, setRows] = useState<CustomerReceivable[]>([]);
  const [search, setSearch] = useState(""); const [status, setStatus] = useState(""); const [documentType, setDocumentType] = useState("");
  const [overdue, setOverdue] = useState(false); const [dueFrom, setDueFrom] = useState(""); const [dueTo, setDueTo] = useState("");
  const [loading, setLoading] = useState(false); const [error, setError] = useState(""); const [selected, setSelected] = useState<CustomerReceivable | null>(null);
  const loadGeneration = useRef(0);
  const canPay = hasPermission(session, "CUSTOMER_RECEIVABLES_PAY");

  const load = useCallback(async () => {
    const generation = ++loadGeneration.current;
    const query = new URLSearchParams();
    if (initialCustomerId) query.set("customerId", initialCustomerId); if (search.trim()) query.set("search", search.trim()); if (status) query.set("status", status);
    if (documentType) query.set("documentType", documentType); if (overdue) query.set("overdue", "true"); if (dueFrom) query.set("dueFrom", dueFrom); if (dueTo) query.set("dueTo", dueTo);
    setLoading(true); setError("");
    try { const result = await request<CustomerReceivable[]>(`/customer-receivables${query.size ? `?${query}` : ""}`, { token: session.accessToken }); if (generation === loadGeneration.current) setRows(result); }
    catch (failure) { if (generation === loadGeneration.current) setError(failure instanceof Error ? failure.message : "No se pudieron cargar las deudas"); }
    finally { if (generation === loadGeneration.current) setLoading(false); }
  }, [documentType, dueFrom, dueTo, initialCustomerId, overdue, request, search, session.accessToken, status]);

  useEffect(() => { void load(); return () => { loadGeneration.current += 1; }; }, [load]);

  return <main className="customer-receivables-screen">
    <header className="entry-topbar"><strong>DEUDAS DE CLIENTES</strong></header>
    <SessionTopControls locale={locale} session={session} languageLabel="Idioma" shutdownLabel="Cerrar" changePasswordLabel="Cambiar contrase帽a" logoutLabel="Cerrar usuario" shutdownConfirmTitle="Cerrar aplicacion" shutdownConfirmText="驴Quieres cerrar la aplicacion?" noLabel="No" yesLabel="Si" onLocaleChange={onLocaleChange} onLogout={onLogout} />
    <section className="customer-receivables-panel">
      <header><div><h1>Deudas de clientes</h1><p>Consulta y cobro de albaranes y facturas pendientes</p></div><button type="button" onClick={onBack}>Volver</button></header>
      <div className="customer-receivables-filters">
        <label>Buscar deuda<input value={search} onChange={(event) => setSearch(event.target.value)} /></label>
        <label>Estado<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">Todos</option><option value="PENDIENTE">Pendiente</option><option value="PARCIAL">Parcial</option><option value="PAGADO">Pagado</option></select></label>
        <label>Tipo de documento<select value={documentType} onChange={(event) => setDocumentType(event.target.value)}><option value="">Todos</option><option value="ALBARAN_VENTA">Albaran</option><option value="FACTURA_VENTA">Factura</option></select></label>
        <label className="receivables-checkbox"><input aria-label="Solo vencidos" type="checkbox" checked={overdue} onChange={(event) => setOverdue(event.target.checked)} />Solo vencidos</label>
        <label>Vencimiento desde<input type="date" value={dueFrom} onChange={(event) => setDueFrom(event.target.value)} /></label>
        <label>Vencimiento hasta<input type="date" value={dueTo} onChange={(event) => setDueTo(event.target.value)} /></label>
      </div>
      {error && <div className="receivables-error"><p role="alert">{error}</p><button type="button" onClick={() => void load()}>Reintentar</button></div>}
      <div className="customer-receivables-table" role="table" aria-label="Deudas de clientes">
        <div role="row" className="receivable-row header">{["Documento", "Cliente", "Emision", "Vencimiento", "Total", "Pagado", "Pendiente", "Estado", "Acciones"].map((value) => <span role="columnheader" key={value}>{value}</span>)}</div>
        {loading && <p>Cargando...</p>}
        {!loading && rows.map((row) => <div role="row" className={`receivable-row${row.overdue ? " overdue" : ""}`} key={row.documentId}>
          <strong role="cell">{row.documentNumber}</strong><span role="cell">{row.customerName}</span><span role="cell">{row.issueDate}</span><span role="cell">{row.dueDate || "-"}</span><span role="cell">{money(row.total, locale)}</span><span role="cell">{money(row.paidTotal, locale)}</span><span role="cell">{money(row.pendingTotal, locale)}</span><span role="cell">{row.status}</span>
          <span role="cell"><button type="button" aria-label={`Cobrar ${row.documentNumber}`} disabled={!canPay || Number(row.pendingTotal) <= 0 || row.status === "PAGADO"} onClick={() => setSelected(row)}>Cobrar</button></span>
        </div>)}
      </div>
    </section>
    <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    {selected && <CustomerReceivablePaymentDialog receivable={selected} token={session.accessToken} terminalCode={terminalContext.terminalCode} request={request} onCancel={() => setSelected(null)} onPaid={(updated) => { setRows((current) => current.map((row) => row.documentId === updated.documentId ? updated : row)); setSelected(null); void load(); }} />}
  </main>;
}
