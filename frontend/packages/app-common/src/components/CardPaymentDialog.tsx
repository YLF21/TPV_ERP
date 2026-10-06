import { WindowCloseButton } from "./WindowCloseButton";
import { useEffect, useRef } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = { totalCents: number; status: string; submitting: boolean; message: string; onCancel: () => void; onConsult?: () => void; onNewOperation?: () => void; onRetry?: () => void };

export function cardPaymentKeyAction(key: string, submitting: boolean) { return key === "Escape" && !submitting ? "cancel" : null; }
export const activateCardPaymentFocusTrap = activateModalFocusTrap;

export function CardPaymentDialog({ totalCents, status, submitting, message, onCancel, onConsult, onNewOperation, onRetry }: Props) {
  const ref = useRef<HTMLElement>(null);
  const cancelButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!ref.current) return undefined;
    const release = activateCardPaymentFocusTrap(ref.current as unknown as ModalFocusRoot, document);
    if (!cancelButtonRef.current?.disabled) cancelButtonRef.current?.focus();
    return release;
  }, []);
  const finalFailure = status === "DECLINED" || status === "ERROR" || status === "CANCELLED";
  const uncertain = !["APPROVED", "DECLINED", "ERROR", "CANCELLED"].includes(status);
  return <div className="sale-action-overlay" role="presentation">
    <section ref={ref} className="cash-payment-dialog card-payment-dialog" role="dialog" aria-modal="true" aria-labelledby="card-payment-title" onKeyDown={(event) => { if (cardPaymentKeyAction(event.key, submitting) === "cancel") { event.preventDefault(); onCancel(); } }}>
      <header><h2 id="card-payment-title">Procesando pago con tarjeta</h2><WindowCloseButton type="button" aria-label={uncertain ? "Cerrar para revisar" : "Cancelar"} disabled={submitting} onClick={onCancel} onLight desktopOnly /></header>
      <div className="cash-payment-summary"><div><span>Total</span><strong>{money(totalCents)}</strong></div></div>
      <p className={`card-payment-state card-payment-${status.toLowerCase()}`} role="status">{message || (submitting ? "Esperando respuesta del datáfono..." : "Operación no completada")}</p>
      {uncertain && !submitting && <p className="card-payment-review">La operación puede haberse procesado. Consulta su estado antes de iniciar otro cobro.</p>}
        <footer className="cash-payment-actions erp-dialog-actions-row">
          <button ref={cancelButtonRef} type="button" className="erp-dialog-action-cancel erp-dialog-dismiss" disabled={submitting} onClick={onCancel}>{uncertain ? "Cerrar para revisar" : "Cancelar"}</button>
        {uncertain && !submitting && <button type="button" className="erp-dialog-action-confirm" onClick={onConsult}>Consultar estado</button>}
        {finalFailure && <button type="button" className="erp-dialog-action-confirm" disabled={submitting} onClick={onNewOperation ?? onRetry}>Nueva operación</button>}
      </footer>
    </section>
  </div>;
}

function money(cents: number) { return (cents / 100).toLocaleString("es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }
