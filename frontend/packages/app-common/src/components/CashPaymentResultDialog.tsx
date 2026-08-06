import { useEffect, useRef } from "react";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { IssuedVoucherPrintSnapshot } from "../sale/voucherPrinting";

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
  issuedVoucher?: IssuedVoucherPrintSnapshot;
  voucherPrintStatus?: TicketPrintUiStatus;
  onRetryPrint?: () => void;
  onRetryVoucherPrint?: () => void;
  onFinish: () => void;
};

export function combinedResultPrintStatus(
  ticketStatus: TicketPrintUiStatus | undefined,
  voucherStatus: TicketPrintUiStatus | undefined,
): TicketPrintUiStatus {
  const statuses = [ticketStatus ?? "SKIPPED", voucherStatus].filter(Boolean);
  if (statuses.includes("FAILED")) return "FAILED";
  if (statuses.includes("PRINTING")) return "PRINTING";
  if (statuses.includes("PRINTED")) return "PRINTED";
  return "SKIPPED";
}

export function CashPaymentResultDialog(props: CashPaymentResultDialogProps) {
  const onFinishRef = useRef(props.onFinish);
  const dismissedRef = useRef(false);
  const interactedWhilePrintingRef = useRef(false);
  const resultPrintStatus = combinedResultPrintStatus(
    props.printStatus,
    props.issuedVoucher ? props.voucherPrintStatus : undefined,
  );

  useEffect(() => {
    onFinishRef.current = props.onFinish;
  }, [props.onFinish]);

  useEffect(() => {
    dismissedRef.current = false;
    interactedWhilePrintingRef.current = false;
  }, [props.ticketNumber]);

  useEffect(() => {
    function finishOnce() {
      if (dismissedRef.current) return;
      dismissedRef.current = true;
      onFinishRef.current();
    }

    function handleInteraction(event: Event) {
      const target = event.target as Element | null;
      if (target?.closest?.(".cash-payment-print-retry")) return;

      if (resultPrintStatus === "FAILED") return;
      if (resultPrintStatus === "PRINTING") {
        interactedWhilePrintingRef.current = true;
        return;
      }
      finishOnce();
    }

    window.addEventListener("keydown", handleInteraction, true);
    window.addEventListener("pointerdown", handleInteraction, true);
    return () => {
      window.removeEventListener("keydown", handleInteraction, true);
      window.removeEventListener("pointerdown", handleInteraction, true);
    };
  }, [resultPrintStatus, props.ticketNumber]);

  useEffect(() => {
    if (resultPrintStatus !== "PRINTED" || !interactedWhilePrintingRef.current
      || dismissedRef.current) return;
    dismissedRef.current = true;
    onFinishRef.current();
  }, [resultPrintStatus]);

  return <CashPaymentResultContent {...props} />;
}

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
  issuedVoucher,
  voucherPrintStatus = "SKIPPED",
  onRetryPrint,
  onRetryVoucherPrint,
}: CashPaymentResultDialogProps) {
  const t = createTranslator(locale);
  return (
    <div className="cash-payment-result-layer" role="presentation">
      <section
        className="cash-payment-dialog cash-payment-result-dialog"
        role="region"
        aria-live="polite"
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
        {issuedVoucher && (
          <div className="cash-payment-voucher-result">
            <p>{t("payment.result.voucherIssued")} <strong>{issuedVoucher.code}</strong></p>
            <p>{t("payment.result.voucherAmount")} <strong>{money(Math.round(Number(issuedVoucher.amount) * 100))}</strong></p>
            {voucherPrintStatus === "PRINTING" && <p role="status">{t("payment.result.voucherPrinting")}</p>}
            {voucherPrintStatus === "PRINTED" && <p role="status">{t("payment.result.voucherPrinted")}</p>}
            {voucherPrintStatus === "FAILED" && (
              <div className="cash-payment-print-status cash-payment-print-error" role="alert">
                <span>{t("payment.result.voucherPrintFailed")}</span>
                <button type="button" className="cash-payment-print-retry"
                  onClick={onRetryVoucherPrint}>{t("payment.result.retryVoucherPrint")}</button>
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

function money(cents: number) {
  return (cents / 100).toLocaleString("es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
