import { useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import { remainingPaymentCents, type AllocationKind, type PaymentSession } from "../sale/paymentOrchestration";
import type { LocaleCode } from "../types";

type Props = {
  locale: LocaleCode;
  session: PaymentSession;
  providers: string[];
  manualCardEnabled: boolean;
  onAdd: (input: { kind: AllocationKind; amountCents: number; provider?: string; reference?: string }) => void;
  onQuery: (operationId: string) => void;
  onManage?: (operationId: string) => void;
};

const localeName: Record<LocaleCode, string> = { es: "es-ES", en: "en-US", zh: "zh-CN" };

export function PaymentAllocationPanel({ locale, session, providers, manualCardEnabled, onAdd, onQuery, onManage }: Props) {
  const t = createTranslator(locale);
  const money = (cents: number) => (cents / 100).toLocaleString(localeName[locale], { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const remaining = remainingPaymentCents(session);
  const [amount, setAmount] = useState(String(remaining / 100));
  const amountCents = Math.round(Number(amount.replace(",", ".")) * 100);
  const compensationRequired = session.status === "COMPENSATION_REQUIRED";
  return <section className="payment-allocation-panel" aria-label={t("payment.split.title")}>
    <h3>{t("payment.split.title")}</h3>
    <strong>{t("payment.split.remaining")}: {money(remaining)}</strong>
    {compensationRequired && <p role="alert">{t("payment.split.compensationRequired")}</p>}
    <ul>{session.allocations.map((allocation) => <li key={allocation.idempotencyKey}>
      <span>{allocation.provider ?? t(allocation.kind === "CASH" ? "payment.split.cash" : "payment.split.manualCard")}</span>{" · "}
      <span>{money(allocation.amountCents)}</span>{" · "}<b>{t(`payment.split.status.${allocation.status}`)}</b>
      {allocation.authorization && <span>{` · ${allocation.authorization}`}</span>}
      {allocation.message && <span>{` · ${allocation.message}`}</span>}
      {(allocation.status === "TIMEOUT" || allocation.status === "PENDING") && allocation.operationId &&
        <button type="button" onClick={() => onQuery(allocation.operationId!)}>{t("payment.split.query")}</button>}
      {allocation.operationId && onManage && <button type="button" onClick={() => onManage(allocation.operationId!)}>{t("payment.split.manage")}</button>}
    </li>)}</ul>
    {remaining > 0 && !compensationRequired && <div>
      <label>{t("payment.split.amount")} <input value={amount} onChange={(event) => setAmount(event.currentTarget.value)} /></label>
      <button type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => onAdd({ kind: "CASH", amountCents })}>{t("payment.split.cash")}</button>
      {manualCardEnabled && <button type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => { const reference=globalThis.prompt?.(t("payment.split.manualReference"))?.trim(); if(reference)onAdd({ kind: "MANUAL_CARD", amountCents, reference }); }}>{t("payment.split.manualCard")}</button>}
      {providers.map((provider) => <button key={provider} type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => onAdd({ kind: "INTEGRATED_CARD", amountCents, provider })}>{provider}</button>)}
    </div>}
  </section>;
}
