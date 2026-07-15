import { useEffect, useRef } from "react";
import type { RefObject } from "react";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { activateModalFocusTrap, modalFocusTarget, type ModalFocusRoot } from "./modalFocusTrap";

export type TicketPrintUiStatus = "PRINTING" | "PRINTED" | "FAILED" | "SKIPPED";

type CashPaymentResultDialogProps = {
  locale?: LocaleCode;
  ticketNumber: string;
  totalCents: number;
  receivedCents?: number;
  changeCents?: number;
  method?: string;
  authorization?: string;
  reference?: string;
  printStatus?: TicketPrintUiStatus;
  onRetryPrint?: () => void;
  onFinish: () => void;
};

type CashPaymentResultContentProps = CashPaymentResultDialogProps & {
  dialogRef?: RefObject<HTMLElement | null>;
};

export const focusTrapTarget = modalFocusTarget;

export function CashPaymentResultDialog(props: CashPaymentResultDialogProps) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => dialogRef.current
    ? activateCashResultFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  return <CashPaymentResultContent {...props} dialogRef={dialogRef} />;
}

export const activateCashResultFocusTrap = activateModalFocusTrap;

export function CashPaymentResultContent({
  locale = "es",
  ticketNumber,
  totalCents,
  receivedCents,
  changeCents,
  method,
  authorization,
  reference,
  printStatus = "SKIPPED",
  onRetryPrint,
  onFinish,
  dialogRef,
}: CashPaymentResultContentProps) {
  const t = createTranslator(locale);
  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="cash-payment-dialog cash-payment-result-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cash-payment-result-title"
      >
        <header className="cash-payment-result-header">
          <h2 id="cash-payment-result-title">Pago completado</h2>
          <span className="cash-payment-result-mark" aria-hidden="true">✓</span>
        </header>
        <p className="cash-payment-ticket">Ticket <strong>{ticketNumber}</strong></p>
        {method && <p className="cash-payment-ticket">Método <strong>{method}</strong>{authorization && <> · Autorización <strong>{authorization}</strong></>}{reference && <> · Referencia <strong>{reference}</strong></>}</p>}
        <div className="cash-payment-summary">
          <div><span>Total</span><strong>{money(totalCents)}</strong></div>
          {receivedCents != null && <div><span>Dinero recibido</span><strong>{money(receivedCents)}</strong></div>}
          {changeCents != null && <div className="cash-change"><span>Cambio</span><strong>{money(changeCents)}</strong></div>}
        </div>
        {printStatus === "PRINTING" && <p className="cash-payment-print-status" role="status">{t("payment.result.printing")}</p>}
        {printStatus === "PRINTED" && <p className="cash-payment-print-status" role="status">{t("payment.result.printed")}</p>}
        {printStatus === "FAILED" && (
          <div className="cash-payment-print-status cash-payment-print-error" role="alert">
            <span>{t("payment.result.printFailed")}</span>
            <button type="button" className="cash-payment-print-retry" onClick={onRetryPrint}>{t("payment.result.retryPrint")}</button>
          </div>
        )}
        <footer className="cash-payment-actions">
          <button type="button" autoFocus onClick={onFinish}>Finalizar</button>
        </footer>
      </section>
    </div>
  );
}

function money(cents: number) {
  return (cents / 100).toLocaleString("es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
