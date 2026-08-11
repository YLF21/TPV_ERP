import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { ApiError, apiRequest, type UserSession } from "@tpverp/app-common";
import {
  loadStoreDocumentPrintConfiguration,
  removeStoreDocumentLogo,
  saveStoreDocumentObservations,
  uploadStoreDocumentLogo,
  type StoreDocumentPrintConfiguration,
} from "./storeDocumentPrintConfigurationApi";

type Props = {
  session: UserSession;
  storeName: string;
  t: (key: string) => string;
  request?: typeof apiRequest;
};

const MAX_LOGO_BYTES = 2 * 1024 * 1024;
const MAX_OBSERVATIONS_LENGTH = 2000;

function failureMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) return error.message || fallback;
  return error instanceof Error && error.message ? error.message : fallback;
}

export function StoreDocumentPrintSettingsScreen({
  session,
  storeName,
  t,
  request = apiRequest,
}: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [configuration, setConfiguration] = useState<StoreDocumentPrintConfiguration | null>(null);
  const [ticket, setTicket] = useState("");
  const [invoice, setInvoice] = useState("");
  const [deliveryNote, setDeliveryNote] = useState("");
  const [pendingLogo, setPendingLogo] = useState<File | null>(null);
  const [pendingPreview, setPendingPreview] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<"logo" | "observations" | null>(null);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const canManage = session.permissions.includes("ADMIN");

  function apply(value: StoreDocumentPrintConfiguration) {
    setConfiguration(value);
    setTicket(value.ticketObservations ?? "");
    setInvoice(value.invoiceObservations ?? "");
    setDeliveryNote(value.deliveryNoteObservations ?? "");
  }

  useEffect(() => {
    let active = true;
    setLoading(true);
    void loadStoreDocumentPrintConfiguration(session.accessToken, request)
      .then((value) => { if (active) apply(value); })
      .catch((error) => {
        if (active) setMessage({
          kind: "error",
          text: failureMessage(error, t("gestion.documentPrint.loadError")),
        });
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [request, session.accessToken, t]);

  useEffect(() => () => {
    if (pendingPreview) URL.revokeObjectURL(pendingPreview);
  }, [pendingPreview]);

  function selectLogo(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0] ?? null;
    setMessage(null);
    if (!file) return;
    if (!(["image/png", "image/jpeg"].includes(file.type)) || file.size > MAX_LOGO_BYTES) {
      event.currentTarget.value = "";
      setPendingLogo(null);
      setPendingPreview(null);
      setMessage({ kind: "error", text: t("gestion.documentPrint.logoInvalid") });
      return;
    }
    setPendingLogo(file);
    setPendingPreview(URL.createObjectURL(file));
  }

  async function saveLogo() {
    if (!canManage || !pendingLogo || busy) return;
    setBusy("logo");
    setMessage(null);
    try {
      apply(await uploadStoreDocumentLogo(pendingLogo, session.accessToken, request));
      setPendingLogo(null);
      setPendingPreview(null);
      if (inputRef.current) inputRef.current.value = "";
      setMessage({ kind: "success", text: t("gestion.documentPrint.logoSaved") });
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentPrint.saveError")) });
    } finally {
      setBusy(null);
    }
  }

  async function removeLogo() {
    if (!canManage || busy || !configuration?.logo) return;
    setBusy("logo");
    setMessage(null);
    try {
      apply(await removeStoreDocumentLogo(session.accessToken, request));
      setPendingLogo(null);
      setPendingPreview(null);
      setMessage({ kind: "success", text: t("gestion.documentPrint.logoRemoved") });
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentPrint.saveError")) });
    } finally {
      setBusy(null);
    }
  }

  async function saveObservations() {
    if (!canManage || busy) return;
    setBusy("observations");
    setMessage(null);
    try {
      apply(await saveStoreDocumentObservations(
        { ticket, invoice, deliveryNote }, session.accessToken, request,
      ));
      setMessage({ kind: "success", text: t("gestion.documentPrint.observationsSaved") });
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentPrint.saveError")) });
    } finally {
      setBusy(null);
    }
  }

  const preview = pendingPreview ?? configuration?.logo?.dataUri ?? null;

  return (
    <section className="gestion-workspace gestion-document-print-workspace">
      <header className="gestion-document-print-header">
        <div>
          <span>{t("gestion.configuration.navigation")}</span>
          <h2>{t("gestion.documentPrint.title")}</h2>
          <p>{t("gestion.documentPrint.description")}</p>
        </div>
        <aside>
          <strong>{t("gestion.documentPrint.storeScope")}</strong>
          <small>{storeName}</small>
        </aside>
      </header>

      {message && <p className={`gestion-document-print-message ${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}

      {loading ? <p className="gestion-document-print-loading">{t("common.loading")}</p> : (
        <>
          <section className="gestion-document-logo-panel" aria-labelledby="document-logo-title">
            <div className={`gestion-document-logo-preview ${preview ? "has-logo" : ""}`}>
              {preview ? <img src={preview} alt={t("gestion.documentPrint.logoPreview")} /> : <span>{t("gestion.documentPrint.noLogo")}</span>}
            </div>
            <div className="gestion-document-logo-copy">
              <h3 id="document-logo-title">{t("gestion.documentPrint.logo")}</h3>
              <p>{t("gestion.documentPrint.logoHelp")}</p>
              <input
                ref={inputRef}
                type="file"
                accept="image/png,image/jpeg,.png,.jpg,.jpeg"
                disabled={!canManage || busy !== null}
                onChange={selectLogo}
              />
              <div className="gestion-document-print-actions">
                <button type="button" className="gestion-primary-button" disabled={!canManage || busy !== null || !pendingLogo} onClick={() => void saveLogo()}>{t("gestion.documentPrint.saveLogo")}</button>
                <button type="button" disabled={!canManage || busy !== null || !configuration?.logo} onClick={() => void removeLogo()}>{t("gestion.documentPrint.removeLogo")}</button>
              </div>
            </div>
          </section>

          <section className="gestion-document-observations-panel" aria-labelledby="document-observations-title">
            <header>
              <h3 id="document-observations-title">{t("gestion.documentPrint.observations")}</h3>
              <p>{t("gestion.documentPrint.observationsHelp")}</p>
            </header>
            <div className="gestion-document-observations-grid">
              {([
                ["ticket", ticket, setTicket],
                ["invoice", invoice, setInvoice],
                ["deliveryNote", deliveryNote, setDeliveryNote],
              ] as const).map(([kind, value, update]) => (
                <label key={kind}>
                  <span>{t(`gestion.documentPrint.${kind}`)}</span>
                  <textarea value={value} rows={7} maxLength={MAX_OBSERVATIONS_LENGTH} disabled={!canManage || busy !== null} onChange={(event) => update(event.currentTarget.value)} />
                  <small>{value.length} / {MAX_OBSERVATIONS_LENGTH}</small>
                </label>
              ))}
            </div>
            <footer>
              <button type="button" className="gestion-primary-button" disabled={!canManage || busy !== null} onClick={() => void saveObservations()}>{t("gestion.documentPrint.saveObservations")}</button>
            </footer>
          </section>
        </>
      )}
    </section>
  );
}
