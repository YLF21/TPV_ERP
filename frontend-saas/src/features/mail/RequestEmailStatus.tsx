import { useRef, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useI18n } from "../../i18n";
import { clientAccessApi } from "../../lib/client-access-api";
import { mailApi, type RequestEmailDelivery } from "../../lib/mail-api";
import type { Credentials } from "../../lib/types";
import { formatDate } from "../../shared/lib";
import { StatusPill } from "../../shared/ui";
import { useMailCopy } from "./mail-copy";
import "./mail.css";

export function RequestEmailStatus({ credentials, id, initialDelivery }: {
  credentials: Credentials;
  id: string;
  initialDelivery: RequestEmailDelivery | null;
}) {
  const { t } = useI18n();
  const copy = useMailCopy();
  const [current, setCurrent] = useState<{ delivery: RequestEmailDelivery | null }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<"retryError" | "deliveryLoadError" | null>(null);
  const inFlight = useRef(false);
  const delivery = current ? current.delivery : initialDelivery;
  const queued = delivery?.status === "PENDING";
  const uncertain = delivery?.lastError === "SMTP_DELIVERY_UNCERTAIN";
  const settings = useRemote(() => queued ? mailApi.settings(credentials).catch(() => null) : Promise.resolve(null),
    [credentials.accessToken, queued]);
  const needsSettings = settings.data && (!settings.data.enabled || !settings.data.configured);

  async function update(retry: boolean) {
    if (inFlight.current || (retry && (error || delivery?.status !== "FAILED"))) return;
    if (retry && uncertain && !window.confirm(copy.confirmUncertainRetry)) return;
    inFlight.current = true;
    setBusy(true);
    try {
      const next = retry ? await clientAccessApi.retryEmail(credentials, id) : await clientAccessApi.request(credentials, id);
      setCurrent({ delivery: next.delivery });
      setError(null);
      settings.reload();
    } catch {
      setError(retry ? "retryError" : "deliveryLoadError");
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return <section className="client-user-panel" aria-label={copy.delivery} aria-busy={busy}>
    <h3>{copy.delivery}</h3>
    {!delivery ? <p className="module-help">{copy.notScheduled}</p> : <>
      <dl className="client-user-detail">
        <div><dt>{t("status")}</dt><dd><StatusPill status={copy[delivery.status]}
          tone={delivery.status === "DELIVERED" ? "ok" : delivery.status === "PROCESSING" ? "muted" : "warning"} /></dd></div>
        <div><dt>{copy.attempts}</dt><dd>{delivery.attempts}</dd></div>
        {delivery.nextAttemptAt && delivery.status === "PENDING" && !needsSettings &&
          <div><dt>{copy.nextAttempt}</dt><dd><time dateTime={delivery.nextAttemptAt}>{formatDate(delivery.nextAttemptAt)}</time></dd></div>}
        {delivery.deliveredAt && <div><dt>{copy.acceptedAt}</dt>
          <dd><time dateTime={delivery.deliveredAt}>{formatDate(delivery.deliveredAt)}</time></dd></div>}
      </dl>
      {delivery.lastError && <p className="module-help request-email-notice">
        {copy[uncertain ? "deliveryUncertain" : delivery.lastError === "SMTP_AUTH_FAILED" ? "authFailure" : "deliveryFailure"]}
      </p>}
      {needsSettings && <p className="module-help request-email-notice">{copy.pendingConfiguration}</p>}
      {error && <p className="notice error" role="alert">{copy[error]}</p>}
      {delivery.status !== "DELIVERED" && <div className="request-email-actions">
        <button type="button" className="secondary-button" disabled={busy} onClick={() => void update(false)}>{copy.refreshDelivery}</button>
        {delivery.status === "FAILED" && <button type="button" className="secondary-button" disabled={busy || Boolean(error)}
          onClick={() => void update(true)}>{copy.retryDelivery}</button>}
        {(needsSettings || delivery.lastError) && <a href="#/smtp">{t("smtpSettings")}</a>}
      </div>}
    </>}
  </section>;
}
