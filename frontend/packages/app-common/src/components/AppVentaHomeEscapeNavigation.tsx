import { useEffect, useRef, useState, type ReactNode } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = {
  children: ReactNode;
  locale: LocaleCode;
  onConfirmHome: () => void;
};

const modalSelector = '[role="dialog"], [role="alertdialog"]';

export function hasOpenAppVentaFunctionalLayer(root: ParentNode = document) {
  return Boolean(root.querySelector(`${modalSelector}, [aria-expanded="true"]`));
}

export function AppVentaHomeEscapeNavigation({ children, locale, onConfirmHome }: Props) {
  const [confirmationOpen, setConfirmationOpen] = useState(false);
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);
  const confirmButtonRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const t = createTranslator(locale);

  function cancelNavigation() {
    setConfirmationOpen(false);
    const focusTarget = previouslyFocusedRef.current;
    queueMicrotask(() => focusTarget?.isConnected && focusTarget.focus());
  }

  function confirmNavigation() {
    setConfirmationOpen(false);
    onConfirmHome();
  }

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.repeat) return;
      if (confirmationOpen) {
        if (event.key !== "Enter" && event.key !== "Escape") return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (event.key === "Enter") confirmNavigation();
        else cancelNavigation();
        return;
      }
      if (event.key !== "Escape" || event.defaultPrevented) return;
      if (event.target instanceof HTMLSelectElement || hasOpenAppVentaFunctionalLayer()) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      previouslyFocusedRef.current = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
      setConfirmationOpen(true);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [confirmationOpen, onConfirmHome]);

  useEffect(() => {
    if (!confirmationOpen || !dialogRef.current) return;
    confirmButtonRef.current?.focus();
    return activateModalFocusTrap(
      dialogRef.current as unknown as ModalFocusRoot,
      document,
      { restoreFocus: false },
    );
  }, [confirmationOpen]);

  return (
    <>
      {children}
      {confirmationOpen && (
        <div className="app-venta-home-confirm-overlay" role="presentation">
          <section
            ref={dialogRef}
            className="app-venta-home-confirm-dialog"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="app-venta-home-confirm-title"
            aria-describedby="app-venta-home-confirm-message app-venta-home-confirm-shortcuts"
          >
            <header>
              <h2 id="app-venta-home-confirm-title">{t("appVenta.escapeHome.title")}</h2>
            </header>
            <p id="app-venta-home-confirm-message">{t("appVenta.escapeHome.message")}</p>
            <p id="app-venta-home-confirm-shortcuts" className="app-venta-home-confirm-shortcuts">
              {t("appVenta.escapeHome.shortcuts")}
            </p>
            <footer>
              <button type="button" onClick={cancelNavigation}>{t("common.cancel")}</button>
              <button ref={confirmButtonRef} type="button" className="primary" onClick={confirmNavigation}>
                {t("appVenta.escapeHome.confirm")}
              </button>
            </footer>
          </section>
        </div>
      )}
    </>
  );
}
