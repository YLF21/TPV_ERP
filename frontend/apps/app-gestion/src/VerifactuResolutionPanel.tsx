import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, type LocaleCode } from "@tpverp/app-common";
import {
  createVerifactuCorrection,
  loadVerifactuResolution,
  retryVerifactuSubmission,
  type VerifactuResolution,
  type VerifactuResolutionAction
} from "./verifactuManagementApi";
import {
  verifactuOperationLabel,
  verifactuStatusLabel,
  type VerifactuTranslator
} from "./verifactuPresentation";

export type VerifactuResolutionTarget = {
  recordId: string;
  documentNumber: string;
};

type ActionForm = "retry" | "correction" | null;

type CorrectionDraft = {
  reason: string;
  recipientTaxId: string;
  recipientName: string;
  operationDescription: string;
};

const emptyCorrection: CorrectionDraft = {
  reason: "",
  recipientTaxId: "",
  recipientName: "",
  operationDescription: ""
};

export function VerifactuResolutionPanel({
  target,
  token,
  locale: _locale,
  t,
  onClose,
  onCompleted
}: {
  target: VerifactuResolutionTarget | null;
  token?: string;
  locale: LocaleCode;
  t: VerifactuTranslator;
  onClose: () => void;
  onCompleted: () => void;
}) {
  const [resolution, setResolution] = useState<VerifactuResolution | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [form, setForm] = useState<ActionForm>(null);
  const [retryReason, setRetryReason] = useState("");
  const [correction, setCorrection] = useState(emptyCorrection);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<"conflict" | "generic" | null>(null);
  const [success, setSuccess] = useState<"retry" | "correction" | null>(null);
  const request = useRef(0);

  useEffect(() => {
    setResolution(null);
    setForm(null);
    setRetryReason("");
    setCorrection(emptyCorrection);
    setSubmitError(null);
    setSuccess(null);
    if (!target) {
      request.current += 1;
      setLoading(false);
      setLoadError(false);
      return;
    }
    const requestId = ++request.current;
    setLoading(true);
    setLoadError(false);
    void loadVerifactuResolution(target.recordId, token)
      .then((next) => {
        if (requestId === request.current) setResolution(next);
      })
      .catch(() => {
        if (requestId === request.current) setLoadError(true);
      })
      .finally(() => {
        if (requestId === request.current) setLoading(false);
      });
    return () => { request.current += 1; };
  }, [target, token]);

  if (!target) return null;

  const permitted = (action: VerifactuResolutionAction) =>
    Boolean(resolution?.permittedActions.includes(action));

  async function submitRetry(event: FormEvent) {
    event.preventDefault();
    if (!resolution || !retryReason.trim() || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await retryVerifactuSubmission(
        resolution.recordId, resolution.version, retryReason, token
      );
      setSuccess("retry");
      setForm(null);
      onCompleted();
    } catch (error) {
      setSubmitError(error instanceof ApiError && error.status === 409 ? "conflict" : "generic");
    } finally {
      setSubmitting(false);
    }
  }

  async function submitCorrection(event: FormEvent) {
    event.preventDefault();
    if (!resolution || !validCorrection(correction) || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await createVerifactuCorrection(resolution.recordId, correction, token);
      setSuccess("correction");
      setForm(null);
      onCompleted();
    } catch (error) {
      setSubmitError(error instanceof ApiError && error.status === 409 ? "conflict" : "generic");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <aside className="gestion-verifactu-resolution" aria-label={t("verifactu.resolution.title")}>
      <header>
        <div>
          <span>{t("verifactu.resolution.eyebrow")}</span>
          <h3>{target.documentNumber}</h3>
        </div>
        <button type="button" onClick={onClose}>{t("verifactu.management.close")}</button>
      </header>

      <div className="gestion-verifactu-resolution-body">
        {loading && <div className="gestion-verifactu-message">{t("verifactu.resolution.loading")}</div>}
        {!loading && loadError && (
          <div className="gestion-verifactu-message error" role="alert">
            {t("verifactu.resolution.loadError")}
          </div>
        )}
        {!loading && resolution && (
          <>
            <dl className="gestion-verifactu-resolution-summary">
              <div><dt>{t("verifactu.management.status")}</dt><dd>{verifactuStatusLabel(resolution.status, t)}</dd></div>
              <div><dt>{t("verifactu.management.fiscalOperation")}</dt><dd>{verifactuOperationLabel(resolution.operation, t)}</dd></div>
              <div><dt>{t("verifactu.management.errorCode")}</dt><dd>{resolution.errorCode || "—"}</dd></div>
            </dl>

            <section className={`gestion-verifactu-decision decision-${decisionTone(resolution.recommendedAction)}`}>
              <span>{t("verifactu.resolution.recommendation")}</span>
              <strong>{actionLabel(resolution.recommendedAction, t)}</strong>
              <p>{actionExplanation(resolution.recommendedAction, t)}</p>
            </section>

            {success && (
              <div className="gestion-verifactu-action-result success" role="status">
                {t(`verifactu.resolution.${success}Success`)}
              </div>
            )}
            {submitError && (
              <div className="gestion-verifactu-action-result error" role="alert">
                {t(`verifactu.resolution.${submitError}Error`)}
              </div>
            )}

            {!success && resolution.recommendedAction === "RETRY" && permitted("RETRY") && form !== "retry" && (
              <button className="gestion-verifactu-primary-action" type="button" onClick={() => { setForm("retry"); setSubmitError(null); }}>
                {t("verifactu.resolution.prepareRetry")}
              </button>
            )}
            {!success && resolution.recommendedAction === "CREATE_CORRECTION" && permitted("CREATE_CORRECTION") && form !== "correction" && (
              <button className="gestion-verifactu-primary-action" type="button" onClick={() => { setForm("correction"); setSubmitError(null); }}>
                {t("verifactu.resolution.prepareCorrection")}
              </button>
            )}

            {!success && actionable(resolution.recommendedAction) && resolution.permittedActions.length === 0 && (
              <p className="gestion-verifactu-permission-note">{t("verifactu.resolution.permissionRequired")}</p>
            )}

            {form === "retry" && (
              <form className="gestion-verifactu-action-form" onSubmit={submitRetry}>
                <p>{t("verifactu.resolution.retryWarning")}</p>
                <label>
                  <span>{t("verifactu.resolution.reason")}</span>
                  <textarea
                    autoFocus
                    required
                    maxLength={500}
                    value={retryReason}
                    onChange={(event) => setRetryReason(event.target.value)}
                  />
                </label>
                <div>
                  <button type="submit" className="primary" disabled={!retryReason.trim() || submitting}>
                    {submitting ? t("verifactu.resolution.processing") : t("verifactu.resolution.confirmRetry")}
                  </button>
                  <button type="button" disabled={submitting} onClick={() => setForm(null)}>{t("verifactu.resolution.cancel")}</button>
                </div>
              </form>
            )}

            {form === "correction" && (
              <form className="gestion-verifactu-action-form" onSubmit={submitCorrection}>
                <p>{t("verifactu.resolution.correctionWarning")}</p>
                <label>
                  <span>{t("verifactu.resolution.reason")}</span>
                  <textarea
                    autoFocus
                    required
                    maxLength={500}
                    value={correction.reason}
                    onChange={(event) => setCorrection({ ...correction, reason: event.target.value })}
                  />
                </label>
                <label>
                  <span>{t("verifactu.resolution.recipientTaxId")}</span>
                  <input maxLength={9} value={correction.recipientTaxId} onChange={(event) => setCorrection({ ...correction, recipientTaxId: event.target.value })} />
                </label>
                <label>
                  <span>{t("verifactu.resolution.recipientName")}</span>
                  <input maxLength={120} value={correction.recipientName} onChange={(event) => setCorrection({ ...correction, recipientName: event.target.value })} />
                </label>
                <label>
                  <span>{t("verifactu.resolution.operationDescription")}</span>
                  <textarea maxLength={500} value={correction.operationDescription} onChange={(event) => setCorrection({ ...correction, operationDescription: event.target.value })} />
                </label>
                {!validCorrection(correction) && correction.reason.trim() && (
                  <p className="field-error">{t("verifactu.resolution.correctionValidation")}</p>
                )}
                <div>
                  <button type="submit" className="primary" disabled={!validCorrection(correction) || submitting}>
                    {submitting ? t("verifactu.resolution.processing") : t("verifactu.resolution.confirmCorrection")}
                  </button>
                  <button type="button" disabled={submitting} onClick={() => setForm(null)}>{t("verifactu.resolution.cancel")}</button>
                </div>
              </form>
            )}
          </>
        )}
      </div>
    </aside>
  );
}

function validCorrection(draft: CorrectionDraft) {
  const hasRecipient = Boolean(draft.recipientTaxId.trim() && draft.recipientName.trim());
  const incompleteRecipient = Boolean(draft.recipientTaxId.trim()) !== Boolean(draft.recipientName.trim());
  return Boolean(draft.reason.trim())
    && !incompleteRecipient
    && (hasRecipient || Boolean(draft.operationDescription.trim()));
}

function actionable(action: string) {
  return action === "RETRY" || action === "CREATE_CORRECTION";
}

function decisionTone(action: string) {
  if (action === "RETRY" || action === "CREATE_CORRECTION") return "warning";
  if (action === "TECHNICAL_REVIEW") return "danger";
  if (action === "NONE") return "success";
  return "neutral";
}

function actionLabel(action: string, t: VerifactuTranslator) {
  const key = `verifactu.resolution.action.${action}`;
  const translated = t(key);
  return translated === key ? action : translated;
}

function actionExplanation(action: string, t: VerifactuTranslator) {
  const key = `verifactu.resolution.explanation.${action}`;
  const translated = t(key);
  return translated === key ? t("verifactu.resolution.explanation.UNKNOWN") : translated;
}
