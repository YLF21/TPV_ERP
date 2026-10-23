import { FormEvent, useEffect, useRef, useState } from "react";
import { Eye, EyeSlash, Key, Link, LockKey } from "@phosphor-icons/react";
import { ApiConnectionError, ApiError, apiRequest, checkBackendConnection, createTranslator, type LocaleCode } from "@tpverp/app-common";
import { AppBrand } from "../../../packages/app-common/src/components/AppBrand";
import { TopDateTime } from "../../../packages/app-common/src/components/TopDateTime";
import { EntryLanguagePicker } from "../../../packages/app-common/src/components/EntryLanguagePicker";
import { frontendVersion } from "../../../packages/app-common/src/api/runtime";
import { loadBackendAddress } from "../../../packages/app-common/src/api/backendAddress";
import "../../../packages/app-common/src/components/DesktopLoginScreen.css";
import "./ServerTerminalSetupScreen.css";

type InstallationLoginResult = { accessToken: string; mustChangePassword: boolean };
type InstallationStatus = { organizationProvisioned?: boolean; initialAdminPasswordChangeRequired?: boolean };
type SetupStage = "pairing" | "manual" | "password" | "complete";
type InstallationSession = { token: string; password: string; needsPairing: boolean };

export function ServerTerminalSetupScreen({ locale, onLocaleChange, onOrganizationReady, onConfigureConnection, onRecoverFromBackup }: {
  locale: LocaleCode; onLocaleChange?: (locale: LocaleCode) => void;
  onOrganizationReady: () => void; onConfigureConnection: () => void; onRecoverFromBackup?: () => void;
}) {
  const t = createTranslator(locale);
  const [backendOnline, setBackendOnline] = useState<boolean | null>(null);
  const [backendAddress, setBackendAddress] = useState<string | null>(null);
  const [stage, setStage] = useState<SetupStage>("pairing");
  const [pairingCode, setPairingCode] = useState("");
  const [password, setPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [errorKey, setErrorKey] = useState("");
  const lifecycle = useRef(0);
  const operation = useRef(false);
  const waitTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const session = useRef<InstallationSession | null>(null);
  const readyCalled = useRef(false);
  const readyCallback = useRef(onOrganizationReady);
  readyCallback.current = onOrganizationReady;

  function active(id: number) { return lifecycle.current === id; }
  function releaseSession() {
    const previous = session.current;
    session.current = null;
    if (previous) void apiRequest("/auth/logout", { method: "POST", token: previous.token }).catch(() => undefined);
  }
  function complete(id: number) {
    if (!active(id) || readyCalled.current) return;
    releaseSession();
    setPassword(""); setNewPassword(""); setConfirmation("");
    setStage("complete");
    readyCalled.current = true;
    readyCallback.current();
  }
  async function login(id: number, currentPassword: string, needsPairing: boolean) {
    const result = await apiRequest<InstallationLoginResult>("/auth/installation-login", {
      method: "POST", body: { userName: "ADMIN", password: currentPassword }
    });
    if (!active(id)) {
      void apiRequest("/auth/logout", { method: "POST", token: result.accessToken }).catch(() => undefined);
      return;
    }
    releaseSession();
    session.current = { token: result.accessToken, password: currentPassword, needsPairing };
    setPassword("");
    if (result.mustChangePassword) {
      setStage("password");
      setErrorKey("");
    } else if (needsPairing) {
      await linkWithSession(id);
    } else complete(id);
  }
  async function linkWithSession(id: number) {
    const current = session.current;
    if (!current || !active(id)) return;
    await apiRequest("/licenses/link-saas/bootstrap-empty", {
      method: "POST", token: current.token, body: { pairingCode: pairingCode.trim() }
    });
    complete(id);
  }
  async function readStatus(id: number): Promise<InstallationStatus | null> {
    if (!await checkBackendConnection()) {
      if (active(id)) { setBackendOnline(false); setBackendAddress(null); }
      return null;
    }
    if (!active(id)) return null;
    let status: InstallationStatus;
    try { status = await apiRequest<InstallationStatus>("/installation/status"); }
    catch (caught) {
      if (active(id)) { setBackendOnline(false); setBackendAddress(null); }
      throw caught;
    }
    if (!active(id)) return null;
    const address = await loadBackendAddress();
    if (!active(id)) return null;
    setBackendOnline(true); setBackendAddress(address);
    return status;
  }
  async function applyStatus(id: number, status: InstallationStatus) {
    if (!active(id)) return;
    if (status.organizationProvisioned !== false) {
      if (status.initialAdminPasswordChangeRequired === true) await login(id, "0000", false);
      else complete(id);
    }
  }
  async function run(id: number, task: () => Promise<void>) {
    if (operation.current || !active(id)) return;
    operation.current = true;
    setBusy(true); setErrorKey(""); setWaiting(false);
    waitTimer.current = setTimeout(() => { if (active(id)) setWaiting(true); }, 2000);
    try { await task(); }
    catch (caught) {
      if (!active(id)) return;
      if (caught instanceof ApiConnectionError) { setBackendOnline(false); setBackendAddress(null); }
      setErrorKey(caught instanceof ApiConnectionError ? "gestion.serverSetup.offline"
        : caught instanceof ApiError && caught.status === 401 ? "gestion.serverSetup.invalidAdmin"
        : "gestion.serverSetup.error");
    } finally {
      if (active(id)) {
        if (waitTimer.current) clearTimeout(waitTimer.current);
        waitTimer.current = null; operation.current = false;
        setBusy(false); setWaiting(false);
      }
    }
  }
  async function refresh(id: number) {
    setBackendOnline(null); setBackendAddress(null);
    await run(id, async () => {
      const status = await readStatus(id);
      if (status) await applyStatus(id, status);
    });
  }
  useEffect(() => {
    const id = ++lifecycle.current;
    operation.current = false;
    void refresh(id);
    return () => {
      ++lifecycle.current;
      if (waitTimer.current) clearTimeout(waitTimer.current);
      releaseSession();
    };
  }, []);

  async function recoverPairingFailure(id: number, caught: unknown, initial: boolean) {
    const status = await readStatus(id);
    if (!status || !active(id)) throw caught;
    if (status.organizationProvisioned !== false) {
      await applyStatus(id, status);
      return;
    }
    if (initial && caught instanceof ApiError && caught.problem?.code === "INITIAL_ADMIN_SETUP_UNAVAILABLE") {
      releaseSession(); setStage("manual"); setPassword("");
      setErrorKey("gestion.serverSetup.manualDescription");
      return;
    }
    // A changed manual credential remains usable by its owner on a later retry.
    if (!initial) { releaseSession(); setStage("manual"); setPassword(""); }
    setErrorKey(caught instanceof ApiConnectionError ? "gestion.serverSetup.offline" : "gestion.serverSetup.licenseError");
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (operation.current || backendOnline !== true) return;
    const id = lifecycle.current;
    if (stage === "password") {
      const current = session.current;
      if (!current) return;
      if (!/^\d{4,12}$/.test(newPassword)) { setErrorKey("gestion.serverSetup.passwordFormat"); return; }
      if (newPassword === current.password) { setErrorKey("gestion.serverSetup.passwordDifferent"); return; }
      if (newPassword !== confirmation) { setErrorKey("gestion.serverSetup.passwordMismatch"); return; }
      await run(id, async () => {
        let changed: InstallationLoginResult;
        try {
          changed = await apiRequest<InstallationLoginResult>("/auth/installation-password", {
            method: "PUT", token: current.token, body: { currentPassword: current.password, newPassword }
          });
        } catch (caught) {
          if (caught instanceof ApiConnectionError) {
            const status = await readStatus(id);
            if (status && status.organizationProvisioned !== false && status.initialAdminPasswordChangeRequired === false) {
              complete(id); return;
            }
            if (status && status.organizationProvisioned === false
              && status.initialAdminPasswordChangeRequired === false && current.needsPairing && active(id)) {
              // The response cannot prove whether the change committed. Authenticate
              // the current credential again before attempting protected linking.
              releaseSession();
              setPassword(""); setNewPassword(""); setConfirmation("");
              setStage("manual");
              setErrorKey("gestion.serverSetup.manualDescription");
              return;
            }
          }
          throw caught;
        }
        if (!active(id)) {
          void apiRequest("/auth/logout", { method: "POST", token: changed.accessToken }).catch(() => undefined);
          return;
        }
        session.current = { token: changed.accessToken, password: newPassword, needsPairing: current.needsPairing };
        setNewPassword(""); setConfirmation("");
        if (changed.mustChangePassword) throw new Error("password_change_required");
        if (current.needsPairing) {
          try { await linkWithSession(id); } catch (caught) { await recoverPairingFailure(id, caught, false); }
        } else complete(id);
      });
      return;
    }
    if (!pairingCode.trim()) { setErrorKey("gestion.serverSetup.pairingRequired"); return; }
    if (stage === "manual") {
      if (!password) return;
      await run(id, async () => {
        try { await login(id, password, true); }
        catch (caught) {
          if (caught instanceof ApiError && caught.status === 401) throw caught;
          await recoverPairingFailure(id, caught, false);
        }
      });
      return;
    }
    await run(id, async () => {
      try {
        await apiRequest("/licenses/link-saas/bootstrap-initial", { method: "POST", body: { pairingCode: pairingCode.trim() } });
      } catch (caught) { await recoverPairingFailure(id, caught, true); return; }
      if (active(id)) {
        try { await login(id, "0000", false); }
        catch (caught) { await recoverPairingFailure(id, caught, true); }
      }
    });
  }

  const showFields = backendOnline === true && !waiting && stage !== "complete";
  return (
    <main className="login-screen login-screen-desktop login-desktop server-setup-screen" data-app="gestion">
      <header className="entry-topbar"><strong className="app-brand-static"><AppBrand app="venta" label={t("gestion.title")} /></strong></header>
      <TopDateTime locale={locale} />
      {onLocaleChange && <EntryLanguagePicker locale={locale} onLocaleChange={onLocaleChange} />}
      <div className="login-content"><div className="login-entry">
        <div className="login-desktop-brand"><AppBrand app="venta" label={t("gestion.title")} /><span>{t("login.managementBrand")}</span></div>
        <form className="login-panel server-setup-panel" onSubmit={submit} aria-labelledby="server-setup-title">
          <header className="login-panel-heading">
            <h1 id="server-setup-title">{t(stage === "password" ? "gestion.serverSetup.passwordTitle" : "gestion.serverSetup.title")}</h1>
            <span>{t(stage === "password" ? "gestion.serverSetup.passwordRequired" : "gestion.serverSetup.description")}</span>
          </header>
          <div className={`server-setup-connection ${backendOnline === true ? "online" : backendOnline === false ? "offline" : "checking"}`}>
            {backendOnline === true && <div><span>{t("gestion.serverSetup.server")}</span><code>{backendAddress ?? t("gestion.serverSetup.addressUnavailable")}</code></div>}
            <strong role="status" aria-live="polite">{t(backendOnline === null ? "login.backendChecking" : backendOnline ? "login.backendOnline" : "gestion.serverSetup.offline")}</strong>
            {backendOnline === false && <button type="button" className="login-retry-button" disabled={busy} onClick={() => void refresh(lifecycle.current)}>{t("login.backendRetry")}</button>}
            {stage !== "password" && <button type="button" className="login-secondary-action" disabled={busy && backendOnline !== null} onClick={onConfigureConnection}>{t("terminalLink.title")}</button>}
            {onRecoverFromBackup && window.tpvDesktop?.disasterRecovery && <button type="button" className="login-secondary-action" onClick={onRecoverFromBackup}>{t("gestion.disaster.entry")}</button>}
          </div>
          {waiting && <section className="server-setup-wait" role="status" aria-live="polite"><strong>{t("gestion.serverSetup.waitTitle")}</strong><span>{t("gestion.serverSetup.waitDescription")}</span></section>}
          {showFields && <>
            {stage === "password" ? <>
              <PasswordField id="server-setup-new-password" label={t("gestion.serverSetup.newPassword")} value={newPassword} onChange={setNewPassword} disabled={busy} autoFocus />
              <PasswordField id="server-setup-confirm-password" label={t("gestion.serverSetup.confirmPassword")} value={confirmation} onChange={setConfirmation} disabled={busy} />
            </> : <>
              {stage === "manual" && <div className="login-field">
                <label htmlFor="server-setup-password">{t("gestion.serverSetup.manualPassword")}</label>
                <div className="login-input-wrap"><LockKey size={30} aria-hidden="true" />
                  <input id="server-setup-password" type={passwordVisible ? "text" : "password"} autoComplete="current-password" value={password} disabled={busy} onChange={event => setPassword(event.target.value)} />
                  <button type="button" className="login-password-toggle" disabled={busy} aria-label={t(passwordVisible ? "gestion.serverSetup.hidePassword" : "gestion.serverSetup.showPassword")} aria-pressed={passwordVisible} aria-controls="server-setup-password" onMouseDown={event => event.preventDefault()} onClick={() => setPasswordVisible(value => !value)}>{passwordVisible ? <EyeSlash size={34} aria-hidden="true" /> : <Eye size={34} aria-hidden="true" />}</button>
                </div>
              </div>}
              <section className="server-setup-license"><header><strong>{t("gestion.serverSetup.licenseTitle")}</strong><span>{t("gestion.serverSetup.licenseDescription")}</span></header>
                <div className="login-field"><label htmlFor="server-setup-pairing-code">{t("gestion.licenses.pairingCode")}</label><div className="login-input-wrap"><Link size={30} aria-hidden="true" /><input id="server-setup-pairing-code" autoFocus autoComplete="off" spellCheck={false} value={pairingCode} disabled={busy} onChange={event => setPairingCode(event.target.value)} placeholder={t("gestion.licenses.pairingPlaceholder")} /></div></div>
              </section>
            </>}
            <button type="submit" className="login-submit" disabled={busy || (stage !== "password" && (!pairingCode.trim() || (stage === "manual" && !password)))}>{t(busy ? "login.loading" : stage === "password" ? "gestion.serverSetup.savePassword" : "gestion.serverSetup.submit")}</button>
          </>}
          {errorKey && <strong className="login-error" role="alert">{t(errorKey)}</strong>}
        </form>
      </div></div>
      <footer className="report-footer-context"><span>v{frontendVersion}</span></footer>
    </main>
  );
}

function PasswordField({ id, label, value, onChange, disabled, autoFocus }: {
  id: string; label: string; value: string; onChange: (value: string) => void; disabled: boolean; autoFocus?: boolean;
}) {
  return <div className="login-field"><label htmlFor={id}>{label}</label><div className="login-input-wrap"><Key size={30} aria-hidden="true" /><input id={id} type="password" inputMode="numeric" autoComplete="new-password" value={value} disabled={disabled} autoFocus={autoFocus} onChange={event => onChange(event.target.value)} /></div></div>;
}
