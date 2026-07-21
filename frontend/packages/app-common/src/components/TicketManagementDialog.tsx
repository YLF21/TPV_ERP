import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import type { PaymentRefundLineOption, PaymentRefundLineSelection } from "../sale/paymentOperations";
import { printConfirmedTicketAutomatically, type ConfirmedTicketPrintSnapshot } from "../sale/ticketPrinting";

type TicketPayment = {
  id: string;
  methodName: string;
  amount: number | string;
  paymentTerminalProvider?: string | null;
  paymentTerminalStatus?: string | null;
};

type Ticket = {
  id: string;
  estado: string;
  numero?: string | null;
  fecha: string;
  customerId?: string | null;
  customerName?: string | null;
  total: number | string;
  pendingTotal: number | string;
  payments: TicketPayment[];
};

type CustomerOption = { id: string; fiscalName?: string | null; clientId?: string | null };
type Voucher = { code: string; balance: number | string; status: string };
type TicketReturnResult = { receipt: ConfirmedTicketPrintSnapshot };
type ReturnAttempt = {
  signature: string;
  requestId: string;
  cards: Array<{ originalPaymentId: string; operationId: string; idempotencyKey: string }>;
};

type Props = {
  token?: string;
  locale: LocaleCode;
  terminalContext: TerminalContext;
  onClose: () => void;
};

