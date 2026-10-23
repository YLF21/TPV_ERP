import { WindowCloseButton } from "./WindowCloseButton";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = {
  children: ReactNode;
  locale: LocaleCode;
  onConfirmHome: () => void;
  navigationBlocked?: boolean;
};

const modalSelector = '[role="dialog"], [role="alertdialog"]';

export function hasOpenAppVentaFunctionalLayer(root: ParentNode = document) {
  return Boolean(root.querySelector(`${modalSelector}, [aria-expanded="true"]`));
}

export function AppVentaHomeEscapeNavigation({ children, locale, onConfirmHome, navigationBlocked = false }: Props) {
  const [confirmationOpen, setConfirmationOpen] = useState(false);
  const [blockedNoticeOpen, setBlockedNoticeOpen] = useState(false);
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
    if (navigationBlocked) return;
    cancelNavigation();
    onConfirmHome();
  }

  useEffect(() => {
    const requestConfirmation = (focusTarget: HTMLElement | null) => {
      if (confirmationOpen || hasOpenAppVentaFunctionalLayer()) return;
      if (navigationBlocked) {
        setBlockedNoticeOpen(true);
        return;
      }
      previouslyFocusedRef.current = focusTarget;
      setConfirmationOpen(true);
    };
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
      requestConfirmation(document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null);
    };
    const handleBrandClick = (event: MouseEvent) => {
      if (event.defaultPrevented || !(event.target instanceof Element)) return;
      const brandButton = event.target.closest<HTMLButtonElement>("button.report-brand-back");
      if (!brandButton || brandButton.disabled) return;
      // Capture before the shared screen's direct onBack; only APP VENTA mounts this guard.
      event.preventDefault();
      event.stopImmediatePropagation();
      requestConfirmation(brandButton);
    };
    window.addEventListener("keydown", handleKeyDown);
    window.addEventListener("click", handleBrandClick, true);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("click", handleBrandClick, true);
    };
  }, [confirmationOpen, navigationBlocked, onConfirmHome]);

  useEffect(() => {
    if (navigationBlocked) setConfirmationOpen(false);
    else setBlockedNoticeOpen(false);
  }, [navigationBlocked]);

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
      {blockedNoticeOpen && navigationBlocked && (
        <aside className="app-notice-toast" role="alert" aria-live="assertive">
          <span>{t("appVenta.escapeHome.blocked")}</span>
          <button type="button" onClick={() => setBlockedNoticeOpen(false)}>
            {t("common.close")}
          </button>
        </aside>
      )}
      {confirmationOpen && !navigationBlocked && (
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
              <WindowCloseButton type="button" aria-label={t("common.close")} onClick={cancelNavigation} desktopOnly />
            </header>
            <p id="app-venta-home-confirm-message">{t("appVenta.escapeHome.message")}</p>
            <p id="app-venta-home-confirm-shortcuts" className="app-venta-home-confirm-shortcuts">
              {t("appVenta.escapeHome.shortcuts")}
            </p>
          <footer className="erp-dialog-actions-row">
            <button type="button" className="erp-dialog-action-cancel erp-dialog-dismiss" onClick={cancelNavigation}>{t("common.cancel")}</button>
              <button ref={confirmButtonRef} type="button" className="primary erp-dialog-action-confirm" onClick={confirmNavigation}>
                {t("appVenta.escapeHome.confirm")}
              </button>
            </footer>
          </section>
        </div>
      )}
    </>
  );
}
