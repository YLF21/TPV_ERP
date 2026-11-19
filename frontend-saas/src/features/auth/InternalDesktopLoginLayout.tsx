import { useEffect, useState, type ReactNode } from "react";
import { useI18n } from "../../i18n";
import { appVersion } from "../../lib/appVersion";
import { formatUtcDateTime } from "../../lib/utc-date-time.mjs";
import { LanguageSelector } from "../../shared/ui";
import languageIcon from "../../assets/language.png";
import wordmark from "../../../../frontend/branding/espos-wordmark.png";
import "./internal-desktop-login.css";

/** Desktop-style presentation only. Authentication and account state belong to the caller. */
export function InternalDesktopLoginLayout({ headingId, children, subtitle = "SaaS", footerExtra }: {
  headingId: string;
  children: ReactNode;
  subtitle?: string;
  footerExtra?: ReactNode;
}) {
  const { language, t } = useI18n();
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const context = language === "en" ? "Internal access" : language === "zh" ? "内部访问" : "Acceso interno";
  const locale = language === "zh" ? "zh-CN" : language === "en" ? "en-GB" : "es-ES";

  return <main className="saas-desktop-login" lang={language === "zh" ? "zh-CN" : language}>
    <header className="saas-desktop-login-topbar">
      <div className="saas-desktop-login-context"><img src={wordmark} alt="esPOS" width={94} height={30} draggable={false} /><span>{context}</span></div>
      <div className="saas-desktop-login-tools">
        <time className="saas-desktop-login-clock" dateTime={now.toISOString()}>{formatUtcDateTime(now, locale, true)}</time>
        <LanguageSelector variant="floating" iconSrc={languageIcon} />
      </div>
    </header>
    <div className="saas-desktop-login-content">
      <div className="saas-desktop-login-entry">
        <div className="saas-desktop-login-brand"><img src={wordmark} alt="esPOS" width={200} height={64} draggable={false} />{subtitle && <span>{subtitle}</span>}</div>
        <section className="saas-desktop-login-card login-panel" aria-labelledby={headingId}>{children}</section>
      </div>
    </div>
    <footer className="saas-desktop-login-footer"><span>esPOS · {context}</span><span>{t("saasVersion")} {appVersion}</span>{footerExtra}</footer>
  </main>;
}
