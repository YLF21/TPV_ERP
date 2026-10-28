import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { apiProblemCode, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import { customerDocumentType, customerIdentityFailure } from "./customerDocumentIdentity";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

export type ExistingCentralCustomer = { centralCode: string; documentNumber: string };
export function customerDocumentValidationKey(documentType: string, documentNumber: string) {
  return `${customerDocumentType(documentType)}:${documentNumber.trim().toUpperCase()}`;
}

export function CustomerDocumentValidation({ documentType, documentNumber, session, locale, disabled, onBusyChange, onAvailable, onExisting }: {
  documentType: string; documentNumber: string; session: UserSession; locale: LocaleCode; disabled: boolean;
  onBusyChange: (busy: boolean) => void; onAvailable: (identityKey: string) => void; onExisting: (customer: ExistingCentralCustomer) => void;
}) {
  const t = createTranslator(locale);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [invalid, setInvalid] = useState(false);
  const request = useRef<AbortController | null>(null);
  const busyCallback = useRef(onBusyChange);
  busyCallback.current = onBusyChange;
  useEffect(() => {
    setMessage(""); setInvalid(false); setBusy(false);
    return () => { request.current?.abort(); request.current = null; busyCallback.current(false); };
  }, [documentType, documentNumber, session.accessToken]);

  async function validate() {
    if (disabled || request.current || !documentNumber.trim()) return;
    const controller = new AbortController();
    request.current = controller; setBusy(true); busyCallback.current(true); setMessage(""); setInvalid(false);
    const identityKey = customerDocumentValidationKey(documentType, documentNumber);
    try {
      const result = await apiRequest<ExistingCentralCustomer>("/customers/central-lookup", {
        token: session.accessToken, signal: controller.signal,
        body: { documentType: customerDocumentType(documentType), documentNumber: documentNumber.trim() },
      });
      if (controller.signal.aborted) return;
      if (!result || typeof result.centralCode !== "string" || !result.centralCode.trim() || typeof result.documentNumber !== "string" || !result.documentNumber.trim()) throw new Error("invalid_customer_lookup_response");
      onExisting(result);
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (apiProblemCode(failure) === "CUSTOMER_CENTRAL_NOT_FOUND") {
        setMessage("party.customerEditor.available"); onAvailable(identityKey);
      } else {
        setMessage(customerIdentityFailure(failure)?.messageKey ?? "party.customerEditor.validationError"); setInvalid(true);
      }
    } finally {
      if (request.current === controller) { request.current = null; setBusy(false); busyCallback.current(false); }
    }
  }

  return <div className="party-document-validation">
    <button type="button" disabled={disabled || busy || !documentNumber.trim()} onClick={() => void validate()}>{t(busy ? "common.loading" : "party.customerEditor.validate")}</button>
    {message && <small role={invalid ? "alert" : "status"} className={invalid ? "party-field-error" : "party-document-available"}>{t(message)}</small>}
  </div>;
}

export function ExistingCustomerNotice({ customer, locale, onAccept }: { customer: ExistingCentralCustomer; locale: LocaleCode; onAccept: () => void }) {
  const t = createTranslator(locale);
  const id = useId();
  const root = useRef<HTMLElement>(null);
  const accept = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const release = root.current ? activateModalFocusTrap(root.current as unknown as ModalFocusRoot, document) : undefined;
    accept.current?.focus();
    return release;
  }, []);
  return createPortal(<div className="erp-confirm-overlay" onKeyDown={event => {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); onAccept(); }
  }}>
    <section ref={root} className="filter-dialog erp-classic-window erp-confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby={id} aria-describedby={`${id}-message`}>
      <header><h2 id={id}>{t("party.customerEditor.existsTitle")}</h2></header>
      <p id={`${id}-message`} className="erp-confirm-message" style={{ whiteSpace: "pre-line" }}>{t("party.customerEditor.exists").replace("{code}", customer.centralCode).replace("{document}", customer.documentNumber)}</p>
      <footer className="filter-actions erp-dialog-actions-row"><button ref={accept} type="button" className="erp-dialog-action-confirm" onClick={onAccept}>{t("common.accept")}</button></footer>
    </section>
  </div>, document.body);
}
