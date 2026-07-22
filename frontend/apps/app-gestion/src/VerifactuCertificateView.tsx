import { useEffect, useRef, useState, type FormEvent, type MouseEvent, type ReactNode } from "react";
import type { LocaleCode } from "@tpverp/app-common";
import {
  deleteVerifactuCertificate,
  importVerifactuCertificate,
  loadVerifactuCertificates,
  type VerifactuManagedCertificate
} from "./verifactuManagementApi";
import {
  formatVerifactuDateTime,
  type VerifactuTranslator
} from "./verifactuPresentation";

const MAX_CERTIFICATE_BYTES = 10 * 1024 * 1024;
const REPLACE_CONFIRMATION = "SUSTITUIR CERTIFICADO";
const DELETE_CONFIRMATION = "ELIMINAR CERTIFICADO";

type DialogMode = "import" | "replace" | "delete" | null;
type ResultKind = "imported" | "replaced" | "deleted" | null;

export function VerifactuCertificateView({
  locale,
  token,
  revision,
  t,
  onChanged
}: {
  locale: LocaleCode;
  token?: string;
  revision: number;
  t: VerifactuTranslator;
  onChanged: () => void;
}) {
  const [certificates, setCertificates] = useState<VerifactuManagedCertificate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [dialog, setDialog] = useState<DialogMode>(null);
  const [result, setResult] = useState<ResultKind>(null);
  const request = useRef(0);

  const active = certificates.find((certificate) => certificate.status === "ACTIVO") ?? null;
  const previous = certificates.find((certificate) => certificate.status === "ANTERIOR") ?? null;

  useEffect(() => {
    const requestId = ++request.current;
    setLoading(true);
    setError(false);
    void loadVerifactuCertificates(token)
      .then((next) => {
        if (requestId === request.current) setCertificates(next);
      })
      .catch(() => {
        if (requestId !== request.current) return;
        setCertificates([]);
        setError(true);
      })
      .finally(() => {
        if (requestId === request.current) setLoading(false);
      });
    return () => { request.current += 1; };
  }, [revision, token]);

  function completed(next: ResultKind) {
    setDialog(null);
    setResult(next);
    onChanged();
  }

  if (loading && certificates.length === 0) {
    return <div className="gestion-verifactu-message">{t("verifactu.certificate.loading")}</div>;
  }

  if (error) {
    return <div className="gestion-verifactu-message error" role="alert">{t("verifactu.certificate.loadError")}</div>;
  }

  return (
    <div className="gestion-verifactu-certificate">
      <section className={`gestion-verifactu-certificate-state ${active ? "configured" : "missing"}`}>
        <div>
          <span>{t("verifactu.certificate.currentState")}</span>
          <strong>{active ? t("verifactu.certificate.configured") : t("verifactu.certificate.notConfigured")}</strong>
        </div>
        <p>{active ? t("verifactu.certificate.configuredHint") : t("verifactu.certificate.notConfiguredHint")}</p>
      </section>

      {result && (
        <div className="gestion-verifactu-action-result success" role="status">
          {t(`verifactu.certificate.${result}`)}
        </div>
      )}

      {active ? (
        <CertificatePanel
          certificate={active}
          locale={locale}
          title={t("verifactu.certificate.activeTitle")}
          t={t}
        />
      ) : (
        <section className="gestion-verifactu-panel gestion-verifactu-certificate-empty">
          <h3>{t("verifactu.certificate.emptyTitle")}</h3>
          <p>{t("verifactu.certificate.emptyDescription")}</p>
        </section>
      )}

      {previous && (
        <CertificatePanel
          certificate={previous}
          locale={locale}
          title={t("verifactu.certificate.previousTitle")}
          t={t}
          secondary
        />
      )}

      <section className="gestion-verifactu-certificate-actions" aria-label={t("verifactu.certificate.actions")}>
        <div>
          <h3>{active ? t("verifactu.certificate.replaceTitle") : t("verifactu.certificate.importTitle")}</h3>
          <p>{active ? t("verifactu.certificate.replaceHint") : t("verifactu.certificate.importHint")}</p>
        </div>
        <div className="gestion-verifactu-certificate-buttons">
          <button
            type="button"
            className="primary"
            onClick={() => { setResult(null); setDialog(active ? "replace" : "import"); }}
          >
            {t(active ? "verifactu.certificate.replace" : "verifactu.certificate.import")}
          </button>
          {active && (
            <button
              type="button"
              className="danger"
              disabled={!active.canDelete}
              onClick={() => { setResult(null); setDialog("delete"); }}
            >
              {t("verifactu.certificate.delete")}
            </button>
          )}
        </div>
        {active && !active.canDelete && (
          <p className="gestion-verifactu-certificate-blocked">
            {deleteBlockLabel(active.deleteBlockReason, t)}
          </p>
        )}
      </section>

      {(dialog === "import" || dialog === "replace") && (
        <CertificateImportDialog
          active={dialog === "replace" ? active : null}
          token={token}
          t={t}
          onClose={() => setDialog(null)}
          onCompleted={() => completed(dialog === "replace" ? "replaced" : "imported")}
        />
      )}
      {dialog === "delete" && active && (
        <CertificateDeleteDialog
          token={token}
          t={t}
          onClose={() => setDialog(null)}
          onCompleted={() => completed("deleted")}
        />
      )}
    </div>
  );
}

