import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError, classifyApiFailure } from "../../../../packages/app-common/src/api/client";
import type { MailProps } from "./MailTypes";

const safeMailErrors: Record<string, string> = {
  "message.smtp.encryption_key_missing": "mail.credentialsMissing",
  "message.smtp.encryption_key_invalid": "mail.credentialsMissing",
  "message.smtp.credentials_require_resave": "mail.error.resave",
  "message.smtp.credential_decryption_failed": "mail.error.resave",
  "message.smtp.authentication_failed": "mail.error.authentication",
  "message.smtp.connection_failed": "mail.error.connection",
  "message.smtp.retry_pending": "mail.error.retryPending",
  "message.smtp.delivery_uncertain": "mail.uncertainHint",
  "message.smtp.configuration_failed": "mail.error.configuration",
  "message.smtp.tls_required": "mail.error.tls",
  "message.smtp.tls_conflict": "mail.error.tls",
  "message.smtp.host_invalid": "mail.error.host",
  "message.smtp.port_invalid": "mail.error.port",
  "message.smtp.subject_invalid": "mail.error.subject",
  "message.member_card_delivery.retry_unsafe": "mail.uncertainHint",
  "message.member_card_delivery.resend_unavailable": "mail.error.resend",
  "message.member_card_delivery.sender_not_configured": "mail.error.configuration",
  "Correo de prueba no válido": "mail.error.email",
  "Selecciona como máximo 10000 clientes": "mail.error.audienceLimit",
  "Selecciona un grupo de hasta 10000 clientes para esta campaña": "mail.error.audienceLimit",
  "Selecciona una fecha entre ahora y los próximos 12 meses": "mail.error.schedule",
  "El asunto debe ocupar una línea": "mail.error.subject",
  "Color no válido": "mail.error.color",
  "Campos disponibles: {name}, {storeName} y {memberId}": "mail.templateVariables",
  "Enlace no válido": "mail.error.link",
  "El botón debe usar un enlace HTTPS": "mail.error.link",
};

export function mailErrorMessage(cause: unknown, t: MailProps["t"]): string {
  // Only known messages are displayed; raw transport exceptions can contain credentials.
  const detail = cause instanceof ApiError ? cause.problem?.detail : undefined;
  const code = cause instanceof ApiError ? cause.problem?.code : undefined;
  for (const key of [code, detail, cause instanceof Error ? cause.message : undefined]) {
    if (typeof key === "string" && Object.hasOwn(safeMailErrors, key)) return t(safeMailErrors[key]);
  }
  const kind = classifyApiFailure(cause);
  if (kind === "offline") return t("mail.error.connection");
  if (kind === "conflict") return t("mail.error.conflict");
  if (kind === "authentication" || kind === "forbidden") return t("mail.error.session");
  if (cause instanceof ApiError && cause.status === 400) return t("mail.error.invalid");
  return t("mail.actionError");
}

export function isMailHttpsUrl(value: string): boolean {
  const trimmed = value.trim();
  if (!/^https:\/\//i.test(trimmed) || /[\s\\]/.test(trimmed)) return false;
  try {
    const url = new URL(trimmed);
    return url.protocol === "https:" && !!url.hostname && !url.username && !url.password;
  } catch { return false; }
}

export function useMailResource<T>(path: string, { request, session }: MailProps) {
  const [value, setValue] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(false);
    request<T>(path, { token: session.accessToken }).then((result) => {
      if (active) setValue(result);
    }).catch(() => { if (active) setError(true); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [path, request, session.accessToken, revision]);
  return { value, setValue, loading, error, reload: () => setRevision((n) => n + 1) };
}

export function useMailAction(t: MailProps["t"]) {
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [feedback, setFeedback] = useState<{ error: boolean; text: string } | null>(null);
  async function run(action: () => Promise<void>, success?: string) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setFeedback(null);
    try {
      await action();
      if (success) setFeedback({ error: false, text: t(success) });
    } catch (cause) {
      setFeedback({ error: true, text: mailErrorMessage(cause, t) });
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return { busy, run, feedback, clearFeedback: () => setFeedback(null) };
}

export function MailFeedback({ value }: { value: ReturnType<typeof useMailAction>["feedback"] }) {
  return value ? <p className={`mail-feedback ${value.error ? "is-error" : "is-success"}`} role={value.error ? "alert" : "status"}>{value.text}</p> : null;
}

export function MailLoad({ loading, error, reload, t, children }: {
  loading: boolean; error: boolean; reload: () => void; t: MailProps["t"]; children: ReactNode;
}) {
  if (error) return <div className="mail-feedback is-error" role="alert"><p>{t("mail.loadError")}</p><button type="button" onClick={reload}>{t("mail.refresh")}</button></div>;
  if (loading) return <p role="status">{t("common.loading")}</p>;
  return <>{children}</>;
}

export function EmailPreview({ html, t }: { html: string; t: MailProps["t"] }) {
  const [mobile, setMobile] = useState(false);
  // No script, navigation, same-origin or form permissions are granted to email content.
  const document = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src https: data: cid:; font-src 'none'; form-action 'none'; base-uri 'none'">${html}`;
  return <section className="mail-preview">
    <div className="mail-actions"><h3>{t("mail.preview")}</h3>
      <button type="button" aria-pressed={mobile} onClick={() => setMobile(!mobile)}>{t(mobile ? "mail.desktop" : "mail.mobile")}</button>
    </div>
    <iframe title={t("mail.preview")} sandbox="" referrerPolicy="no-referrer" srcDoc={document} className={mobile ? "is-mobile" : ""} />
  </section>;
}

export function formatMailDate(value: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString();
}
