import { FormEvent, useEffect, useRef, useState } from "react";
import { api } from "../lib/api";
import type { Credentials } from "../lib/types";
import { useI18n } from "../i18n";
import { useWorkspaceLabels } from "../i18n/workspace";
import { errorMessage } from "./lib";
import "./account-password.css";

export function AccountPassword({ credentials, onLogout }: { credentials: Credentials; onLogout: () => void }) {
  const l = useWorkspaceLabels();
  const { t } = useI18n();
  const [currentPassword, setCurrent] = useState("");
  const [newPassword, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
  return <details className="account-password"><summary>{l("changePassword")}</summary>
    <form className="compact-form-grid" onSubmit={submit}>
      <label>{l("currentPassword")}<input type="password" autoComplete="current-password" value={currentPassword} onChange={event => setCurrent(event.target.value)} required disabled={busy} /></label>
      <label>{l("newPassword")}<input type="password" autoComplete="new-password" minLength={4} value={newPassword} onChange={event => setNext(event.target.value)} required disabled={busy} /></label>
      <label>{l("confirmPassword")}<input type="password" autoComplete="new-password" minLength={4} value={confirm} onChange={event => setConfirm(event.target.value)} required disabled={busy} /></label>
      {error && <div role="alert" className="notice error wide-field">{error}</div>}
      <button type="submit" className="primary-button" disabled={busy}>{l("changePassword")}</button>
    </form>
  </details>;
}
