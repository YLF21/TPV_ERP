import { useEffect, useMemo, useState, type FormEvent } from "react";
import type { LocaleCode } from "@tpverp/app-common";
import {
  transitionFiscalMode,
  type FiscalMode,
  type FiscalStatus
} from "./verifactuManagementApi";
import { formatVerifactuDate, type VerifactuTranslator } from "./verifactuPresentation";

export function FiscalModeControlView({
  locale,
  timezone: _timezone,
  token,
  status,
  t,
  onChanged
}: {
  locale: LocaleCode;
  timezone?: string | null;
  token?: string;
  status: FiscalStatus | null;
  t: VerifactuTranslator;
  onChanged: (status: FiscalStatus) => void;
}) {
  const targets = useMemo(() => transitionTargets(status?.mode), [status?.mode]);
  const [targetMode, setTargetMode] = useState<FiscalMode>(targets[0] ?? "VERIFACTU");
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [verifactuEndDate, setVerifactuEndDate] = useState("");
  const [aeatAckReference, setAeatAckReference] = useState("");
  const [working, setWorking] = useState(false);
  const [error, setError] = useState(false);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    setTargetMode(targets[0] ?? "VERIFACTU");
    setReason("");
    setConfirmation("");
    setVerifactuEndDate("");
    setAeatAckReference("");
    setError(false);
    setSuccess(false);
  }, [status?.modeVersion]);

  if (!status) {
    return <div className="gestion-verifactu-message error" role="alert">{t("verifactu.mode.statusRequired")}</div>;
  }
  const currentStatus = status;
  const confirmationPhrase = t("verifactu.mode.confirmationPhrase");

  const requiresExitData = currentStatus.mode === "VERIFACTU"
    && targetMode === "NO_VERIFACTU"
    && status.runtimeClass === "REAL";
  const valid = reason.trim().length >= 10
    && confirmation === confirmationPhrase
    && (!requiresExitData || Boolean(verifactuEndDate && aeatAckReference.trim()));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    setWorking(true);
    setError(false);
    setSuccess(false);
    try {
      const next = await transitionFiscalMode({
        targetMode,
        expectedVersion: currentStatus.modeVersion,
        reason: reason.trim(),
        confirmation: true,
        fechaFinVeriFactu: requiresExitData ? verifactuEndDate : null,
        aeatAckReference: requiresExitData ? aeatAckReference.trim() : null
      }, token);
      setSuccess(true);
      setConfirmation("");
      onChanged(next);
    } catch {
      setError(true);
    } finally {
      setWorking(false);
    }
  }

  return <div className="gestion-fiscal-mode-control">
    <section className="gestion-verifactu-panel">
      <header><div><span className="gestion-eyebrow">{t("verifactu.mode.currentEyebrow")}</span><h3>{t("verifactu.mode.currentTitle")}</h3></div></header>
      <dl className="gestion-verifactu-details">
        <div><dt>{t("verifactu.management.fiscalMode")}</dt><dd>{modeLabel(status.mode, t)}</dd></div>
        <div><dt>{t("verifactu.mode.version")}</dt><dd>{status.modeVersion}</dd></div>
        <div><dt>{t("verifactu.management.fiscalPermanence")}</dt><dd>{formatVerifactuDate(status.verifactuBlockedUntil, locale)}</dd></div>
      </dl>
      <p>{modeExplanation(status.mode, t)}</p>
    </section>

    <section className="gestion-verifactu-panel">
      <header><div><span className="gestion-eyebrow">{t("verifactu.mode.changeEyebrow")}</span><h3>{t("verifactu.mode.changeTitle")}</h3></div></header>
      {status.scheduledTransition ? (
        <div className={`gestion-fiscal-mode-scheduled ${status.scheduledTransition.status === "FALLIDA" ? "has-error" : ""}`}>
          <strong>{status.scheduledTransition.status === "FALLIDA" ? t("verifactu.management.fiscalTransitionFailed") : t("verifactu.management.fiscalTransitionScheduled")}</strong>
          <span>{modeLabel(status.scheduledTransition.newMode, t)}</span>
          {status.scheduledTransition.lastErrorCode && <code>{status.scheduledTransition.lastErrorCode}</code>}
        </div>
      ) : (
        <form onSubmit={submit}>
          <p className="gestion-fiscal-mode-warning">{transitionWarning(status.mode, targetMode, t)}</p>
          <label htmlFor="fiscal-mode-target">
            <span>{t("verifactu.mode.target")}</span>
            <select id="fiscal-mode-target" value={targetMode} onChange={(event) => setTargetMode(event.target.value as FiscalMode)}>
              {targets.map((target) => <option key={target} value={target}>{modeLabel(target, t)}</option>)}
            </select>
          </label>
          <label htmlFor="fiscal-mode-reason">
            <span>{t("verifactu.mode.reason")}</span>
            <textarea id="fiscal-mode-reason" maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} />
            <small>{t("verifactu.mode.reasonHint")}</small>
          </label>
          {requiresExitData && <div className="gestion-fiscal-mode-exit-fields">
            <label htmlFor="fiscal-mode-end-date"><span>{t("verifactu.mode.endDate")}</span><input id="fiscal-mode-end-date" type="date" value={verifactuEndDate} onChange={(event) => setVerifactuEndDate(event.target.value)} /></label>
            <label htmlFor="fiscal-mode-ack"><span>{t("verifactu.mode.ack")}</span><input id="fiscal-mode-ack" maxLength={128} value={aeatAckReference} onChange={(event) => setAeatAckReference(event.target.value)} /></label>
          </div>}
          <label htmlFor="fiscal-mode-confirmation">
            <span>{t("verifactu.mode.confirmation")}</span>
            <input id="fiscal-mode-confirmation" value={confirmation} autoComplete="off" onChange={(event) => setConfirmation(event.target.value)} />
            <small>{t("verifactu.mode.typeConfirmation").replace("{text}", confirmationPhrase)}</small>
          </label>
          <button type="submit" className="primary" disabled={working || !valid}>{working ? t("verifactu.mode.changing") : t("verifactu.mode.confirmChange")}</button>
          {success && <p className="gestion-form-success" role="status">{t("verifactu.mode.success")}</p>}
          {error && <p className="gestion-form-error" role="alert">{t("verifactu.mode.error")}</p>}
        </form>
      )}
    </section>
  </div>;
}

function transitionTargets(mode: FiscalMode | undefined): FiscalMode[] {
  if (mode === "PRE_SIF") return ["VERIFACTU", "NO_VERIFACTU"];
  if (mode === "NO_VERIFACTU") return ["VERIFACTU"];
  if (mode === "VERIFACTU") return ["NO_VERIFACTU"];
  return [];
}

function modeLabel(mode: string, t: VerifactuTranslator) {
  return t(`verifactu.management.fiscalMode.${mode}`);
}

function modeExplanation(mode: string, t: VerifactuTranslator) {
  return t(`verifactu.mode.explanation.${mode}`);
}

function transitionWarning(current: string, target: string, t: VerifactuTranslator) {
  return t(`verifactu.mode.warning.${current}.${target}`);
}
