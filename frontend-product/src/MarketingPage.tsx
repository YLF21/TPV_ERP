import {
  ArrowRight, Buildings, CalendarCheck, ChartBar, ChatCircleDots, Check,
  CloudArrowUp, Cube, Desktop, DeviceMobile, DownloadSimple, Headset,
  Globe, Package, PaperPlaneTilt, Receipt, ShieldCheck, ShoppingCart, SquaresFour,
  Storefront
} from "@phosphor-icons/react";
import { useEffect, useRef, useState, type FormEvent, type UIEvent } from "react";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";

type MarketingTab = "inicio" | "solucion" | "funcionalidades" | "apps" | "ventajas" | "contacto";
type MarketingCopy = ReturnType<typeof getMarketingContent>;

const navigation: MarketingTab[] = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];
const appMeta = [
  { id: "venta", number: "01", icon: ShoppingCart },
  { id: "gestion", number: "02", icon: ChartBar },
  { id: "pda", number: "03", icon: Cube },
  { id: "saas", number: "04", icon: CloudArrowUp }
] as const;
const featureIcons = [Receipt, Package, Buildings, ShieldCheck];
const benefitIcons = [Storefront, CloudArrowUp, ShieldCheck, Headset];

function readMarketingTab(): MarketingTab {
  const candidate = window.location.hash.replace(/^#\/?producto\/?/, "").split("/")[0];
  return navigation.includes(candidate as MarketingTab) ? candidate as MarketingTab : "inicio";
}

function Brand({ footer = false }: { footer?: boolean }) {
  return <span className={`mk-brand${footer ? " mk-brand-footer" : ""}`}><SquaresFour weight="fill" aria-hidden="true" /><strong>TPV</strong><span>ERP</span></span>;
}

function LanguageSelector({ language, label, mobile = false, onChange }: { language: MarketingLanguage; label: string; mobile?: boolean; onChange: (language: MarketingLanguage) => void }) {
  return <label className={`mk-language-selector${mobile ? " is-mobile" : ""}`}>
    <Globe weight="bold" aria-hidden="true" />
    <span className="mk-visually-hidden">{label}</span>
    <select value={language} onChange={(event) => onChange(event.target.value as MarketingLanguage)} aria-label={label}>
      <option value="es">ES</option>
      <option value="en">EN</option>
      <option value="zh">中文</option>
    </select>
  </label>;
}

export default function MarketingPage({ language, onLanguageChange }: { language: MarketingLanguage; onLanguageChange: (language: MarketingLanguage) => void }) {
  const [activeTab, setActiveTab] = useState<MarketingTab>(() => readMarketingTab());
  const [menuOpen, setMenuOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const pageRef = useRef<HTMLElement | null>(null);
  const copy = getMarketingContent(language);

  useEffect(() => {
    const syncTab = () => setActiveTab(readMarketingTab());
    window.addEventListener("hashchange", syncTab);
    window.addEventListener("popstate", syncTab);
    return () => { window.removeEventListener("hashchange", syncTab); window.removeEventListener("popstate", syncTab); };
  }, []);

  useEffect(() => {
    pageRef.current?.scrollTo({ top: 0, behavior: "auto" });
    setMenuOpen(false);
    setScrolled(false);
  }, [activeTab]);

  useEffect(() => {
    document.documentElement.lang = language === "zh" ? "zh-CN" : language;
  }, [language]);

  function updateHeader(event: UIEvent<HTMLElement>) {
    setScrolled(event.currentTarget.scrollTop > 18);
  }

  return <main className="mk-page" ref={pageRef} onScroll={updateHeader}>
    <header className={`mk-header${scrolled ? " is-scrolled" : ""}`}>
      <a className="mk-brand-link" href="#/producto/inicio" aria-label="TPV ERP, inicio"><Brand /></a>
      <button className="mk-menu-button" type="button" aria-expanded={menuOpen} aria-controls="mk-navigation" aria-label={menuOpen ? "Cerrar menú" : "Abrir menú"} onClick={() => setMenuOpen((open) => !open)}><span /><span /><span /></button>
      <nav id="mk-navigation" className={menuOpen ? "is-open" : ""} aria-label="Navegación comercial">
        {navigation.map((id, index) => <a key={id} href={`#/producto/${id}`} className={activeTab === id ? "active" : undefined} aria-current={activeTab === id ? "page" : undefined}>{copy.navigation[index]}</a>)}
        <LanguageSelector mobile language={language} label={copy.language} onChange={onLanguageChange} />
        <a className="mk-mobile-client-link" href="#/producto/apps"><DownloadSimple aria-hidden="true" /> {copy.common.download} <ArrowRight aria-hidden="true" /></a>
      </nav>
      <div className="mk-header-actions">
        <LanguageSelector language={language} label={copy.language} onChange={onLanguageChange} />
        <a className="mk-client-button" href="#/producto/apps"><DownloadSimple aria-hidden="true" /> {copy.common.download} <ArrowRight aria-hidden="true" /></a>
      </div>
    </header>

    {activeTab === "inicio" && <HomePage copy={copy} />}
    {activeTab === "solucion" && <SolutionPage copy={copy} />}
    {activeTab === "funcionalidades" && <FeaturesPage copy={copy} />}
    {activeTab === "apps" && <AppsPage copy={copy} />}
    {activeTab === "ventajas" && <AdvantagesPage copy={copy} />}
    {activeTab === "contacto" && <ContactPage copy={copy} />}

    <footer className="mk-footer">
      <a href="#/producto/inicio" aria-label="TPV ERP, inicio"><Brand footer /></a>
      <p>{copy.common.footer}</p>
      <div><a href="#/producto/apps">{copy.common.applications}</a><a href="#/producto/ventajas">{copy.common.advantages}</a><a href="#/producto/apps">{copy.common.download}</a></div>
      <small>© {new Date().getFullYear()} TPV ERP</small>
    </footer>
  </main>;
}

function HomePage({ copy }: { copy: MarketingCopy }) {
  return <>
    <section className="mk-hero">
      <div className="mk-grid-decoration" aria-hidden="true" />
      <div className="mk-hero-copy">
        <p className="mk-hero-kicker">{copy.home.kicker}</p>
        <h1>{copy.home.title}<br /><em>{copy.home.titleAccent}</em></h1>
        <p className="mk-lead"><strong>{copy.home.lead}</strong><span>{copy.home.body}</span></p>
        <div className="mk-actions"><a className="mk-primary" href="#/producto/apps">{copy.common.download} <ArrowRight /></a><a className="mk-secondary" href="#/producto/contacto">{copy.common.demo}</a></div>
        <ul className="mk-checks">{copy.home.checks.map((item) => <li key={item}><Check weight="bold" /> {item}</li>)}</ul>
      </div>
      <div className="mk-hero-product"><img src="/marketing/hero-ecosystem.png" alt="Panel central, terminal de venta y aplicación móvil de TPV ERP" /></div>
    </section>
    <section className="mk-app-strip" aria-label={copy.common.advantages}>
      {copy.home.benefits.map(([title, body], index) => {
        const BenefitIcon = benefitIcons[index];
        return <article key={title}><BenefitIcon weight="duotone" aria-hidden="true" /><span><strong>{title}</strong><small>{body}</small></span></article>;
      })}
    </section>
    <section className="mk-intro">
      <p className="mk-kicker">{copy.home.introKicker}</p>
      <h2>{copy.home.introTitle}<br /><span>{copy.home.introAccent}</span></h2>
      <p>{copy.home.introBody}</p>
      <a href="#/producto/solucion">{copy.home.introLink} <ArrowRight /></a>
    </section>
  </>;
}

function SolutionPage({ copy }: { copy: MarketingCopy }) {
  return <section className="mk-inner-page">
    <header className="mk-page-heading"><p className="mk-kicker">{copy.solution.kicker}</p><h1>{copy.solution.title}<br /><span>{copy.solution.accent}</span></h1><p>{copy.solution.body}</p></header>
    <div className="mk-principles">
      {copy.solution.principles.map(([title, body], index) => {
        const PrincipleIcon = [Desktop, CloudArrowUp, Storefront][index];
        return <article key={title}><PrincipleIcon weight="duotone" /><span>0{index + 1}</span><h2>{title}</h2><p>{body}</p></article>;
      })}
    </div>
    <div className="mk-dark-band"><div><p className="mk-kicker">{copy.solution.bandKicker}</p><h2>{copy.solution.bandTitle}</h2><p>{copy.solution.bandBody}</p><a className="mk-primary" href="#/producto/apps">{copy.solution.bandAction} <ArrowRight /></a></div><ChartBar weight="duotone" aria-hidden="true" /></div>
  </section>;
}

function FeaturesPage({ copy }: { copy: MarketingCopy }) {
  return <section className="mk-inner-page mk-features-page">
    <header className="mk-page-heading"><p className="mk-kicker">{copy.features.kicker}</p><h1>{copy.features.title}<br /><span>{copy.features.accent}</span></h1><p>{copy.features.body}</p></header>
    <div className="mk-feature-list">{copy.features.groups.map(([eyebrow, title, body, items], index) => {
      const FeatureIcon = featureIcons[index];
      return <article key={title} className={index % 2 ? "is-dark" : ""}><div className="mk-feature-number">0{index + 1}</div><div className="mk-feature-icon"><FeatureIcon weight="duotone" /></div><div><p className="mk-kicker">{eyebrow}</p><h2>{title}</h2><p>{body}</p><ul>{items.map((item) => <li key={item}><Check weight="bold" /> {item}</li>)}</ul></div></article>;
    })}</div>
  </section>;
}

function AppsPage({ copy }: { copy: MarketingCopy }) {
  return <section className="mk-inner-page mk-apps-page">
    <header className="mk-page-heading"><p className="mk-kicker">{copy.apps.kicker}</p><h1>{copy.apps.title}<br /><span>{copy.apps.accent}</span></h1><p>{copy.apps.body}</p></header>
    <div className="mk-apps-grid">{appMeta.map(({ id, number, icon: AppIcon }, index) => {
      const [name, label, description, bullets, audience, scope] = copy.apps.items[index];
      return <article id={id} key={id}><div className="mk-app-card-head"><span>{number}</span><AppIcon weight="duotone" /></div><p className="mk-kicker">{label}</p><h2>{name}</h2><p>{description}</p><ul>{bullets.map((bullet) => <li key={bullet}><Check weight="bold" /> {bullet}</li>)}</ul><dl className="mk-app-details"><div><dt>{copy.apps.detailLabels[0]}</dt><dd>{audience}</dd></div><div><dt>{copy.apps.detailLabels[1]}</dt><dd>{scope}</dd></div></dl></article>;
    })}</div>
  </section>;
}

function AdvantagesPage({ copy }: { copy: MarketingCopy }) {
  return <section className="mk-inner-page mk-advantages-page">
    <header className="mk-page-heading"><p className="mk-kicker">{copy.advantagesPage.kicker}</p><h1>{copy.advantagesPage.title}<br /><span>{copy.advantagesPage.accent}</span></h1><p>{copy.advantagesPage.body}</p></header>
    <div className="mk-comparison" role="table" aria-label={copy.advantagesPage.kicker}>
      <div className="mk-comparison-row is-head" role="row">{copy.advantagesPage.headers.map((header, index) => index === 3 ? <strong role="columnheader" key={header}>{header}</strong> : <span role="columnheader" key={header}>{header}</span>)}</div>
      {copy.advantagesPage.rows.map(([label, traditional, cloud, ours]) => <div className="mk-comparison-row" role="row" key={label}><strong role="cell">{label}</strong><span role="cell" data-label={copy.advantagesPage.headers[1]}>{traditional}</span><span role="cell" data-label={copy.advantagesPage.headers[2]}>{cloud}</span><span className="is-ours" role="cell" data-label={copy.advantagesPage.headers[3]}><Check weight="bold" /> {ours}</span></div>)}
    </div>
    <p className="mk-note">{copy.advantagesPage.note}</p>
  </section>;
}

function ContactPage({ copy }: { copy: MarketingCopy }) {
  const [selectedApp, setSelectedApp] = useState("venta");
  const [prepared, setPrepared] = useState(false);

  function prepareDemo(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const productIndex = appMeta.findIndex((app) => app.id === selectedApp);
    const product = copy.apps.items[productIndex]?.[0] ?? "TPV ERP";
    const body = [
      `${copy.contact.product}: ${product}`,
      `${copy.contact.name}: ${data.get("name") ?? ""}`,
      `${copy.contact.company}: ${data.get("company") ?? ""}`,
      `${copy.contact.email}: ${data.get("email") ?? ""}`,
      `${copy.contact.phone}: ${data.get("phone") ?? ""}`,
      `${copy.contact.caseLabel}: ${data.get("message") ?? ""}`
    ].join("\n");
    setPrepared(true);
    window.location.href = `mailto:?subject=${encodeURIComponent(`${copy.contact.formTag} — ${product}`)}&body=${encodeURIComponent(body)}`;
  }

  return <section className="mk-contact-page">
    <img className="mk-contact-scene" src="/marketing/contact-ecosystem.png" alt="Terminal de venta, portátil, PDA y panel SaaS conectados en un comercio" />
    <div className="mk-contact-stage">
      <div className="mk-contact-story">
        <p className="mk-kicker">{copy.contact.kicker}</p>
        <h1>{copy.contact.title} <span>{copy.contact.accent}</span></h1>
        <p>{copy.contact.body}</p>
        <div className="mk-contact-products" role="group" aria-label={copy.contact.groupLabel}>
          {appMeta.map(({ id, icon: AppIcon }, index) => {
            const [name, label] = copy.apps.items[index];
            return <button type="button" key={id} className={`mk-contact-product tone-${index}${selectedApp === id ? " is-selected" : ""}`} aria-pressed={selectedApp === id} onClick={() => setSelectedApp(id)}>
            <AppIcon weight="duotone" aria-hidden="true" />
            <span><small>APP</small><strong>{name.replace(/^APP | App$| 应用$/g, "")}</strong><em>{label}</em></span>
            <ArrowRight aria-hidden="true" />
          </button>})}
        </div>
        <div className="mk-contact-trust" aria-label={copy.contact.trustLabel}>
          {copy.contact.trust.map(([title, body], index) => {
            const TrustIcon = [ShieldCheck, CalendarCheck, Headset][index];
            return <span key={title}><TrustIcon weight="duotone" /><strong>{title}</strong><small>{body}</small></span>;
          })}
        </div>
      </div>

      <form className="mk-contact-form" onSubmit={prepareDemo}>
        <p className="mk-contact-form-tag">{copy.contact.formTag}</p>
        <h2>{copy.contact.formTitle}</h2>
        <p>{copy.contact.formBody}</p>
        <label>{copy.contact.product}<select value={selectedApp} onChange={(event) => setSelectedApp(event.target.value)}>{appMeta.map((app, index) => <option value={app.id} key={app.id}>{copy.apps.items[index][0]}</option>)}</select></label>
        <div className="mk-contact-fields">
          <label>{copy.contact.name}<input name="name" autoComplete="name" required placeholder={copy.contact.namePlaceholder} /></label>
          <label>{copy.contact.company}<input name="company" autoComplete="organization" required placeholder={copy.contact.companyPlaceholder} /></label>
          <label>{copy.contact.email}<input name="email" type="email" autoComplete="email" required placeholder="name@company.com" /></label>
          <label>{copy.contact.phone}<input name="phone" type="tel" autoComplete="tel" placeholder="600 000 000" /></label>
        </div>
        <label>{copy.contact.caseLabel}<textarea name="message" rows={3} placeholder={copy.contact.casePlaceholder} /></label>
        <button className="mk-contact-submit" type="submit">{copy.contact.submit} <PaperPlaneTilt weight="bold" /></button>
        <a className="mk-contact-talk" href="mailto:?subject=TPV%20ERP"><ChatCircleDots /> {copy.contact.talk}</a>
        {prepared && <p className="mk-contact-prepared" role="status">{copy.contact.prepared}</p>}
        <small>{copy.contact.pendingRecipient}</small>
      </form>
    </div>
  </section>;
}
