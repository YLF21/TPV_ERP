import { createPortal } from "react-dom";
import { useEffect, useRef, type ReactNode } from "react";

export type FiscalWorkspaceDialogProps = {
  id: string;
  title: string;
  closeLabel: string;
  onClose: () => void;
  variant?: "modal" | "drawer";
  purpose?: "default" | "filters" | "export";
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
  closeDisabled?: boolean;
};

const focusableSelector = [
  "button:not([disabled])",
  "[href]",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex=\"-1\"])"
].join(",");

export function FiscalWorkspaceDialog({
  id,
  title,
  closeLabel,
  onClose,
  variant = "modal",
  purpose = "default",
  children,
  footer,
  className = "",
  closeDisabled = false
}: FiscalWorkspaceDialogProps) {
  const dialogRef = useRef<HTMLElement | null>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  const closeDisabledRef = useRef(closeDisabled);
  onCloseRef.current = onClose;
  closeDisabledRef.current = closeDisabled;

  useEffect(() => {
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    const dialog = dialogRef.current;
    const focusFirstControl = () => {
      const first = dialog?.querySelector<HTMLElement>(focusableSelector);
      first?.focus();
    };
    const focusPrevious = previousFocusRef.current;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        if (!closeDisabledRef.current) {
          event.preventDefault();
          onCloseRef.current();
        }
        return;
      }
      if (event.key !== "Tab" || !dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(focusableSelector));
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const currentIndex = focusable.indexOf(document.activeElement as HTMLElement);
      if (currentIndex < 0) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else {
        const nextIndex = event.shiftKey
          ? (currentIndex - 1 + focusable.length) % focusable.length
          : (currentIndex + 1) % focusable.length;
        event.preventDefault();
        focusable[nextIndex].focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    const frame = window.requestAnimationFrame(focusFirstControl);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("keydown", onKeyDown);
      if (focusPrevious && focusPrevious.isConnected) focusPrevious.focus();
    };
  }, []);

  if (typeof document === "undefined") return null;

  const isDrawer = variant === "drawer";
  const dialogClassName = [
    isDrawer ? "fiscal-workspace-dialog fiscal-workspace-dialog-drawer" : "fiscal-workspace-dialog",
    `fiscal-workspace-dialog-purpose-${purpose}`,
    className
  ].filter(Boolean).join(" ");

  return createPortal(
    <div
      className={`gestion-modal-backdrop fiscal-workspace-dialog-backdrop ${isDrawer ? "has-drawer" : ""}`}
      role="presentation"
      onMouseDown={(event) => {
        if (!closeDisabled && event.target === event.currentTarget) onClose();
      }}
    >
      <section
        ref={dialogRef}
        className={dialogClassName}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        data-dialog-id={id}
      >
        <header>
          <h2 id={`${id}-title`}>{title}</h2>
          <button type="button" onClick={onClose} disabled={closeDisabled} aria-label={closeLabel}>
            ×
          </button>
        </header>
        <div className="fiscal-workspace-dialog-body">{children}</div>
        {footer && <footer>{footer}</footer>}
      </section>
    </div>,
    document.body
  );
}
