import { FormEvent, useEffect, useState } from "react";
import {
  ApiConnectionError,
  ApiError,
  apiBaseUrl,
  apiRequest,
  checkBackendConnection,
  createTranslator,
  type LocaleCode,
  type TerminalContext
} from "@tpverp/app-common";

type InstallationLoginResult = {
  accessToken: string;
  mustChangePassword: boolean;
};

type ProvisioningResult = {
  terminalId: string;
  terminalCode: string;
  storeName: string;
  terminalCredential: string;
};

type InstallationStatus = {
  organizationProvisioned?: boolean;
};

export function ServerTerminalSetupScreen({
  locale,
  onProvisioned
}: {
  locale: LocaleCode;
  onProvisioned: (context: TerminalContext) => void;
}) {
  const t = createTranslator(locale);
  const [username, setUsername] = useState("ADMIN");
  const [password, setPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [requiresPasswordChange, setRequiresPasswordChange] = useState(false);
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [backendOnline, setBackendOnline] = useState<boolean | null>(null);
  const [organizationProvisioned, setOrganizationProvisioned] = useState<boolean | null>(null);
  const [pairingCode, setPairingCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    void loadBackendStatus().then(({ online, provisioned }) => {
      if (!cancelled) {
        setBackendOnline(online);
        setOrganizationProvisioned(provisioned);
      }
    });
    return () => { cancelled = true; };
  }, []);

  async function retryConnection() {
    setBackendOnline(null);
    setOrganizationProvisioned(null);
    setError("");
    const status = await loadBackendStatus();
    setBackendOnline(status.online);
    setOrganizationProvisioned(status.provisioned);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (requiresPasswordChange && !/^\d{4,12}$/.test(newPassword)) {
      setError(t("gestion.serverSetup.passwordFormat"));
      return;
    }
    if (backendOnline !== true || organizationProvisioned === null) return;
    if (!organizationProvisioned && !pairingCode.trim()) {
      setError(t("gestion.serverSetup.pairingRequired"));
      return;
    }
    setBusy(true);
    setError("");
    let token = "";
    let organizationReady = organizationProvisioned;
    try {
      const login = await apiRequest<InstallationLoginResult>("/auth/installation-login", {
        method: "POST",
        body: { userName: username, password }
      });
      token = login.accessToken;
      if (login.mustChangePassword) {
        if (!requiresPasswordChange) {
          setRequiresPasswordChange(true);
          setError(t("gestion.serverSetup.passwordRequired"));
          return;
        }
        const changed = await apiRequest<InstallationLoginResult>("/auth/installation-password", {
          method: "PUT",
          token,
          body: { currentPassword: password, newPassword }
        });
        token = changed.accessToken;
      }
      if (!organizationProvisioned) {
        await apiRequest("/licenses/link-saas/bootstrap-empty", {
          method: "POST",
          token,
          body: { pairingCode: pairingCode.trim() }
        });
        organizationReady = true;
        setOrganizationProvisioned(true);
      }
      const provisioned = await apiRequest<ProvisioningResult>("/terminals/server/provision", {
        method: "POST",
        token
      });
      const context: TerminalContext = {
        storeName: provisioned.storeName,
        terminalCode: provisioned.terminalCode,
        terminalId: provisioned.terminalId,
        terminalCredential: provisioned.terminalCredential
      };
      const saved = await window.tpvDesktop?.terminalIdentity?.save(context);
      if (!saved?.ok) {
        throw new Error(saved && "message" in saved ? saved.message : "secure_storage_unavailable");
      }
      onProvisioned(context);
    } catch (caught) {
      if (caught instanceof ApiConnectionError) setBackendOnline(false);
      setError(caught instanceof ApiConnectionError
        ? t("gestion.serverSetup.offline")
        : caught instanceof ApiError && caught.status === 401
          ? t("gestion.serverSetup.invalidAdmin")
          : !organizationReady
            ? t("gestion.serverSetup.licenseError")
            : t("gestion.serverSetup.error"));
    } finally {
      if (token) {
        void apiRequest("/auth/logout", { method: "POST", token }).catch(() => undefined);
      }
      setBusy(false);
    }
  }

  return (
    <main className="login-screen server-setup-screen">
      <header className="entry-topbar">
        <strong className="app-brand-static">{t("gestion.title")}</strong>
      </header>
      <form className="login-panel server-setup-panel" onSubmit={submit}>
        <header className="login-panel-heading">
          <strong>{t("gestion.serverSetup.title")}</strong>
          <span>{t("gestion.serverSetup.description")}</span>
        </header>
        <div className={`server-setup-connection ${backendOnline === true ? "online" : backendOnline === false ? "offline" : "checking"}`}>
          <div>
            <span>{t("gestion.serverSetup.server")}</span>
            <code>{apiBaseUrl}</code>
          </div>
          <strong role="status" aria-live="polite">
            {backendOnline === null
              ? t("login.backendChecking")
              : backendOnline
                ? t("login.backendOnline")
                : t("gestion.serverSetup.offline")}
          </strong>
          {backendOnline === false && (
            <button type="button" disabled={busy} onClick={() => void retryConnection()}>
              {t("login.backendRetry")}
            </button>
          )}
        </div>
        <label>
          <span>{t("gestion.serverSetup.admin")}</span>
          <input autoFocus value={username} disabled={busy} onChange={(event) => setUsername(event.target.value)} />
        </label>
        <div className="server-setup-password-control">
          <label htmlFor="server-setup-password">{t("login.password")}</label>
          <span className="server-setup-password-field">
            <input
              id="server-setup-password"
              type={passwordVisible ? "text" : "password"}
              autoComplete="current-password"
              value={password}
              disabled={busy}
              onChange={(event) => setPassword(event.target.value)}
            />
            <button
              type="button"
              disabled={busy}
              aria-label={t(passwordVisible ? "gestion.serverSetup.hidePassword" : "gestion.serverSetup.showPassword")}
              aria-pressed={passwordVisible}
              onClick={() => setPasswordVisible((visible) => !visible)}
            >
              {t(passwordVisible ? "gestion.serverSetup.hidePassword" : "gestion.serverSetup.showPassword")}
            </button>
          </span>
        </div>
        {requiresPasswordChange && (
          <label>
            <span>{t("gestion.serverSetup.newPassword")}</span>
            <input
              type="password"
              inputMode="numeric"
              value={newPassword}
              disabled={busy}
              onChange={(event) => setNewPassword(event.target.value)}
            />
          </label>
        )}
        {organizationProvisioned === false && (
          <section className="server-setup-license">
            <header>
              <strong>{t("gestion.serverSetup.licenseTitle")}</strong>
              <span>{t("gestion.serverSetup.licenseDescription")}</span>
            </header>
            <label>
              <span>{t("gestion.licenses.pairingCode")}</span>
              <input
                autoComplete="off"
                spellCheck={false}
                value={pairingCode}
                disabled={busy}
                onChange={(event) => setPairingCode(event.currentTarget.value)}
                placeholder={t("gestion.licenses.pairingPlaceholder")}
              />
            </label>
            <small>{t("gestion.serverSetup.licenseSecurity")}</small>
          </section>
        )}
        {error && <strong className="login-error">{error}</strong>}
        <button
          type="submit"
          disabled={busy || backendOnline !== true || organizationProvisioned === null
            || (organizationProvisioned === false && !pairingCode.trim())}
        >
          {busy ? t("login.loading") : t("gestion.serverSetup.submit")}
        </button>
        <p className="server-setup-note">{t("gestion.serverSetup.secureStorage")}</p>
      </form>
    </main>
  );
}

async function loadBackendStatus(): Promise<{ online: boolean; provisioned: boolean | null }> {
  if (!await checkBackendConnection()) return { online: false, provisioned: null };
  try {
    const status = await apiRequest<InstallationStatus>("/installation/status");
    // Compatibility with older backends: the original status contract implied an existing store.
    return { online: true, provisioned: status.organizationProvisioned !== false };
  } catch {
    return { online: false, provisioned: null };
  }
}
