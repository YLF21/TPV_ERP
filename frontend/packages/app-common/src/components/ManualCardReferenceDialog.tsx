import { useEffect, useRef, useState } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = {
  busy: boolean;
  onCancel: () => void;
  onConfirm: (reference: string) => void;
};

export function ManualCardReferenceDialog({ busy, onCancel, onConfirm }: Props) {
  const dialogRef = useRef<HTMLElement>(null);
  const [reference, setReference] = useState("");
  const normalized = reference.trim();

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  return <section ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="manual-card-title" onKeyDown={event=>{
    if(event.key==="Escape"&&!busy){event.preventDefault();onCancel();}
  }}>
    <h2 id="manual-card-title">Cobro con tarjeta manual</h2>
    <label>
      Referencia obligatoria
      <input
        value={reference}
        autoComplete="off"
        onChange={event => setReference(event.currentTarget.value)}
      />
    </label>
    <button type="button" disabled={busy || !normalized} onClick={() => onConfirm(normalized)}>Confirmar</button>
    <button type="button" disabled={busy} onClick={onCancel}>Cancelar</button>
  </section>;
}
