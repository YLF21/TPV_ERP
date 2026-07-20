import { FormEvent, useState } from "react";
import {
  ApiError,
  apiRequest,
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
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (requiresPasswordChange && !/^\d{4,12}$/.test(newPassword)) {
      setError(t("gestion.serverSetup.passwordFormat"));
      return;
    }
    setBusy(true);
    setError("");
    let token = "";
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
      setError(caught instanceof ApiError && caught.status === 401
        ? t("gestion.serverSetup.invalidAdmin")
        : caught instanceof Error ? caught.message : t("gestion.serverSetup.error"));
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
        <label>
          <span>{t("gestion.serverSetup.admin")}</span>
          <input autoFocus value={username} disabled={busy} onChange={(event) => setUsername(event.target.value)} />
        </label>
        <label>
          <span>{t("login.password")}</span>
          <input type="password" value={password} disabled={busy} onChange={(event) => setPassword(event.target.value)} />
        </label>
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
        {error && <strong className="login-error">{error}</strong>}
        <button type="submit" disabled={busy}>{busy ? t("login.loading") : t("gestion.serverSetup.submit")}</button>
        <p className="server-setup-note">{t("gestion.serverSetup.secureStorage")}</p>
      </form>
    </main>
  );
}
