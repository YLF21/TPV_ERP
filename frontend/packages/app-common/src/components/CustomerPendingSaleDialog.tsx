import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import {
  centsFromInput,
  pendingCreateBody,
  pendingHasUncertainCard,
  pendingSummary,
  type PendingPaymentAllocation,
  type PendingSaleDraft,
} from "../sale/customerReceivables";
import { CashPaymentDialog } from "./CashPaymentDialog";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type PaymentMethods = { cash?: string; card?: string; transfer?: string };
type PendingSaleResult = { documentId: string; documentNumber?: string };

type Props = {
  customerName: string;
  draft: PendingSaleDraft;
  token?: string;
  paymentMethods?: PaymentMethods;
  request?: Request;
  onCancel: () => void;
  onSuccess: (result: PendingSaleResult) => void;
};

const uuid = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
const money = (cents: number) => (cents / 100).toLocaleString("es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function CustomerPendingSaleDialog({ customerName, draft: initialDraft, token, paymentMethods, request = apiRequest, onCancel, onSuccess }: Props) {
  const dialogRef = useRef<HTMLElement>(null);
  const [draft, setDraft] = useState(initialDraft);
  const [quoteCents, setQuoteCents] = useState(0);
  const [quoteLoading, setQuoteLoading] = useState(true);
  const [quoteReady, setQuoteReady] = useState(false);
  const [payments, setPayments] = useState<PendingPaymentAllocation[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [cashOpen, setCashOpen] = useState(false);
  const [transferOpen, setTransferOpen] = useState(false);
  const [transferAmount, setTransferAmount] = useState("");
  const [transferReference, setTransferReference] = useState("");
  const [resolvedMethods, setResolvedMethods] = useState<PaymentMethods>(paymentMethods ?? {});
  const summary = useMemo(() => pendingSummary(quoteCents, payments), [payments, quoteCents]);
  const uncertain = pendingHasUncertainCard(payments);
  const hasCardEffect = payments.some((payment) => payment.kind === "INTEGRATED_CARD"
    && !["DECLINED", "ERROR", "CANCELLED"].includes(payment.status));

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    let current = true;
    setQuoteLoading(true);
    setQuoteReady(false);
    setError("");
    request<{ total: number | string }>("/pos/customer-pending-sales/quote", {
      token,
      body: pendingCreateBody(draft, [], 0),
    }).then((quote) => {
      if (current) { setQuoteCents(Math.round(Number(quote.total) * 100)); setQuoteReady(true); }
    }).catch((failure) => {
      if (current) setError(failure instanceof Error ? failure.message : "No se pudo calcular la venta");
    }).finally(() => {
      if (current) setQuoteLoading(false);
    });
    return () => { current = false; };
  }, []);

  useEffect(() => {
    if (paymentMethods !== undefined) return;
    let current = true;
    request<Array<{ id: string; name?: string; nombre?: string; active?: boolean }>>("/payment-methods", { token })
      .then((methods) => {
        if (!current) return;
        const active = methods.filter((method) => method.active !== false);
        const find = (name: string) => active.find((method) => (method.name ?? method.nombre)?.toLocaleUpperCase() === name)?.id;
        setResolvedMethods({ cash: find("EFECTIVO"), card: find("TARJETA"), transfer: find("TRANSFERENCIA") });
      })
      .catch(() => { /* Creation without an initial payment remains available. */ });
    return () => { current = false; };
  }, [paymentMethods, request, token]);

  const confirm = useCallback(async () => {
    if (submitting || quoteLoading || !quoteReady || uncertain || summary.pendingCents < 0 || !draft.dueDate) return;
    setSubmitting(true);
    setError("");
    try {
      const result = await request<PendingSaleResult>("/pos/customer-pending-sales", {
        token,
        body: pendingCreateBody(draft, payments, quoteCents),
      });
      onSuccess(result);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "No se pudo crear la venta pendiente");
    } finally {
      setSubmitting(false);
    }
  }, [draft, onSuccess, payments, quoteCents, quoteLoading, quoteReady, request, submitting, summary.pendingCents, token, uncertain]);

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (cashOpen || transferOpen) return;
      if (event.key === "Escape" && !submitting && !hasCardEffect) {
        event.preventDefault();
        onCancel();
      } else if (event.key === "Enter" && !event.repeat && !(event.target instanceof HTMLButtonElement)) {
        event.preventDefault();
        void confirm();
      }
    };
    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [cashOpen, confirm, hasCardEffect, onCancel, submitting, transferOpen]);

  function saveTransfer() {
    const amountCents = centsFromInput(transferAmount);
    if (!transferReference.trim()) { setError("La referencia de transferencia es obligatoria"); return; }
    if (amountCents <= 0 || amountCents > summary.pendingCents) { setError("El importe de transferencia no es valido"); return; }
    setPayments((current) => [...current, { id: uuid(), kind: "TRANSFER", methodId: resolvedMethods.transfer!, amountCents, reference: transferReference.trim(), status: "APPROVED" }]);
    setTransferOpen(false); setTransferAmount(""); setTransferReference(""); setError("");
  }

  async function chargeCard() {
    if (!resolvedMethods.card || summary.pendingCents <= 0 || uncertain) return;
    const priorCard = payments.some((payment) => payment.kind === "INTEGRATED_CARD");
    const operationId = priorCard ? uuid() : draft.checkoutId;
    const chargeDraft = priorCard ? { ...draft, checkoutId: operationId } : draft;
    if (priorCard) setDraft(chargeDraft);
    const allocation: PendingPaymentAllocation = { id: operationId, operationId, kind: "INTEGRATED_CARD", methodId: resolvedMethods.card, amountCents: summary.pendingCents, status: "PENDING" };
    const retainedPayments = payments.filter((payment) => payment.kind !== "INTEGRATED_CARD");
    setPayments([...retainedPayments, allocation]);
    setError("");
    try {
      const result = await request<{ status: PendingPaymentAllocation["status"]; message?: string }>("/pos/customer-pending-sales/card-charges", {
        token,
        body: { sale: pendingCreateBody(chargeDraft, [...retainedPayments, { ...allocation, status: "APPROVED" }], quoteCents), amount: (summary.pendingCents / 100).toFixed(2) },
      });
      setPayments((current) => current.map((payment) => payment.id === operationId ? { ...payment, status: result.status } : payment));
      if (result.message && result.status !== "APPROVED") setError(result.message);
    } catch (failure) {
      setPayments((current) => current.map((payment) => payment.id === operationId ? { ...payment, status: "TIMEOUT" } : payment));
      setError(failure instanceof Error ? failure.message : "Resultado de tarjeta incierto");
    }
  }

  async function queryCard(payment: PendingPaymentAllocation) {
    if (!payment.operationId) return;
    try {
      const result = await request<{ status: PendingPaymentAllocation["status"] }>(`/payment-terminal/operations/${payment.operationId}/query`, { method: "POST", token });
      setPayments((current) => current.map((candidate) => candidate.id === payment.id ? { ...candidate, status: result.status } : candidate));
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "No se pudo consultar la tarjeta");
    }
  }

  function removePayment(payment: PendingPaymentAllocation) {
    if (payment.kind === "INTEGRATED_CARD") {
      setDraft((current) => ({ ...current, checkoutId: uuid() }));
    }
    setPayments((current) => current.filter((candidate) => candidate.id !== payment.id));
  }

  return <div className="sale-action-overlay pending-sale-overlay" role="presentation">
    <section ref={dialogRef} className="customer-pending-sale-dialog" role="dialog" aria-modal="true" aria-labelledby="customer-pending-title" aria-busy={submitting || quoteLoading} aria-hidden={cashOpen ? true : undefined}>
      <header><h2 id="customer-pending-title">Venta pendiente de cliente</h2><button type="button" aria-label="Cerrar" disabled={submitting || hasCardEffect} onClick={onCancel}>×</button></header>
      <p><strong>Cliente:</strong> {customerName}</p>
      <div className="pending-sale-fields">
        <label>Tipo de documento<select value={draft.type} disabled={submitting} onChange={(event) => setDraft((value) => ({ ...value, type: event.target.value as PendingSaleDraft["type"] }))}><option value="ALBARAN_VENTA">Albaran</option><option value="FACTURA_VENTA">Factura</option></select></label>
        <label>Vencimiento<input type="date" value={draft.dueDate} disabled={submitting} onChange={(event) => setDraft((value) => ({ ...value, dueDate: event.target.value }))} /></label>
      </div>
      <div className="pending-sale-summary" aria-live="polite">
        <div><span>Total</span><strong>{money(summary.totalCents)}</strong></div>
        <div><span>Pagado</span><strong>{money(summary.paidCents)}</strong></div>
        <div><span>Pendiente</span><strong>{money(summary.pendingCents)}</strong></div>
      </div>
      {payments.length > 0 && <ul aria-label="Pagos iniciales">{payments.map((payment) => <li key={payment.id}><span>{payment.kind === "CASH" ? "Efectivo" : payment.kind === "TRANSFER" ? "Transferencia" : "Tarjeta"}: {money(payment.amountCents)} ({payment.status})</span>{payment.kind === "INTEGRATED_CARD" && ["PENDING", "SENT", "TIMEOUT"].includes(payment.status) ? <button type="button" onClick={() => void queryCard(payment)}>Consultar tarjeta</button> : payment.kind === "INTEGRATED_CARD" && payment.status === "APPROVED" ? <span>La tarjeta aprobada requiere anulacion</span> : <button type="button" onClick={() => removePayment(payment)}>Eliminar</button>}</li>)}</ul>}
      <div className="pending-sale-payment-actions">
        <button type="button" disabled={!resolvedMethods.cash || summary.pendingCents <= 0 || uncertain} onClick={() => setCashOpen(true)}>Añadir efectivo</button>
        <button type="button" disabled={!resolvedMethods.card || summary.pendingCents <= 0 || uncertain} onClick={() => void chargeCard()}>Añadir tarjeta</button>
        <button type="button" disabled={!resolvedMethods.transfer || summary.pendingCents <= 0 || uncertain} onClick={() => setTransferOpen(true)}>Añadir transferencia</button>
      </div>
      {transferOpen && <fieldset aria-label="Transferencia"><legend>Transferencia</legend><label>Importe<input aria-label="Importe transferencia" inputMode="decimal" value={transferAmount} onChange={(event) => setTransferAmount(event.target.value)} /></label><label>Referencia<input value={transferReference} onChange={(event) => setTransferReference(event.target.value)} /></label><button type="button" onClick={saveTransfer}>Guardar transferencia</button><button type="button" onClick={() => setTransferOpen(false)}>Cancelar transferencia</button></fieldset>}
      {error && <p className="sale-action-error" role="alert">{error}</p>}
      <footer><button type="button" disabled={submitting || hasCardEffect} onClick={onCancel}>Cancelar</button><button type="button" disabled={submitting || quoteLoading || !quoteReady || uncertain || summary.pendingCents < 0 || !draft.dueDate} onClick={() => void confirm()}>{submitting ? "Creando..." : "Confirmar venta pendiente"}</button></footer>
    </section>
    {cashOpen && <CashPaymentDialog totalCents={summary.pendingCents} submitting={false} error="" initialMode="touch" onCancel={() => setCashOpen(false)} onConfirm={(receivedCents) => { const amountCents = summary.pendingCents; setPayments((current) => [...current, { id: uuid(), kind: "CASH", methodId: resolvedMethods.cash!, amountCents, deliveredCents: receivedCents, changeCents: receivedCents - amountCents, status: "APPROVED" }]); setCashOpen(false); }} />}
  </div>;
}
