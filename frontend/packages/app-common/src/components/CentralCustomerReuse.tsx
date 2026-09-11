import { useEffect, useRef, useState } from "react";
import { apiProblemCode, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import type { CustomerView } from "./PartyDirectoryPanel";
import { customerDocumentType, customerIdentityFailure } from "./customerDocumentIdentity";
import "./CentralCustomerReuse.css";

type Candidate = {
  customerId: string; revision: number; centralCode: string; fiscalName: string;
  documentType: string; documentNumber: string; active: boolean; localCustomerId?: string | null;
};
type Props = {
  documentType: string; documentNumber: string; session: UserSession; locale: LocaleCode;
  disabled: boolean; onBusyChange: (busy: boolean) => void; onAdopted: (customer: CustomerView) => void;
};

/** Explicit reuse, never automatic adoption on a duplicate-registration error. */
export function CentralCustomerReuse({ documentType, documentNumber, session, locale, disabled, onBusyChange, onAdopted }: Props) {
  const t = createTranslator(locale);
  const [candidate, setCandidate] = useState<Candidate | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const requestRef = useRef<AbortController | null>(null);
  const busyCallback = useRef(onBusyChange);
  busyCallback.current = onBusyChange;
  const allowed = session.permissions.some((permission) => ["ADMIN", "VENTA", "CUSTOMERS_WRITE", "GESTION_CLIENTE_PROVEEDOR"].includes(permission));

  useEffect(() => {
    setCandidate(null); setError(""); setBusy(false);
    return () => {
      requestRef.current?.abort(); requestRef.current = null;
      busyCallback.current(false);
    };
  }, [documentType, documentNumber, session.accessToken, allowed]);

  async function perform(adopt: boolean) {
    if (!allowed || requestRef.current || disabled || !documentNumber.trim() || adopt && !candidate?.active) return;
    const controller = new AbortController();
    requestRef.current = controller; setBusy(true); onBusyChange(true); setError("");
    if (!adopt) setCandidate(null);
    try {
      if (adopt && candidate) {
        const result = await apiRequest<{ customer: CustomerView }>("/customers/adopt-central", {
          token: session.accessToken, signal: controller.signal,
          body: { customerId: candidate.customerId, expectedRevision: candidate.revision,
            documentType: candidate.documentType, documentNumber: candidate.documentNumber },
        });
        if (!controller.signal.aborted) onAdopted(result.customer);
      } else {
        const result = await apiRequest<Candidate>("/customers/central-lookup", {
          token: session.accessToken, signal: controller.signal,
          body: { documentType: customerDocumentType(documentType), documentNumber: documentNumber.trim() },
        });
        if (!controller.signal.aborted) setCandidate(result);
      }
    } catch (failure) {
      if (controller.signal.aborted) return;
      const identity = customerIdentityFailure(failure);
      setError(apiProblemCode(failure) === "CUSTOMER_CENTRAL_NOT_FOUND" ? "party.central.notFound"
        : identity?.messageKey ?? "party.central.error");
      if (adopt) setCandidate(null);
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null; setBusy(false); onBusyChange(false);
      }
    }
  }

  if (!allowed) return null;
  return <section className="party-central-customer" aria-label={t("party.central.title")} aria-busy={busy}>
    <div className="party-central-customer-search">
      <span>{t("party.central.help")}</span>
      <button type="button" disabled={disabled || busy || !documentNumber.trim()} onClick={() => void perform(false)}>
        {t(busy ? "common.loading" : "party.central.search")}
      </button>
    </div>
    {candidate && <div className="party-central-customer-result">
      <div><strong>{candidate.centralCode} · {candidate.fiscalName}</strong><span>{candidate.documentType} · {candidate.documentNumber}</span></div>
      <button type="button" disabled={disabled || busy || !candidate.active} onClick={() => void perform(true)}>{t("party.central.use")}</button>
      {!candidate.active && <span role="status">{t("party.central.inactive")}</span>}
    </div>}
    {error && <p role="alert">{t(error)}</p>}
  </section>;
}
