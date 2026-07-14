import { useState } from "react";

type Props = {
  busy: boolean;
  onCancel: () => void;
  onConfirm: (reference: string) => void;
};

export function ManualCardReferenceDialog({ busy, onCancel, onConfirm }: Props) {
  const [reference, setReference] = useState("");
  const normalized = reference.trim();

  return <div role="dialog" aria-modal="true" aria-labelledby="manual-card-title">
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
  </div>;
}
