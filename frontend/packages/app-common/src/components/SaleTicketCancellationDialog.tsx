import { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { getHardwareBridge } from "../hardware/hardware";
import { createTranslator } from "../i18n/LocalizedMessages";
import { formatEuroAmount } from "../money";
import type { LocaleCode, Permission, TerminalContext } from "../types";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";
import { TicketReturnDialog, type ReturnCartLine } from "./TicketReturnDialog";

type Ticket = {
  id: string;
  numero?: string | null;
  fecha: string;
  total: number | string;
  customerName?: string | null;
};

type ManualReference = {
  paymentId: string;
  paymentMethod: string;
  amount: number | string;
};

type CancellationPreview = {
  ticket: Ticket;
  manualReferences: ManualReference[];
  integratedCardPayments: Array<{ paymentId: string; amount: number | string }>;
  cashAmount: number | string;
  openCashDrawer: boolean;
  consumedVoucherCodes: string[];
  generatedVoucherCodes: string[];
};

type CancellationResult = {
  ticket: Ticket;
  restoredVouchers: Array<{ code: string; balance: number | string }>;
  invalidatedVoucherCodes: string[];
  openCashDrawer: boolean;
  receipt: CancellationReceipt;
};

type CancellationReceipt = {
  operationId: string;
  originalTicketNumber: string;
  originalIssuedAt?: string | null;
  cancelledAt: string;
  total: number | string;
  reason: string;
  operatorUsername: string;
  authorizerUsername: string;
  delegated: boolean;
  payments: Array<{
    method: string;
    amount: number | string;
    reference?: string | null;
  }>;
};

type StoredCancellationAttempt = {
  requestId: string;
  reason: string;
  authorizerUsername: string;
  manualReferences: Record<string, string>;
};

const TICKET_HAS_PREVIOUS_RETURNS = "TICKET_HAS_PREVIOUS_RETURNS";
const TICKET_NOT_FOUND = "TICKET_NOT_FOUND";

type Props = {
  token?: string;
  locale: LocaleCode;
  currentUsername?: string;
  permissions?: Permission[];
  authorization?: SaleOperationAuthorization;
  terminalContext: TerminalContext;
  mode: "LAST" | "BY_NUMBER";
  initialTicketNumber?: string;
  canStartInvoiceCancellation?: boolean;
  onClose: () => void;
  onFiscalMutation?: () => void;
  onInvoiceAddToCart?: (lines: ReturnCartLine[]) => void;
};

export function SaleTicketCancellationDialog({
  token,
  locale,
  currentUsername = "",
  permissions = [],
  authorization,
  terminalContext,
  mode,
  initialTicketNumber = "",
  canStartInvoiceCancellation = true,
  onClose,
  onFiscalMutation,
  onInvoiceAddToCart,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const confirmButtonRef = useRef<HTMLButtonElement>(null);
  const [ticketNumber, setTicketNumber] = useState(initialTicketNumber);
  const [preview, setPreview] = useState<CancellationPreview | null>(null);
  const [reason, setReason] = useState("");
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [password, setPassword] = useState("");
  const [manualReferences, setManualReferences] = useState<Record<string, string>>({});
  const [reprintRestoredVoucher, setReprintRestoredVoucher] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [warning, setWarning] = useState("");
  const [message, setMessage] = useState("");
  const [receiptToRetry, setReceiptToRetry] = useState<CancellationReceipt | null>(null);
  const [invoiceNumberToRectify, setInvoiceNumberToRectify] = useState("");
  const effectiveAuthorization = authorization ?? (
    permissions.includes("ADMIN")
      || permissions.includes("GESTION_VENTAS")
      || permissions.includes("GESTION_CUENTAS")
      ? { mode: "CURRENT_PASSWORD", requireUsername: false, requirePassword: true }
      : { mode: "DELEGATED", requireUsername: true, requirePassword: true }
  ) satisfies SaleOperationAuthorization;
  const reasons = [
    t("sale.ticketCancel.reason.payment"),
    t("sale.ticketCancel.reason.duplicate"),
    t("sale.ticketCancel.reason.lines"),
    t("sale.ticketCancel.reason.customer"),
  ];

  const amount = useMemo(
    () => formatEuroAmount(preview?.ticket.total ?? 0, locale),
    [locale, preview],
  );
  const confirmationReady = Boolean(preview
    && reason.trim()
    && saleOperationAuthorizationComplete(
      effectiveAuthorization,
      authorizerUsername,
      password,
    )
    && !preview.manualReferences.some(
      (item) => !manualReferences[item.paymentId]?.trim(),
    ));

  async function loadPreview(number?: string) {
    if (mode === "BY_NUMBER" && !number?.trim()) return;
    setBusy(true);
    setError("");
    setWarning("");
    setMessage("");
    try {
      const path = mode === "LAST"
        ? "/tickets/cancellation-preview/last"
        : `/tickets/cancellation-preview?number=${encodeURIComponent(number!.trim())}`;
      const loaded = await apiRequest<CancellationPreview>(path, { token });
      setPreview(loaded);
      setTicketNumber(loaded.ticket.numero ?? "");
      const stored = readStoredAttempt(loaded.ticket.id);
      setReason(stored?.reason ?? "");
      setAuthorizerUsername(stored?.authorizerUsername ?? "");
      setManualReferences(stored?.manualReferences ?? {});
      setPassword("");
    } catch (failure) {
      setPreview(null);
      const problemCode = apiProblemCode(failure);
      if (mode === "BY_NUMBER" && problemCode === TICKET_NOT_FOUND && onInvoiceAddToCart) {
        if (canStartInvoiceCancellation) {
          setInvoiceNumberToRectify(number!.trim());
        } else {
          setError(t("invoiceCancellation.emptyCart"));
        }
      } else if (problemCode === TICKET_HAS_PREVIOUS_RETURNS) {
        setWarning(t("sale.ticketCancel.warning.previousReturns"));
      } else {
        setError(failure instanceof Error ? failure.message : t("sale.ticketCancel.error.load"));
      }
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    if (mode === "LAST") void loadPreview();
    else if (initialTicketNumber.trim()) void loadPreview(initialTicketNumber);
    else searchRef.current?.focus();
  }, [initialTicketNumber, mode, token]);

  useEffect(() => invoiceNumberToRectify || !dialogRef.current
    ? undefined
    : activateModalFocusTrap(
      dialogRef.current as unknown as ModalFocusRoot,
      document,
    ), [invoiceNumberToRectify]);

  useEffect(() => {
    if (invoiceNumberToRectify) return undefined;
    const handler = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onClose();
    };
    globalThis.addEventListener("keydown", handler);
    return () => globalThis.removeEventListener("keydown", handler);
  }, [busy, invoiceNumberToRectify, onClose]);

  async function printRestoredVouchers(
    vouchers: Array<{ code: string; balance: number | string }>,
  ) {
    const hardware = getHardwareBridge();
    for (const voucher of vouchers) {
      const balance = Number(voucher.balance);
      const result = await hardware.printTicket({
        documentNumber: voucher.code,
        storeName: terminalContext.storeName,
        terminalCode: terminalContext.terminalCode,
        issuedAt: new Date().toISOString(),
        lines: [{
          name: t("sale.ticketCancel.restoredVoucher"),
          quantity: 1,
          price: balance,
          total: balance,
        }],
        payments: [],
        total: balance,
      });
      if (!result.ok) throw new Error(result.message);
    }
  }

  function formatReceiptDate(value?: string | null) {
    if (!value) return "—";
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime())
      ? value
      : new Intl.DateTimeFormat(locale, {
          dateStyle: "short",
          timeStyle: "medium",
        }).format(parsed);
  }

  async function printCancellationReceipt(receipt: CancellationReceipt) {
    const result = await getHardwareBridge().printTicket({
      layout: "CANCELLATION_RECEIPT",
      title: t("sale.ticketCancel.receipt.title"),
      notice: t("sale.ticketCancel.receipt.nonFiscal"),
      documentNumber: `AN-${receipt.originalTicketNumber}`,
      storeName: terminalContext.storeName,
      terminalCode: terminalContext.terminalCode,
      issuedAt: formatReceiptDate(receipt.cancelledAt),
      details: [
        {
          label: t("sale.ticketCancel.receipt.originalTicket"),
          value: receipt.originalTicketNumber,
        },
        {
          label: t("sale.ticketCancel.receipt.originalIssuedAt"),
          value: formatReceiptDate(receipt.originalIssuedAt),
        },
        {
          label: t("sale.ticketCancel.receipt.reason"),
          value: receipt.reason,
        },
        {
          label: t("sale.ticketCancel.receipt.operator"),
          value: receipt.operatorUsername,
        },
        {
          label: t("sale.ticketCancel.receipt.authorizer"),
          value: receipt.authorizerUsername,
        },
      ],
      lines: [],
      payments: receipt.payments.map((payment) => ({
        method: payment.method,
        amount: Number(payment.amount),
        reference: payment.reference ?? undefined,
      })),
      total: Number(receipt.total),
      labels: {
        terminal: t("sale.ticketCancel.receipt.terminal"),
        item: "",
        quantity: "",
        price: "",
        total: t("sale.ticketCancel.receipt.total"),
      },
    });
    if (!result.ok) {
      throw new Error(result.message || t("sale.ticketCancel.receipt.printFailed"));
    }
  }

  async function retryCancellationReceipt() {
    if (!receiptToRetry) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await printCancellationReceipt(receiptToRetry);
      setReceiptToRetry(null);
      setMessage(t("sale.ticketCancel.receipt.printed"));
    } catch {
      setError(t("sale.ticketCancel.receipt.printFailed"));
    } finally {
      setBusy(false);
    }
  }

  async function cancelTicket() {
    if (!preview || !reason.trim() || !saleOperationAuthorizationComplete(
      effectiveAuthorization,
      authorizerUsername,
      password,
    )) return;
    setBusy(true);
    setError("");
    setMessage("");
    const storageKey = `tpv.ticket-cancellation.${preview.ticket.id}`;
    const stored = readStoredAttempt(preview.ticket.id);
    const requestId = stored?.requestId ?? crypto.randomUUID();
    globalThis.localStorage.setItem(storageKey, JSON.stringify({
      requestId,
      reason: reason.trim(),
      authorizerUsername: authorizerUsername.trim(),
      manualReferences,
    } satisfies StoredCancellationAttempt));
    try {
      const result = await apiRequest<CancellationResult>(
        `/tickets/${encodeURIComponent(preview.ticket.id)}/cancel`,
        {
          token,
          body: {
            requestId,
            reason: reason.trim(),
            ...saleOperationCredentials(
              effectiveAuthorization,
              authorizerUsername,
              password,
            ),
            manualCompensations: manualReferences,
          },
        },
      );
      globalThis.localStorage.removeItem(storageKey);
      const warnings: string[] = [];
      if (result.openCashDrawer) {
        const drawer = await getHardwareBridge().openCashDrawer();
        if (!drawer.ok) warnings.push(drawer.message);
      }
      if (reprintRestoredVoucher && result.restoredVouchers.length > 0) {
        try {
          await printRestoredVouchers(result.restoredVouchers);
        } catch (failure) {
          warnings.push(failure instanceof Error
            ? failure.message
            : t("sale.ticketCancel.error.voucherPrint"));
        }
      }
      try {
        await printCancellationReceipt(result.receipt);
        setReceiptToRetry(null);
      } catch {
        setReceiptToRetry(result.receipt);
        warnings.push(t("sale.ticketCancel.receipt.printFailed"));
      }
      onFiscalMutation?.();
      setPreview(null);
      setPassword("");
      setMessage(warnings.length > 0
        ? `${t("sale.ticketCancel.success")} ${warnings.join(" · ")}`
        : t("sale.ticketCancel.success"));
    } catch (failure) {
      setPassword("");
      setError(failure instanceof Error ? failure.message : t("sale.ticketCancel.error.cancel"));
    } finally {
      setBusy(false);
    }
  }

  if (invoiceNumberToRectify) {
    return (
      <TicketReturnDialog
        token={token}
        locale={locale}
        mode="INVOICE_CANCELLATION"
        initialSourceCode={invoiceNumberToRectify}
        onClose={onClose}
        onAddToCart={onInvoiceAddToCart!}
      />
    );
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-ticket-operation-dialog sale-ticket-cancellation-dialog"
        role="dialog"
        aria-modal="true"
        aria-busy={busy}
        aria-labelledby="sale-ticket-cancel-title"
      >
        <header className="sale-ticket-operation-header">
          <h2 id="sale-ticket-cancel-title">{t(mode === "BY_NUMBER"
            ? "sale.documentCancel.title"
            : "sale.ticketCancel.title")}</h2>
          <button
            type="button"
            aria-label={t("common.close")}
            onClick={onClose}
            disabled={busy}
          >×</button>
        </header>

        {mode === "BY_NUMBER" && (
          <form
            className="sale-ticket-operation-search"
            onSubmit={(event) => {
              event.preventDefault();
              void loadPreview(ticketNumber);
            }}
          >
            <label>
              {t("sale.documentCancel.documentCode")}
              <input
                ref={searchRef}
                value={ticketNumber}
                onChange={(event) => setTicketNumber(event.currentTarget.value)}
                autoComplete="off"
              />
            </label>
            <button type="submit" disabled={busy || !ticketNumber.trim()}>
              {t("sale.documentCancel.search")}
            </button>
          </form>
        )}

        {preview && (
          <div className="sale-ticket-operation-body">
            <div className="sale-ticket-operation-summary">
              <div>
                <span>{t("sale.ticketCancel.ticketCode")}</span>
                <strong>{preview.ticket.numero}</strong>
              </div>
              <div>
                <span>{t("sale.ticketOperation.date")}</span>
                <strong>{preview.ticket.fecha}</strong>
              </div>
              <div>
                <span>{t("sale.ticketOperation.customer")}</span>
                <strong>{preview.ticket.customerName || t("sale.ticketOperation.noCustomer")}</strong>
              </div>
              <div className="sale-ticket-operation-summary-total">
                <span>{t("sale.ticketOperation.total")}</span>
                <b>{amount}</b>
              </div>
            </div>

            <label>
              {t("sale.ticketCancel.reason")}
              <input
                list="sale-ticket-cancel-reasons"
                value={reason}
                onChange={(event) => setReason(event.currentTarget.value)}
                maxLength={500}
                autoComplete="off"
              />
              <datalist id="sale-ticket-cancel-reasons">
                {reasons.map((option) => <option key={option} value={option} />)}
              </datalist>
            </label>

            {preview.manualReferences.map((reference) => (
              <label key={reference.paymentId}>
                {t("sale.ticketCancel.refundReference")} · {reference.paymentMethod}
                <input
                  value={manualReferences[reference.paymentId] ?? ""}
                  onChange={(event) => {
                    const value = event.currentTarget.value;
                    setManualReferences((current) => ({
                      ...current,
                      [reference.paymentId]: value,
                    }));
                  }}
                  autoComplete="off"
                />
              </label>
            ))}

            <div
              onKeyDown={(event) => {
                if (event.key !== "Enter"
                  || !(event.target instanceof HTMLInputElement)
                  || event.target.type !== "password"
                  || busy
                  || !confirmationReady) return;
                event.preventDefault();
                event.stopPropagation();
                confirmButtonRef.current?.focus();
              }}
            >
              <SaleOperationAuthorizationFields
                locale={locale}
                currentUsername={currentUsername}
                authorization={effectiveAuthorization}
                username={authorizerUsername}
                password={password}
                disabled={busy}
                onUsernameChange={setAuthorizerUsername}
                onPasswordChange={setPassword}
              />
            </div>

            {preview.consumedVoucherCodes.length > 0 && (
              <label className="sale-ticket-operation-checkbox">
                <input
                  type="checkbox"
                  checked={reprintRestoredVoucher}
                  onChange={(event) => setReprintRestoredVoucher(event.currentTarget.checked)}
                />
                {t("sale.ticketCancel.reprintVoucher")}
              </label>
            )}
          </div>
        )}

        {warning && (
          <div className="sale-ticket-operation-warning" role="status">
            <strong>{t("sale.ticketCancel.warning.previousReturnsTitle")}</strong>
            <span>{warning}</span>
          </div>
        )}
        {error && <p className="sale-error" role="alert">{error}</p>}
        {message && <p className="sale-status" role="status">{message}</p>}
        <footer className="sale-ticket-operation-footer">
          <button type="button" onClick={onClose} disabled={busy}>
            {t("sale.dialog.cancel")}
          </button>
          {preview && (
            <button
              ref={confirmButtonRef}
              type="button"
              className="danger"
              disabled={busy || !confirmationReady}
              onClick={() => void cancelTicket()}
            >
              {t(busy ? "sale.ticketCancel.processing" : "sale.ticketCancel.confirm")}
            </button>
          )}
          {!preview && receiptToRetry && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void retryCancellationReceipt()}
            >
              {t("sale.ticketCancel.receipt.retry")}
            </button>
          )}
        </footer>
      </section>
    </div>
  );
}

function apiProblemCode(failure: unknown): string {
  if (!failure || typeof failure !== "object" || !("problem" in failure)) return "";
  const problem = (failure as { problem?: Record<string, unknown> }).problem;
  return typeof problem?.code === "string" ? problem.code : "";
}

function readStoredAttempt(ticketId: string): StoredCancellationAttempt | null {
  const raw = globalThis.localStorage.getItem(`tpv.ticket-cancellation.${ticketId}`);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<StoredCancellationAttempt>;
    return typeof parsed.requestId === "string"
      ? {
        requestId: parsed.requestId,
        reason: typeof parsed.reason === "string" ? parsed.reason : "",
        authorizerUsername: typeof parsed.authorizerUsername === "string"
          ? parsed.authorizerUsername
          : "",
        manualReferences: parsed.manualReferences && typeof parsed.manualReferences === "object"
          ? parsed.manualReferences
          : {},
      }
      : null;
  } catch {
    // Compatibility with the first id-only local format used during development.
    return { requestId: raw, reason: "", authorizerUsername: "", manualReferences: {} };
  }
}
