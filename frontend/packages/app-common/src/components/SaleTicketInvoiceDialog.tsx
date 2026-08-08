import { useEffect, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { formatEuroAmount } from "../money";
import {
  printPendingCommercialDocument,
  type PendingCommercialDocumentPrintSnapshot,
  type TicketPrintOutcome,
} from "../sale/ticketPrinting";
import type { LocaleCode, TerminalContext } from "../types";
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
  documentNumber?: string | null;
  active: boolean;
};

type Props = {
  token?: string;
  locale: LocaleCode;
  terminalContext: TerminalContext;
  currentUsername?: string;
  initialTicketNumber?: string;
  authorization?: SaleOperationAuthorization;
  onClose: () => void;
  onFiscalMutation?: () => void;
  printInvoice?: (
    snapshot: PendingCommercialDocumentPrintSnapshot,
    terminal: TerminalContext,
    locale: LocaleCode,
  ) => Promise<TicketPrintOutcome>;
};

type InvoicePrintDocument = Omit<PendingCommercialDocumentPrintSnapshot, "kind">;

function printInvoiceByDefault(
  snapshot: PendingCommercialDocumentPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
) {
  return printPendingCommercialDocument(snapshot, terminal, undefined, locale);
}

export function SaleTicketInvoiceDialog({
  token,
  locale,
  terminalContext,
  currentUsername = "",
  initialTicketNumber = "",
  authorization = {
    mode: "DIRECT",
    requireUsername: false,
    requirePassword: false,
  },
  onClose,
  onFiscalMutation,
  printInvoice = printInvoiceByDefault,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const ticketNumberRef = useRef<HTMLInputElement>(null);
  const customerSearchRef = useRef<HTMLInputElement>(null);
  const focusAfterTicketLoadRef = useRef<"ticket" | "customer" | null>(null);
  const customerSearchSequenceRef = useRef(0);
  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [ticketNumber, setTicketNumber] = useState(initialTicketNumber);
  const [customerId, setCustomerId] = useState("");
  const [selectedCustomer, setSelectedCustomer] = useState<Customer | null>(null);
  const [customerQuery, setCustomerQuery] = useState("");
  const [customerResults, setCustomerResults] = useState<Customer[]>([]);
  const [customerResultIndex, setCustomerResultIndex] = useState(-1);
  const [customerSearchBusy, setCustomerSearchBusy] = useState(false);
  const [customerSearchError, setCustomerSearchError] = useState("");
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [authorizerPassword, setAuthorizerPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [pendingPrintDocument, setPendingPrintDocument] =
    useState<PendingCommercialDocumentPrintSnapshot | null>(null);
  const [pendingPrintInvoiceId, setPendingPrintInvoiceId] = useState<string | null>(null);

  async function loadTicket(path: string, focusAfterLoad: "ticket" | "customer") {
    focusAfterTicketLoadRef.current = focusAfterLoad;
    setBusy(true);
    setError("");
    setMessage("");
    setSelectedCustomer(null);
    setCustomerId("");
    setCustomerQuery("");
    setCustomerResults([]);
    try {
      const loaded = await apiRequest<Ticket>(path, { token });
      setTicket(loaded);
      setTicketNumber(loaded.numero ?? "");
      if (loaded.customerId) {
        try {
          const existingCustomer = await apiRequest<Customer>(
            `/customers/sale-options/${encodeURIComponent(loaded.customerId)}`,
            { token },
          );
          setSelectedCustomer(existingCustomer);
          setCustomerId(existingCustomer.active ? existingCustomer.id : "");
        } catch {
          setSelectedCustomer(null);
        }
      }
    } catch (failure) {
      setTicket(null);
      setError(failure instanceof Error ? failure.message : t("sale.ticketInvoice.error.load"));
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    const normalizedTicketNumber = initialTicketNumber.trim();
    void loadTicket(normalizedTicketNumber
      ? `/tickets/by-number?number=${encodeURIComponent(normalizedTicketNumber)}`
      : "/tickets/last-current-terminal", "ticket");
  }, [initialTicketNumber, token]);

  useEffect(() => {
    const query = customerQuery.trim();
    const sequence = ++customerSearchSequenceRef.current;
    if (!query) {
      setCustomerResults([]);
      setCustomerResultIndex(-1);
      setCustomerSearchBusy(false);
      setCustomerSearchError("");
      return;
    }
    setCustomerSearchBusy(true);
    setCustomerSearchError("");
    const timer = globalThis.setTimeout(() => {
      void apiRequest<Customer[]>(
        `/customers/sale-options/search?q=${encodeURIComponent(query)}&limit=25`,
        { token },
      ).then((results) => {
        if (sequence !== customerSearchSequenceRef.current) return;
        setCustomerResults(results);
        setCustomerResultIndex(results.findIndex((customer) => customer.active));
      }).catch((failure) => {
        if (sequence !== customerSearchSequenceRef.current) return;
        setCustomerResults([]);
        setCustomerResultIndex(-1);
        setCustomerSearchError(
          failure instanceof Error ? failure.message : t("sale.ticketInvoice.customerSearchError"),
        );
      }).finally(() => {
        if (sequence === customerSearchSequenceRef.current) setCustomerSearchBusy(false);
      });
    }, 250);
    return () => globalThis.clearTimeout(timer);
  }, [customerQuery, token]);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    if (!ticket) return;
    if (focusAfterTicketLoadRef.current === "customer") {
      customerSearchRef.current?.focus();
    } else if (focusAfterTicketLoadRef.current === "ticket") {
      ticketNumberRef.current?.focus();
      ticketNumberRef.current?.select();
    }
    focusAfterTicketLoadRef.current = null;
  }, [ticket]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onClose();
    };
    globalThis.addEventListener("keydown", handler);
    return () => globalThis.removeEventListener("keydown", handler);
  }, [busy, onClose]);

  function selectCustomer(customer: Customer) {
    if (!customer.active) return;
    setSelectedCustomer(customer);
    setCustomerId(customer.id);
    setCustomerQuery("");
    setCustomerResults([]);
    setCustomerSearchError("");
  }

  function moveCustomerResult(direction: 1 | -1) {
    if (!customerResults.some((customer) => customer.active)) return;
    let next = customerResultIndex;
    for (let attempts = 0; attempts < customerResults.length; attempts += 1) {
      next = (next + direction + customerResults.length) % customerResults.length;
      if (customerResults[next]?.active) {
        setCustomerResultIndex(next);
        return;
      }
    }
  }

  const canConvert = Boolean(ticket
    && customerId
    && selectedCustomer?.active === true
    && saleOperationAuthorizationComplete(
      authorization,
      authorizerUsername,
      authorizerPassword,
    ));

  async function outputInvoice(
    invoiceId: string,
    knownSnapshot: PendingCommercialDocumentPrintSnapshot | null = null,
  ) {
    let snapshot = knownSnapshot;
    try {
      if (!snapshot) {
        const printDocument = await apiRequest<InvoicePrintDocument>(
          `/invoices/${encodeURIComponent(invoiceId)}/print-document`,
          { token },
        );
        snapshot = { kind: "COMMERCIAL_DOCUMENT", ...printDocument };
      }
      const outcome = await printInvoice(snapshot, terminalContext, locale);
      if (outcome.status !== "FAILED") {
        setPendingPrintDocument(null);
        setPendingPrintInvoiceId(null);
        return true;
      }
    } catch {
      // The invoice is already fiscalized; only its local output can be retried here.
    }
    setPendingPrintDocument(snapshot);
    setPendingPrintInvoiceId(invoiceId);
    setError(t("sale.ticketInvoice.error.print"));
    return false;
  }

  async function retryPrint() {
    if (!pendingPrintInvoiceId || busy) return;
    setBusy(true);
    setError("");
    try {
      if (await outputInvoice(pendingPrintInvoiceId, pendingPrintDocument)) {
        setMessage(t("sale.ticketInvoice.success"));
      }
    } finally {
      setBusy(false);
    }
  }

  async function convert() {
    if (!ticket || !customerId || selectedCustomer?.active !== true
      || !saleOperationAuthorizationComplete(
        authorization,
        authorizerUsername,
        authorizerPassword,
      )) return;
    setBusy(true);
    setError("");
    try {
      const result = await apiRequest<Ticket>(
        `/tickets/${encodeURIComponent(ticket.id)}/invoice`, {
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
      setMessage(t("sale.ticketInvoice.success.created"));
      if (await outputInvoice(result.id)) {
        setMessage(t("sale.ticketInvoice.success"));
      }
    } catch (failure) {
      setAuthorizerPassword("");
      setError(failure instanceof ApiError
          && failure.problem?.code === "TICKET_ALREADY_INVOICED"
        ? t("sale.ticketInvoice.error.alreadyInvoiced")
        : failure instanceof Error
          ? failure.message
          : t("sale.ticketInvoice.error.convert"));
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
            if (!busy && ticketNumber.trim()) {
              void loadTicket(
                `/tickets/by-number?number=${encodeURIComponent(ticketNumber.trim())}`,
                "customer",
              );
            }
          }}
        >
          <label>
            {t("sale.ticketInvoice.ticketCode")}
            <input
              ref={ticketNumberRef}
              value={ticketNumber}
              onChange={(event) => setTicketNumber(event.currentTarget.value)}
              autoComplete="off"
            />
          </label>
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

            <section className="sale-ticket-customer-search" aria-labelledby="sale-ticket-customer-title">
              <div className="sale-ticket-customer-search__heading">
                <div>
                  <h3 id="sale-ticket-customer-title">{t("sale.ticketInvoice.customer")}</h3>
                  <p>{t("sale.ticketInvoice.customerSearchHint")}</p>
                </div>
                <span>{t("sale.ticketInvoice.customerSearchLimit")}</span>
              </div>
              <label className="sale-ticket-customer-search__field">
                <span>{t("sale.ticketInvoice.customerSearchLabel")}</span>
                <input
                  ref={customerSearchRef}
                  value={customerQuery}
                  onChange={(event) => {
                    setCustomerQuery(event.currentTarget.value);
                    setSelectedCustomer(null);
                    setCustomerId("");
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                      event.preventDefault();
                      moveCustomerResult(event.key === "ArrowDown" ? 1 : -1);
                    } else if (event.key === "Enter" || event.key === "Insert") {
                      const selected = customerResults[customerResultIndex];
                      if (selected?.active) {
                        event.preventDefault();
                        selectCustomer(selected);
                      } else if (event.key === "Enter" && canConvert && !busy) {
                        event.preventDefault();
                        void convert();
                      }
                    }
                  }}
                  placeholder={t("sale.ticketInvoice.customerSearchPlaceholder")}
                  autoComplete="off"
                  aria-controls="sale-ticket-customer-results"
                  aria-busy={customerSearchBusy}
                />
              </label>

              {customerQuery.trim() && (
                <div className="sale-ticket-customer-results" id="sale-ticket-customer-results">
                  <table aria-label={t("sale.ticketInvoice.customerResults")}>
                    <thead>
                      <tr>
                        <th>{t("sale.ticketInvoice.customerCode")}</th>
                        <th>{t("sale.ticketInvoice.customerName")}</th>
                        <th>{t("sale.ticketInvoice.customerDocument")}</th>
                        <th>{t("sale.ticketInvoice.customerStatus")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {customerResults.map((customer, index) => (
                        <tr
                          key={customer.id}
                          className={`${index === customerResultIndex ? "is-current" : ""} ${customer.active ? "" : "is-inactive"}`.trim()}
                          aria-disabled={!customer.active}
                          onMouseDown={(event) => event.preventDefault()}
                          onClick={() => selectCustomer(customer)}
                        >
                          <td>{customer.clientId || "—"}</td>
                          <td>{customer.fiscalName || "—"}</td>
                          <td>{customer.documentNumber || "—"}</td>
                          <td>
                            <span className={`sale-ticket-customer-status ${customer.active ? "is-active" : "is-inactive"}`}>
                              {t(customer.active
                                ? "sale.ticketInvoice.customerActive"
                                : "sale.ticketInvoice.customerInactive")}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {!customerSearchBusy && customerResults.length === 0 && !customerSearchError && (
                    <p className="sale-ticket-customer-results__empty">
                      {t("sale.ticketInvoice.customerNoResults")}
                    </p>
                  )}
                  {customerSearchBusy && (
                    <p className="sale-ticket-customer-results__empty" role="status">
                      {t("sale.ticketInvoice.customerSearching")}
                    </p>
                  )}
                </div>
              )}
              {customerSearchError && <p className="sale-error" role="alert">{customerSearchError}</p>}

              {selectedCustomer && (
                <div className={`sale-ticket-selected-customer ${selectedCustomer.active ? "" : "is-inactive"}`.trim()}>
                  <div>
                    <span>{t("sale.ticketInvoice.selectedCustomer")}</span>
                    <strong>{selectedCustomer.fiscalName || "—"}</strong>
                    <small>
                      {[selectedCustomer.clientId, selectedCustomer.documentNumber]
                        .filter(Boolean).join(" · ")}
                    </small>
                  </div>
                  <span className={`sale-ticket-customer-status ${selectedCustomer.active ? "is-active" : "is-inactive"}`}>
                    {t(selectedCustomer.active
                      ? "sale.ticketInvoice.customerActive"
                      : "sale.ticketInvoice.customerInactiveUnavailable")}
                  </span>
                </div>
              )}
            </section>

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
                || !canConvert}
              onClick={() => void convert()}
            >
              {t("sale.ticketInvoice.confirm")}
            </button>
          )}
          {pendingPrintInvoiceId && (
            <button
              type="button"
              className="primary"
              disabled={busy}
              onClick={() => void retryPrint()}
            >
              {t("sale.ticketInvoice.retryPrint")}
            </button>
          )}
        </footer>
      </section>
    </div>
  );
}
