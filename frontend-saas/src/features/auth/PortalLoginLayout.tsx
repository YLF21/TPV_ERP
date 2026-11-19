import type { ElementType, ReactNode } from "react";
import { useI18n } from "../../i18n";
import { LanguageSelector } from "../../shared/ui";
import { appVersion } from "../../lib/appVersion";
import wordmark from "../../../../frontend/branding/espos-wordmark.png";
import "./tenant-login.css";

/** Presentation shared by both portals; account state and authentication stay with each entry. */
export function PortalLoginLayout({ portal, kicker, title, accent, description, features, note, headingId, cardClassName, footerExtra, children }: {
  portal: string;
  kicker: string;
  title: string;
  accent: string;
  description: string;
  features: readonly { title: string; icon: ElementType }[];
  note: string;
  headingId: string;
  cardClassName?: string;
  footerExtra?: ReactNode;
  children: ReactNode;
}) {
  const { language, t } = useI18n();
  const introId = `${headingId}-intro`;
  return <main className={`tenant-login-page${cardClassName ? " tenant-login-with-account" : ""}`} lang={language === "zh" ? "zh-CN" : language}>
    <header className="tenant-login-topbar">
      <div className="tenant-login-brand"><img src={wordmark} alt="esPOS" width={120} height={38} /><span>{portal}</span></div>
      <div className="tenant-login-language"><LanguageSelector /></div>
    </header>
    <div className="tenant-login-content">
      <section className="tenant-login-story" aria-labelledby={introId}>
        <p className="tenant-login-kicker">{kicker}</p>
        <h1 id={introId}>{title}<br /><span>{accent}</span></h1>
        <p className="tenant-login-intro">{description}</p>
        {features.length > 0 && <ul>{features.map(({ title: featureTitle, icon: Icon }) => <li key={featureTitle}><span><Icon size={21} aria-hidden="true" /></span>{featureTitle}</li>)}</ul>}
        {note && <p className="tenant-login-permissions">{note}</p>}
      </section>
      <section className={`tenant-login-card${cardClassName ? ` ${cardClassName}` : ""}`} aria-labelledby={headingId}>
        {children}
      </section>
    </div>
    <footer className="tenant-login-footer"><span>esPOS · {portal}</span><span>{t("saasVersion")} {appVersion}</span>{footerExtra}</footer>
  </main>;
}
