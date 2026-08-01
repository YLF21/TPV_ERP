import { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import type { ConfirmedTicketPrintSnapshot } from "../sale/ticketPrinting";
import { printConfirmedTicketAutomatically } from "../sale/ticketPrinting";
import type { PaymentRefundLineOption, PaymentRefundLineSelection } from "../sale/paymentOperations";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

type PreviewPayment = {
  id: string;
  methodName: string;
  amount: number | string;
  paymentTerminalProvider?: string | null;
};

type ReturnPreview = {
  ticketId: string;
  ticketNumber: string;
  date: string;
  total: number | string;
  lines: PaymentRefundLineOption[];
  payments: PreviewPayment[];
};

type ReturnResult = {
  documentId: string;
  voucherCode?: string | null;
  receipt: ConfirmedTicketPrintSnapshot;
};

type ReturnAttempt = {
  signature: string;
  requestId: string;
  cards: Array<{ originalPaymentId: string; operationId: string; idempotencyKey: string }>;
};

type Props = {
  token?: string;
  locale: LocaleCode;
  terminalContext: TerminalContext;
  authorization?: SaleOperationAuthorization;
  onClose: () => void;
  onFiscalMutation?: () => void;
};

export function TicketReturnDialog({
  token,
  locale,
  terminalContext,
  authorization = {
    mode: "DIRECT",
    requireUsername: false,
    requirePassword: false,
  },
  onClose,
  onFiscalMutation,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const [ticketNumber, setTicketNumber] = useState("");
  const [preview, setPreview] = useState<ReturnPreview | null>(null);
  const [selections, setSelections] = useState<PaymentRefundLineSelection[]>([]);
  const [cashAmount, setCashAmount] = useState("0.00");
  const [voucherAmount, setVoucherAmount] = useState("0.00");
  const [cardAmounts, setCardAmounts] = useState<Record<string, string>>({});
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  const amount = useMemo(() => selections.reduce((total, selection) => {
    const option = preview?.lines.find((candidate) => candidate.lineId === selection.lineId);
    if (!option) return total;
    const available = Number(option.refundableQuantity);
    return total + (available > 0
      ? Number(option.refundableTotal) * Number(selection.quantity) / available
      : 0);
  }, 0), [preview, selections]);
  const allocated = Number(cashAmount || 0) + Number(voucherAmount || 0)
    + Object.values(cardAmounts).reduce((total, value) => total + Number(value || 0), 0);
  const allocationMatches = amount > 0 && Math.abs(allocated - amount) < 0.005;

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || busy) return;
      event.preventDefault();
      onClose();
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [busy, onClose]);

  useEffect(() => {
    if (!preview) return;
    let remaining = amount;
    const nextCards: Record<string, string> = {};
    for (const payment of preview.payments.filter((candidate) => Boolean(candidate.paymentTerminalProvider))) {
      const cardAmount = Math.min(remaining, Number(payment.amount));
      if (cardAmount > 0) {
        nextCards[payment.id] = cardAmount.toFixed(2);
        remaining = Math.max(0, remaining - cardAmount);
      }
    }
    setCardAmounts(nextCards);
    setCashAmount(remaining.toFixed(2));
    setVoucherAmount("0.00");
  }, [amount, preview]);

  async function searchTicket() {
    const normalized = ticketNumber.trim();
    if (!normalized || busy) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const result = await apiRequest<ReturnPreview>(
        `/tickets/return-preview?ticketNumber=${encodeURIComponent(normalized)}`,
        { token },
      );
      setPreview(result);
      setTicketNumber(result.ticketNumber);
      setSelections([]);
      setAuthorizerUsername("");
      setPassword("");
      if (result.lines.length === 0) setMessage(t("ticketReturn.noAvailableLines"));
    } catch (reason) {
      setPreview(null);
      setError(reason instanceof Error ? reason.message : t("ticketReturn.searchError"));
    } finally {
      setBusy(false);
    }
  }

  function selection(lineId: string) {
    return selections.find((candidate) => candidate.lineId === lineId);
  }

  function toggleLine(option: PaymentRefundLineOption, checked: boolean) {
    setSelections((current) => checked
      ? [...current.filter((candidate) => candidate.lineId !== option.lineId), {
          lineId: option.lineId,
          quantity: String(option.refundableQuantity),
          serialNumbers: [],
        }]
      : current.filter((candidate) => candidate.lineId !== option.lineId));
  }

  function updateQuantity(option: PaymentRefundLineOption, quantity: string) {
    setSelections((current) => current.map((candidate) => candidate.lineId === option.lineId
      ? { ...candidate, quantity }
      : candidate));
  }

  function toggleSerial(option: PaymentRefundLineOption, serial: string, checked: boolean) {
    const current = selection(option.lineId);
    const serialNumbers = checked
      ? [...(current?.serialNumbers ?? []), serial]
      : (current?.serialNumbers ?? []).filter((candidate) => candidate !== serial);
    setSelections((values) => [
      ...values.filter((candidate) => candidate.lineId !== option.lineId),
      ...(serialNumbers.length > 0 ? [{
        lineId: option.lineId,
        quantity: String(serialNumbers.length),
        serialNumbers,
      }] : []),
    ]);
  }

  async function confirmReturn() {
    if (!preview || !allocationMatches || !saleOperationAuthorizationComplete(
      authorization,
      authorizerUsername,
      password,
    ) || busy) return;
    setBusy(true);
    setError("");
    setMessage("");
    const attemptKey = `tpv:ticket-return:${preview.ticketId}`;
    try {
      const cardDrafts = preview.payments
        .filter((payment) => Number(cardAmounts[payment.id] ?? 0) > 0)
        .map((payment) => ({
          originalPaymentId: payment.id,
          amount: Number(cardAmounts[payment.id]).toFixed(2),
        }));
      const bodyLines = selections.filter((line) => Number(line.quantity) > 0);
      const signature = JSON.stringify({
        cashAmount: Number(cashAmount || 0).toFixed(2),
        voucherAmount: Number(voucherAmount || 0).toFixed(2),
        cardDrafts,
        lines: bodyLines,
      });
      const stored = readAttempt(globalThis.localStorage?.getItem(attemptKey));
      const attempt = stored?.signature === signature ? stored : {
        signature,
        requestId: randomId(),
        cards: cardDrafts.map((card) => ({
          originalPaymentId: card.originalPaymentId,
          operationId: randomId(),
          idempotencyKey: randomId(),
        })),
      };
      globalThis.localStorage?.setItem(attemptKey, JSON.stringify(attempt));
      const cards = cardDrafts.map((card) => ({
        ...card,
        ...attempt.cards.find((candidate) => candidate.originalPaymentId === card.originalPaymentId)!,
      }));
      const result = await apiRequest<ReturnResult>(
        `/tickets/${encodeURIComponent(preview.ticketId)}/returns`,
        {
          token,
          method: "POST",
          body: {
            requestId: attempt.requestId,
            ...saleOperationCredentials(
              authorization,
              authorizerUsername,
              password,
            ),
            cashAmount: Number(cashAmount || 0).toFixed(2),
            voucherAmount: Number(voucherAmount || 0).toFixed(2),
            cards,
            lines: bodyLines,
          },
        },
      );
      globalThis.localStorage?.removeItem(attemptKey);
      onFiscalMutation?.();
      const print = await printConfirmedTicketAutomatically(result.receipt, terminalContext);
      setPreview(null);
      setSelections([]);
      setAuthorizerUsername("");
      setPassword("");
      setMessage(result.voucherCode
        ? `${t("ticketReturn.successVoucher")} ${result.voucherCode}`
        : t("ticketReturn.success"));
      if (print.status === "FAILED") setError(print.technicalMessage ?? t("ticketReturn.printError"));
    } catch (reason) {
      setPassword("");
      if (reason instanceof Error && /DECLINED|CANCELLED|ERROR/.test(reason.message)) {
        globalThis.localStorage?.removeItem(attemptKey);
      }
      setError(reason instanceof Error ? reason.message : t("ticketReturn.confirmError"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog wide ticket-return-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ticket-return-title"
        aria-busy={busy}
      >
        <header>
          <div>
            <h2 id="ticket-return-title">{t("ticketReturn.title")}</h2>
            <p>{t("ticketReturn.description")}</p>
          </div>
          <button type="button" aria-label={t("common.close")} disabled={busy} onClick={onClose}>×</button>
        </header>

        <div className="ticket-return-search">
          <label>
            <span>{t("ticketReturn.ticketCode")}</span>
            <input
              autoFocus
              autoComplete="off"
              value={ticketNumber}
              onChange={(event) => setTicketNumber(event.currentTarget.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  void searchTicket();
                }
              }}
            />
          </label>
          <button type="button" className="primary" disabled={!ticketNumber.trim() || busy} onClick={() => void searchTicket()}>
            {t("ticketReturn.search")}
          </button>
        </div>

        {preview && (
          <>
            <div className="ticket-return-summary">
              <span><small>{t("ticketReturn.ticket")}</small><strong>{preview.ticketNumber}</strong></span>
              <span><small>{t("ticketReturn.date")}</small><strong>{new Date(`${preview.date}T00:00:00`).toLocaleDateString(locale)}</strong></span>
              <span><small>{t("ticketReturn.originalTotal")}</small><strong>{money(preview.total, locale)}</strong></span>
            </div>
            <div className="ticket-return-lines">
              {preview.lines.map((option) => {
                const selected = selection(option.lineId);
                const serials = option.refundableSerialNumbers ?? [];
                return (
                  <article key={option.lineId}>
                    <div className="ticket-return-line-heading">
                      <label>
                        {!serials.length && (
                          <input
                            type="checkbox"
                            checked={Boolean(selected)}
                            onChange={(event) => toggleLine(option, event.currentTarget.checked)}
                          />
                        )}
                        <span><strong>{option.name}</strong><small>{option.code}</small></span>
                      </label>
                      <span>{t("ticketReturn.available")}: {String(option.refundableQuantity)}</span>
                    </div>
                    {serials.length > 0 ? (
                      <div className="ticket-return-serials">
                        {serials.map((serial) => (
                          <label key={serial}>
                            <input
                              type="checkbox"
                              checked={selected?.serialNumbers?.includes(serial) ?? false}
                              onChange={(event) => toggleSerial(option, serial, event.currentTarget.checked)}
                            />
                            <span>S/N: {serial}</span>
                          </label>
                        ))}
                      </div>
                    ) : selected ? (
                      <label className="ticket-return-quantity">
                        <span>{t("ticketReturn.quantity")}</span>
                        <input
                          type="number"
                          min="0.001"
                          max={Number(option.refundableQuantity)}
                          step="0.001"
                          value={selected.quantity}
                          onChange={(event) => updateQuantity(option, event.currentTarget.value)}
                        />
                      </label>
                    ) : null}
                  </article>
                );
              })}
            </div>
            <div className="ticket-return-settlement">
              <section>
                <h3>{t("ticketReturn.refundMethods")}</h3>
                <label><span>{t("ticketReturn.cash")}</span><input type="number" min="0" step="0.01" value={cashAmount} onChange={(event) => setCashAmount(event.currentTarget.value)} /></label>
                {preview.payments.filter((payment) => Boolean(payment.paymentTerminalProvider)).map((payment) => (
                  <label key={payment.id}>
                    <span>{t("ticketReturn.card")} · {payment.paymentTerminalProvider}</span>
                    <input type="number" min="0" max={Number(payment.amount)} step="0.01" value={cardAmounts[payment.id] ?? "0.00"} onChange={(event) => setCardAmounts((current) => ({ ...current, [payment.id]: event.currentTarget.value }))} />
                  </label>
                ))}
                <label><span>{t("ticketReturn.voucher")}</span><input type="number" min="0" step="0.01" value={voucherAmount} onChange={(event) => setVoucherAmount(event.currentTarget.value)} /></label>
              </section>
              <section>
                {authorization.mode !== "DIRECT" && <h3>{t("ticketReturn.authorization")}</h3>}
                <SaleOperationAuthorizationFields
                  locale={locale}
                  authorization={authorization}
                  username={authorizerUsername}
                  password={password}
                  disabled={busy}
                  onUsernameChange={setAuthorizerUsername}
                  onPasswordChange={setPassword}
                />
                <dl>
                  <div><dt>{t("ticketReturn.returnTotal")}</dt><dd>{money(amount, locale)}</dd></div>
                  <div><dt>{t("ticketReturn.allocated")}</dt><dd>{money(allocated, locale)}</dd></div>
                </dl>
                {!allocationMatches && amount > 0 && <p className="sale-action-error">{t("ticketReturn.allocationMismatch")}</p>}
              </section>
            </div>
          </>
        )}

        {message && <p className="ticket-management-success" role="status">{message}</p>}
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        <footer className="sale-action-buttons">
          <button type="button" disabled={busy} onClick={onClose}>{t("common.cancel")}</button>
          {preview && (
            <button
              type="button"
              className="primary"
              disabled={!allocationMatches
                || !saleOperationAuthorizationComplete(
                  authorization,
                  authorizerUsername,
                  password,
                )
                || busy}
              onClick={() => void confirmReturn()}
            >
              {t("ticketReturn.confirm")}
            </button>
          )}
        </footer>
      </section>
    </div>
  );
}

function money(value: number | string, locale: LocaleCode) {
  return new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(Number(value));
}

function randomId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

function readAttempt(value: string | null | undefined): ReturnAttempt | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as ReturnAttempt;
    return parsed && typeof parsed.signature === "string"
      && typeof parsed.requestId === "string"
      && Array.isArray(parsed.cards)
      ? parsed
      : null;
  } catch {
    return null;
  }
}