export function TicketManagementDialog({ token, locale, terminalContext, onClose }: Props) {
  const t = createTranslator(locale);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [cancelReason, setCancelReason] = useState("");
  const [customers, setCustomers] = useState<CustomerOption[]>([]);
  const [invoiceCustomerId, setInvoiceCustomerId] = useState("");
  const [vouchers, setVouchers] = useState<Voucher[]>([]);
  const [voucherCode, setVoucherCode] = useState("");
  const [refundPrepared, setRefundPrepared] = useState(false);
  const [refundOptions, setRefundOptions] = useState<PaymentRefundLineOption[]>([]);
  const [refundLines, setRefundLines] = useState<PaymentRefundLineSelection[]>([]);
  const [refundPassword, setRefundPassword] = useState("");
  const [refundCashAmount, setRefundCashAmount] = useState("0.00");
  const [refundCardAmounts, setRefundCardAmounts] = useState<Record<string, string>>({});

  const selected = tickets.find((ticket) => ticket.id === selectedId) ?? null;
  const filtered = useMemo(() => {
    const value = query.trim().toLocaleLowerCase();
    if (!value) return tickets;
    return tickets.filter((ticket) => [ticket.numero, ticket.customerName, ticket.fecha, ticket.estado]
      .some((field) => String(field ?? "").toLocaleLowerCase().includes(value)));
  }, [query, tickets]);
  const refundAmount = refundLines.reduce((sum, line) => {
    const option = refundOptions.find((candidate) => candidate.lineId === line.lineId);
    const quantity = Number(line.quantity);
    const available = Number(option?.refundableQuantity ?? 0);
    return sum + (option && available > 0 ? Number(option.refundableTotal) * quantity / available : 0);
  }, 0);
  const refundAllocated = Number(refundCashAmount || 0) + Object.values(refundCardAmounts)
    .reduce((sum, value) => sum + Number(value || 0), 0);
  const refundAllocationMatches = Math.abs(refundAllocated - refundAmount) < 0.005;
  const activeVouchers = vouchers.filter((voucher) => voucher.status === "ACTIVE");
  const selectedTotal = Number(selected?.total ?? 0);
  const selectedPendingTotal = Number(selected?.pendingTotal ?? 0);
  const canIssueVoucher = selected?.estado === "CONFIRMADO" && selectedTotal < 0;
  const canConsumeVoucher = selectedPendingTotal > 0 && activeVouchers.length > 0 && Boolean(voucherCode);
  const voucherIssueHint = !selected
    ? t("ticketManagement.voucher.issue.selectRefund")
    : selected.estado !== "CONFIRMADO"
      ? t("ticketManagement.voucher.issue.confirmedRequired")
      : selectedTotal >= 0
        ? t("ticketManagement.voucher.issue.negativeRequired")
        : t("ticketManagement.voucher.issue.ready");
  const voucherConsumeHint = !selected
    ? t("ticketManagement.voucher.consume.selectPending")
    : selectedPendingTotal <= 0
      ? t("ticketManagement.voucher.consume.alreadyPaid")
      : activeVouchers.length === 0
        ? t("ticketManagement.voucher.consume.none")
        : !voucherCode
          ? t("ticketManagement.voucher.consume.select")
          : interpolate(t("ticketManagement.voucher.consume.ready"), { amount: formatTicketAmount(selectedPendingTotal, locale) });

  async function load(preferredId = selectedId) {
    setBusy(true);
    setError("");
    try {
      const [loadedTickets, loadedCustomers, loadedVouchers] = await Promise.all([
        apiRequest<Ticket[]>("/tickets", { token }),
        apiRequest<CustomerOption[]>("/customers/sale-options", { token }).catch(() => []),
        apiRequest<Voucher[]>("/vouchers", { token }).catch(() => [])
      ]);
      setTickets(loadedTickets);
      setCustomers(loadedCustomers);
      setVouchers(loadedVouchers);
      setSelectedId(loadedTickets.some((ticket) => ticket.id === preferredId) ? preferredId : loadedTickets[0]?.id ?? "");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("ticketManagement.error.load"));
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => { void load(""); }, [token]);
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    globalThis.addEventListener("keydown", closeOnEscape);
    return () => globalThis.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);
  useEffect(() => {
    setCancelReason("");
    setInvoiceCustomerId(selected?.customerId ?? "");
    setVoucherCode("");
    setRefundPrepared(false);
    setRefundOptions([]);
    setRefundLines([]);
    setRefundPassword("");
    setRefundCashAmount("0.00");
    setRefundCardAmounts({});
    setError("");
    setMessage("");
  }, [selectedId]);

  async function execute(action: () => Promise<unknown>, success: string) {
    if (busy) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await action();
      setMessage(success);
      await load(selectedId);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("ticketManagement.error.operation"));
    } finally {
      setBusy(false);
    }
  }

  async function reprint() {
    if (!selected) return;
    setBusy(true);
    setError("");
    try {
      const snapshot = await apiRequest<ConfirmedTicketPrintSnapshot>(`/tickets/${encodeURIComponent(selected.id)}/print`, { token });
      const outcome = await printConfirmedTicketAutomatically(snapshot, terminalContext);
      if (outcome.status === "FAILED") throw new Error(outcome.technicalMessage ?? t("ticketManagement.error.print"));
      setMessage(outcome.status === "SKIPPED" ? t("ticketManagement.print.skipped") : t("ticketManagement.print.success"));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("ticketManagement.error.print"));
    } finally {
      setBusy(false);
    }
  }

  async function prepareRefund() {
    if (!selected || busy) return;
    setBusy(true);
    setError("");
    try {
      const options = await apiRequest<PaymentRefundLineOption[]>(`/tickets/${encodeURIComponent(selected.id)}/return-options`, { token });
      const fullAmount = options.reduce((sum, option) => sum + Number(option.refundableTotal), 0);
      let remaining = fullAmount;
      const cardAmounts: Record<string, string> = {};
      for (const payment of selected.payments.filter((candidate) => Boolean(candidate.paymentTerminalProvider))) {
        const amount = Math.min(remaining, Number(payment.amount));
        if (amount > 0) {
          cardAmounts[payment.id] = amount.toFixed(2);
          remaining = Math.max(0, remaining - amount);
        }
      }
      setRefundPrepared(true);
      setRefundOptions(options);
      setRefundLines(options.map((option) => ({ lineId: option.lineId, quantity: String(option.refundableQuantity) })));
      setRefundCardAmounts(cardAmounts);
      setRefundCashAmount(remaining.toFixed(2));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("ticketManagement.error.refundPrepare"));
    } finally {
      setBusy(false);
    }
  }

  function updateRefundQuantity(lineId: string, quantity: string) {
    setRefundLines((current) => current.map((line) => line.lineId === lineId ? { ...line, quantity } : line));
  }

  async function confirmRefund() {
    if (!selected || !refundPrepared || refundAmount <= 0 || !refundPassword || !refundAllocationMatches || busy) return;
    setBusy(true);
    setError("");
    setMessage("");
    const attemptKey = `tpv:ticket-return:${selected.id}`;
    try {
      const cardDrafts = selected.payments
        .filter((payment) => Number(refundCardAmounts[payment.id] ?? 0) > 0)
        .map((payment) => ({
          originalPaymentId: payment.id,
          amount: Number(refundCardAmounts[payment.id]).toFixed(2)
        }));
      const cashAmount = Number(refundCashAmount || 0).toFixed(2);
      const lines = refundLines.filter((line) => Number(line.quantity) > 0);
      const signature = JSON.stringify({ cashAmount, cardDrafts, lines });
      const stored = globalThis.localStorage?.getItem(attemptKey);
      const parsed = readReturnAttempt(stored);
      const attempt = parsed?.signature === signature ? parsed : {
        signature,
        requestId: randomRequestId(),
        cards: cardDrafts.map((card) => ({
          originalPaymentId: card.originalPaymentId,
          operationId: randomRequestId(),
          idempotencyKey: randomRequestId()
        }))
      };
      globalThis.localStorage?.setItem(attemptKey, JSON.stringify(attempt));
      const cards = cardDrafts.map((card) => ({
        ...card,
        ...attempt.cards.find((value) => value.originalPaymentId === card.originalPaymentId)!
      }));
      const result = await apiRequest<TicketReturnResult>(`/tickets/${encodeURIComponent(selected.id)}/returns`, {
        token,
        method: "POST",
        body: {
          requestId: attempt.requestId,
          password: refundPassword,
          cashAmount,
          cards,
          lines
        }
      });
      globalThis.localStorage?.removeItem(attemptKey);
      const printOutcome = await printConfirmedTicketAutomatically(result.receipt, terminalContext);
      if (printOutcome.status === "FAILED") {
        setError(printOutcome.technicalMessage ?? t("ticketManagement.error.print"));
      }
      setMessage(t("ticketManagement.refund.success"));
      setRefundPassword("");
      setRefundPrepared(false);
      await load(selected.id);
    } catch (reason) {
      if (reason instanceof Error && /DECLINED|CANCELLED|ERROR/.test(reason.message)) {
        globalThis.localStorage?.removeItem(attemptKey);
      }
      setError(reason instanceof Error ? reason.message : t("ticketManagement.error.operation"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section className="sale-action-dialog wide ticket-management-dialog" role="dialog" aria-modal="true" aria-labelledby="ticket-management-title">
        <header className="ticket-management-header">
          <div>
            <span className="ticket-management-eyebrow">{t("ticketManagement.eyebrow")}</span>
            <h2 id="ticket-management-title">{t("ticketManagement.title")}</h2>
            <p>{t("ticketManagement.subtitle")}</p>
          </div>
          <button type="button" aria-label={t("ticketManagement.closeAria")} onClick={onClose}>×</button>
        </header>
        <div className="ticket-management-toolbar">
          <label className="ticket-management-search">
            <span>{t("ticketManagement.search")}</span>
            <input
              autoFocus
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t("ticketManagement.searchPlaceholder")}
            />
          </label>
          <span className="ticket-management-count">{interpolate(t("ticketManagement.resultCount"), { count: filtered.length })}</span>
        </div>
        <div className="ticket-management-layout">
          <aside className="ticket-management-list" aria-label={t("ticketManagement.listAria")}>
            {filtered.map((ticket) => (
              <button
                type="button"
                className={ticket.id === selectedId ? "selected" : ""}
                aria-pressed={ticket.id === selectedId}
                key={ticket.id}
                onClick={() => setSelectedId(ticket.id)}
              >
                <span className="ticket-list-main">
                  <span><strong>{ticket.numero ?? t("ticketManagement.noNumber")}</strong><b>{formatTicketAmount(ticket.total, locale)}</b></span>
                  <small>{new Date(`${ticket.fecha}T00:00:00`).toLocaleDateString(locale)} · {ticket.customerName ?? t("ticketManagement.noCustomer")}</small>
                </span>
                <span className={`ticket-list-status ticket-list-status-${ticket.estado.toLocaleLowerCase()}`}>{t(`ticketManagement.status.${ticket.estado}`)}</span>
              </button>
            ))}
            {!busy && filtered.length === 0 && <p>{t("ticketManagement.noMatches")}</p>}
          </aside>
          <div className="ticket-management-detail" aria-live="polite">
            {!selected && <div className="ticket-management-empty"><strong>{t("ticketManagement.selectTicket")}</strong><span>{t("ticketManagement.selectTicketHint")}</span></div>}
            {selected && <>
              <div className="ticket-management-summary">
                <span><small>{t("ticketManagement.number")}</small><strong>{selected.numero ?? "-"}</strong></span>
                <span><small>{t("ticketManagement.status")}</small><strong className={`ticket-summary-status ticket-summary-status-${selected.estado.toLocaleLowerCase()}`}>{t(`ticketManagement.status.${selected.estado}`)}</strong></span>
                <span><small>{t("ticketManagement.total")}</small><strong>{formatTicketAmount(selected.total, locale)}</strong></span>
              </div>
              <div className="ticket-management-actions">
                <article className="ticket-action-card ticket-action-reprint">
                  <div><h3>{t("ticketManagement.reprint.title")}</h3><p>{t("ticketManagement.reprint.hint")}</p></div>
                  <button type="button" className="primary" disabled={busy} onClick={() => void reprint()}>{t("ticketManagement.reprint.action")}</button>
                </article>
                <fieldset className="ticket-action-card ticket-action-danger">
                  <legend>{t("ticketManagement.cancel.title")}</legend>
                  <p>{t("ticketManagement.cancel.hint")}</p>
                  <div className="ticket-action-row">
                    <input aria-label={t("ticketManagement.cancel.reasonAria")} value={cancelReason} onChange={(event) => setCancelReason(event.target.value)} placeholder={t("ticketManagement.cancel.reasonPlaceholder")}/>
                    <button type="button" className="danger" disabled={busy || selected.estado !== "CONFIRMADO" || !cancelReason.trim()} onClick={() => void execute(() => apiRequest(`/tickets/${encodeURIComponent(selected.id)}/cancel`, { token, method: "POST", body: { reason: cancelReason } }), t("ticketManagement.cancel.success"))}>{t("ticketManagement.cancel.action")}</button>
                  </div>
                </fieldset>
                <fieldset className="ticket-action-card">
                  <legend>{t("ticketManagement.invoice.title")}</legend>
                  <p>{selected.customerName
                    ? interpolate(t("ticketManagement.invoice.currentCustomer"), { customer: selected.customerName })
                    : t("ticketManagement.invoice.noCustomer")}</p>
                  <div className="ticket-action-row">
                    <select aria-label={t("ticketManagement.invoice.customerAria")} value={invoiceCustomerId} onChange={(event) => setInvoiceCustomerId(event.target.value)}><option value="">{t("ticketManagement.invoice.selectCustomer")}</option>{customers.map((customer) => <option value={customer.id} key={customer.id}>{customer.fiscalName ?? customer.clientId ?? customer.id}</option>)}</select>
                    <button type="button" className="primary" disabled={busy || selected.estado !== "CONFIRMADO" || !invoiceCustomerId} onClick={() => void execute(() => apiRequest(`/tickets/${encodeURIComponent(selected.id)}/invoice`, { token, method: "POST", body: { customerId: invoiceCustomerId } }), t("ticketManagement.invoice.success"))}>{t("ticketManagement.invoice.action")}</button>
                  </div>
                </fieldset>
                <fieldset className="ticket-action-card">
                  <legend>{t("ticketManagement.refund.title")}</legend>
                  <p>{t("ticketManagement.refund.hint")}</p>
                  <button type="button" className="primary ticket-action-full" disabled={busy || selected.estado !== "CONFIRMADO"} onClick={() => void prepareRefund()}>{t("ticketManagement.refund.prepare")}</button>
                  {refundPrepared && <div className="ticket-refund-lines">
                    {refundOptions.map((option) => <label key={option.lineId}>
                      <span>{option.code} · {option.name} ({interpolate(t("ticketManagement.refund.max"), { quantity: option.refundableQuantity })})</span>
                      <input type="number" min="0" max={Number(option.refundableQuantity)} step="0.001" value={refundLines.find((line) => line.lineId === option.lineId)?.quantity ?? "0"} onChange={(event) => updateRefundQuantity(option.lineId, event.target.value)} />
                    </label>)}
                    <strong>{t("ticketManagement.refund.amount")}: {formatTicketAmount(refundAmount, locale)}</strong>
                    <div className="ticket-refund-payouts">
                      <h4>{t("ticketManagement.refund.payoutTitle")}</h4>
                      <label><span>{t("ticketManagement.refund.cash")}</span><input type="number" min="0" step="0.01" value={refundCashAmount} onChange={(event) => setRefundCashAmount(event.target.value)} /></label>
                      {selected.payments.filter((payment) => Boolean(payment.paymentTerminalProvider)).map((payment) => <label key={payment.id}>
                        <span>{interpolate(t("ticketManagement.refund.card"), { provider: payment.paymentTerminalProvider ?? "" })}</span>
                        <input type="number" min="0" max={Number(payment.amount)} step="0.01" value={refundCardAmounts[payment.id] ?? "0.00"} onChange={(event) => setRefundCardAmounts((current) => ({ ...current, [payment.id]: event.target.value }))} />
                      </label>)}
                      <small className={refundAllocationMatches ? "ticket-refund-balanced" : "sale-action-error"}>{interpolate(t(refundAllocationMatches ? "ticketManagement.refund.allocated" : "ticketManagement.refund.allocationMismatch"), { amount: formatTicketAmount(refundAllocated, locale), total: formatTicketAmount(refundAmount, locale) })}</small>
                    </div>
                    <label><span>{t("ticketManagement.refund.pin")}</span><input type="password" inputMode="numeric" value={refundPassword} onChange={(event) => setRefundPassword(event.target.value)} /></label>
                    <button type="button" className="primary" disabled={busy || refundAmount <= 0 || !refundPassword || !refundAllocationMatches} onClick={() => void confirmRefund()}>{t("ticketManagement.refund.confirm")}</button>
                  </div>}
                </fieldset>
                <fieldset className="ticket-action-card">
                  <legend>{t("ticketManagement.voucher.title")}</legend>
                  <p>{t("ticketManagement.voucher.hint")}</p>
                  <div className="ticket-voucher-grid">
                    <section className={`ticket-voucher-action ${canIssueVoucher ? "ready" : "blocked"}`}>
                      <div className="ticket-voucher-action-heading">
                        <span>{t("ticketManagement.voucher.origin")}</span>
                        <div><strong>{t("ticketManagement.voucher.issue.title")}</strong><small>{t("ticketManagement.voucher.issue.subtitle")}</small></div>
                      </div>
                      <button type="button" className="primary" title={voucherIssueHint} disabled={busy || !canIssueVoucher} onClick={() => void execute(() => apiRequest(`/vouchers/issue-from-ticket/${encodeURIComponent(selected.id)}`, { token, method: "POST" }), t("ticketManagement.voucher.issue.success"))}>{t("ticketManagement.voucher.issue.action")}</button>
                      <p className="ticket-action-hint">{voucherIssueHint}</p>
                    </section>
                    <section className={`ticket-voucher-action ${selectedPendingTotal > 0 && activeVouchers.length > 0 ? "ready" : "blocked"}`}>
                      <div className="ticket-voucher-action-heading">
                        <span>{t("ticketManagement.voucher.use")}</span>
                        <div><strong>{t("ticketManagement.voucher.consume.title")}</strong><small>{interpolate(t(activeVouchers.length === 1 ? "ticketManagement.voucher.activeOne" : "ticketManagement.voucher.activeMany"), { count: activeVouchers.length })}</small></div>
                      </div>
                      <select aria-label={t("ticketManagement.voucher.activeAria")} value={voucherCode} disabled={selectedPendingTotal <= 0 || activeVouchers.length === 0} onChange={(event) => setVoucherCode(event.target.value)}>
                        <option value="">{activeVouchers.length === 0 ? t("ticketManagement.voucher.noActive") : t("ticketManagement.voucher.selectActive")}</option>
                        {activeVouchers.map((voucher) => <option value={voucher.code} key={voucher.code}>{voucher.code} · {formatTicketAmount(voucher.balance, locale)}</option>)}
                      </select>
                      <button type="button" className="primary" title={voucherConsumeHint} disabled={busy || !canConsumeVoucher} onClick={() => void execute(() => apiRequest(`/vouchers/${encodeURIComponent(voucherCode)}/consume`, { token, method: "POST", body: { ticketId: selected.id, pendingAmount: selected.pendingTotal, reason: "APP VENTA" } }), t("ticketManagement.voucher.consume.success"))}>{t("ticketManagement.voucher.consume.action")}</button>
                      <p className="ticket-action-hint">{voucherConsumeHint}</p>
                    </section>
                  </div>
                </fieldset>
              </div>
            </>}
          </div>
        </div>
        <footer className="ticket-management-footer">
          <div className="ticket-management-feedback">
            {busy && <p aria-live="polite">{t("ticketManagement.processing")}</p>}
            {message && <p className="ticket-management-success" role="status">{message}</p>}
            {error && <p className="sale-action-error" role="alert">{error}</p>}
          </div>
          <button type="button" className="ticket-management-close" onClick={onClose}>{t("common.close")}</button>
        </footer>
      </section>
    </div>
  );
}

function formatTicketAmount(amount: string | number, locale: LocaleCode) {
  return new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(Number(amount));
}

function interpolate(template: string, values: Record<string, string | number>) {
  return Object.entries(values).reduce((message, [key, value]) => message.replaceAll(`{${key}}`, String(value)), template);
}

function randomRequestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

function readReturnAttempt(value: string | null | undefined): ReturnAttempt | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as ReturnAttempt;
    return parsed && typeof parsed.signature === "string" && typeof parsed.requestId === "string" && Array.isArray(parsed.cards)
      ? parsed
      : null;
  } catch {
    return null;
  }
}
