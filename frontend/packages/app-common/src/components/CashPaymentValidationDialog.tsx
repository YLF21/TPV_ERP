import { useEffect, useRef } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type CashPaymentValidationDialogProps = {
  message: string;
  onAccept: () => void;
};

export function CashPaymentValidationDialog({ message, onAccept }: CashPaymentValidationDialogProps) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
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
        <header><h2 id="cash-payment-validation-title">Aviso</h2></header>
        <p id="cash-payment-validation-message">{message}</p>
        <footer><button type="button" autoFocus onClick={onAccept}>Aceptar</button></footer>
      </section>
    </div>
  );
}
