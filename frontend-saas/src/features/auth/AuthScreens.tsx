import { FormEvent, useEffect, useState } from "react";
import { ArrowRight, Eye, EyeSlash, LockKey, User } from "@phosphor-icons/react";
import type { LoginCredentials } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n, Language } from "../../i18n/index";
import { formatUtcDateTime } from "../../lib/utc-date-time.mjs";
import { PortalLoginLayout } from "./PortalLoginLayout";
import { InternalDesktopLoginLayout } from "./InternalDesktopLoginLayout";
import { resolvePortalUrl } from "../../../../frontend-product/src/portalAccess";

const entryContent = {
  es: {
    entering: "Accediendo…", customer: "¿Buscas el acceso de tu tienda?", customerLink: "Abrir Venta (TPV)",
    changeTitle: "Tu cuenta.", changeAccent: "Lista para continuar.",
    changeDescription: "Completa la actualización de tu contraseña antes de continuar con el acceso.",
    changeNote: "Utiliza una contraseña propia y evita compartirla."
  },
  en: {
    entering: "Signing in…", customer: "Looking for your store's portal?", customerLink: "Open Sales (POS)",
    changeTitle: "Your account.", changeAccent: "Ready to continue.",
    changeDescription: "Complete your password update before continuing with sign-in.",
    changeNote: "Use your own password and avoid sharing it."
  },
  zh: {
    entering: "正在登录…", customer: "需要访问你的门店门户？", customerLink: "打开销售（收银）",
    changeTitle: "你的账号。", changeAccent: "准备好继续。",
    changeDescription: "请先完成密码更新，再继续登录。",
    changeNote: "请使用自己的密码，避免与他人共享。"
  }
} as const;

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
  const copy = entryContent[language];
  const clientHref = resolvePortalUrl(import.meta.env.VITE_CLIENT_PORTAL_URL, window.location.origin, "venta");
  const [username, setUsername] = useState("ADMIN");
  const [password, setPassword] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [mode] = useState<"login" | "request" | "confirm">("login");
  const [recoveryToken, setRecoveryToken] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (loading) return;
    if (mode === "request") void onRequestRecovery(username);
    else if (mode === "confirm") void onConfirmRecovery(recoveryToken, newPassword, confirmation);
    else void onLogin({ username: username.trim(), password });
  }

  return (
    <InternalDesktopLoginLayout headingId="internal-signin-title" subtitle="SaaS"
      footerExtra={clientHref ? <a className="saas-desktop-login-client-link" href={clientHref} referrerPolicy="no-referrer" title={copy.customer}>{copy.customerLink} <ArrowRight size={16} aria-hidden="true" /></a> : undefined}>
        <header className="saas-desktop-login-heading">
          <h1 id="internal-signin-title">{t("centralServer")}</h1>
          <p>{t("adminAccess")}</p>
        </header>
        {notice && <div className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"} aria-live={notice.type === "error" ? "assertive" : "polite"}>{notice.text}</div>}
        <form className="stack-form" onSubmit={submit} aria-describedby="internal-signin-help" aria-busy={loading}>
          <div className="saas-desktop-login-field">
            <label htmlFor="central-login-username">{t("username")}</label>
            <div className="saas-desktop-login-input-wrap">
            <User size={28} weight="fill" aria-hidden="true" />
            <input
              id="central-login-username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              placeholder={t("username")}
              required
              disabled={loading}
            />
            </div>
          </div>
          {mode === "login" && <div className="saas-desktop-login-field">
            <label htmlFor="central-login-password">{t("loginPassword")}</label>
            <div className="saas-desktop-login-input-wrap">
            <LockKey size={28} weight="fill" aria-hidden="true" />
            <input id="central-login-password" value={password} onChange={(event) => setPassword(event.target.value)} type={passwordVisible ? "text" : "password"} autoComplete="current-password" placeholder="****" required disabled={loading} />
            <button className="saas-desktop-login-password-toggle" type="button"
              aria-label={t(passwordVisible ? "hidePassword" : "showPassword")}
              aria-pressed={passwordVisible} aria-controls="central-login-password" disabled={loading}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => setPasswordVisible((visible) => !visible)}>
              {passwordVisible ? <EyeSlash size={28} aria-hidden="true" /> : <Eye size={28} aria-hidden="true" />}
            </button>
            </div>
          </div>}
          {mode === "confirm" && <>
            <label><span>{t("recoveryToken")}</span><input value={recoveryToken} onChange={(event) => setRecoveryToken(event.target.value)} autoComplete="one-time-code" required minLength={32} /></label>
            <label><span>{t("newPassword")}</span><input value={newPassword} onChange={(event) => setNewPassword(event.target.value)} type="password" autoComplete="new-password" required minLength={4} /></label>
            <label><span>{t("confirmPassword")}</span><input value={confirmation} onChange={(event) => setConfirmation(event.target.value)} type="password" autoComplete="new-password" required minLength={4} /></label>
          </>}
          <button className="primary-button saas-desktop-login-submit" type="submit" disabled={loading}><span>{loading ? copy.entering : mode === "login" ? t("enter") : mode === "request" ? t("recoveryRequest") : t("recoveryConfirm")}</span></button>
        </form>
        <p id="internal-signin-help" className="saas-desktop-login-help">{t("internalAccessOnly")}</p>
    </InternalDesktopLoginLayout>
  );
}

export function RequiredPasswordChangeScreen({ username, loading, notice, onSubmit, onCancel, portal = "internal" }: {
  username: string; loading: boolean; notice: Notice;
  onSubmit: (newPassword: string, confirmation: string) => Promise<void>; onCancel: () => void;
  portal?: "internal" | "tenant";
}) {
  const { t, language } = useI18n();
  const copy = entryContent[language];
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const content = <>
    <p className={portal === "internal" ? "saas-desktop-login-account" : "tenant-login-eyebrow"}>{username}</p>
    {portal === "internal"
      ? <h1 id="password-change-title">{t("passwordChangeTitle")}</h1>
      : <h2 id="password-change-title">{t("passwordChangeTitle")}</h2>}
    <p id="password-change-help" className={portal === "internal" ? "saas-desktop-login-help" : "tenant-login-help"}>{t("passwordChangeHelp")}</p>
    <form className="stack-form" aria-describedby="password-change-help" aria-busy={loading} onSubmit={(event) => { event.preventDefault(); if (!loading) void onSubmit(newPassword, confirmation); }}>
      {notice && <div className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"} aria-live={notice.type === "error" ? "assertive" : "polite"}>{notice.text}</div>}
      <label><span>{t("newPassword")}</span><input type="password" autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} minLength={4} required autoFocus disabled={loading} /></label>
      <label><span>{t("confirmPassword")}</span><input type="password" autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} minLength={4} required disabled={loading} /></label>
      <button className={portal === "internal" ? "primary-button saas-desktop-login-submit" : "primary-button tenant-login-submit"} type="submit" disabled={loading}>{t("changeOwnPassword")}</button>
      <button className="secondary-button tenant-login-secondary" type="button" disabled={loading} onClick={onCancel}>{t("logout")}</button>
    </form>
  </>;
  if (portal === "internal") return <InternalDesktopLoginLayout headingId="password-change-title" subtitle="SaaS">{content}</InternalDesktopLoginLayout>;
  return <PortalLoginLayout portal={t("clientPortal")} kicker={t("passwordChangeTitle")}
    title={copy.changeTitle} accent={copy.changeAccent} description={copy.changeDescription} features={[]} note={copy.changeNote}
    headingId="password-change-title" cardClassName="login-panel">{content}</PortalLoginLayout>;
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
