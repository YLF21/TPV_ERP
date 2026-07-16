import { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { CashPaymentDialog } from "./CashPaymentDialog";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

export type CustomerReceivable = {
  documentId: string; documentType: "ALBARAN_VENTA" | "FACTURA_VENTA"; documentNumber: string;
  customerId: string; customerName: string; issueDate: string; dueDate?: string | null;
  total: number | string; paidTotal: number | string; pendingTotal: number | string;
  status: "PENDIENTE" | "PARCIAL" | "PAGADO"; overdue: boolean;
};

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type Props = { receivable: CustomerReceivable; token?: string; terminalCode: string; request?: Request; onCancel: () => void; onPaid: (value: CustomerReceivable) => void };
type Method = { id: string; name?: string; nombre?: string; active?: boolean };
type Attempt = { paymentId: string; amount: string; methodId: string; status: "CREATED" | "PENDING" | "SENT" | "TIMEOUT" | "APPROVED" | "DECLINED" | "ERROR" | "CANCELLED" };
type StandardAttempt = { requestId: string; kind: "cash" | "transfer"; item: Record<string, unknown> };

const uuid = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
export const receivablePaymentAttemptKey = (terminalCode: string, documentId: string) => `tpverp.receivable.${terminalCode}.${documentId}.card-attempt`;
const decimal = (value: number | string) => Number(value).toFixed(2);
const cents = (value: string) => Math.round(Number(value.replace(",", ".")) * 100);

export function CustomerReceivablePaymentDialog({ receivable, token, terminalCode, request = apiRequest, onCancel, onPaid }: Props) {
  const pendingCents = Math.round(Number(receivable.pendingTotal) * 100);
  const [amount, setAmount] = useState(decimal(receivable.pendingTotal));
  const [methods, setMethods] = useState<{ cash?: string; card?: string; transfer?: string }>({});
  const [cashOpen, setCashOpen] = useState(false);
  const [transferOpen, setTransferOpen] = useState(false);
  const [reference, setReference] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const mounted = useRef(true);
  const dialogRef = useRef<HTMLElement>(null);
  const storageKey = receivablePaymentAttemptKey(terminalCode, receivable.documentId);
  const standardKey = `${storageKey}.standard`;
  const [standardAttempt, setStandardAttempt] = useState<StandardAttempt | null>(() => { try { const value = localStorage.getItem(standardKey); return value ? JSON.parse(value) : null; } catch { return null; } });
  const [cardAttempt, setCardAttempt] = useState<Attempt | null>(() => {
    try { const stored = globalThis.localStorage?.getItem(storageKey); return stored ? JSON.parse(stored) as Attempt : null; }
    catch { return null; }
  });
  const amountCents = useMemo(() => cents(amount), [amount]);
  const collectable = receivable.status !== "PAGADO" && pendingCents > 0;
  const unsafeCard = cardAttempt != null && ["CREATED", "PENDING", "SENT", "TIMEOUT", "APPROVED"].includes(cardAttempt.status);

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => dialogRef.current ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document) : undefined, []);
  useEffect(() => {
    let current = true;
    request<Method[]>("/payment-methods", { token }).then((rows) => {
      const active = rows.filter((row) => row.active !== false);
      const find = (name: string) => active.find((row) => (row.name ?? row.nombre)?.toUpperCase() === name)?.id;
      if (current && mounted.current) setMethods({ cash: find("EFECTIVO"), card: find("TARJETA"), transfer: find("TRANSFERENCIA") });
    }).catch((failure) => { if (current && mounted.current) setError(failure instanceof Error ? failure.message : "No se pudieron cargar las formas de pago"); });
    return () => { current = false; };
  }, [request, token]);

  function validate(): boolean {
    if (!collectable) { setError("Este documento ya esta pagado"); return false; }
    if (!Number.isFinite(amountCents) || amountCents <= 0) { setError("El importe debe ser mayor que cero"); return false; }
    if (amountCents > pendingCents) { setError("El importe no puede superar el saldo pendiente"); return false; }
    setError(""); return true;
  }

  async function postPayment(item: Record<string, unknown>) {
    return request<CustomerReceivable>(`/customer-receivables/${receivable.documentId}/payments`, { token, body: { pagos: [item] } });
  }

  async function payStandard(kind: "cash" | "transfer", receivedCents?: number) {
    if (!standardAttempt && !validate()) return;
    const methodId = standardAttempt ? String(standardAttempt.item.metodoPagoId) : methods[kind]; if (!methodId) { setError("Forma de pago no configurada"); return; }
    if (!standardAttempt && kind === "transfer" && !reference.trim()) { setError("La referencia de transferencia es obligatoria"); return; }
    setBusy(true);
    try {
      const requestId = standardAttempt?.requestId ?? uuid();
      const item = standardAttempt?.item ?? { metodoPagoId: methodId, importe: (amountCents / 100).toFixed(2), principal: true,
        entregado: kind === "cash" ? ((receivedCents ?? amountCents) / 100).toFixed(2) : null,
        cambio: kind === "cash" ? (((receivedCents ?? amountCents) - amountCents) / 100).toFixed(2) : null,
        reference: kind === "transfer" ? reference.trim() : null, requestId };
      const attempt = standardAttempt ?? { requestId, kind, item }; localStorage.setItem(standardKey, JSON.stringify(attempt)); setStandardAttempt(attempt);
      const result = await postPayment(item);
      localStorage.removeItem(standardKey); setStandardAttempt(null);
      setCashOpen(false); setTransferOpen(false);
      setBusy(false);
      if (mounted.current) onPaid(result);
      return;
    } catch (failure) { if (mounted.current) { setError(failure instanceof Error ? failure.message : "No se pudo registrar el cobro"); setBusy(false); } }
  }

  async function finishApprovedCard(attempt: Attempt) {
    const result = await postPayment({ metodoPagoId: attempt.methodId, importe: attempt.amount, principal: true,
      cardMode: "INTEGRATED", paymentTerminalStatus: "APPROVED", requestId: attempt.paymentId,
      paymentTerminalOperationId: attempt.paymentId });
    globalThis.localStorage?.removeItem(storageKey);
    setCardAttempt(null);
    setBusy(false);
    if (mounted.current) onPaid(result);
  }

  async function payCard() {
    if (!validate()) return;
    if (!methods.card) { setError("Forma de pago no configurada"); return; }
    setBusy(true); setError("");
    let attempt: Attempt;
    try {
      const stored = globalThis.localStorage?.getItem(storageKey);
      attempt = stored ? JSON.parse(stored) as Attempt : { paymentId: uuid(), amount: (amountCents / 100).toFixed(2), methodId: methods.card, status: "CREATED" };
      if (attempt.amount !== (amountCents / 100).toFixed(2) || attempt.methodId !== methods.card) {
        setError("Hay un intento de tarjeta pendiente con otro importe"); setBusy(false); return;
      }
      globalThis.localStorage?.setItem(storageKey, JSON.stringify(attempt));
      setCardAttempt(attempt);
      if (attempt.status !== "APPROVED") {
        const terminal = await request<{ status: string }>(`/customer-receivables/${receivable.documentId}/card-charges`, { token, body: { paymentId: attempt.paymentId, amount: attempt.amount } });
        attempt = { ...attempt, status: terminal.status as Attempt["status"] };
        globalThis.localStorage?.setItem(storageKey, JSON.stringify(attempt));
        setCardAttempt(attempt);
        if (terminal.status !== "APPROVED") { setError(`Tarjeta ${terminal.status}. Consulte el estado antes de reintentar.`); setBusy(false); return; }
      }
      await finishApprovedCard(attempt); return;
    } catch (failure) { if (mounted.current) { setError(failure instanceof Error ? `${failure.message}. Reintente la confirmacion con el mismo identificador.` : "Resultado de tarjeta incierto"); setBusy(false); } }
  }

  async function queryCard() {
    if (!cardAttempt) return;
    setBusy(true); setError("");
    try {
      const terminal = await request<{ status: string }>(`/payment-terminal/operations/${cardAttempt.paymentId}/query`, { method: "POST", token });
      const next = { ...cardAttempt, status: terminal.status as Attempt["status"] };
      globalThis.localStorage?.setItem(storageKey, JSON.stringify(next)); setCardAttempt(next);
      if (next.status === "APPROVED") { await finishApprovedCard(next); return; }
      else setError(`Tarjeta ${next.status}. La operacion aun no se puede registrar.`);
    } catch (failure) { if (mounted.current) setError(failure instanceof Error ? failure.message : "No se pudo consultar la tarjeta"); }
    if (mounted.current) setBusy(false);
  }

  useEffect(() => { const handler = (event: KeyboardEvent) => { if (event.key !== "Escape" || busy) return; if (cashOpen) return; if (transferOpen) { event.preventDefault(); event.stopImmediatePropagation(); setTransferOpen(false); return; } if (!unsafeCard) { event.preventDefault(); onCancel(); } }; window.addEventListener("keydown", handler); return () => window.removeEventListener("keydown", handler); }, [busy, cashOpen, onCancel, transferOpen, unsafeCard]);

  return <div className="sale-action-overlay" role="presentation">
    <section ref={dialogRef} className="customer-receivable-payment-dialog" role="dialog" aria-modal="true" aria-labelledby="receivable-payment-title">
      <header><h2 id="receivable-payment-title">Cobrar deuda</h2><button type="button" aria-label="Cerrar" disabled={busy || unsafeCard} onClick={onCancel}>脳</button></header>
      <p><strong>{receivable.documentNumber}</strong> 路 {receivable.customerName}</p>
      <div className="receivable-payment-summary"><span>Saldo pendiente</span><strong>{decimal(receivable.pendingTotal)}</strong></div>
      <label>Importe a cobrar<input aria-label="Importe a cobrar" inputMode="decimal" value={amount} disabled={!collectable || busy || standardAttempt != null || unsafeCard} onChange={(event) => setAmount(event.target.value)} /></label>
      {!collectable && <p>Este documento ya esta pagado</p>}
      <div className="receivable-payment-actions">
        <button type="button" disabled={!collectable || busy || unsafeCard || standardAttempt != null || !methods.cash} onClick={() => { if (validate()) setCashOpen(true); }}>Efectivo</button>
        <button type="button" disabled={!collectable || busy || unsafeCard || standardAttempt != null || !methods.card} onClick={() => void payCard()}>Tarjeta</button>
        <button type="button" disabled={!collectable || busy || unsafeCard || standardAttempt != null || !methods.transfer} onClick={() => { if (validate()) setTransferOpen(true); }}>Transferencia</button>
      </div>
      {transferOpen && <fieldset><legend>Transferencia</legend><label>Referencia<input aria-label="Referencia" autoFocus value={reference} onChange={(event) => setReference(event.target.value)} /></label><button type="button" disabled={busy} onClick={() => void payStandard("transfer")}>Confirmar transferencia</button><button type="button" disabled={busy} onClick={() => setTransferOpen(false)}>Cancelar transferencia</button></fieldset>}
      {cardAttempt && <div className="receivable-card-recovery" aria-live="polite"><span>Estado tarjeta: {cardAttempt.status}</span><button type="button" disabled={busy} onClick={() => void queryCard()}>Consultar estado de tarjeta</button>{cardAttempt.status === "APPROVED" && <button type="button" disabled={busy} onClick={() => void payCard()}>Reintentar confirmacion de tarjeta</button>}</div>}
      {cardAttempt && ["DECLINED", "ERROR", "CANCELLED"].includes(cardAttempt.status) && <button type="button" onClick={() => { localStorage.removeItem(storageKey); setCardAttempt(null); }}>Descartar intento rechazado</button>}
      {standardAttempt && <div aria-live="polite"><p>{standardAttempt.kind === "cash" ? "Efectivo" : "Transferencia"} 路 {String(standardAttempt.item.importe)}{standardAttempt.item.reference ? ` 路 ${String(standardAttempt.item.reference)}` : ""}. Resultado desconocido: no se puede descartar hasta confirmar el backend.</p><button type="button" disabled={busy} onClick={() => void payStandard(standardAttempt.kind)}>Reintentar cobro</button></div>}
      {error && <p className="sale-action-error" role="alert">{error}</p>}
      <footer><button type="button" disabled={busy || unsafeCard} onClick={onCancel}>Cerrar</button></footer>
    </section>
    {cashOpen && <CashPaymentDialog totalCents={amountCents} submitting={busy} error={error} initialMode="touch" onCancel={() => setCashOpen(false)} onConfirm={(received) => void payStandard("cash", received)} />}
  </div>;
}
