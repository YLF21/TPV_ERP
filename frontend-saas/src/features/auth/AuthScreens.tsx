import { FormEvent, useEffect, useState } from "react";
import { Eye, EyeSlash, LockKey, User } from "@phosphor-icons/react";
import esposWordmark from "../../assets/espos-wordmark.png";
import languageIcon from "../../assets/language.png";
import type { LoginCredentials } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n, Language } from "../../i18n/index";
import { LanguageSelector } from "../../shared/ui";
import { appVersion } from "../../lib/appVersion";
import { formatUtcDateTime } from "../../lib/utc-date-time.mjs";

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
  const [passwordVisible, setPasswordVisible] = useState(false);
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
    <main className="login-page saas-central-login">
      <header className="saas-login-topbar">
        <div className="saas-login-brand-context">
          <img className="saas-login-wordmark" src={esposWordmark} alt="esPOS" width={94} height={30} draggable={false} />
        </div>
        <LoginClock language={language} />
        <div className="saas-login-tools">
          <LanguageSelector variant="floating" iconSrc={languageIcon} />
        </div>
      </header>

      <div className="saas-login-entry">
        <div className="saas-login-identity">
          <h1>{t("centralServer")}</h1>
          <img src={esposWordmark} alt="esPOS" width={200} height={64} draggable={false} />
        </div>
        <section className="login-panel" aria-labelledby="central-login-title">
          <header className="login-panel-heading">
            <h2 id="central-login-title">{t("adminAccess")}</h2>
          </header>
          {notice && <div className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"} aria-live={notice.type === "error" ? "assertive" : "polite"}>{notice.text}</div>}
          <form className="stack-form" onSubmit={submit}>
            <div className="saas-login-field">
              <label htmlFor="central-login-username">{t("username")}</label>
              <div className="saas-login-input-wrap">
                <User size={30} weight="fill" aria-hidden="true" />
                <input
                  id="central-login-username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="username"
                  placeholder={t("username")}
                  autoFocus
                  required
                />
              </div>
            </div>
            {mode === "login" && <div className="saas-login-field">
              <label htmlFor="central-login-password">{t("loginPassword")}</label>
              <div className="saas-login-input-wrap">
                <LockKey size={30} weight="fill" aria-hidden="true" />
                <input id="central-login-password" value={password} onChange={(event) => setPassword(event.target.value)} type={passwordVisible ? "text" : "password"} autoComplete="current-password" placeholder="****" required />
                <button className="saas-login-password-toggle" type="button"
                  aria-label={t(passwordVisible ? "hidePassword" : "showPassword")}
                  aria-pressed={passwordVisible} aria-controls="central-login-password"
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => setPasswordVisible((visible) => !visible)}>
                  {passwordVisible ? <EyeSlash size={30} aria-hidden="true" /> : <Eye size={30} aria-hidden="true" />}
                </button>
              </div>
            </div>}
            {mode === "confirm" && <>
              <label><span>{t("recoveryToken")}</span><input value={recoveryToken} onChange={(event) => setRecoveryToken(event.target.value)} autoComplete="one-time-code" required minLength={32} /></label>
              <label><span>{t("newPassword")}</span><input value={newPassword} onChange={(event) => setNewPassword(event.target.value)} type="password" autoComplete="new-password" required minLength={4} /></label>
              <label><span>{t("confirmPassword")}</span><input value={confirmation} onChange={(event) => setConfirmation(event.target.value)} type="password" autoComplete="new-password" required minLength={4} /></label>
            </>}
            <button className="primary-button" type="submit" disabled={loading}>{mode === "login" ? t("enter") : mode === "request" ? t("recoveryRequest") : t("recoveryConfirm")}</button>
          </form>
        </section>
        <p className="saas-login-version">{t("saasVersion")} {appVersion}</p>
      </div>
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
    <header className="login-panel-heading"><strong>esPOS SAAS</strong><span>{username}</span></header>
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
      {formatUtcDateTime(now, locale, true)}
    </time>
  );
}
