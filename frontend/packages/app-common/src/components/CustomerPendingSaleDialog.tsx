import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, Permission, TerminalContext } from "../types";
import {
  printPendingCommercialDocument,
  type PendingCommercialDocumentPrintSnapshot,
  type SalePrintMode,
} from "../sale/ticketPrinting";
import {
  centsFromInput,
  pendingAllocationCents,
  pendingCreateBody,
  pendingHasCardEffect,
  pendingHasUncertainCard,
  pendingSummary,
  type PendingCardPaymentMode,
  type PendingPaymentAllocation,
  type PendingSaleDraft,
} from "../sale/customerReceivables";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import {
  saleMutationCredentialsRequired,
  type SaleMutationAuthorizationRequirement,
  type SaleMutationOperationAuthorizations,
} from "../sale/saleMutationAuthorizations";
import { pendingSaleRecoveryPhase, type PendingSaleRecoveryEnvelope } from "../sale/pendingSaleRecovery";
import { CashPaymentDialog } from "./CashPaymentDialog";
import { ManualCardReferenceDialog } from "./ManualCardReferenceDialog";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";
import { SaleMutationAuthorizationDialog } from "./SaleMutationAuthorizationDialog";

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type PaymentMethods = {
  cash?: string;
  card?: string;
  transfer?: string;
  cardRequiresReference?: boolean;
  transferRequiresReference?: boolean;
};
type PendingSaleResult = { documentId: string; documentNumber?: string };
type PendingSaleMutationResult = { receivable: PendingSaleResult; printDocument: PendingCommercialDocumentPrintSnapshot };
type SalesDocumentMutationResult = {
  document: { id: string; numero?: string | null };
  printDocument: PendingCommercialDocumentPrintSnapshot;
};
type CustomerCreditQuote = {
  enabled: boolean;
  blocked?: boolean;
  blockReason?: string | null;
  limit?: number | string | null;
  outstandingDebt: number | string;
  overdueDebt: number | string;
  availableCredit?: number | string | null;
  availableAfterSale?: number | string | null;
  paymentTermDays: number;
  proposedOutstanding: number | string;
  requiresOverride: boolean;
  limitExceeded?: boolean;
  overdueBlocked?: boolean;
  manualBlocked?: boolean;
  creditRequired?: boolean;
};
type PendingSaleQuote = { total: number | string; credit?: CustomerCreditQuote };
type Props = {
  customerName: string;
  locale?: LocaleCode;
  currentUsername?: string;
  draft: PendingSaleDraft;
  token?: string;
  permissions?: Permission[];
  paymentMethods?: PaymentMethods;
  disabled?: boolean;
  request?: Request;
  terminalContext?: TerminalContext;
  recovery?: PendingSaleRecoveryEnvelope;
  onPersistRecovery?: (envelope: PendingSaleRecoveryEnvelope) => void;
  onClearRecovery?: () => void;
  printDocument?: typeof printPendingCommercialDocument;
  printMode?: SalePrintMode;
  onCancel: () => void;
  onSuccess: (result: PendingSaleResult, retryPrint?: () => Promise<unknown>) => void;
  endpointBase?: string;
  allowPayments?: boolean;
  requireFullPayment?: boolean;
  lockDocumentType?: boolean;
  title?: string;
  confirmLabel?: string;
  createPendingAuthorization?: SaleOperationAuthorization | null;
  creditOverrideAuthorization?: SaleOperationAuthorization | null;
  manualCardPaymentAuthorization?: SaleOperationAuthorization | null;
  transferPaymentAuthorization?: SaleOperationAuthorization | null;
  cardPaymentMode?: PendingCardPaymentMode | null;
  saleMutationAuthorizations?: readonly SaleMutationAuthorizationRequirement[] | null;
};

const uuid = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
const directAuthorization: SaleOperationAuthorization = {
  mode: "DIRECT",
  requireUsername: false,
  requirePassword: false,
};
const money = (cents: number, locale: LocaleCode) => (cents / 100).toLocaleString(
  locale === "zh" ? "zh-CN" : locale,
  { minimumFractionDigits: 2, maximumFractionDigits: 2 }
);

export function cardQueryResultStatus(current: PendingPaymentAllocation["status"], incoming: PendingPaymentAllocation["status"]) {
  return current === "APPROVED" && incoming !== "APPROVED" ? "APPROVED" : incoming;
}

