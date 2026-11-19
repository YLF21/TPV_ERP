import { FormEvent, useEffect, useRef, useState } from "react";
import { UserCircle, X } from "@phosphor-icons/react";
import { api } from "../lib/api";
import type { Credentials } from "../lib/types";
import { useI18n } from "../i18n";
import { useWorkspaceLabels } from "../i18n/workspace";
import { errorMessage } from "./lib";
import "./account-password.css";

export function AccountPassword({ credentials, onLogout, userLabel }: { credentials: Credentials; onLogout: () => void; userLabel?: string }) {
  const l = useWorkspaceLabels();
  const { t } = useI18n();
  const [currentPassword, setCurrent] = useState("");
  const [newPassword, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const detailsRef = useRef<HTMLDetailsElement | null>(null);
  const active = useRef(false);
  const currentToken = useRef(credentials.accessToken); currentToken.current = credentials.accessToken;
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  useEffect(() => { setCurrent(""); setNext(""); setConfirm(""); setError(null); setBusy(false); }, [credentials.accessToken]);
  async function submit(event: FormEvent) {
    event.preventDefault(); setError(null);
    if (newPassword !== confirm) { setError(l("passwordMismatch")); return; }
    if (newPassword.length < 4) { setError(t("passwordTooShort")); return; }
    const token = credentials.accessToken;
    const current = () => active.current && currentToken.current === token;
    setBusy(true);
    try { await api.changeOwnPassword(credentials, { currentPassword, newPassword }); if (current()) onLogout(); }
    catch (failure) { if (current()) setError(errorMessage(failure)); }
    finally { if (current()) setBusy(false); }
  }
  function closePanel() {
    if (busy) return;
    setCurrent(""); setNext(""); setConfirm(""); setError(null);
    if (detailsRef.current) {
      detailsRef.current.open = false;
      detailsRef.current.querySelector("summary")?.focus();
    }
  }
  const triggerLabel = userLabel ? `${t("username")}: ${userLabel} · ${l("changePassword")}` : l("changePassword");
  const fields = <>
    <label>{l("currentPassword")}<input className={userLabel ? "control-input" : undefined} type="password" autoComplete="current-password" value={currentPassword} onChange={event => setCurrent(event.target.value)} required disabled={busy} /></label>
    <label>{l("newPassword")}<input className={userLabel ? "control-input" : undefined} type="password" autoComplete="new-password" minLength={4} value={newPassword} onChange={event => setNext(event.target.value)} required disabled={busy} /></label>
    <label>{l("confirmPassword")}<input className={userLabel ? "control-input" : undefined} type="password" autoComplete="new-password" minLength={4} value={confirm} onChange={event => setConfirm(event.target.value)} required disabled={busy} /></label>
    {error && <div role="alert" className="notice error wide-field">{error}</div>}
  </>;
  const saveButton = <button type="submit" className="primary-button" disabled={busy}>{l("changePassword")}</button>;
  return <details className="account-password" ref={detailsRef} onKeyDown={event => {
    if (userLabel && detailsRef.current?.open && event.key === "Escape") {
      event.preventDefault(); event.stopPropagation(); closePanel();
    }
  }}>
    <summary className={userLabel ? "account-password-trigger" : undefined} aria-label={triggerLabel} title={triggerLabel}>
      {userLabel ? <><UserCircle size={19} aria-hidden="true" /><span>{userLabel}</span></> : l("changePassword")}
    </summary>
    <form className={`compact-form-grid${userLabel ? " account-password-popup" : ""}`} aria-label={l("changePassword")} onSubmit={submit}>
      {userLabel && <header className="account-password-heading">
        <h2>{l("changePassword")}</h2>
        <button className="account-password-close" type="button" aria-label={t("close")} title={t("close")} disabled={busy} onClick={closePanel}><X size={18} aria-hidden="true" /></button>
      </header>}
      {userLabel ? <div className="account-password-fields">{fields}</div> : fields}
      {userLabel ? <footer className="account-password-actions">
        <button type="button" className="secondary-button" disabled={busy} onClick={closePanel}>{l("cancel")}</button>
        {saveButton}
      </footer> : saveButton}
    </form>
  </details>;
}
