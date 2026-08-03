import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
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
  authorization?: SaleOperationAuthorization;
  onClose: () => void;
  onFiscalMutation?: () => void;
};

export function SaleTicketInvoiceDialog({
  token,
  locale,
  currentUsername = "",
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
  const [ticketNumber, setTicketNumber] = useState("");
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
    void Promise.all([
      loadTicket("/tickets/last-current-terminal"),
      apiRequest<Customer[]>("/customers/sale-options", { token })
        .then(setCustomers)
        .catch(() => setCustomers([])),
    ]);
  }, [token]);

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
    <div className="sale-modal-backdrop">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-ticket-operation-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-ticket-invoice-title"
      >
        <header>
          <div>
            <span>F12</span>
            <h2 id="sale-ticket-invoice-title">{t("sale.ticketInvoice.title")}</h2>
          </div>
          <button type="button" onClick={onClose} disabled={busy}>×</button>
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
              <strong>{ticket.numero}</strong>
              <span>{ticket.fecha}</span>
              <b>{Number(ticket.total).toFixed(2)} €</b>
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
        <footer>
          <button type="button" onClick={onClose} disabled={busy}>
            {t("sale.dialog.cancel")}
          </button>
          {ticket && (
            <button
              type="button"
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
