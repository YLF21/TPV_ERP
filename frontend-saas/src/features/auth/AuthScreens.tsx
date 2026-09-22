import { FormEvent, useEffect, useState } from "react";


import type { LoginCredentials } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n, Language } from "../../i18n/index";
import { LanguageSelector } from "../../shared/ui";

export function LoginScreen({
  onLogin,
  onRequestRecovery,
  onConfirmRecovery,
  loading,
  notice
}: {
  onLogin: (credentials: LoginCredentials) => Promise<void>;
  onRequestRecovery: (username: string) => Promise<void>;
  onConfirmRecovery: (token: string, newPassword: string, confirmation: string) => Promise<void>;
  loading: boolean;
  notice: Notice;
}) {
  const { t, language } = useI18n();
  const [username, setUsername] = useState("ADMIN");
  const [password, setPassword] = useState("");
  const [mode] = useState<"login" | "request" | "confirm">("login");
  const [recoveryToken, setRecoveryToken] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (mode === "request") void onRequestRecovery(username);
    else if (mode === "confirm") void onConfirmRecovery(recoveryToken, newPassword, confirmation);
    else void onLogin({ username: username.trim(), password });
  }

  return (
    <main className="login-page">
      <header className="saas-login-topbar">
        <strong className="saas-login-brand">APP SAAS</strong>
        <span className="saas-login-context">{t("centralAdministration")}</span>
        <span className="saas-login-terminal">{t("internalPortal")}</span>
        <div className="saas-login-tools">
          <LanguageSelector variant="floating" />
          <LoginClock language={language} />
        </div>
      </header>

      <section className="login-panel" aria-label={t("adminAccess")}>
        <header className="login-panel-heading">
          <strong>APP SAAS</strong>
          <span>{t("internalPortal")}</span>
        </header>
        {notice && <div className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"} aria-live={notice.type === "error" ? "assertive" : "polite"}>{notice.text}</div>}
        <form className="stack-form" onSubmit={submit}>
          <label>
            <span>{t("username")}</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              placeholder={t("username")}
              autoFocus
              required
            />
          </label>
          {mode === "login" && <label><span>{t("password")}</span><input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" placeholder={t("password")} required /></label>}
          {mode === "confirm" && <>
            <label><span>{t("recoveryToken")}</span><input value={recoveryToken} onChange={(event) => setRecoveryToken(event.target.value)} autoComplete="one-time-code" required minLength={32} /></label>
            <label><span>{t("newPassword")}</span><input value={newPassword} onChange={(event) => setNewPassword(event.target.value)} type="password" autoComplete="new-password" required minLength={4} /></label>
            <label><span>{t("confirmPassword")}</span><input value={confirmation} onChange={(event) => setConfirmation(event.target.value)} type="password" autoComplete="new-password" required minLength={4} /></label>
          </>}
          <button className="primary-button" type="submit" disabled={loading}>{mode === "login" ? t("enter") : mode === "request" ? t("recoveryRequest") : t("recoveryConfirm")}</button>
        </form>
      </section>
    </main>
  );
}

export function RequiredPasswordChangeScreen({ username, loading, notice, onSubmit, onCancel }: {
  username: string; loading: boolean; notice: Notice;
  onSubmit: (newPassword: string, confirmation: string) => Promise<void>; onCancel: () => void;
}) {
  const { t } = useI18n();
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  return <main className="login-page"><section className="login-panel" aria-labelledby="password-change-title">
    <header className="login-panel-heading"><strong>APP SAAS</strong><span>{username}</span></header>
    <form className="stack-form" onSubmit={(event) => { event.preventDefault(); void onSubmit(newPassword, confirmation); }}>
      <h1 id="password-change-title">{t("passwordChangeTitle")}</h1><p>{t("passwordChangeHelp")}</p>
      {notice && <div className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"} aria-live={notice.type === "error" ? "assertive" : "polite"}>{notice.text}</div>}
      <label><span>{t("newPassword")}</span><input type="password" autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} minLength={4} required autoFocus /></label>
      <label><span>{t("confirmPassword")}</span><input type="password" autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} minLength={4} required /></label>
      <button className="primary-button" type="submit" disabled={loading}>{t("changeOwnPassword")}</button>
      <button className="secondary-button" type="button" disabled={loading} onClick={onCancel}>{t("logout")}</button>
    </form>
  </section></main>;
}

export function LoginClock({ language }: { language: Language }) {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const locale = language === "zh" ? "zh-CN" : language === "en" ? "en-GB" : "es-ES";
  return (
    <time className="saas-login-clock" dateTime={now.toISOString()}>
      {new Intl.DateTimeFormat(locale, {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
      }).format(now)}
    </time>
  );
}