export function CustomerPendingSaleDialog({
  customerName,
  locale = "es",
  currentUsername = "",
  draft: initialDraft,
  token,
  permissions = [],
  paymentMethods,
  disabled = false,
  request = apiRequest,
  terminalContext,
  recovery,
  onPersistRecovery,
  onClearRecovery,
  printDocument = printPendingCommercialDocument,
  printMode = "DEFAULT",
  onCancel,
  onSuccess,
  endpointBase = "/pos/customer-pending-sales",
  allowPayments = true,
  requireFullPayment = false,
  lockDocumentType = false,
  title,
  confirmLabel,
  createPendingAuthorization,
  creditOverrideAuthorization,
  manualCardPaymentAuthorization,
  transferPaymentAuthorization,
  cardPaymentMode,
  saleMutationAuthorizations,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const mountedRef = useRef(true);
  const queryGenerationRef = useRef(0);
  const queryingOperationRef = useRef<string | null>(null);
  const [draft, setDraft] = useState(recovery?.draft ?? initialDraft);
  const [quoteCents, setQuoteCents] = useState(recovery?.quoteCents ?? 0);
  const [quoteLoading, setQuoteLoading] = useState(!recovery);
  const [quoteReady, setQuoteReady] = useState(recovery?.quoteReady ?? false);
  const [credit, setCredit] = useState<CustomerCreditQuote | null>(null);
  const [creditOverrideReason, setCreditOverrideReason] = useState(recovery?.draft.creditOverride?.reason ?? "");
  const [createPendingUsername, setCreatePendingUsername] = useState("");
  const [createPendingPassword, setCreatePendingPassword] = useState("");
  const [creditOverrideUsername, setCreditOverrideUsername] = useState("");
  const [creditOverridePassword, setCreditOverridePassword] = useState("");
  const [manualCardPaymentUsername, setManualCardPaymentUsername] = useState("");
  const [manualCardPaymentPassword, setManualCardPaymentPassword] = useState("");
  const [transferPaymentUsername, setTransferPaymentUsername] = useState("");
  const [transferPaymentPassword, setTransferPaymentPassword] = useState("");
  const [mutationAuthorizationAction, setMutationAuthorizationAction] =
    useState<"CONFIRM" | "INTEGRATED_CARD" | null>(null);
  const [payments, setPayments] = useState<PendingPaymentAllocation[]>(recovery?.payments ?? []);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [cashOpen, setCashOpen] = useState(false);
  const [cashAmountCents, setCashAmountCents] = useState(0);
  const [manualCardOpen, setManualCardOpen] = useState(false);
  const [manualCardAmountCents, setManualCardAmountCents] = useState(0);
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
  // Undefined preserves standalone legacy consumers. Production screens pass an
  // explicit policy or null, so missing configuration fails closed there.
  const effectiveCreatePendingAuthorization = createPendingAuthorization === undefined
    ? directAuthorization
    : createPendingAuthorization;
  const effectiveCreditOverrideAuthorization = creditOverrideAuthorization === undefined
    ? (permissions.includes("ADMIN") || permissions.includes("CUSTOMER_CREDIT_OVERRIDE")
      ? directAuthorization
      : null)
    : creditOverrideAuthorization;
  const effectiveManualCardPaymentAuthorization = manualCardPaymentAuthorization === undefined
    ? directAuthorization
    : manualCardPaymentAuthorization;
  const effectiveTransferPaymentAuthorization = transferPaymentAuthorization === undefined
    ? directAuthorization
    : transferPaymentAuthorization;
  // Undefined preserves the historic integrated-card behavior for standalone
  // consumers. Production screens always pass a resolved mode or null.
  const effectiveCardPaymentMode = cardPaymentMode === undefined
    ? "INTEGRATED"
    : cardPaymentMode;
  const effectiveSaleMutationAuthorizations =
    saleMutationAuthorizations === undefined ? [] : saleMutationAuthorizations;
  const mutationCredentialRequirements = effectiveSaleMutationAuthorizations
    ? saleMutationCredentialsRequired(effectiveSaleMutationAuthorizations)
    : [];
  const saleMutationSecurityUnavailable =
    effectiveSaleMutationAuthorizations === null;
  const hasManualCardPayment = payments.some((payment) =>
    payment.kind === "MANUAL_CARD" && payment.status === "APPROVED");
  const hasTransferPayment = payments.some((payment) =>
    payment.kind === "TRANSFER" && payment.status === "APPROVED");
  const canOverrideCredit = Boolean(effectiveCreditOverrideAuthorization);
  const availableCreditCents = credit?.availableCredit == null ? null : Math.round(Number(credit.availableCredit) * 100);
  const hasExplicitHardBlock = credit?.manualBlocked !== undefined || credit?.overdueBlocked !== undefined;
  const hasHardCreditBlock = (pendingCents: number) => pendingCents > 0 && (credit?.enabled === false
    || (hasExplicitHardBlock
      ? credit?.manualBlocked === true || credit?.overdueBlocked === true
      : credit?.blocked === true && credit?.blockReason !== "CREDIT_LIMIT_EXCEEDED"));
  const requiresOverrideForPending = (pendingCents: number) => Boolean(
    credit && pendingCents > 0 && (availableCreditCents !== null
      ? pendingCents > availableCreditCents
      : credit.limitExceeded === true
        || (credit.requiresOverride && credit.blockReason === "CREDIT_LIMIT_EXCEEDED")),
  );
  const hardCreditBlock = hasHardCreditBlock(summary.pendingCents);
  const requiresCreditOverride = requiresOverrideForPending(summary.pendingCents);
  const pendingAuthorizationMissing = summary.pendingCents > 0 && (
    !effectiveCreatePendingAuthorization
    || !saleOperationAuthorizationComplete(
      effectiveCreatePendingAuthorization,
      createPendingUsername,
      createPendingPassword,
    )
  );
  const creditOverrideMissing = requiresCreditOverride && (
    !effectiveCreditOverrideAuthorization
    || !creditOverrideReason.trim()
    || !saleOperationAuthorizationComplete(
      effectiveCreditOverrideAuthorization,
      creditOverrideUsername,
      creditOverridePassword,
    )
  );
  const manualCardAuthorizationMissing = hasManualCardPayment && (
    !effectiveManualCardPaymentAuthorization
    || !saleOperationAuthorizationComplete(
      effectiveManualCardPaymentAuthorization,
      manualCardPaymentUsername,
      manualCardPaymentPassword,
    )
  );
  const transferAuthorizationMissing = hasTransferPayment && (
    !effectiveTransferPaymentAuthorization
    || !saleOperationAuthorizationComplete(
      effectiveTransferPaymentAuthorization,
      transferPaymentUsername,
      transferPaymentPassword,
    )
  );
  const creditConfirmationBlocked = hardCreditBlock
    || pendingAuthorizationMissing
    || creditOverrideMissing
    || manualCardAuthorizationMissing
    || transferAuthorizationMissing
    || saleMutationSecurityUnavailable;
  const plannedCardAmountCents = pendingAllocationCents(
    allocationAmount,
    summary.pendingCents,
  );
  const pendingAfterPlannedCard = Math.max(
    0,
    summary.pendingCents - plannedCardAmountCents,
  );
  const plannedCardRequiresOverride = requiresOverrideForPending(
    pendingAfterPlannedCard,
  );
  const cardCreditAuthorizationBlocked = plannedCardAmountCents > 0 && (
    hasHardCreditBlock(pendingAfterPlannedCard)
    || (pendingAfterPlannedCard > 0 && (
      !effectiveCreatePendingAuthorization
      || !saleOperationAuthorizationComplete(
        effectiveCreatePendingAuthorization,
        createPendingUsername,
        createPendingPassword,
      )
    ))
    || (plannedCardRequiresOverride && (
      !effectiveCreditOverrideAuthorization
      || !creditOverrideReason.trim()
      || !saleOperationAuthorizationComplete(
        effectiveCreditOverrideAuthorization,
        creditOverrideUsername,
        creditOverridePassword,
      )
    ))
  );

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
    setManualCardOpen(false);
    setTransferOpen(false);
  }, [createDurable]);

  useEffect(() => {
    if (recovery) return;
    let current = true;
    setQuoteLoading(true); setQuoteReady(false); setError("");
    request<PendingSaleQuote>(`${endpointBase}/quote`, {
      token, body: pendingCreateBody(draft, [], 0),
    }).then((quote) => {
      if (current) { setQuoteCents(Math.round(Number(quote.total) * 100)); setCredit(quote.credit ?? null); setQuoteReady(true); }
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
    request<Array<{
      id: string;
      name?: string;
      nombre?: string;
      active?: boolean;
      requiresReference?: boolean;
    }>>("/payment-methods", { token })
      .then((methods) => {
        if (!current) return;
        const active = methods.filter((method) => method.active !== false);
        const find = (name: string) => active.find((method) => (method.name ?? method.nombre)?.toLocaleUpperCase() === name)?.id;
        const byName = (name: string) => active.find((method) =>
          (method.name ?? method.nombre)?.toLocaleUpperCase() === name);
        const card = byName("TARJETA");
        const transfer = byName("TRANSFERENCIA");
        setResolvedMethods({
          cash: find("EFECTIVO"),
          card: card?.id,
          transfer: transfer?.id,
          cardRequiresReference: card?.requiresReference === true,
          transferRequiresReference: transfer?.requiresReference === true,
        });
      }).catch(() => { /* A sale without initial payment remains valid. */ });
    return () => { current = false; };
  }, [paymentMethods, request, token]);

  const clearOperationCredentials = useCallback(() => {
    setCreatePendingUsername("");
    setCreatePendingPassword("");
    setCreditOverrideUsername("");
    setCreditOverridePassword("");
    setManualCardPaymentUsername("");
    setManualCardPaymentPassword("");
    setTransferPaymentUsername("");
    setTransferPaymentPassword("");
  }, []);

  const cancelPendingSale = useCallback(() => {
    setMutationAuthorizationAction(null);
    clearOperationCredentials();
    onCancel();
  }, [clearOperationCredentials, onCancel]);

  useEffect(() => {
    if (!disabled) return;
    if (!hasCardEffect && !createDurable) cancelPendingSale();
    else setError(t("pendingSale.recoveryError"));
  }, [cancelPendingSale, createDurable, disabled, hasCardEffect, t]);

  const confirm = useCallback(async (
    saleMutationCredentials?: SaleMutationOperationAuthorizations,
  ) => {
    if (disabled || submitting || quoteLoading || !quoteReady || uncertain || cardFinalFailure
      || summary.pendingCents < 0 || (requireFullPayment && summary.pendingCents !== 0)
      || !draft.dueDate || creditConfirmationBlocked) return;
    if (mutationCredentialRequirements.length > 0
      && saleMutationCredentials === undefined) {
      setMutationAuthorizationAction("CONFIRM");
      return;
    }
    setSubmitting(true); setError("");
    try {
      const requestDraft = requiresCreditOverride
        ? { ...draft, creditOverride: { reason: creditOverrideReason.trim() } }
        : draft;
      const operationCredentials = {
        ...(summary.pendingCents > 0 && effectiveCreatePendingAuthorization
          ? {
              createPending: saleOperationCredentials(
                effectiveCreatePendingAuthorization,
                createPendingUsername,
                createPendingPassword,
              ),
            }
          : {}),
        ...(requiresCreditOverride && effectiveCreditOverrideAuthorization
          ? {
              creditOverride: saleOperationCredentials(
                effectiveCreditOverrideAuthorization,
                creditOverrideUsername,
                creditOverridePassword,
              ),
            }
          : {}),
        ...(hasManualCardPayment && effectiveManualCardPaymentAuthorization
          ? {
              manualCardPayment: saleOperationCredentials(
                effectiveManualCardPaymentAuthorization,
                manualCardPaymentUsername,
                manualCardPaymentPassword,
              ),
            }
          : {}),
        ...(hasTransferPayment && effectiveTransferPaymentAuthorization
          ? {
              transferPayment: saleOperationCredentials(
                effectiveTransferPaymentAuthorization,
                transferPaymentUsername,
                transferPaymentPassword,
              ),
            }
          : {}),
        ...(saleMutationCredentials
          ? { saleMutations: saleMutationCredentials }
          : {}),
      };
      persistRecovery(requestDraft, payments, true);
      setCreateDurable(true);
      const result = await request<PendingSaleMutationResult | SalesDocumentMutationResult>(endpointBase, {
        token,
        body: pendingCreateBody(
          requestDraft,
          payments,
          quoteCents,
          operationCredentials,
        ),
      });
      const completed = "receivable" in result
        ? result.receivable
        : { documentId: result.document.id, documentNumber: result.document.numero ?? undefined };
      try { onClearRecovery?.(); }
      catch { /* The confirmed idempotent checkout remains safe to replay. */ }
      let retryPrint: (() => Promise<unknown>) | undefined;
      if (terminalContext) {
        const effectivePrintMode = draft.printMode ?? printMode;
        const retry = () => effectivePrintMode === "DEFAULT"
          ? printDocument(result.printDocument, terminalContext, undefined, locale)
          : printDocument(result.printDocument, terminalContext, undefined, locale, effectivePrintMode);
        try { if ((await retry()).status === "FAILED") retryPrint = retry; }
        catch { retryPrint = retry; }
      }
      if (retryPrint) onSuccess(completed, retryPrint); else onSuccess(completed);
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
    } finally {
      clearOperationCredentials();
      setSubmitting(false);
    }
  }, [cardFinalFailure, clearOperationCredentials, createPendingPassword, createPendingUsername, creditConfirmationBlocked, creditOverridePassword, creditOverrideReason, creditOverrideUsername, disabled, draft, effectiveCreatePendingAuthorization, effectiveCreditOverrideAuthorization, effectiveManualCardPaymentAuthorization, effectiveTransferPaymentAuthorization, endpointBase, hasManualCardPayment, hasTransferPayment, locale, manualCardPaymentPassword, manualCardPaymentUsername, mutationCredentialRequirements, onClearRecovery, onSuccess, payments, persistRecovery, printDocument, printMode, quoteCents, quoteLoading, quoteReady, request, requireFullPayment, requiresCreditOverride, submitting, summary.pendingCents, terminalContext, token, transferPaymentPassword, transferPaymentUsername, uncertain]);

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (cashOpen || manualCardOpen || transferOpen) return;
      if (event.key === "Escape" && (!submitting || Boolean(error)) && !hasCardEffect && !createDurable) { event.preventDefault(); cancelPendingSale(); }
      else if (event.key === "Enter" && !event.repeat && !(event.target instanceof HTMLButtonElement)) { event.preventDefault(); void confirm(); }
    };
    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [cancelPendingSale, cashOpen, confirm, createDurable, error, hasCardEffect, manualCardOpen, submitting, transferOpen]);

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

  function addManualCard(amountCents: number, reference?: string) {
    if (createDurable || !resolvedMethods.card) return;
    setPayments((current) => [...current, {
      id: uuid(),
      kind: "MANUAL_CARD",
      methodId: resolvedMethods.card!,
      amountCents,
      reference: reference?.trim() || undefined,
      mode: "MANUAL",
      status: "APPROVED",
    }]);
    setAllocationAmount("");
    setManualCardOpen(false);
    setManualCardAmountCents(0);
    setError("");
  }

  async function chargeCard(
    saleMutationCredentials?: SaleMutationOperationAuthorizations,
  ) {
    if (createDurable || !resolvedMethods.card || summary.pendingCents <= 0
      || uncertain || !effectiveCardPaymentMode) return;
    const amountCents = selectedAllocationCents();
    if (amountCents === 0) return;
    const pendingAfterCard = Math.max(0, summary.pendingCents - amountCents);
    const cardRequiresCreditOverride = requiresOverrideForPending(pendingAfterCard);
    if (hasHardCreditBlock(pendingAfterCard)
      || (pendingAfterCard > 0 && (
        !effectiveCreatePendingAuthorization
        || !saleOperationAuthorizationComplete(
          effectiveCreatePendingAuthorization,
          createPendingUsername,
          createPendingPassword,
        )
      ))
      || (cardRequiresCreditOverride && (
        !effectiveCreditOverrideAuthorization
        || !creditOverrideReason.trim()
        || !saleOperationAuthorizationComplete(
          effectiveCreditOverrideAuthorization,
          creditOverrideUsername,
          creditOverridePassword,
        )
      ))) return;
    if (effectiveCardPaymentMode === "MANUAL") {
      if (!effectiveManualCardPaymentAuthorization) {
        setError(t("pendingSale.authorization.manualCardConfigurationUnavailable"));
        return;
      }
      if (resolvedMethods.cardRequiresReference) {
        setManualCardAmountCents(amountCents);
        setManualCardOpen(true);
      } else {
        addManualCard(amountCents);
      }
      return;
    }
    if (saleMutationSecurityUnavailable) {
      setError(t("pendingSale.authorization.configurationUnavailable"));
      return;
    }
    if (mutationCredentialRequirements.length > 0
      && saleMutationCredentials === undefined) {
      setMutationAuthorizationAction("INTEGRATED_CARD");
      return;
    }
    const priorCard = payments.some((payment) => payment.kind === "INTEGRATED_CARD");
    const operationId = priorCard ? uuid() : draft.checkoutId;
    const authorizedDraft = cardRequiresCreditOverride && canOverrideCredit && creditOverrideReason.trim()
      ? { ...draft, creditOverride: { reason: creditOverrideReason.trim() } }
      : draft;
    const chargeDraft = priorCard ? { ...authorizedDraft, checkoutId: operationId } : authorizedDraft;
    if (chargeDraft !== draft) setDraft(chargeDraft);
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
      const operationCredentials = {
        ...(pendingAfterCard > 0 && effectiveCreatePendingAuthorization
          ? {
              createPending: saleOperationCredentials(
                effectiveCreatePendingAuthorization,
                createPendingUsername,
                createPendingPassword,
              ),
            }
          : {}),
        ...(cardRequiresCreditOverride && effectiveCreditOverrideAuthorization
          ? {
              creditOverride: saleOperationCredentials(
                effectiveCreditOverrideAuthorization,
                creditOverrideUsername,
                creditOverridePassword,
              ),
            }
          : {}),
        ...(saleMutationCredentials
          ? { saleMutations: saleMutationCredentials }
          : {}),
      };
      const result = await request<{ status: PendingPaymentAllocation["status"]; message?: string }>(`${endpointBase}/card-charges`, {
        token,
        body: {
          sale: pendingCreateBody(
            chargeDraft,
            [...retainedPayments, { ...allocation, status: "APPROVED" }],
            quoteCents,
            operationCredentials,
          ),
          amount: (amountCents / 100).toFixed(2),
        },
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
    if (payment.kind === "MANUAL_CARD"
      && !payments.some((candidate) =>
        candidate.id !== payment.id
        && candidate.kind === "MANUAL_CARD"
        && candidate.status === "APPROVED")) {
      setManualCardPaymentUsername("");
      setManualCardPaymentPassword("");
    }
    if (payment.kind === "TRANSFER"
      && !payments.some((candidate) =>
        candidate.id !== payment.id
        && candidate.kind === "TRANSFER"
        && candidate.status === "APPROVED")) {
      setTransferPaymentUsername("");
      setTransferPaymentPassword("");
    }
    setPayments((current) => current.filter((candidate) => candidate.id !== payment.id));
  }

  function submitMutationAuthorization(
    authorizations: SaleMutationOperationAuthorizations,
  ) {
    const action = mutationAuthorizationAction;
    setMutationAuthorizationAction(null);
    if (action === "CONFIRM") {
      void confirm(authorizations);
    } else if (action === "INTEGRATED_CARD") {
      void chargeCard(authorizations);
    }
  }

  const paymentLabel = (payment: PendingPaymentAllocation) => t(payment.kind === "CASH"
    ? "receivables.payment.cash"
    : payment.kind === "TRANSFER" ? "receivables.payment.transfer" : "receivables.payment.card");

  return <div className="sale-action-overlay pending-sale-overlay" role="presentation">
    <section ref={dialogRef} className="customer-pending-sale-dialog" role="dialog" aria-modal="true" aria-labelledby="customer-pending-title" aria-busy={submitting || quoteLoading} aria-hidden={cashOpen || manualCardOpen || Boolean(mutationAuthorizationAction) ? true : undefined}>
      <header><h2 id="customer-pending-title">{title ?? t("pendingSale.title")}</h2><button type="button" aria-label={t("common.close")} disabled={submitting || hasCardEffect || createDurable} onClick={cancelPendingSale}>×</button></header>
      <p><strong>{t("pendingSale.customer")}:</strong> {customerName}</p>
      <div className="pending-sale-fields">
        <label>{t("pendingSale.documentType")}<select value={draft.type} disabled={lockDocumentType || disabled || submitting || hasCardEffect || createDurable} onChange={(event) => { if (!lockDocumentType && !disabled && !submitting && !hasCardEffect && !createDurable) setDraft((value) => ({ ...value, type: event.target.value as PendingSaleDraft["type"] })); }}><option value="ALBARAN_VENTA">{t("receivables.type.deliveryNote")}</option><option value="FACTURA_VENTA">{t("receivables.type.invoice")}</option></select></label>
        <label>{t("pendingSale.dueDate")}<input type="date" value={draft.dueDate} disabled={disabled || submitting || hasCardEffect || createDurable} onChange={(event) => { if (!disabled && !submitting && !hasCardEffect && !createDurable) setDraft((value) => ({ ...value, dueDate: event.target.value })); }} /></label>
      </div>
      <div className="pending-sale-summary" aria-live="polite">
        <div><span>{t("pendingSale.total")}</span><strong>{money(summary.totalCents, locale)}</strong></div>
        <div><span>{t("pendingSale.paid")}</span><strong>{money(summary.paidCents, locale)}</strong></div>
        <div><span>{t("pendingSale.pending")}</span><strong>{money(summary.pendingCents, locale)}</strong></div>
      </div>
      {credit && <section className="pending-sale-credit" aria-label={t("pendingSale.credit.title")}>
        <h3>{t("pendingSale.credit.title")}</h3>
        <div className="pending-sale-credit-summary">
          <div><span>{t("pendingSale.credit.currentDebt")}</span><strong>{money(Math.round(Number(credit.outstandingDebt) * 100), locale)}</strong></div>
          <div><span>{t("pendingSale.credit.overdueDebt")}</span><strong>{money(Math.round(Number(credit.overdueDebt) * 100), locale)}</strong></div>
          <div><span>{t("pendingSale.credit.limit")}</span><strong>{credit.limit == null ? t("pendingSale.credit.unlimited") : money(Math.round(Number(credit.limit) * 100), locale)}</strong></div>
          <div><span>{t("pendingSale.credit.proposedDebt")}</span><strong>{money(Math.round(Number(credit.outstandingDebt) * 100) + summary.pendingCents, locale)}</strong></div>
          <div><span>{t("pendingSale.credit.availableAfter")}</span><strong>{availableCreditCents == null ? t("pendingSale.credit.unlimited") : money(availableCreditCents - summary.pendingCents, locale)}</strong></div>
        </div>
        {hardCreditBlock && <div className="pending-sale-credit-warning pending-sale-credit-hard-block">
          <strong>{t("pendingSale.credit.hardBlocked")}</strong>
          <span>{t("pendingSale.credit.hardBlockedExplanation")}</span>
        </div>}
        {!hardCreditBlock && requiresCreditOverride && <div className="pending-sale-credit-warning">
          <strong>{t("pendingSale.credit.overrideRequired")}</strong>
          <span>{canOverrideCredit ? t("pendingSale.credit.overrideExplanation") : t("pendingSale.credit.supervisorRequired")}</span>
          {effectiveCreditOverrideAuthorization && <>
            <label>{t("pendingSale.credit.overrideReason")}<textarea required maxLength={500} value={creditOverrideReason} disabled={disabled || submitting} onChange={(event) => setCreditOverrideReason(event.target.value)} /></label>
            <SaleOperationAuthorizationFields
              locale={locale}
              currentUsername={currentUsername}
              authorization={effectiveCreditOverrideAuthorization}
              username={creditOverrideUsername}
              password={creditOverridePassword}
              disabled={disabled || submitting}
              onUsernameChange={setCreditOverrideUsername}
              onPasswordChange={setCreditOverridePassword}
            />
          </>}
        </div>}
      </section>}
      {summary.pendingCents > 0 && !effectiveCreatePendingAuthorization && (
        <p className="sale-action-error" role="alert">
          {t("pendingSale.authorization.configurationUnavailable")}
        </p>
      )}
      {saleMutationSecurityUnavailable && (
        <p className="sale-action-error" role="alert">
          {t("pendingSale.authorization.configurationUnavailable")}
        </p>
      )}
      {summary.pendingCents > 0
        && effectiveCreatePendingAuthorization
        && effectiveCreatePendingAuthorization.mode !== "DIRECT" && (
        <section className="pending-sale-authorization" aria-label={t("pendingSale.authorization.pendingTitle")}>
          <h3>{t("pendingSale.authorization.pendingTitle")}</h3>
          <SaleOperationAuthorizationFields
            locale={locale}
            currentUsername={currentUsername}
            authorization={effectiveCreatePendingAuthorization}
            username={createPendingUsername}
            password={createPendingPassword}
            disabled={disabled || submitting}
            onUsernameChange={setCreatePendingUsername}
            onPasswordChange={setCreatePendingPassword}
          />
        </section>
      )}
      {hasManualCardPayment && !effectiveManualCardPaymentAuthorization && (
        <p className="sale-action-error" role="alert">
          {t("pendingSale.authorization.manualCardConfigurationUnavailable")}
        </p>
      )}
      {hasManualCardPayment
        && effectiveManualCardPaymentAuthorization
        && effectiveManualCardPaymentAuthorization.mode !== "DIRECT" && (
        <section className="pending-sale-authorization" aria-label={t("pendingSale.authorization.manualCardTitle")}>
          <h3>{t("pendingSale.authorization.manualCardTitle")}</h3>
          <SaleOperationAuthorizationFields
            locale={locale}
            currentUsername={currentUsername}
            authorization={effectiveManualCardPaymentAuthorization}
            username={manualCardPaymentUsername}
            password={manualCardPaymentPassword}
            disabled={disabled || submitting}
            onUsernameChange={setManualCardPaymentUsername}
            onPasswordChange={setManualCardPaymentPassword}
          />
        </section>
      )}
      {hasTransferPayment && !effectiveTransferPaymentAuthorization && (
        <p className="sale-action-error" role="alert">
          {t("pendingSale.authorization.transferConfigurationUnavailable")}
        </p>
      )}
      {hasTransferPayment
        && effectiveTransferPaymentAuthorization
        && effectiveTransferPaymentAuthorization.mode !== "DIRECT" && (
        <section className="pending-sale-authorization" aria-label={t("pendingSale.authorization.transferTitle")}>
          <h3>{t("pendingSale.authorization.transferTitle")}</h3>
          <SaleOperationAuthorizationFields
            locale={locale}
            currentUsername={currentUsername}
            authorization={effectiveTransferPaymentAuthorization}
            username={transferPaymentUsername}
            password={transferPaymentPassword}
            disabled={disabled || submitting}
            onUsernameChange={setTransferPaymentUsername}
            onPasswordChange={setTransferPaymentPassword}
          />
        </section>
      )}
      {payments.length > 0 && <ul aria-label={t("pendingSale.initialPayments")}>{payments.map((payment) => <li key={payment.id}><span>{paymentLabel(payment)}: {money(payment.amountCents, locale)} ({t(`paymentTerminal.status.${payment.status}`)})</span>{payment.kind === "INTEGRATED_CARD" && ["PENDING", "SENT", "TIMEOUT"].includes(payment.status) ? <button type="button" disabled={disabled || queryingOperationId === payment.operationId} onClick={() => void queryCard(payment)}>{t("pendingSale.queryCard")}</button> : payment.kind === "INTEGRATED_CARD" && payment.status === "APPROVED" ? <span>{t("pendingSale.approvedCardRequiresVoid")}</span> : <button type="button" disabled={disabled || createDurable} onClick={() => removePayment(payment)}>{t("pendingSale.removePayment")}</button>}</li>)}</ul>}
      {allowPayments && resolvedMethods.card && effectiveCardPaymentMode === null && (
        <p className="sale-action-error" role="alert">
          {t("pendingSale.cardConfigurationUnavailable")}
        </p>
      )}
      {allowPayments
        && resolvedMethods.card
        && effectiveCardPaymentMode === "MANUAL"
        && !hasManualCardPayment
        && !effectiveManualCardPaymentAuthorization && (
        <p className="sale-action-error" role="alert">
          {t("pendingSale.authorization.manualCardConfigurationUnavailable")}
        </p>
      )}
      {allowPayments && <label className="pending-sale-allocation-amount">{t("pendingSale.paymentAmount")}<input aria-label={t("pendingSale.paymentAmount")} inputMode="decimal" value={allocationAmount} disabled={disabled || hasCardEffect || createDurable || summary.pendingCents <= 0 || uncertain} placeholder={money(summary.pendingCents, locale)} onChange={(event) => { if (!createDurable) setAllocationAmount(event.target.value); }} /></label>}
      {allowPayments && <div className="pending-sale-payment-actions">
        <button type="button" className="pending-sale-payment-button" disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.cash || summary.pendingCents <= 0 || uncertain} onClick={openCash}>{t("pendingSale.addCash")}</button>
        <button type="button" className="pending-sale-payment-button" disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.card || !effectiveCardPaymentMode || saleMutationSecurityUnavailable || (effectiveCardPaymentMode === "MANUAL" && !effectiveManualCardPaymentAuthorization) || summary.pendingCents <= 0 || uncertain || cardCreditAuthorizationBlocked} onClick={() => void chargeCard()}>{t("pendingSale.addCard")}</button>
        <button type="button" className="pending-sale-payment-button" disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.transfer || summary.pendingCents <= 0 || uncertain} onClick={() => { if (!createDurable) setTransferOpen(true); }}>{t("pendingSale.addTransfer")}</button>
      </div>}
      {transferOpen && <fieldset aria-label={t("receivables.payment.transfer")} disabled={createDurable}><legend>{t("receivables.payment.transfer")}</legend><label>{t("receivables.payment.amount")}<input aria-label={t("pendingSale.transferAmount")} inputMode="decimal" value={transferAmount} onChange={(event) => setTransferAmount(event.target.value)} /></label><label>{t("receivables.payment.transferReference")}<input value={transferReference} onChange={(event) => setTransferReference(event.target.value)} /></label><button type="button" onClick={saveTransfer}>{t("pendingSale.saveTransfer")}</button><button type="button" onClick={() => setTransferOpen(false)}>{t("pendingSale.cancelTransfer")}</button></fieldset>}
      {error && <p className="sale-action-error" role="alert">{error}</p>}
      <footer className="pending-sale-footer"><button type="button" className="pending-sale-cancel-button" disabled={submitting || hasCardEffect || createDurable} onClick={cancelPendingSale}>{t("common.cancel")}</button><button type="button" className="pending-sale-confirm-button" aria-label={createDurable && !submitting ? `${t("pendingSale.retryCreate")} · ${confirmLabel ?? t("pendingSale.confirm")}` : undefined} disabled={disabled || submitting || quoteLoading || !quoteReady || uncertain || cardFinalFailure || summary.pendingCents < 0 || (requireFullPayment && summary.pendingCents !== 0) || !draft.dueDate || creditConfirmationBlocked} onClick={() => void confirm()}>{submitting ? t("pendingSale.creating") : createDurable ? t("pendingSale.retryCreate") : confirmLabel ?? t("pendingSale.confirm")}</button></footer>
    </section>
    {cashOpen && <CashPaymentDialog totalCents={cashAmountCents} submitting={false} error="" initialMode="touch" onCancel={() => setCashOpen(false)} onConfirm={(receivedCents) => { if (createDurable) return; const amountCents = cashAmountCents; setPayments((current) => [...current, { id: uuid(), kind: "CASH", methodId: resolvedMethods.cash!, amountCents, deliveredCents: receivedCents, changeCents: receivedCents - amountCents, status: "APPROVED" }]); setAllocationAmount(""); setCashOpen(false); }} />}
    {manualCardOpen && <div className="sale-action-overlay manual-card-payment-overlay" role="presentation">
      <div className="manual-card-payment-dialog">
        <ManualCardReferenceDialog
          busy={submitting}
          onCancel={() => {
            setManualCardOpen(false);
            setManualCardAmountCents(0);
          }}
          onConfirm={(reference) => addManualCard(manualCardAmountCents, reference)}
        />
      </div>
    </div>}
    <SaleMutationAuthorizationDialog
      open={Boolean(mutationAuthorizationAction)}
      locale={locale}
      currentUsername={currentUsername}
      requirements={mutationCredentialRequirements}
      busy={submitting}
      error={error}
      onCancel={() => setMutationAuthorizationAction(null)}
      onConfirm={submitMutationAuthorization}
    />
  </div>;
}
