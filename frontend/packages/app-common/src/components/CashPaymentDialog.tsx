import { useEffect, useMemo, useRef, useState } from "react";
import { cashChangeCents, cashInputCents, pressCashKey, setCashShortcut } from "../sale/cashCalculator";
import type { CashInputMode } from "../sale/cashInputMode";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type CashPaymentDialogProps = {
  totalCents: number;
  submitting: boolean;
  error: string;
  initialMode: CashInputMode;
  onCancel: () => void;
  onConfirm: (receivedCents: number) => void;
};

const keypad = ["7", "8", "9", "4", "5", "6", "1", "2", "3", "0", ","];

export type CashPaymentKeyAction = "confirm" | "cancel" | "none";

export function cashPaymentKeyAction(
  key: string,
  receivedCents: number,
  totalCents: number,
  submitting: boolean,
): CashPaymentKeyAction {
  if (submitting) return "none";
  if (key === "Escape") return "cancel";
  if (key === "Enter" && receivedCents >= totalCents) return "confirm";
  return "none";
}

export function CashPaymentDialog({ totalCents, submitting, error, initialMode, onCancel, onConfirm }: CashPaymentDialogProps) {
  const dialogRef = useRef<HTMLElement>(null);
  const [received, setReceived] = useState("");
  const [mode, setMode] = useState<CashInputMode>(initialMode);
  const receivedCents = useMemo(() => cashInputCents(received), [received]);
  const changeCents = cashChangeCents(totalCents, receivedCents);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      const action = cashPaymentKeyAction(event.key, receivedCents, totalCents, submitting);
      if (action === "cancel") {
        event.preventDefault();
        onCancel();
      } else if (action === "confirm") {
        event.preventDefault();
        onConfirm(receivedCents);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onCancel, onConfirm, receivedCents, submitting, totalCents]);

  return (
    <div className="sale-action-overlay" role="presentation">
      <section ref={dialogRef} className="cash-payment-dialog" role="dialog" aria-modal="true" aria-labelledby="cash-payment-title">
        <header>
          <h2 id="cash-payment-title">Cobro en efectivo</h2>
          <button type="button" aria-label="Cerrar" disabled={submitting} onClick={onCancel}>×</button>
        </header>
        <div className="cash-payment-summary" aria-live="polite">
          <div><span>Total</span><strong>{money(totalCents)}</strong></div>
          <div><span>Dinero recibido</span><strong>{money(receivedCents)}</strong></div>
          <div className="cash-change"><span>Cambio</span><strong>{money(changeCents)}</strong></div>
        </div>
        <input
          autoFocus
          className="cash-received-input"
          aria-label="Dinero recibido"
          inputMode="decimal"
          value={received}
          disabled={submitting}
          onChange={(event) => {
            const normalized = event.target.value.replace(".", ",");
            if (/^\d*(?:,\d{0,2})?$/.test(normalized)) setReceived(normalized);
          }}
        />
        <button className="cash-input-mode-toggle" type="button" disabled={submitting} onClick={() => setMode((value) => value === "touch" ? "keyboard" : "touch")}>
          {mode === "touch" ? "Usar teclado físico" : "Mostrar teclado táctil"}
        </button>
        {mode === "touch" && <>
          <div className="cash-shortcuts">
            <button type="button" disabled={submitting} onClick={() => setReceived(setCashShortcut("EXACT", totalCents))}>Exacto</button>
            {[5, 10, 20, 50].map((amount) => (
              <button type="button" disabled={submitting} key={amount} onClick={() => setReceived(setCashShortcut(amount, totalCents))}>{amount} €</button>
            ))}
          </div>
          <div className="cash-keypad">
            {keypad.map((key) => <button type="button" disabled={submitting} aria-label={`Tecla ${key}`} key={key} onClick={() => setReceived((value) => pressCashKey(value, key))}>{key}</button>)}
            <button type="button" disabled={submitting} aria-label="Borrar ultima cifra" onClick={() => setReceived((value) => pressCashKey(value, "BACKSPACE"))}>⌫</button>
            <button type="button" disabled={submitting} aria-label="Limpiar importe" onClick={() => setReceived("")}>C</button>
          </div>
        </>}
        {receivedCents > 0 && receivedCents < totalCents && <p className="sale-action-error" role="alert">El importe recibido no cubre el total</p>}
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        <footer className="cash-payment-actions">
          <button type="button" disabled={submitting} onClick={onCancel}>Cancelar</button>
          <button type="button" disabled={submitting || receivedCents < totalCents} onClick={() => onConfirm(receivedCents)}>{submitting ? "Registrando..." : "Confirmar cobro"}</button>
        </footer>
      </section>
    </div>
  );
}

function money(cents: number) {
  return (cents / 100).toLocaleString("es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