function CertificatePanel({
  certificate,
  locale,
  title,
  t,
  secondary = false
}: {
  certificate: VerifactuManagedCertificate;
  locale: LocaleCode;
  title: string;
  t: VerifactuTranslator;
  secondary?: boolean;
}) {
  return (
    <section className={`gestion-verifactu-panel gestion-verifactu-certificate-panel ${secondary ? "secondary" : ""}`}>
      <header>
        <h3>{title}</h3>
        <span className={`gestion-verifactu-certificate-badge ${secondary ? "previous" : "active"}`}>
          {t(secondary ? "verifactu.certificate.statusPrevious" : "verifactu.certificate.statusActive")}
        </span>
      </header>
      <dl className="gestion-verifactu-certificate-details">
        <Detail label={t("verifactu.certificate.subject")} value={certificate.subject} />
        <Detail label={t("verifactu.certificate.taxId")} value={certificate.taxId} />
        <Detail label={t("verifactu.certificate.issuer")} value={certificate.issuer} />
        <Detail label={t("verifactu.certificate.serialNumber")} value={certificate.serialNumber} mono />
        <Detail
          label={t("verifactu.certificate.validityStatus")}
          value={validityLabel(certificate.validityStatus, t)}
          tone={validityTone(certificate.validityStatus)}
        />
        <Detail
          label={t("verifactu.certificate.daysRemaining")}
          value={daysRemainingLabel(certificate.daysRemaining, t)}
        />
        <Detail label={t("verifactu.certificate.validFrom")} value={formatVerifactuDateTime(certificate.validFrom, locale)} />
        <Detail label={t("verifactu.certificate.validUntil")} value={formatVerifactuDateTime(certificate.validUntil, locale)} />
        <Detail label={t("verifactu.certificate.fingerprint")} value={certificate.fingerprint} mono wide />
      </dl>
    </section>
  );
}

function Detail({ label, value, mono = false, wide = false, tone }: {
  label: string;
  value: string;
  mono?: boolean;
  wide?: boolean;
  tone?: "success" | "warning" | "danger";
}) {
  return <div className={wide ? "wide" : ""}><dt>{label}</dt><dd className={[mono ? "mono" : "", tone ?? ""].filter(Boolean).join(" ")}>{value || "—"}</dd></div>;
}

function CertificateImportDialog({ active, token, t, onClose, onCompleted }: {
  active: VerifactuManagedCertificate | null;
  token?: string;
  t: VerifactuTranslator;
  onClose: () => void;
  onCompleted: () => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [validation, setValidation] = useState<"required" | "tooLarge" | null>(null);
  const [error, setError] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (submitting) return;
    if (!file || !password) {
      setValidation("required");
      clearSensitiveInput();
      return;
    }
    if (file.size > MAX_CERTIFICATE_BYTES) {
      setValidation("tooLarge");
      clearSensitiveInput();
      return;
    }
    if (active && confirmation !== REPLACE_CONFIRMATION) return;
    setSubmitting(true);
    setValidation(null);
    setError(false);
    try {
      await importVerifactuCertificate(
        file,
        password,
        active ? { expectedActiveCertificateId: active.id, confirmation } : null,
        token
      );
      onCompleted();
    } catch {
      setError(true);
    } finally {
      clearSensitiveInput();
      setSubmitting(false);
    }
  }

  function clearSensitiveInput() {
    setPassword("");
    setFile(null);
    if (fileInput.current) fileInput.current.value = "";
  }

  const title = t(active ? "verifactu.certificate.replaceDialogTitle" : "verifactu.certificate.importDialogTitle");
  return (
    <CertificateModal title={title} closeLabel={t("verifactu.management.close")} busy={submitting} onClose={onClose}>
      <form className="gestion-verifactu-certificate-form" onSubmit={submit}>
        <p className="gestion-verifactu-certificate-warning">
          {t(active ? "verifactu.certificate.replaceWarning" : "verifactu.certificate.importWarning")}
        </p>
        <label>
          <span>{t("verifactu.certificate.file")}</span>
          <input
            ref={fileInput}
            autoFocus
            required
            type="file"
            aria-label={t("verifactu.certificate.file")}
            accept=".p12,.pfx,application/x-pkcs12"
            onChange={(event) => { setFile(event.target.files?.[0] ?? null); setValidation(null); }}
          />
          <small>{t("verifactu.certificate.fileHint")}</small>
        </label>
        <label>
          <span>{t("verifactu.certificate.password")}</span>
          <input
            required
            type="password"
            aria-label={t("verifactu.certificate.password")}
            autoComplete="new-password"
            value={password}
            onChange={(event) => { setPassword(event.target.value); setValidation(null); }}
          />
          <small>{t("verifactu.certificate.passwordHint")}</small>
        </label>
        {active && (
          <label>
            <span>{t("verifactu.certificate.replaceConfirmation")}</span>
            <input
              required
              autoComplete="off"
              aria-label={t("verifactu.certificate.replaceConfirmation")}
              value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)}
            />
            <small>{t("verifactu.certificate.typeToConfirm").replace("{text}", REPLACE_CONFIRMATION)}</small>
          </label>
        )}
        {validation && <p className="gestion-inline-error" role="alert">{t(`verifactu.certificate.${validation}`)}</p>}
        {error && <p className="gestion-inline-error" role="alert">{t("verifactu.certificate.importError")}</p>}
        <footer>
          <button type="button" disabled={submitting} onClick={onClose}>{t("verifactu.resolution.cancel")}</button>
          <button
            type="submit"
            className="primary"
            disabled={submitting || !file || !password || Boolean(active && confirmation !== REPLACE_CONFIRMATION)}
          >
            {submitting ? t("verifactu.resolution.processing") : t(active ? "verifactu.certificate.confirmReplace" : "verifactu.certificate.confirmImport")}
          </button>
        </footer>
      </form>
    </CertificateModal>
  );
}

