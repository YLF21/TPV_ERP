import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import "./ErpClassicWindow.css";
export function ErpConfirmDialog({ title, message, confirmLabel, cancelLabel, onConfirm, onCancel, busy = false }: {
  title: string; message: string; confirmLabel: string; cancelLabel: string;
  onConfirm: () => void; onCancel: () => void; busy?: boolean;
}) {
  const root = useRef<HTMLElement>(null); const id = useId();
  useEffect(() => { if (root.current) return activateModalFocusTrap(root.current as unknown as ModalFocusRoot, document); }, []);
  return createPortal(<div className="erp-confirm-overlay" onKeyDown={event => { if (event.key === "Escape") { event.stopPropagation(); if (!busy) onCancel(); } }}>
    <section ref={root} role="alertdialog" aria-modal="true" aria-labelledby={id} aria-describedby={`${id}-message`} className="filter-dialog erp-classic-window erp-confirm-dialog">
      <header><h2 id={id}>{title}</h2></header><p id={`${id}-message`} className="erp-confirm-message">{message}</p>
      <footer className="filter-actions"><button type="button" disabled={busy} onClick={onCancel}>{cancelLabel}</button><button type="button" disabled={busy} onClick={onConfirm}>{confirmLabel}</button></footer>
    </section></div>, document.body);
}
