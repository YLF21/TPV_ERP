import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { useEffect, useRef, useState } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = {
  busy: boolean;
  onCancel: () => void;
  onConfirm: (reference: string) => void;
};

export function ManualCardReferenceDialog({ busy, onCancel, onConfirm }: Props) {
  const dialogRef = useRef<HTMLElement>(null);
  const referenceInputRef = useRef<HTMLInputElement>(null);
  const [reference, setReference] = useState("");
  const normalized = reference.trim();

  useEffect(() => {
    if (!dialogRef.current) return undefined;
    const release = activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document);
    referenceInputRef.current?.focus();
    return release;
  }, []);

  return <section ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="manual-card-title" onKeyDown={event=>{
    if(event.key==="Escape"&&!busy){event.preventDefault();onCancel();}
  }}>
    <header><h2 id="manual-card-title">Cobro con tarjeta manual</h2><WindowCloseButton type="button" aria-label="Cancelar" disabled={busy} onClick={onCancel} onLight desktopOnly /></header>
    <label>
      Referencia obligatoria
      <input
        ref={referenceInputRef}
        value={reference}
        autoComplete="off"
        onChange={event => setReference(event.currentTarget.value)}
      />
    </label>
    <footer className="erp-dialog-actions-row">
      <DialogDismissButton type="button" className="erp-dialog-action-cancel erp-dialog-dismiss" disabled={busy} onClick={onCancel}>Cancelar</DialogDismissButton>
      <button type="button" className="erp-dialog-action-confirm" disabled={busy || !normalized} onClick={() => onConfirm(normalized)}>Confirmar</button>
    </footer>
  </section>;
}
