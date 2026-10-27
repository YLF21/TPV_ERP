import { X } from "@phosphor-icons/react";
import { Component, Suspense, lazy, useId, useMemo, useState, type ReactNode } from "react";
import type { MarketingLanguage } from "./marketingI18n";
import type { MarketingDialogProps } from "./MarketingDialogs";
import { MarketingModalLayer, useModalDialog } from "./MarketingModal";

const feedbackCopy = {
  es: {
    loadingTitle: "Preparando la ventana",
    loadingBody: "Estamos cargando esta información. Puedes cerrar la ventana si lo prefieres.",
    errorTitle: "No se ha podido abrir la ventana",
    errorBody: "Comprueba tu conexión y vuelve a intentarlo. Si el problema continúa, recarga la página.",
    retry: "Volver a intentar",
    close: "Cerrar ventana"
  },
  en: {
    loadingTitle: "Preparing this window",
    loadingBody: "We are loading this information. You can close the window if you prefer.",
    errorTitle: "This window could not be opened",
    errorBody: "Check your connection and try again. If the problem continues, reload the page.",
    retry: "Try again",
    close: "Close window"
  },
  zh: {
    loadingTitle: "正在准备窗口",
    loadingBody: "正在加载相关信息。您也可以先关闭此窗口。",
    errorTitle: "无法打开此窗口",
    errorBody: "请检查网络连接后重试。如果仍无法打开，请刷新页面。",
    retry: "重试",
    close: "关闭窗口"
  }
} satisfies Record<MarketingLanguage, Record<string, string>>;

export function getMarketingDialogFeedbackContent(language: MarketingLanguage) {
  return feedbackCopy[language];
}

class DialogLoadBoundary extends Component<{ children: ReactNode; fallback: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    // Do not retain or log exception details: a public dialog needs only safe,
    // actionable feedback, not a stack trace or user-specific information.
    return { failed: true };
  }

  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

export function MarketingDialogFeedback({ language, failed = false, onClose, onRetry }: { language: MarketingLanguage; failed?: boolean; onClose: () => void; onRetry?: () => void }) {
  const copy = getMarketingDialogFeedbackContent(language);
  const { dialogRef, closeRef } = useModalDialog(onClose);
  const id = useId();
  return <MarketingModalLayer onClose={onClose}>
    <section ref={dialogRef} className="mk-download-dialog" data-testid={failed ? "marketing-dialog-error" : "marketing-dialog-loading"} role="dialog" tabIndex={-1} aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-body`} aria-busy={!failed}>
      <header className="mk-product-dialog-header">
        <div><h2 id={`${id}-title`}>{failed ? copy.errorTitle : copy.loadingTitle}</h2><p id={`${id}-body`} role={failed ? "alert" : "status"}>{failed ? copy.errorBody : copy.loadingBody}</p></div>
        <button ref={closeRef} className="mk-dialog-close" type="button" onClick={onClose} aria-label={copy.close}><X weight="bold" aria-hidden="true" /></button>
      </header>
      {failed && <div className="mk-actions"><button className="mk-primary" type="button" onClick={onRetry}>{copy.retry}</button><button className="mk-secondary" type="button" onClick={onClose}>{copy.close}</button></div>}
    </section>
  </MarketingModalLayer>;
}

export function MarketingDialogLoader(props: MarketingDialogProps) {
  const [attempt, setAttempt] = useState(0);
  // A retry creates a fresh React.lazy instance, rather than reusing its rejected
  // promise. The browser still owns module caching and may require a page reload
  // after a deployment changes chunk URLs; feedback explains that fallback.
  const Dialogs = useMemo(() => lazy(() => import("./MarketingDialogs")), [attempt]);
  const retry = () => setAttempt(current => current + 1);
  return <DialogLoadBoundary key={attempt} fallback={<MarketingDialogFeedback language={props.language} failed onClose={props.onClose} onRetry={retry} />}>
    <Suspense fallback={<MarketingDialogFeedback language={props.language} onClose={props.onClose} />}>
      <Dialogs {...props} />
    </Suspense>
  </DialogLoadBoundary>;
}
