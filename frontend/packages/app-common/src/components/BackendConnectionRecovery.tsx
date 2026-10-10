import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { createTranslator } from "../i18n/LocalizedMessages";
import { prepareOfflineApplicationClose, resetOfflineApplicationClosePreparation } from "../sale/offlineClosePreparation";
import type { LocaleCode } from "../types";
import "./BackendConnectionRecovery.css";

type RecoveryState = "CONNECTED" | "OFFLINE" | "CHECKING";
type RecoveryResult = { ok: boolean; state: RecoveryState; backendIp?: string; errorCode?: string };

export function BackendConnectionRecovery({ locale, onRecovered }: { locale: LocaleCode; onRecovered?: () => void }) {
  const bridge = window.tpvDesktop?.connectionRecovery;
  const [visible, setVisible] = useState(false);
  const [connectedOnce, setConnectedOnce] = useState(false);
  const [busy, setBusy] = useState<"retry" | "close" | null>(null);
  const [closeError, setCloseError] = useState(false);
  const retryButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const offlineObserved = useRef(false);
  const t = createTranslator(locale);

  const apply = useCallback((result: RecoveryResult) => {
    if (result.state === "CONNECTED") {
      const shouldReactivate = offlineObserved.current;
      offlineObserved.current = false;
      setConnectedOnce(true);
      setVisible(false);
      setCloseError(false);
      if (shouldReactivate) onRecovered?.();
    } else if (result.state === "OFFLINE") {
      offlineObserved.current = true;
      setVisible(true);
    }
  }, [onRecovered]);

  useEffect(() => {
    if (!bridge) return;
    let active = true;
    let eventReceived = false;
    const unsubscribe = bridge.onStatus((result) => {
      eventReceived = true;
      if (active) apply(result);
    });
    void bridge.status().then((result) => {
      if (!eventReceived && active) apply(result);
    }).catch(() => {
      if (!eventReceived && active) setVisible(true);
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, [bridge, apply]);

  useEffect(() => {
    if (!visible) return;
    const root = document.getElementById("root");
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const wasInert = root?.inert ?? false;
    if (root) root.inert = true;
    retryButton.current?.focus();
    function keepFocus(event: KeyboardEvent) {
      // Native ERP shortcuts listen on window, including keyup (PrintScreen).
      // Keep default Enter/Space button activation but contain every modal key.
      event.stopImmediatePropagation();
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
      if (event.key !== "Tab") return;
      const first = retryButton.current;
      const last = closeButton.current;
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      } else if (!dialog.current?.contains(document.activeElement)) {
        event.preventDefault();
        first.focus();
      }
    }
    const containKey = (event: KeyboardEvent) => event.stopImmediatePropagation();
    window.addEventListener("keydown", keepFocus, true);
    window.addEventListener("keyup", containKey, true);
    window.addEventListener("keypress", containKey, true);
    return () => {
      window.removeEventListener("keydown", keepFocus, true);
      window.removeEventListener("keyup", containKey, true);
      window.removeEventListener("keypress", containKey, true);
      if (root) root.inert = wasInert;
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, [visible]);

  if (!bridge || !visible) return null;

  async function retry() {
    if (!bridge || busy) return;
    setBusy("retry");
    setCloseError(false);
    try {
      const result = await bridge.retry();
      if (result.state === "CONNECTED") {
        apply(result);
      } else {
        setVisible(true);
      }
    } catch {
      setVisible(true);
    } finally {
      setBusy(null);
    }
  }

  async function close() {
    if (busy) return;
    setBusy("close");
    setCloseError(false);
    try {
      await prepareOfflineApplicationClose();
      await window.tpvDesktop?.closeApplication();
    } catch {
      resetOfflineApplicationClosePreparation();
      setCloseError(true);
    } finally {
      setBusy(null);
    }
  }

  return createPortal(
    <div className="backend-recovery-backdrop">
      <div ref={dialog} className="backend-recovery-dialog" role="alertdialog" aria-modal="true"
        aria-labelledby="backend-recovery-title" aria-describedby="backend-recovery-detail">
        <h1 id="backend-recovery-title">{t(connectedOnce
          ? "connectionRecovery.lost"
          : "connectionRecovery.startup")}</h1>
        <p id="backend-recovery-detail">{t("connectionRecovery.detail")}</p>
        {closeError && <p className="backend-recovery-error" role="alert">{t("connectionRecovery.closeError")}</p>}
        <div className="backend-recovery-actions">
          <button ref={retryButton} type="button" className="backend-recovery-retry" disabled={busy !== null}
            onClick={() => void retry()}>{busy === "retry" ? t("connectionRecovery.retrying") : t("connectionRecovery.retry")}</button>
          <button ref={closeButton} type="button" className="backend-recovery-close" disabled={busy !== null}
            onClick={() => void close()}>{busy === "close" ? t("connectionRecovery.closing") : t("connectionRecovery.close")}</button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
