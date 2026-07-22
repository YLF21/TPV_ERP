import { useEffect, useRef } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";

type CashPaymentValidationDialogProps = {
  message: string;
  locale?: LocaleCode;
  onAccept: () => void;
};

export function CashPaymentValidationDialog({ message, locale = "es", onAccept }: CashPaymentValidationDialogProps) {
  const dialogRef = useRef<HTMLElement>(null);
  const t = createTranslator(locale);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document, { restoreFocus: false })
    : undefined, []);

  return (
    <div className="cash-payment-validation-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="cash-payment-validation-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="cash-payment-validation-title"
        aria-describedby="cash-payment-validation-message"
        onKeyDown={(event) => {
          if (event.key !== "Enter" && event.key !== "Escape") return;
          event.preventDefault();
          event.stopPropagation();
          onAccept();
        }}
      >
        <header><h2 id="cash-payment-validation-title">{t("cashPayment.validation.title")}</h2></header>
        <p id="cash-payment-validation-message">{message}</p>
        <footer><button type="button" autoFocus onClick={onAccept}>{t("cashPayment.validation.accept")}</button></footer>
      </section>
    </div>
  );
}
