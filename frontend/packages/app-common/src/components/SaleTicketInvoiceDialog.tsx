import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { formatEuroAmount } from "../money";
import type { LocaleCode } from "../types";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

type Ticket = {
  id: string;
  numero?: string | null;
  fecha: string;
  total: number | string;
  customerId?: string | null;
  customerName?: string | null;
};

type Customer = {
  id: string;
  clientId?: string | null;
  fiscalName?: string | null;
};

type Props = {
  token?: string;
  locale: LocaleCode;
  currentUsername?: string;
  initialTicketNumber?: string;
  authorization?: SaleOperationAuthorization;
  onClose: () => void;
  onFiscalMutation?: () => void;
};

export function SaleTicketInvoiceDialog({
  token,
  locale,
  currentUsername = "",
  initialTicketNumber = "",
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
  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [ticketNumber, setTicketNumber] = useState(initialTicketNumber);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [customerId, setCustomerId] = useState("");
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [authorizerPassword, setAuthorizerPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  async function loadTicket(path: string) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const loaded = await apiRequest<Ticket>(path, { token });
      setTicket(loaded);
      setTicketNumber(loaded.numero ?? "");
      setCustomerId(loaded.customerId ?? "");
    } catch (failure) {
      setTicket(null);
      setError(failure instanceof Error ? failure.message : t("sale.ticketInvoice.error.load"));
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    const normalizedTicketNumber = initialTicketNumber.trim();
    void Promise.all([
      loadTicket(normalizedTicketNumber
        ? `/tickets/by-number?number=${encodeURIComponent(normalizedTicketNumber)}`
        : "/tickets/last-current-terminal"),
      apiRequest<Customer[]>("/customers/sale-options", { token })
        .then(setCustomers)
        .catch(() => setCustomers([])),
    ]);
  }, [initialTicketNumber, token]);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onClose();
    };
    globalThis.addEventListener("keydown", handler);
    return () => globalThis.removeEventListener("keydown", handler);
  }, [busy, onClose]);

  async function convert() {
    if (!ticket || !customerId || !saleOperationAuthorizationComplete(
      authorization,
      authorizerUsername,
      authorizerPassword,
    )) return;
    setBusy(true);
    setError("");
    try {
      await apiRequest(`/tickets/${encodeURIComponent(ticket.id)}/invoice`, {
        token,
        body: {
          customerId,
          ...saleOperationCredentials(
            authorization,
            authorizerUsername,
            authorizerPassword,
          ),
        },
      });
      onFiscalMutation?.();
      setTicket(null);
      setAuthorizerPassword("");
      setMessage(t("sale.ticketInvoice.success"));
    } catch (failure) {
      setAuthorizerPassword("");
      setError(failure instanceof Error ? failure.message : t("sale.ticketInvoice.error.convert"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-ticket-operation-dialog sale-ticket-invoice-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-ticket-invoice-title"
      >
        <header className="sale-ticket-operation-header">
          <h2 id="sale-ticket-invoice-title">{t("sale.ticketInvoice.title")}</h2>
          <button
            type="button"
            aria-label={t("common.close")}
            onClick={onClose}
            disabled={busy}
          >×</button>
        </header>
        <form
          className="sale-ticket-operation-search"
          onSubmit={(event) => {
            event.preventDefault();
            if (ticketNumber.trim()) {
              void loadTicket(`/tickets/by-number?number=${encodeURIComponent(ticketNumber.trim())}`);
            }
          }}
        >
          <label>
            {t("sale.ticketInvoice.ticketCode")}
            <input
              value={ticketNumber}
              onChange={(event) => setTicketNumber(event.currentTarget.value)}
              autoComplete="off"
            />
          </label>
          <button type="submit" disabled={busy || !ticketNumber.trim()}>
            {t("sale.ticketInvoice.search")}
          </button>
        </form>
        {ticket && (
          <div className="sale-ticket-operation-body">
            <div className="sale-ticket-operation-summary">
              <div>
                <span>{t("sale.ticketInvoice.ticketCode")}</span>
                <strong>{ticket.numero}</strong>
              </div>
              <div>
                <span>{t("sale.ticketOperation.date")}</span>
                <strong>{ticket.fecha}</strong>
              </div>
              <div>
                <span>{t("sale.ticketOperation.customer")}</span>
                <strong>{ticket.customerName || t("sale.ticketOperation.noCustomer")}</strong>
              </div>
              <div className="sale-ticket-operation-summary-total">
                <span>{t("sale.ticketOperation.total")}</span>
                <b>{formatEuroAmount(ticket.total, locale)}</b>
              </div>
            </div>
            <label>
              {t("sale.ticketInvoice.customer")}
              <select
                value={customerId}
                onChange={(event) => setCustomerId(event.currentTarget.value)}
              >
                <option value="">{t("sale.ticketInvoice.selectCustomer")}</option>
                {customers.map((customer) => (
                  <option key={customer.id} value={customer.id}>
                    {[customer.clientId, customer.fiscalName].filter(Boolean).join(" · ")}
                  </option>
                ))}
              </select>
            </label>
            <SaleOperationAuthorizationFields
              locale={locale}
              currentUsername={currentUsername}
              authorization={authorization}
              username={authorizerUsername}
              password={authorizerPassword}
              disabled={busy}
              onUsernameChange={setAuthorizerUsername}
              onPasswordChange={setAuthorizerPassword}
            />
          </div>
        )}
        {error && <p className="sale-error" role="alert">{error}</p>}
        {message && <p className="sale-status" role="status">{message}</p>}
        <footer className="sale-ticket-operation-footer">
          <button type="button" onClick={onClose} disabled={busy}>
            {t("sale.dialog.cancel")}
          </button>
          {ticket && (
            <button
              type="button"
              className="primary"
              disabled={busy
                || !customerId
                || !saleOperationAuthorizationComplete(
                  authorization,
                  authorizerUsername,
                  authorizerPassword,
                )}
              onClick={() => void convert()}
            >
              {t("sale.ticketInvoice.confirm")}
            </button>
          )}
        </footer>
      </section>
    </div>
  );
}
