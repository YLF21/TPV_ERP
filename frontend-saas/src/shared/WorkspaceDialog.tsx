import { useEffect, useId, useRef, type ReactNode } from "react";
import "./workspace-dialog.css";

export function WorkspaceDialog({ title, subtitle, closeLabel, busy, onClose, restoreFocus, children, className = "", focusFirstInput = false }: {
  title: string; subtitle: string; closeLabel: string; busy: boolean; onClose: () => void;
  restoreFocus: HTMLElement | (() => HTMLElement | null) | null; children: ReactNode; className?: string; focusFirstInput?: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const element = dialog.current!;
    element.showModal();
    if (focusFirstInput) element.querySelector<HTMLInputElement>("input:not(:disabled)")?.focus();
    return () => { element.close(); const target = typeof restoreFocus === "function" ? restoreFocus() : restoreFocus; if (target?.isConnected) target.focus({ preventScroll: true }); };
  }, []);
  return <dialog ref={dialog} className={`saas-workspace-dialog ${className}`} aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}
    onKeyDown={event => {
      // An open date picker consumes Escape before the containing window.
      if (event.key === "Escape" && dialog.current?.querySelector(".date-time-popover")) event.preventDefault();
    }}>
    <header className="saas-workspace-dialog-header">
      <div><span>{subtitle}</span><h3 id={titleId}>{title}</h3></div>
      <button type="button" autoFocus disabled={busy} onClick={onClose}>{closeLabel}</button>
    </header>
    <div className="saas-workspace-dialog-body">{children}</div>
  </dialog>;
}
