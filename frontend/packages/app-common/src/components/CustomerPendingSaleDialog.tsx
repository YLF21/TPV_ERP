import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { TerminalContext } from "../types";
import { printPendingCommercialDocument, type PendingCommercialDocumentPrintSnapshot } from "../sale/ticketPrinting";
import {
  centsFromInput,
  pendingAllocationCents,
  pendingCreateBody,
  pendingHasCardEffect,
  pendingHasUncertainCard,
  pendingSummary,
  type PendingPaymentAllocation,
  type PendingSaleDraft,
} from "../sale/customerReceivables";
import { pendingSaleRecoveryPhase, type PendingSaleRecoveryEnvelope } from "../sale/pendingSaleRecovery";
import { CashPaymentDialog } from "./CashPaymentDialog";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type PaymentMethods = { cash?: string; card?: string; transfer?: string };
type PendingSaleResult = { documentId: string; documentNumber?: string };
type PendingSaleMutationResult = { receivable: PendingSaleResult; printDocument: PendingCommercialDocumentPrintSnapshot };
type Props = {
  customerName: string;
  locale?: LocaleCode;
  draft: PendingSaleDraft;
  token?: string;
  paymentMethods?: PaymentMethods;
  disabled?: boolean;
  request?: Request;
  terminalContext?: TerminalContext;
  recovery?: PendingSaleRecoveryEnvelope;
  onPersistRecovery?: (envelope: PendingSaleRecoveryEnvelope) => void;
  onClearRecovery?: () => void;
  printDocument?: typeof printPendingCommercialDocument;
  onCancel: () => void;
  onSuccess: (result: PendingSaleResult, retryPrint?: () => Promise<unknown>) => void;
};

const uuid = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
const money = (cents: number, locale: LocaleCode) => (cents / 100).toLocaleString(
  locale === "zh" ? "zh-CN" : locale,
  { minimumFractionDigits: 2, maximumFractionDigits: 2 }
);

export function cardQueryResultStatus(current: PendingPaymentAllocation["status"], incoming: PendingPaymentAllocation["status"]) {
  return current === "APPROVED" && incoming !== "APPROVED" ? "APPROVED" : incoming;
}

