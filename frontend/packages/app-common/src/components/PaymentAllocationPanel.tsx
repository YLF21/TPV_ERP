import { useEffect, useReducer, useRef, useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import { localizePaymentDiagnostic } from "../i18n/PaymentMessages";
import {
  defaultScannerTimingConfig,
  idleScannerTimingCapture,
  scannerTimingKeyDecision,
} from "../hardware/scannerTimingDetection";
import { remainingPaymentCents, type AllocationKind, type PaymentSession } from "../sale/paymentOrchestration";
import type { LocaleCode } from "../types";
import { MemberWalletDialog, type MemberWalletLot } from "./MemberWalletDialog";

export type CheckoutMethod = "CASH" | "CARD" | "VOUCHER" | "PENDING" | "TRANSFER" | "MEMBER_BALANCE" | "MEMBER_CREDIT" | "DISCOUNT";

export type MemberWalletView = {
  loyaltyAvailable: number | string;
  returnCreditAvailable: number | string;
  totalAvailable: number | string;
  lots: MemberWalletLot[];
};

export type MemberWalletSelection = {
  requestedCents: number;
  loyaltyCents: number;
  returnCreditCents: number;
  returnCreditAvailableCents: number;
};

export type PaymentAllocationInput = {
  kind: AllocationKind;
  amountCents: number;
  provider?: string;
  voucherCode?: string;
  reference?: string;
  deliveredCents?: number;
  changeCents?: number;
  comment?: string;
  transferDate?: string;
};

type AddOptions = {
  finalizeWhenCovered?: boolean;
};

export type VoucherLookup = {
  code: string;
  balance: number | string;
  status: "ACTIVE" | "CONSUMED" | "INVALIDATED";
};

type Props = {
  locale: LocaleCode;
  session: PaymentSession;
  providers: string[];
  manualCardEnabled: boolean;
  cashEnabled?: boolean;
  cardEnabled?: boolean;
  voucherEnabled?: boolean;
  transferEnabled?: boolean;
  manualCardRequiresReference?: boolean;
  transferRequiresReference?: boolean;
  transferDateEnabled?: boolean;
  vouchers?: Array<{ code: string; balance: number | string }>;
  interfaceMode?: "KEYBOARD" | "TOUCH";
  initialMethod?: CheckoutMethod;
  customerSelected?: boolean;
  pendingEnabled?: boolean;
  pendingVisible?: boolean;
  discountVisible?: boolean;
  clearVisible?: boolean;
  acceptVisible?: boolean;
  acceptSubmitsCurrent?: boolean;
  acceptOpenSession?: boolean;
  acceptLabel?: string;
  acceptWithLockedIntegratedPayment?: boolean;
  closeDisabled?: boolean;
  commentEnabled?: boolean;
  checkoutDiscountCents?: number;
  memberBalanceCents?: number;
  memberBalanceAvailableCents?: number;
  memberWallet?: MemberWalletView | null;
  voucherOnlyRefund?: boolean;
  onResolveVoucher?: (code: string) => Promise<VoucherLookup | null>;
  onAdd: (input: PaymentAllocationInput, options?: AddOptions) => void;
  onQuery: (operationId: string) => void;
  onManage?: (operationId: string) => void;
  onClear?: () => void;
  onAccept?: () => void;
  onClose?: () => void;
  onDiscount?: (amountCents: number) => void;
  onMemberBalance?: (amountCents: number) => void;
  onMemberWallet?: (selection: MemberWalletSelection) => void;
  allowAdd?: boolean;
  busy?: boolean;
  error?: string;
};

const localeName: Record<LocaleCode, string> = { es: "es-ES", en: "en-US", zh: "zh-CN" };

function localDateInput(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

const labels = {
  es: {
    title: "COBRO", amount: "IMPORTE / RECIBIDO", document: "Nº DOCUMENTO", comment: "COMENTARIO",
    cash: "Efectivo", card: "Tarjeta", voucher: "Vale", pending: "Pendiente",
    transfer: "Transferencia", memberBalance: "Saldo socio", returnCredit: "Saldo a favor", discount: "Descuento", method: "FORMA DE PAGO",
    tableAmount: "IMPORTE", change: "Cambio", total: "TOTAL A COBRAR", paid: "COBRADO",
    remaining: "FALTA", accept: "ACEPTAR", cancel: "CANCELAR", exact: "Exacto",
    clear: "Eliminar pagos", customerRequired: "Selecciona un cliente para dejar el ticket pendiente",
    referenceRequired: "Este método requiere Nº Documento", invalid: "Introduce un importe válido",
    scannerIgnored: "Código de barras ignorado durante el cobro",
    voucherCode: "CÓDIGO DE VALE", voucherCodeRequired: "Introduce el código del vale",
    voucherNotFound: "Vale no encontrado", voucherConsumed: "Este vale ya fue consumido",
    voucherInvalidated: "Este vale está invalidado", voucherNoBalance: "Este vale no tiene saldo disponible",
    voucherLookupFailed: "No se pudo consultar el vale. Inténtalo de nuevo",
    voucherOnlyRefund: "Este ticket regalo solo puede devolverse mediante un vale.",
    voucherOnlyRemainder: "La parte que no procede de un pago real solo puede devolverse mediante un vale.",
  },
  en: {
    title: "CHECKOUT", amount: "AMOUNT / RECEIVED", document: "DOCUMENT No.", comment: "COMMENT",
    cash: "Cash", card: "Card", voucher: "Voucher", pending: "Pending",
    transfer: "Transfer", memberBalance: "Member balance", returnCredit: "Return credit", discount: "Discount", method: "PAYMENT METHOD",
    tableAmount: "AMOUNT", change: "Change", total: "TOTAL DUE", paid: "PAID",
    remaining: "REMAINING", accept: "ACCEPT", cancel: "CANCEL", exact: "Exact",
    clear: "Clear payments", customerRequired: "Select a customer before leaving the ticket pending",
    referenceRequired: "This method requires a document number", invalid: "Enter a valid amount",
    scannerIgnored: "Barcode ignored during checkout",
    voucherCode: "VOUCHER CODE", voucherCodeRequired: "Enter the voucher code",
    voucherNotFound: "Voucher not found", voucherConsumed: "This voucher has already been used",
    voucherInvalidated: "This voucher is invalidated", voucherNoBalance: "This voucher has no available balance",
    voucherLookupFailed: "The voucher could not be checked. Try again",
    voucherOnlyRefund: "This gift receipt can only be refunded as a voucher.",
    voucherOnlyRemainder: "The part not backed by a real payment can only be refunded as a voucher.",
  },
  zh: {
    title: "收款", amount: "金额 / 实收", document: "单据号", comment: "备注",
    cash: "现金", card: "银行卡", voucher: "代金券", pending: "挂账",
    transfer: "转账", memberBalance: "会员余额", returnCredit: "退货余额", discount: "折扣", method: "付款方式",
    tableAmount: "金额", change: "找零", total: "应收合计", paid: "已收",
    remaining: "未收", accept: "确认", cancel: "取消", exact: "正好",
    clear: "清除付款", customerRequired: "挂账前请选择客户",
    referenceRequired: "此方式需要单据号", invalid: "请输入有效金额",
    scannerIgnored: "收款期间已忽略条码",
    voucherCode: "代金券代码", voucherCodeRequired: "请输入代金券代码",
    voucherNotFound: "未找到代金券", voucherConsumed: "该代金券已使用",
    voucherInvalidated: "该代金券已作废", voucherNoBalance: "该代金券无可用余额",
    voucherLookupFailed: "无法查询代金券，请重试",
    voucherOnlyRefund: "礼品小票退货只能退还为代金券。",
    voucherOnlyRemainder: "非实际付款的部分只能退还为代金券。",
  },
} satisfies Record<LocaleCode, Record<string, string>>;

const methodLabels = (locale: LocaleCode) => ({
  CASH: labels[locale].cash,
  MANUAL_CARD: labels[locale].card,
  INTEGRATED_CARD: labels[locale].card,
  VOUCHER: labels[locale].voucher,
  PENDING: labels[locale].pending,
  TRANSFER: labels[locale].transfer,
  MEMBER_CREDIT: labels[locale].returnCredit,
});

function decimalCents(value: number | string | undefined) {
  const amount = Number(value ?? 0);
  return Number.isFinite(amount) ? Math.max(0, Math.round(amount * 100)) : 0;
}

export function hasLockedIntegratedPayment(
  allocations: ReadonlyArray<{ kind: string; status: string }>,
) {
  return allocations.some((allocation) =>
    allocation.kind === "INTEGRATED_CARD"
    && !["DECLINED", "ERROR", "CANCELLED"].includes(allocation.status));
}

function checkoutMethodForAllocation(kind: AllocationKind): CheckoutMethod {
  if (kind === "MANUAL_CARD" || kind === "INTEGRATED_CARD") return "CARD";
  return kind;
}

function parseCents(value: string) {
  const normalized = value.trim().replace(",", ".");
  if (!/^\d+(?:\.\d{0,2})?$/.test(normalized)) return 0;
  return Math.round(Number(normalized) * 100);
}

function centsInput(cents: number) {
  return (Math.max(0, cents) / 100).toFixed(2).replace(".", ",");
}

export function PaymentAllocationPanel({
  locale,
  session,
  providers,
  manualCardEnabled,
  cashEnabled = true,
  cardEnabled = true,
  voucherEnabled = true,
  transferEnabled = true,
  manualCardRequiresReference = false,
  transferRequiresReference = false,
  transferDateEnabled = false,
  vouchers = [],
  interfaceMode = "KEYBOARD",
  initialMethod = "CASH",
  customerSelected = false,
  pendingEnabled = true,
  pendingVisible = true,
  discountVisible = true,
  clearVisible = true,
  acceptVisible = true,
  acceptSubmitsCurrent = false,
  acceptOpenSession = false,
  acceptLabel,
  acceptWithLockedIntegratedPayment = false,
  closeDisabled = false,
  commentEnabled = true,
  checkoutDiscountCents = 0,
  memberBalanceCents = 0,
  memberBalanceAvailableCents = 0,
  memberWallet = null,
  voucherOnlyRefund = false,
  onResolveVoucher,
  onAdd,
  onQuery,
  onManage,
  onClear,
  onAccept,
  onClose,
  onDiscount,
  onMemberBalance,
  onMemberWallet,
  allowAdd = true,
  busy = false,
  error = "",
}: Props) {
  const t = createTranslator(locale);
  const copy = labels[locale];
  const money = (cents: number) => (cents / 100).toLocaleString(localeName[locale], {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const remaining = remainingPaymentCents(session);
  const refund = session.direction === "REFUND";
  const zero = session.direction === "ZERO";
  const monetaryRefundAvailable = (session.refundPaymentAvailability ?? [])
    .filter((availability) => availability.kind && availability.kind !== "VOUCHER")
    .reduce((sum, availability) => sum + availability.availableAmountCents, 0);
  const hasVoucherOnlyRemainder = refund && !voucherOnlyRefund
    && monetaryRefundAvailable < session.totalCents;
  const approved = session.totalCents - remaining;
  const walletLoyaltyAvailableCents = memberWallet
    ? decimalCents(memberWallet.loyaltyAvailable)
    : memberBalanceAvailableCents;
  const walletReturnCreditAvailableCents = memberWallet
    ? decimalCents(memberWallet.returnCreditAvailable)
    : 0;
  const preWalletTotalCents = session.totalCents + memberBalanceCents;
  const memberBalanceLimit = Math.min(
    walletLoyaltyAvailableCents,
    preWalletTotalCents,
  );
  const memberWalletLimit = Math.min(
    walletLoyaltyAvailableCents + walletReturnCreditAvailableCents,
    preWalletTotalCents,
  );
  const [method, setMethod] = useState<CheckoutMethod>(initialMethod);
  const [amount, setAmount] = useState(centsInput(remaining));
  const [voucherCode, setVoucherCode] = useState("");
  const [reference, setReference] = useState("");
  const [transferDate, setTransferDate] = useState("");
  const [comment, setComment] = useState("");
  const [validation, setValidation] = useState("");
  const [voucherResolving, setVoucherResolving] = useState(false);
  const [walletOpen, setWalletOpen] = useState(false);
  const [focusAmountAfterSubmit, setFocusAmountAfterSubmit] = useState(false);
  const [focusVoucherAfterResolve, setFocusVoucherAfterResolve] = useState(false);
  const amountRef = useRef<HTMLInputElement>(null);
  const voucherCodeRef = useRef<HTMLInputElement>(null);
  const voucherResolveGuardRef = useRef(false);
  const referenceRef = useRef<HTMLInputElement>(null);
  const transferDateRef = useRef<HTMLInputElement>(null);
  const scannerCaptureRef = useRef(idleScannerTimingCapture);
  const amountCents = parseCents(amount);
  const cashAppliedCents = Math.min(amountCents, remaining);
  const cashChangeCents = method === "CASH" ? Math.max(0, amountCents - remaining) : 0;
  const compensationRequired = session.status === "COMPENSATION_REQUIRED";
  const effectiveRows = session.allocations.filter((allocation) =>
    !["DECLINED", "ERROR", "CANCELLED"].includes(allocation.status));
  const recoveryAllocation = effectiveRows[effectiveRows.length - 1];
  const recoveryMethod = recoveryAllocation
    ? checkoutMethodForAllocation(recoveryAllocation.kind)
    : undefined;
  const selectedMethod = (!allowAdd || compensationRequired) && recoveryMethod
    ? recoveryMethod
    : method;
  const integratedPaymentLocked = hasLockedIntegratedPayment(session.allocations);
  const integratedPaymentInFlight = session.allocations.some((allocation) =>
    allocation.kind === "INTEGRATED_CARD"
    && ["READY", "PENDING", "TIMEOUT"].includes(allocation.status));
  const entryLocked = busy || voucherResolving || !allowAdd || compensationRequired;
  const acceptAddsCurrentPayment = acceptSubmitsCurrent && remaining > 0;
  const integratedPaymentBlocksAccept = integratedPaymentInFlight
    || (integratedPaymentLocked
      && !acceptAddsCurrentPayment
      && !acceptWithLockedIntegratedPayment);

  function refundAvailabilityForMethod(value: CheckoutMethod) {
    if (!refund) return undefined;
    if (value === "VOUCHER" || value === "MEMBER_CREDIT") return remaining;
    const kinds: AllocationKind[] = value === "CASH"
      ? ["CASH"]
      : value === "CARD"
        ? ["MANUAL_CARD", "INTEGRATED_CARD"]
        : [];
    if (kinds.length === 0) return undefined;
    return (session.refundPaymentAvailability ?? [])
      .filter((availability) => availability.kind && kinds.includes(availability.kind))
      .reduce((sum, availability) => sum + availability.availableAmountCents, 0);
  }

  function refundAvailabilityForKind(kind: AllocationKind) {
    if (!refund) return 0;
    return (session.refundPaymentAvailability ?? [])
      .filter((availability) => availability.kind === kind)
      .reduce((sum, availability) => sum + availability.availableAmountCents, 0);
  }

  const cardAllocationKind: "MANUAL_CARD" | "INTEGRATED_CARD" = refund
    && refundAvailabilityForKind("MANUAL_CARD") > 0
    && refundAvailabilityForKind("INTEGRATED_CARD") <= 0
      ? "MANUAL_CARD"
      : providers.length > 0
        ? "INTEGRATED_CARD"
        : "MANUAL_CARD";

  useEffect(() => {
    setAmount(centsInput(method === "MEMBER_BALANCE" ? memberBalanceLimit : remaining));
  }, [memberBalanceLimit, method, remaining]);

  function methodAvailable(next: CheckoutMethod) {
    if (refund && voucherOnlyRefund) {
      return (next === "VOUCHER" && voucherEnabled)
        || (next === "MEMBER_CREDIT" && customerSelected && Boolean(memberWallet));
    }
    if (next === "CASH") return cashEnabled;
    if (next === "CARD") return cardEnabled && (manualCardEnabled || providers.length > 0);
    if (next === "VOUCHER") return voucherEnabled;
    if (next === "TRANSFER") return !refund && transferEnabled;
    if (next === "PENDING") return !refund && pendingVisible && pendingEnabled;
    if (next === "MEMBER_BALANCE") return !refund && customerSelected
      && Boolean(onMemberWallet || onMemberBalance)
      && (!onMemberWallet || Boolean(memberWallet))
      && memberBalanceLimit > 0 && effectiveRows.length === 0;
    if (next === "MEMBER_CREDIT") return refund && customerSelected && Boolean(memberWallet);
    if (next === "DISCOUNT") return !refund && discountVisible;
    return effectiveRows.length === 0;
  }

  function availableMethod(preferred: CheckoutMethod) {
    const candidates: CheckoutMethod[] = [
      preferred, "CASH", "CARD", "TRANSFER", "VOUCHER", "MEMBER_CREDIT", "PENDING", "MEMBER_BALANCE", "DISCOUNT",
    ];
    return candidates.find((candidate, index) =>
      candidates.indexOf(candidate) === index && methodAvailable(candidate)) ?? preferred;
  }

  useEffect(() => {
    const nextMethod = availableMethod(initialMethod);
    setMethod(nextMethod);
    queueMicrotask(() => {
      const target = nextMethod === "VOUCHER" && !refund
        ? voucherCodeRef.current
        : amountRef.current;
      target?.focus();
      target?.select();
    });
  }, [
    cardEnabled, cashEnabled, customerSelected, discountVisible, initialMethod, manualCardEnabled,
    memberWallet, memberWalletLimit, onMemberWallet, pendingEnabled, pendingVisible, providers.length,
    transferEnabled, voucherEnabled, voucherOnlyRefund,
  ]);

  useEffect(() => {
    if (!focusAmountAfterSubmit || busy || !allowAdd || compensationRequired) return;
    setFocusAmountAfterSubmit(false);
    queueMicrotask(() => {
      amountRef.current?.focus();
      amountRef.current?.select();
    });
  }, [allowAdd, busy, compensationRequired, focusAmountAfterSubmit, remaining]);

  useEffect(() => {
    if (!focusVoucherAfterResolve || voucherResolving || busy || !allowAdd || compensationRequired) return;
    setFocusVoucherAfterResolve(false);
    queueMicrotask(() => {
      voucherCodeRef.current?.focus();
      voucherCodeRef.current?.select();
    });
  }, [allowAdd, busy, compensationRequired, focusVoucherAfterResolve, voucherResolving]);

  useEffect(() => {
    const protectCheckoutFromScanner = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.altKey || event.metaKey) {
        scannerCaptureRef.current = idleScannerTimingCapture;
        return;
      }
      if (event.target !== amountRef.current) {
        scannerCaptureRef.current = idleScannerTimingCapture;
        return;
      }

      const decision = scannerTimingKeyDecision(
        scannerCaptureRef.current,
        event.key,
        defaultScannerTimingConfig,
        event.timeStamp,
        amountRef.current?.value ?? centsInput(remaining),
      );
      scannerCaptureRef.current = decision.next;
      if (!decision.detected) return;

      event.preventDefault();
      event.stopImmediatePropagation();
      setAmount(decision.restoreInput ?? centsInput(remaining));
      setValidation(copy.scannerIgnored);
      queueMicrotask(() => {
        amountRef.current?.focus();
        amountRef.current?.select();
      });
    };

    window.addEventListener("keydown", protectCheckoutFromScanner, true);
    return () => window.removeEventListener("keydown", protectCheckoutFromScanner, true);
  }, [
    copy.scannerIgnored,
    remaining,
  ]);

  function selectMethod(next: CheckoutMethod) {
    if (!allowAdd || compensationRequired || busy || !methodAvailable(next)) return;
    if (next === "MEMBER_BALANCE" && onMemberWallet) {
      setValidation("");
      setWalletOpen(true);
      return;
    }
    setMethod(next);
    setValidation("");
    setAmount(centsInput(next === "MEMBER_BALANCE" ? memberBalanceLimit : remaining));
    queueMicrotask(() => {
      const target = next === "VOUCHER" && !refund
        ? voucherCodeRef.current
        : amountRef.current;
      target?.focus();
      target?.select();
    });
  }

  async function submitVoucher(finalizeWhenCovered: boolean) {
    if (voucherResolveGuardRef.current) return;
    voucherResolveGuardRef.current = true;
    const requestedCode = voucherCode.trim();
    let completed = false;
    setVoucherResolving(true);
    setValidation("");
    try {
      const listedVoucher = vouchers.find((voucher) =>
        voucher.code.toLocaleUpperCase() === requestedCode.toLocaleUpperCase());
      const resolved = onResolveVoucher
        ? await onResolveVoucher(requestedCode)
        : listedVoucher
          ? { ...listedVoucher, status: "ACTIVE" as const }
          : null;
      if (!resolved) {
        setValidation(copy.voucherNotFound);
        return;
      }
      if (resolved.status === "CONSUMED") {
        setValidation(copy.voucherConsumed);
        return;
      }
      if (resolved.status === "INVALIDATED") {
        setValidation(copy.voucherInvalidated);
        return;
      }
      const balanceCents = Math.round(Number(resolved.balance) * 100);
      const appliedCents = Math.min(remaining, balanceCents);
      if (!Number.isFinite(balanceCents) || appliedCents <= 0) {
        setValidation(copy.voucherNoBalance);
        return;
      }
      onAdd({
        kind: "VOUCHER",
        amountCents: appliedCents,
        voucherCode: resolved.code,
        ...(reference.trim() ? { reference: reference.trim() } : {}),
        ...(comment.trim() ? { comment: comment.trim() } : {}),
      }, { finalizeWhenCovered });
      completed = true;
      setVoucherCode("");
      setReference("");
      setComment("");
      setFocusAmountAfterSubmit(true);
    } catch {
      setValidation(copy.voucherLookupFailed);
    } finally {
      voucherResolveGuardRef.current = false;
      setVoucherResolving(false);
      if (!completed) {
        setFocusVoucherAfterResolve(true);
      }
    }
  }

  function submit(finalizeWhenCovered = false) {
    if (!allowAdd || compensationRequired || busy || voucherResolving) return;
    if (!methodAvailable(method)) {
      setMethod(availableMethod(method));
      return;
    }
    if (method === "PENDING" && !customerSelected) {
      setValidation(copy.customerRequired);
      return;
    }
    if ((method !== "VOUCHER" || refund)
        && (amountCents <= 0 || (method !== "CASH" && method !== "MEMBER_BALANCE" && amountCents > remaining))) {
      setValidation(copy.invalid);
      return;
    }
    if (method === "MEMBER_BALANCE") {
      if (!onMemberBalance || effectiveRows.length > 0 || amountCents > memberBalanceLimit) {
        setValidation(copy.invalid);
        return;
      }
      onMemberBalance(amountCents);
      setValidation("");
      return;
    }
    if (method === "DISCOUNT") {
      if (!onDiscount || effectiveRows.length > 0 || amountCents >= session.totalCents + checkoutDiscountCents) {
        setValidation(copy.invalid);
        return;
      }
      onDiscount(amountCents);
      setValidation("");
      return;
    }
    if (method === "VOUCHER" && !refund && !voucherCode.trim()) {
      setValidation(copy.voucherCodeRequired);
      queueMicrotask(() => {
        voucherCodeRef.current?.focus();
        voucherCodeRef.current?.select();
      });
      return;
    }
    if (method === "VOUCHER" && !refund) {
      void submitVoucher(finalizeWhenCovered);
      return;
    }
    const needsReference = (method === "CARD" && cardAllocationKind === "MANUAL_CARD" && manualCardRequiresReference)
      || (method === "TRANSFER" && transferRequiresReference);
    if (needsReference && !reference.trim()) {
      setValidation(copy.referenceRequired);
      queueMicrotask(() => {
        referenceRef.current?.focus();
        referenceRef.current?.select();
      });
      return;
    }
    const common = {
      amountCents: method === "CASH" ? cashAppliedCents : amountCents,
      ...(reference.trim() ? { reference: reference.trim() } : {}),
      ...(commentEnabled && comment.trim() ? { comment: comment.trim() } : {}),
    };
    if (method === "CASH") {
      onAdd({
        kind: "CASH",
        ...common,
        deliveredCents: refund ? cashAppliedCents : amountCents,
        changeCents: refund ? 0 : cashChangeCents,
      }, { finalizeWhenCovered });
    } else if (method === "CARD") {
      onAdd(cardAllocationKind === "INTEGRATED_CARD" && providers[0]
        ? { kind: "INTEGRATED_CARD", provider: providers[0], ...common }
        : { kind: "MANUAL_CARD", ...common }, { finalizeWhenCovered });
    } else if (method === "VOUCHER") {
      onAdd({ kind: "VOUCHER", ...common }, { finalizeWhenCovered });
    } else if (method === "TRANSFER") {
      onAdd({
        kind: "TRANSFER",
        ...common,
        ...(transferDateEnabled && transferDate ? { transferDate } : {}),
      }, { finalizeWhenCovered });
    } else if (method === "MEMBER_CREDIT") {
      onAdd({ kind: "MEMBER_CREDIT", ...common }, { finalizeWhenCovered });
    } else {
      onAdd({ kind: "PENDING", ...common }, { finalizeWhenCovered });
    }
    setVoucherCode("");
    setReference("");
    setTransferDate("");
    setComment("");
    setValidation("");
    setFocusAmountAfterSubmit(true);
  }

  function appendKey(value: string) {
    setAmount((current) => {
      if (value === "clear") return "";
      if (value === "backspace") return current.slice(0, -1);
      if (value === "," && current.includes(",")) return current;
      const next = current === "0,00" ? value : `${current}${value}`;
      const [, decimals = ""] = next.split(",");
      return decimals.length <= 2 ? next : current;
    });
    amountRef.current?.focus();
  }

  useEffect(() => {
    if (interfaceMode !== "KEYBOARD") return;
    const handleKey = (event: KeyboardEvent) => {
      if (walletOpen) return;
      if (event.ctrlKey && event.key.toLocaleLowerCase() === "o") {
        event.preventDefault();
        document.getElementById("checkout-comment")?.focus();
        return;
      }
      if (event.ctrlKey && event.key.toLocaleLowerCase() === "n") {
        event.preventDefault();
        document.getElementById("checkout-reference")?.focus();
        return;
      }
      const methodKey: Partial<Record<string, CheckoutMethod>> = {
        "*": "CASH", "+": "CARD", F9: "VOUCHER", F8: "PENDING",
        F7: "TRANSFER", F10: refund ? "MEMBER_CREDIT" : "MEMBER_BALANCE", F11: "DISCOUNT",
      };
      const next = methodKey[event.key];
      if (next) {
        event.preventDefault();
        selectMethod(next);
        return;
      }
      if (event.key === "F12") {
        event.preventDefault();
        if (!busy && !integratedPaymentLocked) onClear?.();
      } else if (event.key === "Escape") {
        event.preventDefault();
        if (!busy && !integratedPaymentLocked && !closeDisabled) onClose?.();
      } else if (event.key === "Enter"
          && (event.target === amountRef.current
            || event.target === voucherCodeRef.current
            || event.target === referenceRef.current
            || event.target === transferDateRef.current
            || event.target instanceof HTMLBodyElement)) {
        event.preventDefault();
        submit(true);
      }
    };
    window.addEventListener("keydown", handleKey, true);
    return () => window.removeEventListener("keydown", handleKey, true);
  }, [
    allowAdd, amountCents, busy, cashAppliedCents, cashChangeCents, checkoutDiscountCents,
    closeDisabled, comment, compensationRequired, customerSelected, effectiveRows.length, integratedPaymentLocked, interfaceMode,
    cardEnabled, cashEnabled, commentEnabled, manualCardEnabled, manualCardRequiresReference, method,
    memberBalanceAvailableCents, memberBalanceCents, memberWallet, memberWalletLimit, onClear, onClose, onDiscount,
    onMemberBalance, onMemberWallet, pendingEnabled, providers, reference, refund, remaining,
    session.totalCents, transferDate, transferDateEnabled, transferEnabled, transferRequiresReference, voucherCode,
    voucherEnabled, voucherOnlyRefund, voucherResolving, onResolveVoucher, walletOpen,
  ]);

  const allMethods: Array<{ value: CheckoutMethod; shortcut: string; visible?: boolean; disabled?: boolean }> = [
    { value: "CASH", shortcut: "*", visible: cashEnabled && !voucherOnlyRefund },
    { value: "CARD", shortcut: "+", visible: cardEnabled && !voucherOnlyRefund, disabled: !manualCardEnabled && providers.length === 0 },
    { value: "VOUCHER", shortcut: "F9", visible: voucherEnabled || voucherOnlyRefund, disabled: !voucherEnabled },
    { value: "PENDING", shortcut: "F8", visible: !refund && pendingVisible, disabled: !pendingEnabled },
    { value: "TRANSFER", shortcut: "F7", visible: !refund && transferEnabled },
    { value: "MEMBER_BALANCE", shortcut: "F10", visible: !refund && Boolean(onMemberWallet || onMemberBalance), disabled: !customerSelected || (Boolean(onMemberWallet) && !memberWallet) || memberBalanceLimit <= 0 || effectiveRows.length > 0 },
    { value: "MEMBER_CREDIT", shortcut: "F10", visible: refund, disabled: !customerSelected || !memberWallet },
    { value: "DISCOUNT", shortcut: "F11", visible: !refund && discountVisible, disabled: effectiveRows.length > 0 },
  ];
  const methods = allMethods.filter((item) => item.visible !== false);
  const buttonLabel = (value: CheckoutMethod) => ({
    CASH: copy.cash, CARD: copy.card, VOUCHER: copy.voucher,
    PENDING: copy.pending, TRANSFER: copy.transfer, MEMBER_BALANCE: copy.memberBalance,
    MEMBER_CREDIT: copy.returnCredit,
    DISCOUNT: copy.discount,
  })[value];

  return <><div className="sale-checkout-overlay" role="presentation">
    <section className={`sale-checkout-dialog ${interfaceMode === "TOUCH" ? "is-touch" : "is-keyboard"}`}
      role="dialog" aria-modal="true" aria-labelledby="sale-checkout-title" aria-busy={busy}>
      <header className="sale-checkout-header">
        <h2 id="sale-checkout-title">{refund ? (locale === "es" ? "DEVOLUCIÓN" : locale === "en" ? "REFUND" : "退款") : copy.title}</h2>
        <button type="button" aria-label={copy.cancel}
          disabled={busy || integratedPaymentLocked || closeDisabled}
          onClick={onClose}>×</button>
      </header>

      <div className="sale-checkout-body">
        <div className="sale-checkout-main">
          {!zero && <div className="sale-checkout-entry">
            <label>
              <span>{refund
                ? (locale === "es" ? "IMPORTE A DEVOLVER" : locale === "en" ? "REFUND AMOUNT" : "\u9000\u6b3e\u91d1\u989d")
                : copy.amount}</span>
              <input ref={amountRef} inputMode="decimal" autoComplete="off" value={amount}
                disabled={entryLocked || (selectedMethod === "VOUCHER" && !refund)}
                onChange={(event) => setAmount(event.currentTarget.value)} />
            </label>
            <div className={`sale-checkout-meta${selectedMethod === "VOUCHER" ? " has-voucher" : ""}${selectedMethod === "TRANSFER" && transferDateEnabled ? " has-transfer-date" : ""}${!commentEnabled ? " no-comment" : ""}`}>
              {selectedMethod === "VOUCHER" && !refund && <label><span>{copy.voucherCode}</span>
                <input ref={voucherCodeRef} id="checkout-voucher-code" autoComplete="off"
                  value={voucherCode}
                  disabled={entryLocked}
                  onChange={(event) => setVoucherCode(event.currentTarget.value)} />
              </label>}
              <label><span>{copy.document}</span>
                <input ref={referenceRef} id="checkout-reference" autoComplete="off" value={reference}
                  disabled={entryLocked}
                  onChange={(event) => setReference(event.currentTarget.value)} />
              </label>
              {selectedMethod === "TRANSFER" && transferDateEnabled && <label>
                <span>{locale === "es" ? "FECHA DE TRANSFERENCIA" : locale === "en" ? "TRANSFER DATE" : "转账日期"}</span>
                <input ref={transferDateRef} id="checkout-transfer-date" type="date"
                  max={localDateInput()} value={transferDate} disabled={entryLocked}
                  onChange={(event) => setTransferDate(event.currentTarget.value)} />
              </label>}
              {commentEnabled && <label><span>{copy.comment}</span>
                <input id="checkout-comment" autoComplete="off" maxLength={512} value={comment}
                  disabled={entryLocked}
                  onChange={(event) => setComment(event.currentTarget.value)} />
              </label>}
            </div>
          </div>}

          {selectedMethod === "CASH" && !refund && !entryLocked && <p className="sale-checkout-change">
            {copy.change}: <strong>{money(cashChangeCents)} €</strong>
          </p>}

          {refund && (voucherOnlyRefund || hasVoucherOnlyRemainder) && <p
            className="sale-checkout-policy-notice"
            role="note"
          >
            {voucherOnlyRefund ? copy.voucherOnlyRefund : copy.voucherOnlyRemainder}
          </p>}

          {!zero && <div className="sale-checkout-methods" aria-label={copy.method}>
            {methods.map((item) => <button key={item.value} type="button"
              className={selectedMethod === item.value ? "selected" : ""}
              disabled={item.disabled || entryLocked}
              onClick={() => selectMethod(item.value)}>
              <span>{buttonLabel(item.value)}
                {refund && refundAvailabilityForMethod(item.value) !== undefined && <small>
                  {t("payment.refund.originalAvailable")}: {money(refundAvailabilityForMethod(item.value) ?? 0)} €
                </small>}
              </span>
              {interfaceMode === "KEYBOARD" && <kbd aria-hidden="true">{item.shortcut}</kbd>}
            </button>)}
          </div>}

          <div className="sale-checkout-table-wrap">
            <table className="sale-checkout-table">
              <thead><tr>
                <th>{copy.method}</th><th>{copy.tableAmount}</th>
                <th>{copy.document}</th><th>{copy.comment}</th>
              </tr></thead>
              <tbody>
                {memberBalanceCents > 0 && <tr className="discount-row">
                  <td>{copy.memberBalance}</td><td>-{money(memberBalanceCents)} €</td><td>—</td><td>—</td>
                </tr>}
                {checkoutDiscountCents > 0 && <tr className="discount-row">
                  <td>{copy.discount}</td><td>-{money(checkoutDiscountCents)} €</td><td>—</td><td>—</td>
                </tr>}
                {session.allocations.map((allocation) => <tr key={allocation.idempotencyKey}>
                  <td>{methodLabels(locale)[allocation.kind]}
                    {allocation.voucherCode && <small className="sale-checkout-voucher-code">
                      {copy.voucherCode}: {allocation.voucherCode}
                    </small>}
                  </td>
                  <td>{money(allocation.amountCents)} €</td>
                  <td>{allocation.reference || "—"}
                    {allocation.transferDate && <small>
                      {locale === "es" ? "Fecha" : locale === "en" ? "Date" : "日期"}: {allocation.transferDate}
                    </small>}
                  </td>
                  <td>{allocation.comment || "—"}
                    {(allocation.status === "TIMEOUT" || allocation.status === "PENDING") && allocation.operationId &&
                      <button type="button" onClick={() => onQuery(allocation.operationId!)}>{t("payment.split.query")}</button>}
                    {allocation.operationId && onManage &&
                      <button type="button" onClick={() => onManage(allocation.operationId!)}>{t("payment.split.manage")}</button>}
                    {allocation.message && <small>{localizePaymentDiagnostic(t, allocation.message, allocation.status)}</small>}
                  </td>
                </tr>)}
                {session.allocations.length === 0 && memberBalanceCents === 0 && checkoutDiscountCents === 0 &&
                  <tr className="empty-row"><td colSpan={4}>—</td></tr>}
              </tbody>
            </table>
          </div>

          <div className="sale-checkout-totals">
            <span>{refund ? (locale === "es" ? "TOTAL A DEVOLVER" : locale === "en" ? "TOTAL REFUND" : "退款总额") : copy.total}<strong>{money(session.totalCents)} €</strong></span>
            <span>{refund ? (locale === "es" ? "DEVUELTO" : locale === "en" ? "REFUNDED" : "已退款") : copy.paid}<strong>{money(approved)} €</strong></span>
            <span className="remaining">{refund ? (locale === "es" ? "PENDIENTE" : locale === "en" ? "REMAINING" : "待退款") : copy.remaining}<strong>{money(remaining)} €</strong></span>
          </div>

          {(validation || error) && <p className="sale-checkout-error" role="alert">{validation || error}</p>}
          {compensationRequired && <p className="sale-checkout-error" role="alert">{t("payment.split.compensationRequired")}</p>}

          <footer className="sale-checkout-footer">
            {clearVisible && <button type="button" className="clear"
              disabled={busy || integratedPaymentLocked
                || (session.allocations.length === 0 && memberBalanceCents === 0 && checkoutDiscountCents === 0)}
              onClick={onClear}>{interfaceMode === "KEYBOARD" && <kbd>F12</kbd>}{copy.clear}</button>}
            <span />
            <button type="button" disabled={busy || integratedPaymentLocked || closeDisabled}
              onClick={onClose}>{copy.cancel}</button>
            {acceptVisible && <button type="button" className="primary"
              disabled={busy || integratedPaymentBlocksAccept || (acceptAddsCurrentPayment
                ? !allowAdd || compensationRequired || remaining <= 0
                : !acceptOpenSession && session.status !== "COVERED")}
              onClick={acceptAddsCurrentPayment ? () => submit(true) : onAccept}>
              {acceptLabel ?? copy.accept}
            </button>}
          </footer>
        </div>

        {interfaceMode === "TOUCH" && !zero && <aside className="sale-checkout-keypad" aria-label="Teclado numérico">
          <button type="button" className="exact" disabled={entryLocked}
            onClick={() => setAmount(centsInput(remaining))}>{copy.exact}</button>
          {[500, 1000, 2000, 5000].map((cents) =>
            <button type="button" key={cents} disabled={entryLocked}
              onClick={() => setAmount(centsInput(cents))}>{cents / 100} €</button>)}
          {["7", "8", "9", "4", "5", "6", "1", "2", "3", ",", "0", "backspace"].map((key) =>
            <button type="button" key={key} disabled={entryLocked} onClick={() => appendKey(key)}>
              {key === "backspace" ? "⌫" : key}
            </button>)}
          <button type="button" className="clear-key" disabled={entryLocked}
            onClick={() => appendKey("clear")}>C</button>
          <button type="button" className="enter-key" disabled={entryLocked}
            onClick={() => submit(true)}>↵</button>
        </aside>}
      </div>
    </section>
  </div>
    {walletOpen && memberWallet && <MemberWalletDialog
      locale={locale}
      lots={memberWallet.lots}
      maxAmountCents={memberWalletLimit}
      busy={busy}
      error={error || undefined}
      onCancel={() => setWalletOpen(false)}
      onConfirm={(requestedCents) => {
        const loyaltyCents = Math.min(requestedCents, walletLoyaltyAvailableCents, preWalletTotalCents);
        const returnCreditCents = Math.min(
          requestedCents - loyaltyCents,
          walletReturnCreditAvailableCents,
          preWalletTotalCents - loyaltyCents,
        );
        onMemberWallet?.({
          requestedCents,
          loyaltyCents,
          returnCreditCents,
          returnCreditAvailableCents: walletReturnCreditAvailableCents,
        });
        setWalletOpen(false);
      }}
    />}
  </>;
}

type ManualCardDialogState = { open: boolean; reference: string };
type ManualCardDialogAction = { type: "open" | "cancel" | "submit" } | { type: "change"; reference: string };

export function manualCardDialogState(state: ManualCardDialogState, action: ManualCardDialogAction): ManualCardDialogState {
  switch (action.type) {
    case "open": return { open: true, reference: "" };
    case "change": return { ...state, reference: action.reference };
    case "cancel":
    case "submit": return { open: false, reference: "" };
  }
}

type ManualCardReferenceDialogProps = {
  locale: LocaleCode;
  reference: string;
  onReferenceChange: (reference: string) => void;
  onCancel: () => void;
  onConfirm: () => void;
};

export function ManualCardReferenceDialog({
  locale, reference, onReferenceChange, onCancel, onConfirm,
}: ManualCardReferenceDialogProps) {
  const t = createTranslator(locale);
  const [, dispatch] = useReducer(manualCardDialogState, { open: true, reference });
  return <div role="dialog" aria-modal="true" aria-labelledby="manual-card-reference-title">
    <h3 id="manual-card-reference-title">{t("payment.split.manualCardDialogTitle")}</h3>
    <label>{t("payment.split.manualReference")}
      <input autoFocus autoComplete="off" value={reference}
        onChange={(event) => { dispatch({ type: "change", reference: event.currentTarget.value }); onReferenceChange(event.currentTarget.value); }} />
    </label>
    <button type="button" disabled={!reference.trim()} onClick={onConfirm}>{t("payment.split.confirm")}</button>
    <button type="button" onClick={onCancel}>{t("payment.split.cancel")}</button>
  </div>;
}

type LegacyPaymentAllocationPanelProps = {
  locale: LocaleCode;
  session: PaymentSession;
  providers: string[];
  manualCardEnabled: boolean;
  vouchers?: Array<{ code: string; balance: number | string }>;
  onAdd: (input: PaymentAllocationInput) => void;
  onQuery: (operationId: string) => void;
  onManage?: (operationId: string) => void;
  allowAdd?: boolean;
};

/**
 * Recovery and compensation states keep the compact, non-modal presentation.
 * The operational sale screen uses PaymentAllocationPanel as the unified checkout.
 */
export function LegacyPaymentAllocationPanel({
  locale,
  session,
  providers,
  manualCardEnabled,
  vouchers = [],
  onAdd,
  onQuery,
  onManage,
  allowAdd = true,
}: LegacyPaymentAllocationPanelProps) {
  const t = createTranslator(locale);
  const money = (cents: number) => (cents / 100).toLocaleString(localeName[locale], {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const remaining = remainingPaymentCents(session);
  const [amount, setAmount] = useState(String(remaining / 100));
  const [voucherCode, setVoucherCode] = useState("");
  const [manualCardDialog, dispatchManualCardDialog] = useReducer(
    manualCardDialogState,
    { open: false, reference: "" },
  );
  const amountCents = Math.round(Number(amount.replace(",", ".")) * 100);
  const compensationRequired = session.status === "COMPENSATION_REQUIRED";
  const selectedVoucher = vouchers.find((voucher) => voucher.code === voucherCode);
  const voucherBalanceCents = Math.round(Number(selectedVoucher?.balance ?? 0) * 100);

  useEffect(() => setAmount(String(remaining / 100)), [remaining]);

  return <section className="payment-allocation-panel" aria-label={t("payment.split.title")}>
    <h3>{t("payment.split.title")}</h3>
    <strong>{t("payment.split.remaining")}: {money(remaining)}</strong>
    {compensationRequired && <p role="alert">{t("payment.split.compensationRequired")}</p>}
    <ul>{session.allocations.map((allocation, index) => <li
      key={allocation.idempotencyKey || allocation.operationId || `${allocation.kind}-${index}`}>
      <span>{allocation.provider ?? t(
        allocation.kind === "CASH"
          ? "payment.split.cash"
          : allocation.kind === "VOUCHER"
            ? "payment.split.voucher"
            : "payment.split.manualCard",
      )}</span>{" · "}
      <span>{money(allocation.amountCents)}</span>{" · "}
      <b>{t(`payment.split.status.${allocation.status}`)}</b>
      {allocation.authorization && <span>{` · ${allocation.authorization}`}</span>}
      {allocation.message && <span>{` · ${localizePaymentDiagnostic(t, allocation.message, allocation.status)}`}</span>}
      {(allocation.status === "TIMEOUT" || allocation.status === "PENDING") && allocation.operationId
        && <button type="button" onClick={() => onQuery(allocation.operationId!)}>{t("payment.split.query")}</button>}
      {allocation.operationId && onManage
        && <button type="button" onClick={() => onManage(allocation.operationId!)}>{t("payment.split.manage")}</button>}
    </li>)}</ul>

    {allowAdd && remaining > 0 && !compensationRequired && <div>
      <label>{t("payment.split.amount")} <input value={amount}
        onChange={(event) => setAmount(event.currentTarget.value)} /></label>
      <button type="button" disabled={amountCents <= 0 || amountCents > remaining}
        onClick={() => onAdd({ kind: "CASH", amountCents })}>{t("payment.split.cash")}</button>
      {manualCardEnabled && <button type="button" disabled={amountCents <= 0 || amountCents > remaining}
        onClick={() => dispatchManualCardDialog({ type: "open" })}>{t("payment.split.manualCard")}</button>}
      {providers.map((provider) => <button key={provider} type="button"
        disabled={amountCents <= 0 || amountCents > remaining}
        onClick={() => onAdd({ kind: "INTEGRATED_CARD", amountCents, provider })}>{provider}</button>)}
      {vouchers.length > 0 && <div className="payment-voucher-allocation">
        <label>{t("payment.split.voucher")}
          <select value={voucherCode} onChange={(event) => setVoucherCode(event.currentTarget.value)}>
            <option value="">{t("payment.split.voucherSelect")}</option>
            {vouchers.map((voucher) => <option key={voucher.code} value={voucher.code}>
              {voucher.code} · {money(Math.round(Number(voucher.balance) * 100))}
            </option>)}
          </select>
        </label>
        <button type="button"
          disabled={!voucherCode || amountCents <= 0 || amountCents > remaining || amountCents > voucherBalanceCents}
          onClick={() => onAdd({ kind: "VOUCHER", amountCents, reference: voucherCode })}>
          {t("payment.split.voucherApply")}
        </button>
      </div>}
    </div>}

    {manualCardDialog.open && <ManualCardReferenceDialog
      locale={locale}
      reference={manualCardDialog.reference}
      onReferenceChange={(reference) => dispatchManualCardDialog({ type: "change", reference })}
      onCancel={() => dispatchManualCardDialog({ type: "cancel" })}
      onConfirm={() => {
        const trimmedReference = manualCardDialog.reference.trim();
        if (!trimmedReference) return;
        dispatchManualCardDialog({ type: "submit" });
        onAdd({ kind: "MANUAL_CARD", amountCents, reference: trimmedReference });
      }}
    />}
  </section>;
}
