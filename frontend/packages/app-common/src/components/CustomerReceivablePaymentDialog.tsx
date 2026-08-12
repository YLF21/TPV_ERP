import { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import type { PaymentAllocation, PaymentSession } from "../sale/paymentOrchestration";
import { printCustomerReceivablePaymentReceipt, type CustomerReceivablePaymentReceiptSnapshot } from "../sale/ticketPrinting";
import { CashPaymentResultDialog, type TicketPrintUiStatus } from "./CashPaymentResultDialog";
import { PaymentAllocationPanel, type PaymentAllocationInput } from "./PaymentAllocationPanel";

export type CustomerReceivable = {
  documentId: string;
  documentType: "ALBARAN_VENTA" | "FACTURA_VENTA";
  documentNumber: string;
  customerId: string;
  customerName: string;
  issueDate: string;
  dueDate?: string | null;
  total: number | string;
  paidTotal: number | string;
  pendingTotal: number | string;
  status: "PENDIENTE" | "PARCIAL" | "PAGADO";
  overdue: boolean;
};

type Request = <T>(path: string, options?: {
  method?: string;
  token?: string;
  body?: unknown;
}) => Promise<T>;

type Props = {
  locale?: LocaleCode;
  interfaceMode?: "KEYBOARD" | "TOUCH";
  receivable: CustomerReceivable;
  token?: string;
  terminalCode: string;
  terminalContext?: TerminalContext;
  printReceipt?: typeof printCustomerReceivablePaymentReceipt;
  request?: Request;
  onCancel: () => void;
  onPayment?: (value: CustomerReceivable, retryPrint?: () => Promise<unknown>) => void;
  onPaid: (value: CustomerReceivable, retryPrint?: () => Promise<unknown>) => void;
};

type Method = {
  id: string;
  name?: string;
  nombre?: string;
  active?: boolean;
  requiresReference?: boolean;
};

type TerminalPaymentConfiguration = {
  rules?: { cardManualEnabled?: boolean; integratedCardEnabled?: boolean };
  configuration?: { provider?: string; enabled?: boolean };
};

type Attempt = {
  paymentId: string;
  amount: string;
  methodId: string;
  status: "CREATED" | "PENDING" | "SENT" | "TIMEOUT" | "APPROVED" | "DECLINED" | "ERROR" | "CANCELLED";
  finalOutcome?: boolean;
};

type StandardAttempt = {
  requestId: string;
  kind: "cash" | "card" | "transfer";
  item: Record<string, unknown>;
};

type PaymentMutationResult = {
  receivable: CustomerReceivable;
  paymentReceipt: CustomerReceivablePaymentReceiptSnapshot;
};

type CashCompletion = {
  receivable: CustomerReceivable;
  receipt: CustomerReceivablePaymentReceiptSnapshot;
  totalCents: number;
  receivedCents: number;
  changeCents: number;
  printStatus: TicketPrintUiStatus;
};

const uuid = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
export const receivablePaymentAttemptKey = (terminalCode: string, documentId: string) =>
  `tpverp.receivable.${terminalCode}.${documentId}.card-attempt`;

const decimal = (cents: number) => (cents / 100).toFixed(2);
const finalCardFailure = (attempt: Attempt) => ["DECLINED", "CANCELLED"].includes(attempt.status)
  || (attempt.status === "ERROR" && attempt.finalOutcome === true);

function cardAllocationStatus(status: Attempt["status"]): PaymentAllocation["status"] {
  if (status === "CREATED" || status === "SENT") return "PENDING";
  return status;
}

export function CustomerReceivablePaymentDialog({
  locale = "es",
  interfaceMode = "KEYBOARD",
  receivable,
  token,
  terminalCode,
  terminalContext,
  printReceipt = printCustomerReceivablePaymentReceipt,
  request = apiRequest,
  onCancel,
  onPayment,
  onPaid,
}: Props) {
  const t = createTranslator(locale);
  const [activeReceivable, setActiveReceivable] = useState(receivable);
  const [openingPendingCents, setOpeningPendingCents] = useState(
    Math.round(Number(receivable.pendingTotal) * 100),
  );
  const [recordedAllocations, setRecordedAllocations] = useState<PaymentAllocation[]>([]);
  const pendingCents = Math.round(Number(activeReceivable.pendingTotal) * 100);
  const [methods, setMethods] = useState<{
    cash?: Method;
    card?: Method;
    transfer?: Method;
  }>({});
  const [providers, setProviders] = useState<string[]>([]);
  const [manualCardEnabled, setManualCardEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [cashCompletion, setCashCompletion] = useState<CashCompletion | null>(null);
  const mounted = useRef(true);
  const storageKey = receivablePaymentAttemptKey(terminalCode, activeReceivable.documentId);
  const standardKey = `${storageKey}.standard`;
  const [standardAttempt, setStandardAttempt] = useState<StandardAttempt | null>(() => {
    try {
      const stored = globalThis.localStorage?.getItem(standardKey);
      return stored ? JSON.parse(stored) as StandardAttempt : null;
    } catch {
      return null;
    }
  });
  const [cardAttempt, setCardAttempt] = useState<Attempt | null>(() => {
    try {
      const stored = globalThis.localStorage?.getItem(storageKey);
      return stored ? JSON.parse(stored) as Attempt : null;
    } catch {
      return null;
    }
  });
  const collectable = activeReceivable.status !== "PAGADO" && pendingCents > 0;
  const unsafeCard = cardAttempt != null && !finalCardFailure(cardAttempt);

  useEffect(() => {
    if (receivable.documentId === activeReceivable.documentId) return;
    setActiveReceivable(receivable);
    setOpeningPendingCents(Math.round(Number(receivable.pendingTotal) * 100));
    setRecordedAllocations([]);
  }, [activeReceivable.documentId, receivable]);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    let current = true;
    Promise.all([
      request<Method[]>("/payment-methods", { token }),
      request<TerminalPaymentConfiguration>("/terminal-configuration/payment", { token })
        .catch(() => ({} as TerminalPaymentConfiguration)),
    ]).then(([rows, configuration]) => {
      if (!current || !mounted.current) return;
      const active = rows.filter((row) => row.active !== false);
      const find = (name: string) => active.find((row) =>
        (row.name ?? row.nombre)?.trim().toUpperCase() === name);
      setMethods({
        cash: find("EFECTIVO"),
        card: find("TARJETA"),
        transfer: find("TRANSFERENCIA"),
      });
      setManualCardEnabled(configuration.rules?.cardManualEnabled === true);
      const provider = configuration.configuration?.provider?.trim();
      setProviders(configuration.rules?.integratedCardEnabled
        && configuration.configuration?.enabled
        && provider ? [provider] : []);
    }).catch((failure) => {
      if (current && mounted.current) {
        setError(failure instanceof Error ? failure.message : t("receivables.payment.methodsLoadError"));
      }
    });
    return () => { current = false; };
  }, [locale, request, token]);

  async function postPayment(item: Record<string, unknown>) {
    return request<PaymentMutationResult>(
      `/customer-receivables/${activeReceivable.documentId}/payments`,
      { token, body: { pagos: [item] } },
    );
  }

  function publishPayment(
    value: CustomerReceivable,
    retryPrint?: () => Promise<unknown>,
  ) {
    setActiveReceivable(value);
    if (value.status === "PAGADO" || Number(value.pendingTotal) <= 0) {
      onPaid(value, retryPrint);
    } else {
      onPayment?.(value, retryPrint);
    }
  }

  function rememberAllocation(allocation: PaymentAllocation) {
    setRecordedAllocations((current) => current.some((candidate) =>
      candidate.idempotencyKey === allocation.idempotencyKey)
      ? current
      : [...current, allocation]);
  }

  async function printResult(result: PaymentMutationResult) {
    let retryPrint: (() => Promise<unknown>) | undefined;
    if (terminalContext) {
      const retry = () => printReceipt(result.paymentReceipt, terminalContext, undefined, locale);
      try {
        if ((await retry()).status === "FAILED") retryPrint = retry;
      } catch {
        retryPrint = retry;
      }
    }
    if (mounted.current) publishPayment(result.receivable, retryPrint);
  }

  async function printCashCompletion(completion: CashCompletion) {
    if (!terminalContext) return;
    setCashCompletion((current) => current?.receipt.paymentId === completion.receipt.paymentId
      ? { ...current, printStatus: "PRINTING" }
      : current);
    try {
      const outcome = await printReceipt(completion.receipt, terminalContext, undefined, locale);
      if (mounted.current) setCashCompletion((current) => current?.receipt.paymentId === completion.receipt.paymentId
        ? { ...current, printStatus: outcome.status }
        : current);
    } catch {
      if (mounted.current) setCashCompletion((current) => current?.receipt.paymentId === completion.receipt.paymentId
        ? { ...current, printStatus: "FAILED" }
        : current);
    }
  }

  async function payStandard(input?: PaymentAllocationInput) {
    const attempt = standardAttempt;
    const kind = attempt?.kind ?? (input?.kind === "TRANSFER"
      ? "transfer"
      : input?.kind === "MANUAL_CARD" ? "card" : "cash");
    const method = kind === "cash" ? methods.cash : kind === "card" ? methods.card : methods.transfer;
    if (!attempt && (!collectable || !input || input.amountCents <= 0 || input.amountCents > pendingCents)) {
      setError(t("receivables.payment.invalidAmount"));
      return;
    }
    if (!attempt && !method?.id) {
      setError(t("receivables.payment.methodMissing"));
      return;
    }
    setBusy(true);
    setError("");
    try {
      const requestId = attempt?.requestId ?? uuid();
      const amountCents = input?.amountCents ?? Math.round(Number(attempt?.item.importe) * 100);
      const item = attempt?.item ?? {
        metodoPagoId: method!.id,
        importe: decimal(amountCents),
        principal: true,
        entregado: kind === "cash" ? decimal(input?.deliveredCents ?? amountCents) : null,
        cambio: kind === "cash" ? decimal(input?.changeCents ?? 0) : null,
        reference: input?.reference?.trim() || null,
        cardMode: kind === "card" ? "MANUAL" : null,
        transferDate: kind === "transfer" && input?.transferDate ? input.transferDate : null,
        requestId,
      };
      const nextAttempt = attempt ?? { requestId, kind, item };
      globalThis.localStorage?.setItem(standardKey, JSON.stringify(nextAttempt));
      setStandardAttempt(nextAttempt);
      const result = await postPayment(item);
      globalThis.localStorage?.removeItem(standardKey);
      setStandardAttempt(null);
      setBusy(false);
      rememberAllocation({
        kind: kind === "cash" ? "CASH" : kind === "card" ? "MANUAL_CARD" : "TRANSFER",
        amountCents: Math.round(Number(item.importe) * 100),
        deliveredCents: item.entregado == null
          ? undefined : Math.round(Number(item.entregado) * 100),
        changeCents: item.cambio == null
          ? undefined : Math.round(Number(item.cambio) * 100),
        reference: item.reference ? String(item.reference) : undefined,
        transferDate: item.transferDate ? String(item.transferDate) : undefined,
        idempotencyKey: requestId,
        status: "APPROVED",
      });
      if (kind === "cash") {
        const confirmedCents = Math.round(Number(result.paymentReceipt.amount) * 100);
        const receivedCents = input?.deliveredCents ?? confirmedCents;
        const completion: CashCompletion = {
          receivable: result.receivable,
          receipt: result.paymentReceipt,
          totalCents: confirmedCents,
          receivedCents,
          changeCents: Math.max(0, receivedCents - confirmedCents),
          printStatus: terminalContext ? "PRINTING" : "SKIPPED",
        };
        if (mounted.current) setCashCompletion(completion);
        if (terminalContext) await printCashCompletion(completion);
        return;
      }
      await printResult(result);
    } catch (failure) {
      if (mounted.current) {
        setError(failure instanceof Error ? failure.message : t("receivables.payment.saveError"));
        setBusy(false);
      }
    }
  }

  async function finishApprovedCard(attempt: Attempt) {
    const result = await postPayment({
      metodoPagoId: attempt.methodId,
      importe: attempt.amount,
      principal: true,
      cardMode: "INTEGRATED",
      paymentTerminalStatus: "APPROVED",
      requestId: attempt.paymentId,
      paymentTerminalOperationId: attempt.paymentId,
    });
    globalThis.localStorage?.removeItem(storageKey);
    setCardAttempt(null);
    setBusy(false);
    rememberAllocation({
      kind: "INTEGRATED_CARD",
      amountCents: Math.round(Number(attempt.amount) * 100),
      provider: providers[0],
      idempotencyKey: attempt.paymentId,
      operationId: attempt.paymentId,
      status: "APPROVED",
    });
    await printResult(result);
  }

  async function payCard(amountCents: number) {
    if (!collectable || amountCents <= 0 || amountCents > pendingCents) {
      setError(t("receivables.payment.invalidAmount"));
      return;
    }
    if (!methods.card?.id || providers.length === 0) {
      setError(t("receivables.payment.methodMissing"));
      return;
    }
    setBusy(true);
    setError("");
    let attempt: Attempt;
    try {
      const stored = globalThis.localStorage?.getItem(storageKey);
      attempt = stored ? JSON.parse(stored) as Attempt : {
        paymentId: uuid(),
        amount: decimal(amountCents),
        methodId: methods.card.id,
        status: "CREATED",
      };
      if (attempt.amount !== decimal(amountCents) || attempt.methodId !== methods.card.id) {
        setError(t("receivables.payment.cardAmountConflict"));
        setBusy(false);
        return;
      }
      globalThis.localStorage?.setItem(storageKey, JSON.stringify(attempt));
      setCardAttempt(attempt);
      if (attempt.status !== "APPROVED") {
        const terminal = await request<{ status: string; finalOutcome?: boolean }>(
          `/customer-receivables/${activeReceivable.documentId}/card-charges`,
          { token, body: { paymentId: attempt.paymentId, amount: attempt.amount } },
        );
        attempt = {
          ...attempt,
          status: terminal.status as Attempt["status"],
          finalOutcome: terminal.finalOutcome,
        };
        if (finalCardFailure(attempt)) {
          globalThis.localStorage?.removeItem(storageKey);
          setCardAttempt(null);
        } else {
          globalThis.localStorage?.setItem(storageKey, JSON.stringify(attempt));
          setCardAttempt(attempt);
        }
        if (attempt.status !== "APPROVED") {
          setError(`${t("receivables.payment.cardState")}: ${t(`paymentTerminal.status.${attempt.status}`)}. ${t("receivables.payment.queryBeforeRetry")}`);
          setBusy(false);
          return;
        }
      }
      await finishApprovedCard(attempt);
    } catch (failure) {
      if (mounted.current) {
        setError(failure instanceof Error
          ? `${failure.message}. ${t("receivables.payment.retrySameId")}`
          : t("pendingSale.cardUncertain"));
        setBusy(false);
      }
    }
  }

  async function queryAttempt(operationId: string) {
    if (standardAttempt?.requestId === operationId) {
      await payStandard();
      return;
    }
    if (!cardAttempt || cardAttempt.paymentId !== operationId) return;
    setBusy(true);
    setError("");
    try {
      const terminal = await request<{ status: string; finalOutcome?: boolean }>(
        `/customer-receivables/${activeReceivable.documentId}/card-charges/${cardAttempt.paymentId}/query`,
        { method: "POST", token },
      );
      const next = {
        ...cardAttempt,
        status: terminal.status as Attempt["status"],
        finalOutcome: terminal.finalOutcome,
      };
      if (finalCardFailure(next)) {
        globalThis.localStorage?.removeItem(storageKey);
        setCardAttempt(null);
      } else {
        globalThis.localStorage?.setItem(storageKey, JSON.stringify(next));
        setCardAttempt(next);
      }
      if (next.status === "APPROVED") {
        await finishApprovedCard(next);
        return;
      }
      setError(`${t("receivables.payment.cardState")}: ${t(`paymentTerminal.status.${next.status}`)}. ${t("receivables.payment.cardNotFinal")}`);
    } catch (failure) {
      if (mounted.current) {
        setError(failure instanceof Error ? failure.message : t("pendingSale.cardQueryError"));
      }
    }
    if (mounted.current) setBusy(false);
  }

  const allocations = useMemo<PaymentAllocation[]>(() => {
    if (standardAttempt) {
      return [{
        kind: standardAttempt.kind === "cash"
          ? "CASH"
          : standardAttempt.kind === "card" ? "MANUAL_CARD" : "TRANSFER",
        amountCents: Math.round(Number(standardAttempt.item.importe) * 100),
        deliveredCents: standardAttempt.item.entregado == null
          ? undefined : Math.round(Number(standardAttempt.item.entregado) * 100),
        changeCents: standardAttempt.item.cambio == null
          ? undefined : Math.round(Number(standardAttempt.item.cambio) * 100),
        reference: standardAttempt.item.reference ? String(standardAttempt.item.reference) : undefined,
        transferDate: standardAttempt.item.transferDate ? String(standardAttempt.item.transferDate) : undefined,
        idempotencyKey: standardAttempt.requestId,
        operationId: standardAttempt.requestId,
        status: "PENDING",
        message: t("receivables.payment.unknownResult"),
      }];
    }
    if (cardAttempt) {
      return [{
        kind: "INTEGRATED_CARD",
        amountCents: Math.round(Number(cardAttempt.amount) * 100),
        provider: providers[0],
        idempotencyKey: cardAttempt.paymentId,
        operationId: cardAttempt.paymentId,
        status: cardAllocationStatus(cardAttempt.status),
      }];
    }
    return [];
  }, [cardAttempt, providers, standardAttempt, t]);

  const session = useMemo<PaymentSession>(() => ({
    id: `receivable-${activeReceivable.documentId}`,
    totalCents: openingPendingCents,
    direction: "SALE",
    status: pendingCents === 0 ? "COVERED" : "COLLECTING",
    allocations: [...recordedAllocations, ...allocations],
  }), [activeReceivable.documentId, allocations, openingPendingCents, pendingCents, recordedAllocations]);

  if (cashCompletion) return <CashPaymentResultDialog
    locale={locale}
    ticketNumber={cashCompletion.receipt.documentNumber}
    totalCents={cashCompletion.totalCents}
    receivedCents={cashCompletion.receivedCents}
    changeCents={cashCompletion.changeCents}
    printStatus={cashCompletion.printStatus}
    onRetryPrint={cashCompletion.printStatus === "FAILED"
      ? () => void printCashCompletion(cashCompletion)
      : undefined}
    onFinish={() => {
      const completed = cashCompletion.receivable;
      setCashCompletion(null);
      publishPayment(completed);
    }}
  />;

  return <PaymentAllocationPanel
    locale={locale}
    session={session}
    providers={providers}
    manualCardEnabled={manualCardEnabled}
    cashEnabled={Boolean(methods.cash)}
    cardEnabled={Boolean(methods.card)}
    manualCardRequiresReference={Boolean(methods.card?.requiresReference)}
    voucherEnabled={false}
    transferEnabled={Boolean(methods.transfer)}
    transferRequiresReference={Boolean(methods.transfer?.requiresReference)}
    transferDateEnabled
    interfaceMode={interfaceMode}
    customerSelected
    pendingEnabled={false}
    pendingVisible={false}
    discountVisible={false}
    acceptVisible
    acceptSubmitsCurrent
    clearVisible={false}
    commentEnabled={false}
    allowAdd={collectable && !busy && !standardAttempt && !unsafeCard}
    busy={busy}
    error={!collectable ? t("receivables.payment.alreadyPaid") : error}
    onAdd={(input) => {
      if (input.kind === "CASH" || input.kind === "MANUAL_CARD" || input.kind === "TRANSFER") {
        void payStandard(input);
      } else if (input.kind === "INTEGRATED_CARD") {
        void payCard(input.amountCents);
      }
    }}
    onQuery={(operationId) => void queryAttempt(operationId)}
    onClose={onCancel}
  />;
}