export function CustomerPendingSaleDialog({ customerName, locale = "es", draft: initialDraft, token, paymentMethods, disabled = false, request = apiRequest, terminalContext, recovery, onPersistRecovery, onClearRecovery, printDocument = printPendingCommercialDocument, onCancel, onSuccess }: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const mountedRef = useRef(true);
  const queryGenerationRef = useRef(0);
  const queryingOperationRef = useRef<string | null>(null);
  const [draft, setDraft] = useState(recovery?.draft ?? initialDraft);
  const [quoteCents, setQuoteCents] = useState(recovery?.quoteCents ?? 0);
  const [quoteLoading, setQuoteLoading] = useState(!recovery);
  const [quoteReady, setQuoteReady] = useState(recovery?.quoteReady ?? false);
  const [payments, setPayments] = useState<PendingPaymentAllocation[]>(recovery?.payments ?? []);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [cashOpen, setCashOpen] = useState(false);
  const [cashAmountCents, setCashAmountCents] = useState(0);
  const [allocationAmount, setAllocationAmount] = useState("");
  const [transferOpen, setTransferOpen] = useState(false);
  const [transferAmount, setTransferAmount] = useState("");
  const [transferReference, setTransferReference] = useState("");
  const [resolvedMethods, setResolvedMethods] = useState<PaymentMethods>(paymentMethods ?? {});
  const [queryingOperationId, setQueryingOperationId] = useState<string | null>(null);
  const [createDurable, setCreateDurable] = useState(recovery?.phase === "READY_TO_CREATE");
  const summary = useMemo(() => pendingSummary(quoteCents, payments), [payments, quoteCents]);
  const uncertain = pendingHasUncertainCard(payments);
  const hasCardEffect = pendingHasCardEffect(payments);
  const cardFinalFailure = payments.some((payment) => payment.kind === "INTEGRATED_CARD" && ["DECLINED", "ERROR", "CANCELLED"].includes(payment.status));

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      queryGenerationRef.current += 1;
      queryingOperationRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!createDurable) return;
    setCashOpen(false);
    setTransferOpen(false);
  }, [createDurable]);

  useEffect(() => {
    if (recovery) return;
    let current = true;
    setQuoteLoading(true); setQuoteReady(false); setError("");
    request<{ total: number | string }>("/pos/customer-pending-sales/quote", {
      token, body: pendingCreateBody(draft, [], 0),
    }).then((quote) => {
      if (current) { setQuoteCents(Math.round(Number(quote.total) * 100)); setQuoteReady(true); }
    }).catch((failure) => {
      if (current) setError(failure instanceof Error ? failure.message : t("pendingSale.quoteError"));
    }).finally(() => { if (current) setQuoteLoading(false); });
    return () => { current = false; };
  }, []);

  const persistRecovery = useCallback((nextDraft: PendingSaleDraft, nextPayments: PendingPaymentAllocation[], createAttempted = false) => {
    if (!onPersistRecovery || !terminalContext?.terminalCode || !quoteReady) return;
    onPersistRecovery({
      version: 2,
      phase: pendingSaleRecoveryPhase(nextPayments),
      terminalCode: terminalContext.terminalCode,
      customer: { id: nextDraft.customerId, name: customerName },
      draft: nextDraft,
      quoteCents,
      quoteReady: true,
      payments: nextPayments,
      createAttempted,
      savedAt: new Date().toISOString(),
    });
  }, [customerName, onPersistRecovery, quoteCents, quoteReady, terminalContext?.terminalCode]);

  useEffect(() => {
    if (paymentMethods !== undefined) return;
    let current = true;
    request<Array<{ id: string; name?: string; nombre?: string; active?: boolean }>>("/payment-methods", { token })
      .then((methods) => {
        if (!current) return;
        const active = methods.filter((method) => method.active !== false);
        const find = (name: string) => active.find((method) => (method.name ?? method.nombre)?.toLocaleUpperCase() === name)?.id;
        setResolvedMethods({ cash: find("EFECTIVO"), card: find("TARJETA"), transfer: find("TRANSFERENCIA") });
      }).catch(() => { /* A sale without initial payment remains valid. */ });
    return () => { current = false; };
  }, [paymentMethods, request, token]);

  useEffect(() => {
    if (!disabled) return;
    if (!hasCardEffect && !createDurable) onCancel();
    else setError(t("pendingSale.recoveryError"));
  }, [createDurable, disabled, hasCardEffect, onCancel]);

  const confirm = useCallback(async () => {
    if (disabled || submitting || quoteLoading || !quoteReady || uncertain || cardFinalFailure || summary.pendingCents < 0 || !draft.dueDate) return;
    setSubmitting(true); setError("");
    try {
      persistRecovery(draft, payments, true);
      setCreateDurable(true);
      const result = await request<PendingSaleMutationResult>("/pos/customer-pending-sales", {
        token, body: pendingCreateBody(draft, payments, quoteCents),
      });
      try { onClearRecovery?.(); }
      catch { /* The confirmed idempotent checkout remains safe to replay. */ }
      let retryPrint: (() => Promise<unknown>) | undefined;
      if (terminalContext) {
        const retry = () => printDocument(result.printDocument, terminalContext, undefined, locale);
        try { if ((await retry()).status === "FAILED") retryPrint = retry; }
        catch { retryPrint = retry; }
      }
      if (retryPrint) onSuccess(result.receivable, retryPrint); else onSuccess(result.receivable);
    } catch (failure) {
      const hasIntegratedCard = payments.some((payment) => payment.kind === "INTEGRATED_CARD");
      const definitiveLocalFailure = !hasIntegratedCard
        && failure instanceof ApiError
        && failure.status >= 400
        && failure.status < 500;
      if (definitiveLocalFailure) {
        try { onClearRecovery?.(); }
        catch { /* The failed request is still definitive; storage will be discarded on next entry. */ }
        setCreateDurable(false);
      }
      setError(failure instanceof Error ? failure.message : t("pendingSale.createError"));
    } finally { setSubmitting(false); }
  }, [cardFinalFailure, disabled, draft, onClearRecovery, onSuccess, payments, persistRecovery, quoteCents, quoteLoading, quoteReady, request, submitting, summary.pendingCents, token, uncertain]);

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (cashOpen || transferOpen) return;
      if (event.key === "Escape" && (!submitting || Boolean(error)) && !hasCardEffect && !createDurable) { event.preventDefault(); onCancel(); }
      else if (event.key === "Enter" && !event.repeat && !(event.target instanceof HTMLButtonElement)) { event.preventDefault(); void confirm(); }
    };
    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [cashOpen, confirm, createDurable, error, hasCardEffect, onCancel, submitting, transferOpen]);

  function saveTransfer() {
    if (createDurable) return;
    const amountCents = centsFromInput(transferAmount);
    if (!transferReference.trim()) { setError(t("receivables.payment.referenceRequired")); return; }
    if (amountCents <= 0 || amountCents > summary.pendingCents) { setError(t("pendingSale.transferAmountError")); return; }
    setPayments((current) => [...current, { id: uuid(), kind: "TRANSFER", methodId: resolvedMethods.transfer!, amountCents, reference: transferReference.trim(), status: "APPROVED" }]);
    setTransferOpen(false); setTransferAmount(""); setTransferReference(""); setError("");
  }

  function selectedAllocationCents() {
    const amountCents = pendingAllocationCents(allocationAmount, summary.pendingCents);
    if (amountCents === 0) setError(t("pendingSale.paymentAmountError"));
    return amountCents;
  }

  function openCash() {
    if (createDurable) return;
    const amountCents = selectedAllocationCents();
    if (amountCents === 0) return;
    setError("");
    setCashAmountCents(amountCents);
    setCashOpen(true);
  }

  async function chargeCard() {
    if (createDurable || !resolvedMethods.card || summary.pendingCents <= 0 || uncertain) return;
    const amountCents = selectedAllocationCents();
    if (amountCents === 0) return;
    const priorCard = payments.some((payment) => payment.kind === "INTEGRATED_CARD");
    const operationId = priorCard ? uuid() : draft.checkoutId;
    const chargeDraft = priorCard ? { ...draft, checkoutId: operationId } : draft;
    if (priorCard) setDraft(chargeDraft);
    const allocation: PendingPaymentAllocation = { id: operationId, operationId, mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: resolvedMethods.card, amountCents, status: "PENDING" };
    const retainedPayments = payments.filter((payment) => payment.kind !== "INTEGRATED_CARD");
    const pendingPayments = [...retainedPayments, allocation];
    try { persistRecovery(chargeDraft, pendingPayments); }
    catch {
      setError(t("pendingSale.recoveryError"));
      return;
    }
    setPayments(pendingPayments); setError("");
    try {
      const result = await request<{ status: PendingPaymentAllocation["status"]; message?: string }>("/pos/customer-pending-sales/card-charges", {
        token,
        body: { sale: pendingCreateBody(chargeDraft, [...retainedPayments, { ...allocation, status: "APPROVED" }], quoteCents), amount: (amountCents / 100).toFixed(2) },
      });
      const next = pendingPayments.map((payment) => payment.id === operationId ? { ...payment, status: result.status } : payment);
      try { persistRecovery(chargeDraft, next); }
      catch { setError(t("pendingSale.recoveryError")); }
      setPayments(next);
      setAllocationAmount("");
      if (result.message && result.status !== "APPROVED") setError(result.message);
    } catch (failure) {
      const next = pendingPayments.map((payment) => payment.id === operationId ? { ...payment, status: "TIMEOUT" as const } : payment);
      try { persistRecovery(chargeDraft, next); }
      catch { /* The already persisted PENDING state remains recoverable. */ }
      setPayments(next);
      setError(failure instanceof Error ? failure.message : t("pendingSale.cardUncertain"));
    }
  }

  async function queryCard(payment: PendingPaymentAllocation) {
    if (!payment.operationId || queryingOperationRef.current) return;
    const operationId = payment.operationId;
    const generation = ++queryGenerationRef.current;
    queryingOperationRef.current = operationId;
    setQueryingOperationId(operationId);
    try {
      const result = await request<{ status: PendingPaymentAllocation["status"] }>(`/payment-terminal/operations/${operationId}/query`, { method: "POST", token });
      if (!mountedRef.current || generation !== queryGenerationRef.current || queryingOperationRef.current !== operationId) return;
      const currentPayment = payments.find((candidate) => candidate.id === payment.id && candidate.operationId === operationId);
      if (!currentPayment || currentPayment.operationId !== draft.checkoutId) return;
      const status = cardQueryResultStatus(currentPayment.status, result.status);
      const next = payments.map((candidate) => candidate.id === payment.id && candidate.operationId === operationId ? { ...candidate, status } : candidate);
      try { persistRecovery(draft, next); setError(""); }
      catch { setError(t("pendingSale.recoveryError")); }
      setPayments(next);
    } catch (failure) {
      if (mountedRef.current && generation === queryGenerationRef.current) setError(failure instanceof Error ? failure.message : t("pendingSale.cardQueryError"));
    } finally {
      if (mountedRef.current && generation === queryGenerationRef.current) {
        queryingOperationRef.current = null;
        setQueryingOperationId(null);
      }
    }
  }

  function removePayment(payment: PendingPaymentAllocation) {
    if (createDurable) return;
    if (payment.kind === "INTEGRATED_CARD") {
      try { onClearRecovery?.(); }
      catch { setError(t("pendingSale.recoveryError")); return; }
      setDraft((current) => ({ ...current, checkoutId: uuid() }));
    }
    setPayments((current) => current.filter((candidate) => candidate.id !== payment.id));
  }

  const paymentLabel = (payment: PendingPaymentAllocation) => t(payment.kind === "CASH"
    ? "receivables.payment.cash"
    : payment.kind === "TRANSFER" ? "receivables.payment.transfer" : "receivables.payment.card");

  return <div className="sale-action-overlay pending-sale-overlay" role="presentation">
    <section ref={dialogRef} className="customer-pending-sale-dialog" role="dialog" aria-modal="true" aria-labelledby="customer-pending-title" aria-busy={submitting || quoteLoading} aria-hidden={cashOpen ? true : undefined}>
      <header><h2 id="customer-pending-title">{t("pendingSale.title")}</h2><button type="button" aria-label={t("common.close")} disabled={submitting || hasCardEffect || createDurable} onClick={onCancel}>×</button></header>
      <p><strong>{t("pendingSale.customer")}:</strong> {customerName}</p>
      <div className="pending-sale-fields">
        <label>{t("pendingSale.documentType")}<select value={draft.type} disabled={disabled || submitting || hasCardEffect || createDurable} onChange={(event) => { if (!disabled && !submitting && !hasCardEffect && !createDurable) setDraft((value) => ({ ...value, type: event.target.value as PendingSaleDraft["type"] })); }}><option value="ALBARAN_VENTA">{t("receivables.type.deliveryNote")}</option><option value="FACTURA_VENTA">{t("receivables.type.invoice")}</option></select></label>
        <label>{t("pendingSale.dueDate")}<input type="date" value={draft.dueDate} disabled={disabled || submitting || hasCardEffect || createDurable} onChange={(event) => { if (!disabled && !submitting && !hasCardEffect && !createDurable) setDraft((value) => ({ ...value, dueDate: event.target.value })); }} /></label>
      </div>
      <div className="pending-sale-summary" aria-live="polite">
        <div><span>{t("pendingSale.total")}</span><strong>{money(summary.totalCents, locale)}</strong></div>
        <div><span>{t("pendingSale.paid")}</span><strong>{money(summary.paidCents, locale)}</strong></div>
        <div><span>{t("pendingSale.pending")}</span><strong>{money(summary.pendingCents, locale)}</strong></div>
      </div>
      {payments.length > 0 && <ul aria-label={t("pendingSale.initialPayments")}>{payments.map((payment) => <li key={payment.id}><span>{paymentLabel(payment)}: {money(payment.amountCents, locale)} ({t(`paymentTerminal.status.${payment.status}`)})</span>{payment.kind === "INTEGRATED_CARD" && ["PENDING", "SENT", "TIMEOUT"].includes(payment.status) ? <button type="button" disabled={disabled || queryingOperationId === payment.operationId} onClick={() => void queryCard(payment)}>{t("pendingSale.queryCard")}</button> : payment.kind === "INTEGRATED_CARD" && payment.status === "APPROVED" ? <span>{t("pendingSale.approvedCardRequiresVoid")}</span> : <button type="button" disabled={disabled || createDurable} onClick={() => removePayment(payment)}>{t("pendingSale.removePayment")}</button>}</li>)}</ul>}
      <label className="pending-sale-allocation-amount">{t("pendingSale.paymentAmount")}<input aria-label={t("pendingSale.paymentAmount")} inputMode="decimal" value={allocationAmount} disabled={disabled || hasCardEffect || createDurable || summary.pendingCents <= 0 || uncertain} placeholder={money(summary.pendingCents, locale)} onChange={(event) => { if (!createDurable) setAllocationAmount(event.target.value); }} /></label>
      <div className="pending-sale-payment-actions">
        <button type="button" className="pending-sale-payment-button" disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.cash || summary.pendingCents <= 0 || uncertain} onClick={openCash}>{t("pendingSale.addCash")}</button>
        <button type="button" className="pending-sale-payment-button" disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.card || summary.pendingCents <= 0 || uncertain} onClick={() => void chargeCard()}>{t("pendingSale.addCard")}</button>
        <button type="button" className="pending-sale-payment-button" disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.transfer || summary.pendingCents <= 0 || uncertain} onClick={() => { if (!createDurable) setTransferOpen(true); }}>{t("pendingSale.addTransfer")}</button>
      </div>
      {transferOpen && <fieldset aria-label={t("receivables.payment.transfer")} disabled={createDurable}><legend>{t("receivables.payment.transfer")}</legend><label>{t("receivables.payment.amount")}<input aria-label={t("pendingSale.transferAmount")} inputMode="decimal" value={transferAmount} onChange={(event) => setTransferAmount(event.target.value)} /></label><label>{t("receivables.payment.transferReference")}<input value={transferReference} onChange={(event) => setTransferReference(event.target.value)} /></label><button type="button" onClick={saveTransfer}>{t("pendingSale.saveTransfer")}</button><button type="button" onClick={() => setTransferOpen(false)}>{t("pendingSale.cancelTransfer")}</button></fieldset>}
      {error && <p className="sale-action-error" role="alert">{error}</p>}
      <footer className="pending-sale-footer"><button type="button" className="pending-sale-cancel-button" disabled={submitting || hasCardEffect || createDurable} onClick={onCancel}>{t("common.cancel")}</button><button type="button" className="pending-sale-confirm-button" aria-label={createDurable && !submitting ? `${t("pendingSale.retryCreate")} · ${t("pendingSale.confirm")}` : undefined} disabled={disabled || submitting || quoteLoading || !quoteReady || uncertain || cardFinalFailure || summary.pendingCents < 0 || !draft.dueDate} onClick={() => void confirm()}>{t(submitting ? "pendingSale.creating" : createDurable ? "pendingSale.retryCreate" : "pendingSale.confirm")}</button></footer>
    </section>
    {cashOpen && <CashPaymentDialog totalCents={cashAmountCents} submitting={false} error="" initialMode="touch" onCancel={() => setCashOpen(false)} onConfirm={(receivedCents) => { if (createDurable) return; const amountCents = cashAmountCents; setPayments((current) => [...current, { id: uuid(), kind: "CASH", methodId: resolvedMethods.cash!, amountCents, deliveredCents: receivedCents, changeCents: receivedCents - amountCents, status: "APPROVED" }]); setAllocationAmount(""); setCashOpen(false); }} />}
  </div>;
}