function CertificateDeleteDialog({ token, t, onClose, onCompleted }: {
  token?: string;
  t: VerifactuTranslator;
  onClose: () => void;
  onCompleted: () => void;
}) {
  const [confirmation, setConfirmation] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (confirmation !== DELETE_CONFIRMATION || submitting) return;
    setSubmitting(true);
    setError(false);
    try {
      await deleteVerifactuCertificate(confirmation, token);
      onCompleted();
    } catch {
      setError(true);
    } finally {
      setConfirmation("");
      setSubmitting(false);
    }
  }

  return (
    <CertificateModal
      title={t("verifactu.certificate.deleteDialogTitle")}
      closeLabel={t("verifactu.management.close")}
      busy={submitting}
      onClose={onClose}
    >
      <form className="gestion-verifactu-certificate-form" onSubmit={submit}>
        <p className="gestion-verifactu-certificate-warning danger">{t("verifactu.certificate.deleteWarning")}</p>
        <label>
          <span>{t("verifactu.certificate.deleteConfirmation")}</span>
          <input
            autoFocus
            required
            autoComplete="off"
            aria-label={t("verifactu.certificate.deleteConfirmation")}
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
          <small>{t("verifactu.certificate.typeToConfirm").replace("{text}", DELETE_CONFIRMATION)}</small>
        </label>
        {error && <p className="gestion-inline-error" role="alert">{t("verifactu.certificate.deleteError")}</p>}
        <footer>
          <button type="button" disabled={submitting} onClick={onClose}>{t("verifactu.resolution.cancel")}</button>
          <button
            type="submit"
            className="danger"
            disabled={submitting || confirmation !== DELETE_CONFIRMATION}
          >
            {submitting ? t("verifactu.resolution.processing") : t("verifactu.certificate.confirmDelete")}
          </button>
        </footer>
      </form>
    </CertificateModal>
  );
}

function CertificateModal({ title, closeLabel, busy, onClose, children }: {
  title: string;
  closeLabel: string;
  busy: boolean;
  onClose: () => void;
  children: ReactNode;
}) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !busy) onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [busy, onClose]);

  function closeBackdrop(event: MouseEvent<HTMLDivElement>) {
    if (event.target === event.currentTarget && !busy) onClose();
  }

  return (
    <div className="gestion-modal-backdrop" onMouseDown={closeBackdrop}>
      <section className="gestion-verifactu-certificate-dialog" role="dialog" aria-modal="true" aria-label={title}>
        <header>
          <h2>{title}</h2>
          <button type="button" aria-label={closeLabel} disabled={busy} onClick={onClose}>×</button>
        </header>
        {children}
      </section>
    </div>
  );
}

function deleteBlockLabel(reason: string | null | undefined, t: VerifactuTranslator) {
  if (!reason) return t("verifactu.certificate.deleteBlocked");
  const key = `verifactu.certificate.deleteBlock.${reason}`;
  const translated = t(key);
  return translated === key ? t("verifactu.certificate.deleteBlocked") : translated;
}

function validityLabel(status: string, t: VerifactuTranslator) {
  const key = `verifactu.certificate.validity.${status}`;
  const translated = t(key);
  return translated === key ? status : translated;
}

function validityTone(status: string): "success" | "warning" | "danger" {
  if (status === "VALIDO") return "success";
  if (status === "PROXIMO_A_CADUCAR") return "warning";
  return "danger";
}

function daysRemainingLabel(days: number, t: VerifactuTranslator) {
  const key = days < 0 ? "verifactu.certificate.expiredDaysAgo" : "verifactu.certificate.daysValue";
  return t(key).replace("{count}", String(Math.abs(days)));
}
