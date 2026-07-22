import { useEffect, useReducer, useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import { localizePaymentDiagnostic } from "../i18n/PaymentMessages";
import { remainingPaymentCents, type AllocationKind, type PaymentSession } from "../sale/paymentOrchestration";
import type { LocaleCode } from "../types";

type Props = {
  locale: LocaleCode;
  session: PaymentSession;
  providers: string[];
  manualCardEnabled: boolean;
  vouchers?: Array<{ code: string; balance: number | string }>;
  onAdd: (input: { kind: AllocationKind; amountCents: number; provider?: string; reference?: string }) => void;
  onQuery: (operationId: string) => void;
  onManage?: (operationId: string) => void;
  allowAdd?: boolean;
};

const localeName: Record<LocaleCode, string> = { es: "es-ES", en: "en-US", zh: "zh-CN" };

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

export function ManualCardReferenceDialog({ locale, reference, onReferenceChange, onCancel, onConfirm }: ManualCardReferenceDialogProps) {
  const t = createTranslator(locale);
  const titleId = "manual-card-reference-title";
  return <div role="dialog" aria-modal="true" aria-labelledby={titleId}>
    <h3 id={titleId}>{t("payment.split.manualCardDialogTitle")}</h3>
    <label>{t("payment.split.manualReference")}
      <input autoFocus autoComplete="off" value={reference} onChange={(event) => onReferenceChange(event.currentTarget.value)} />
    </label>
    <button type="button" disabled={!reference.trim()} onClick={onConfirm}>{t("payment.split.confirm")}</button>
    <button type="button" onClick={onCancel}>{t("payment.split.cancel")}</button>
  </div>;
}

export function PaymentAllocationPanel({ locale, session, providers, manualCardEnabled, vouchers = [], onAdd, onQuery, onManage, allowAdd = true }: Props) {
  const t = createTranslator(locale);
  const money = (cents: number) => (cents / 100).toLocaleString(localeName[locale], { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const remaining = remainingPaymentCents(session);
  const [amount, setAmount] = useState(String(remaining / 100));
  const [voucherCode, setVoucherCode] = useState("");
  const [manualCardDialog, dispatchManualCardDialog] = useReducer(manualCardDialogState, { open: false, reference: "" });
  const amountCents = Math.round(Number(amount.replace(",", ".")) * 100);
  const compensationRequired = session.status === "COMPENSATION_REQUIRED";
  const selectedVoucher = vouchers.find((voucher) => voucher.code === voucherCode);
  const voucherBalanceCents = Math.round(Number(selectedVoucher?.balance ?? 0) * 100);
  useEffect(() => setAmount(String(remaining / 100)), [remaining]);
  return <section className="payment-allocation-panel" aria-label={t("payment.split.title")}>
    <h3>{t("payment.split.title")}</h3>
    <strong>{t("payment.split.remaining")}: {money(remaining)}</strong>
    {compensationRequired && <p role="alert">{t("payment.split.compensationRequired")}</p>}
    <ul>{session.allocations.map((allocation) => <li key={allocation.idempotencyKey}>
      <span>{allocation.provider ?? t(allocation.kind === "CASH" ? "payment.split.cash" : allocation.kind === "VOUCHER" ? "payment.split.voucher" : "payment.split.manualCard")}</span>{" · "}
      <span>{money(allocation.amountCents)}</span>{" · "}<b>{t(`payment.split.status.${allocation.status}`)}</b>
      {allocation.authorization && <span>{` · ${allocation.authorization}`}</span>}
      {allocation.message && <span>{` · ${localizePaymentDiagnostic(t, allocation.message, allocation.status)}`}</span>}
      {(allocation.status === "TIMEOUT" || allocation.status === "PENDING") && allocation.operationId &&
        <button type="button" onClick={() => onQuery(allocation.operationId!)}>{t("payment.split.query")}</button>}
      {allocation.operationId && onManage && <button type="button" onClick={() => onManage(allocation.operationId!)}>{t("payment.split.manage")}</button>}
    </li>)}</ul>
    {allowAdd && remaining > 0 && !compensationRequired && <div>
      <label>{t("payment.split.amount")} <input value={amount} onChange={(event) => setAmount(event.currentTarget.value)} /></label>
      <button type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => onAdd({ kind: "CASH", amountCents })}>{t("payment.split.cash")}</button>
      {manualCardEnabled && <button type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => dispatchManualCardDialog({ type: "open" })}>{t("payment.split.manualCard")}</button>}
      {providers.map((provider) => <button key={provider} type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => onAdd({ kind: "INTEGRATED_CARD", amountCents, provider })}>{provider}</button>)}
      {vouchers.length > 0 && <div className="payment-voucher-allocation">
        <label>{t("payment.split.voucher")}
          <select value={voucherCode} onChange={(event) => setVoucherCode(event.currentTarget.value)}>
            <option value="">{t("payment.split.voucherSelect")}</option>
            {vouchers.map((voucher) => <option key={voucher.code} value={voucher.code}>{voucher.code} · {money(Math.round(Number(voucher.balance) * 100))}</option>)}
          </select>
        </label>
        <button type="button" disabled={!voucherCode || amountCents <= 0 || amountCents > remaining || amountCents > voucherBalanceCents} onClick={() => onAdd({ kind: "VOUCHER", amountCents, reference: voucherCode })}>{t("payment.split.voucherApply")}</button>
      </div>}
    </div>}
    {manualCardDialog.open && <ManualCardReferenceDialog
      locale={locale}
      reference={manualCardDialog.reference}
      onReferenceChange={(reference) => dispatchManualCardDialog({ type: "change", reference })}
      onCancel={() => dispatchManualCardDialog({ type: "cancel" })}
      onConfirm={() => {
        const reference = manualCardDialog.reference.trim();
        if (!reference) return;
        dispatchManualCardDialog({ type: "submit" });
        onAdd({ kind: "MANUAL_CARD", amountCents, reference });
      }}
    />}
  </section>;
}
