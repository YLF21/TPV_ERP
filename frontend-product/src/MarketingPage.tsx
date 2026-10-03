import {
  ArrowLeft, ArrowRight, Buildings, CalendarCheck, ChartBar, Check,
  CloudArrowUp, Cube, Desktop, DeviceMobile, DownloadSimple, Headset,
  Globe, Package, PaperPlaneTilt, PlayCircle, Question, Receipt, ShieldCheck,
  ShoppingCart, SquaresFour, Storefront, X
} from "@phosphor-icons/react";
import { useEffect, useRef, useState, type FormEvent, type UIEvent } from "react";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";

type MarketingTab = "inicio" | "solucion" | "funcionalidades" | "apps" | "ventajas" | "contacto";
type MarketingCopy = ReturnType<typeof getMarketingContent>;
type AppId = "venta" | "gestion" | "pda" | "saas";
type MarketingAttribution = {
  landingPath: string;
  referrer: string;
  utmSource: string;
  utmMedium: string;
  utmCampaign: string;
};

const navigation: MarketingTab[] = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];
const marketingApiBase = import.meta.env.VITE_SAAS_API_BASE_URL ?? "";
const downloadUrls: Partial<Record<AppId, string>> = {
  venta: import.meta.env.VITE_DOWNLOAD_VENTA_URL,
  gestion: import.meta.env.VITE_DOWNLOAD_GESTION_URL
};
const checksumUrls: Partial<Record<AppId, string>> = {
  venta: import.meta.env.VITE_DOWNLOAD_VENTA_SHA256_URL,
  gestion: import.meta.env.VITE_DOWNLOAD_GESTION_SHA256_URL
};
const downloadVersion = (import.meta.env.VITE_DOWNLOAD_RELEASE_VERSION as string | undefined)?.trim() || null;
const appMeta = [
  { id: "venta", number: "01", icon: ShoppingCart },
  { id: "gestion", number: "02", icon: ChartBar },
  { id: "pda", number: "03", icon: Cube },
  { id: "saas", number: "04", icon: CloudArrowUp }
] as const;
const featureIcons = [Receipt, Package, Buildings, ShieldCheck];
const benefitIcons = [Storefront, CloudArrowUp, ShieldCheck, Headset];

function limit(value: string | null | undefined, length: number) {
  return (value ?? "").trim().slice(0, length);
}

function readMarketingAttribution(): MarketingAttribution {
  const params = new URLSearchParams(window.location.search);
  const current = {
    landingPath: limit(`${window.location.pathname}${window.location.hash}`, 500),
    referrer: limit(document.referrer, 512),
    utmSource: limit(params.get("utm_source"), 100),
    utmMedium: limit(params.get("utm_medium"), 100),
    utmCampaign: limit(params.get("utm_campaign"), 160)
  };
  try {
    const stored = JSON.parse(sessionStorage.getItem("tpv-marketing-attribution") ?? "{}") as Partial<MarketingAttribution>;
    const attribution = {
      landingPath: limit(stored.landingPath || current.landingPath, 500),
      referrer: limit(stored.referrer || current.referrer, 512),
      utmSource: limit(current.utmSource || stored.utmSource, 100),
      utmMedium: limit(current.utmMedium || stored.utmMedium, 100),
      utmCampaign: limit(current.utmCampaign || stored.utmCampaign, 160)
    };
    sessionStorage.setItem("tpv-marketing-attribution", JSON.stringify(attribution));
    return attribution;
  } catch {
    return current;
  }
}

function safeDownloadUrl(value?: string) {
  const url = value?.trim();
  if (!url || /^(#|javascript:)/i.test(url) || !/^(https?:\/\/|\/)/i.test(url)) return null;
  try {
    const parsed = new URL(url, window.location.origin);
    const host = parsed.hostname.toLowerCase();
    if (["example.com", "example.org", "example.net"].some((domain) => host === domain || host.endsWith(`.${domain}`))) return null;
    return url;
  } catch {
    return null;
  }
}

function useModalDialog(onClose: () => void) {
  const dialogRef = useRef<HTMLElement | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);
  const closeHandlerRef = useRef(onClose);

  // Parent updates must not refocus the close button or reset a scrolled dialog.
  useEffect(() => {
    closeHandlerRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const page = document.querySelector<HTMLElement>(".mk-page");
    const previousOverflow = page?.style.overflow;
    if (page) page.style.overflow = "hidden";
    closeRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeHandlerRef.current();
        return;
      }
      if (event.key !== "Tab" || !dialogRef.current) return;
      const focusable = [...dialogRef.current.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), select, input, textarea, [tabindex]:not([tabindex="-1"])')]
        .filter((element) => !element.hasAttribute("hidden") && element.getAttribute("aria-hidden") !== "true");
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      if (page) page.style.overflow = previousOverflow ?? "";
      previousFocus?.focus();
    };
  }, []);

  return { dialogRef, closeRef };
}

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
  const [downloadsOpen, setDownloadsOpen] = useState(false);
  const [tourApp, setTourApp] = useState<AppId | null>(null);
  const [preferredApp, setPreferredApp] = useState<AppId>("venta");
  const attribution = useRef<MarketingAttribution>(readMarketingAttribution()).current;
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
        <button className="mk-mobile-client-link" type="button" onClick={() => { setMenuOpen(false); setDownloadsOpen(true); }}><DownloadSimple aria-hidden="true" /> {copy.common.download} <ArrowRight aria-hidden="true" /></button>
      </nav>
      <div className="mk-header-actions">
        <LanguageSelector language={language} label={copy.language} onChange={onLanguageChange} />
        <button className="mk-client-button" type="button" onClick={() => setDownloadsOpen(true)}><DownloadSimple aria-hidden="true" /> {copy.common.download} <ArrowRight aria-hidden="true" /></button>
      </div>
    </header>

    {activeTab === "inicio" && <HomePage copy={copy} />}
    {activeTab === "solucion" && <SolutionPage copy={copy} />}
    {activeTab === "funcionalidades" && <FeaturesPage copy={copy} />}
    {activeTab === "apps" && <AppsPage copy={copy} onOpenTour={setTourApp} />}
    {activeTab === "ventajas" && <AdvantagesPage copy={copy} />}
    {activeTab === "contacto" && <ContactPage copy={copy} language={language} initialApp={preferredApp} attribution={attribution} />}

    <footer className="mk-footer">
      <a href="#/producto/inicio" aria-label="TPV ERP, inicio"><Brand footer /></a>
      <p>{copy.common.footer}</p>
      <div><a href="#/producto/apps">{copy.common.applications}</a><a href="#/producto/ventajas">{copy.common.advantages}</a><button type="button" onClick={() => setDownloadsOpen(true)}>{copy.common.download}</button></div>
      <small>© {new Date().getFullYear()} TPV ERP</small>
    </footer>
    {downloadsOpen && <DownloadCenterModal copy={copy} onClose={() => setDownloadsOpen(false)} onRequestSetup={(app) => { setPreferredApp(app); setDownloadsOpen(false); window.location.hash = "#/producto/contacto"; }} />}
    {tourApp && <ProductTourModal copy={copy} initialApp={tourApp} onClose={() => setTourApp(null)} onRequestDemo={(app) => { setPreferredApp(app); setTourApp(null); window.location.hash = "#/producto/contacto"; }} />}
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
        <div className="mk-actions"><a className="mk-primary" href="#/producto/contacto">{copy.common.demo} <ArrowRight /></a><a className="mk-secondary" href="#/producto/solucion">{copy.common.exploreSolution}</a></div>
        <ul className="mk-checks">{copy.home.checks.map((item) => <li key={item}><Check weight="bold" /> {item}</li>)}</ul>
      </div>
      <div className="mk-hero-product"><img src="/marketing/hero-ecosystem.png" alt="Panel central, terminal de venta y aplicación móvil de TPV ERP" /><div className="mk-proof-panel" aria-label={copy.growth.proof.title}>{copy.growth.proof.metrics.map(([value, label]) => <span key={label}><strong>{value}</strong><small>{label}</small></span>)}</div></div>
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
      <div className="mk-home-sector-preview" aria-label={copy.growth.sectors.title}>
        {copy.growth.sectors.items.map(([id, label, headline, , bullets], index) => <article key={id}><span>0{index + 1}</span><p>{label}</p><h3>{headline}</h3><small><Check weight="bold" />{bullets[0]}</small></article>)}
      </div>
    </section>
  </>;
}

function SolutionPage({ copy }: { copy: MarketingCopy }) {
  return <section className="mk-inner-page">
    <MarketingHeading kicker={copy.solution.kicker} title={copy.solution.title} accent={copy.solution.accent} body={copy.solution.body} secondaryHref="#/producto/apps" secondaryLabel={copy.common.seeApps} copy={copy} />
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
    <MarketingHeading kicker={copy.features.kicker} title={copy.features.title} accent={copy.features.accent} body={copy.features.body} secondaryHref="#/producto/apps" secondaryLabel={copy.common.seeApps} copy={copy} />
    <div className="mk-feature-list">{copy.features.groups.map(([eyebrow, title, body, items], index) => {
      const FeatureIcon = featureIcons[index];
      return <article key={title} className={index % 2 ? "is-dark" : ""}><div className="mk-feature-number">0{index + 1}</div><div className="mk-feature-icon"><FeatureIcon weight="duotone" /></div><div><p className="mk-kicker">{eyebrow}</p><h2>{title}</h2><p>{body}</p><ul>{items.map((item) => <li key={item}><Check weight="bold" /> {item}</li>)}</ul></div></article>;
    })}</div>
  </section>;
}

function AppsPage({ copy, onOpenTour }: { copy: MarketingCopy; onOpenTour: (app: AppId) => void }) {
  return <section className="mk-inner-page mk-apps-page">
    <MarketingHeading kicker={copy.apps.kicker} title={copy.apps.title} accent={copy.apps.accent} body={copy.apps.body} secondaryHref="#/producto/ventajas" secondaryLabel={copy.common.compareOptions} copy={copy} />
    <div className="mk-apps-grid">{appMeta.map(({ id, number, icon: AppIcon }, index) => {
      const [name, label, description, bullets, audience, scope] = copy.apps.items[index];
      return <article id={id} key={id}><div className="mk-app-card-head"><span>{number}</span><button className="mk-app-tour-action" type="button" onClick={() => onOpenTour(id)}><PlayCircle weight="bold" /> {copy.tour.viewTour} <ArrowRight /></button><AppIcon weight="duotone" /></div><p className="mk-kicker">{label}</p><h2>{name}</h2><p>{description}</p><ul>{bullets.map((bullet) => <li key={bullet}><Check weight="bold" /> {bullet}</li>)}</ul><dl className="mk-app-details"><div><dt>{copy.apps.detailLabels[0]}</dt><dd>{audience}</dd></div><div><dt>{copy.apps.detailLabels[1]}</dt><dd>{scope}</dd></div></dl></article>;
    })}</div>
  </section>;
}

function AdvantagesPage({ copy }: { copy: MarketingCopy }) {
  return <section className="mk-inner-page mk-advantages-page">
    <MarketingHeading kicker={copy.advantagesPage.kicker} title={copy.advantagesPage.title} accent={copy.advantagesPage.accent} body={copy.advantagesPage.body} secondaryHref="#/producto/apps" secondaryLabel={copy.common.seeApps} copy={copy} />
    <div className="mk-comparison" role="table" aria-label={copy.advantagesPage.kicker}>
      <div className="mk-comparison-row is-head" role="row">{copy.advantagesPage.headers.map((header, index) => index === 3 ? <strong role="columnheader" key={header}>{header}</strong> : <span role="columnheader" key={header}>{header}</span>)}</div>
      {copy.advantagesPage.rows.map(([label, traditional, cloud, ours]) => <div className="mk-comparison-row" role="row" key={label}><strong role="cell">{label}</strong><span role="cell" data-label={copy.advantagesPage.headers[1]}>{traditional}</span><span role="cell" data-label={copy.advantagesPage.headers[2]}>{cloud}</span><span className="is-ours" role="cell" data-label={copy.advantagesPage.headers[3]}><Check weight="bold" /> {ours}</span></div>)}
    </div>
    <p className="mk-note">{copy.advantagesPage.note}</p>
  </section>;
}

function MarketingHeading({ kicker, title, accent, body, secondaryHref, secondaryLabel, copy }: { kicker: string; title: string; accent: string; body: string; secondaryHref: string; secondaryLabel: string; copy: MarketingCopy }) {
  return <header className="mk-page-heading">
    <div className="mk-page-heading-title"><p className="mk-kicker">{kicker}</p><h1>{title}<br /><span>{accent}</span></h1></div>
    <div className="mk-page-heading-support">
      <p>{body}</p>
      <div className="mk-heading-actions"><a className="mk-primary" href="#/producto/contacto">{copy.common.demo} <ArrowRight /></a><a href={secondaryHref}>{secondaryLabel} <ArrowRight /></a></div>
      <small><ShieldCheck weight="fill" /> {copy.common.demoHint}</small>
    </div>
  </header>;
}

function DownloadCenterModal({ copy, onClose, onRequestSetup }: { copy: MarketingCopy; onClose: () => void; onRequestSetup: (app: AppId) => void }) {
  const { dialogRef, closeRef } = useModalDialog(onClose);
  const deliveryIcons = [Desktop, Desktop, DeviceMobile, CloudArrowUp];

  return <div className="mk-product-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section ref={dialogRef} className="mk-download-dialog" data-testid="download-center" role="dialog" aria-modal="true" aria-labelledby="mk-download-title">
      <header className="mk-product-dialog-header">
        <div><p className="mk-kicker">{copy.downloads.kicker}</p><h2 id="mk-download-title">{copy.downloads.title}</h2><p>{copy.downloads.body}</p></div>
        <button ref={closeRef} className="mk-dialog-close" type="button" onClick={onClose} aria-label={copy.downloads.close}><X weight="bold" /></button>
      </header>
      <div className="mk-download-grid">
        {appMeta.map(({ id, icon: AppIcon }, index) => {
          const url = safeDownloadUrl(downloadUrls[id]);
          const checksumUrl = safeDownloadUrl(checksumUrls[id]);
          const DeliveryIcon = deliveryIcons[index];
          const [platform, delivery, detail] = copy.downloads.items[index];
          return <article key={id} data-testid={`download-card-${id}`} data-delivery-status={url ? "available" : id === "venta" || id === "gestion" ? "unpublished" : "restricted"}>
            <div className="mk-download-card-head"><span><AppIcon weight="duotone" /></span><em className={url ? "is-available" : ""}><span />{url ? copy.downloads.available : copy.downloads.assisted}</em></div>
            <h3>{copy.apps.items[index][0]}</h3>
            <p>{detail}</p>
            <dl><div><dt>{copy.downloads.platform}</dt><dd><DeliveryIcon weight="bold" /> {platform}</dd></div><div><dt>{copy.downloads.delivery}</dt><dd>{delivery}</dd></div>{index < 2 && <div><dt>{copy.downloads.requirementsLabel}</dt><dd>{copy.downloads.requirements[index][1]}</dd></div>}{url && downloadVersion && <div><dt>{copy.downloads.publishedLabel}</dt><dd>{downloadVersion}</dd></div>}</dl>
            {url ? <div className="mk-download-actions"><a className="mk-download-primary" href={url} target="_blank" rel="noreferrer"><DownloadSimple weight="bold" /> {copy.downloads.downloadNow}</a>{checksumUrl && <a className="mk-download-checksum" href={checksumUrl} target="_blank" rel="noreferrer" title={copy.downloads.checksumHelp}>SHA-256 <ArrowRight /></a>}</div> : <button className="mk-download-request" type="button" onClick={() => onRequestSetup(id)}>{copy.downloads.requestSetup} <ArrowRight /></button>}
          </article>;
        })}
      </div>
      <p className="mk-download-safety"><ShieldCheck weight="fill" /> {copy.downloads.safety}</p>
    </section>
  </div>;
}

function ProductTourModal({ copy, initialApp, onClose, onRequestDemo }: { copy: MarketingCopy; initialApp: AppId; onClose: () => void; onRequestDemo: (app: AppId) => void }) {
  const [activeApp, setActiveApp] = useState<AppId>(initialApp);
  const [step, setStep] = useState(0);
  const { dialogRef, closeRef } = useModalDialog(onClose);
  const appIndex = appMeta.findIndex((app) => app.id === activeApp);
  const { icon: AppIcon, number } = appMeta[appIndex];
  const [name, label, description, bullets, audience, scope] = copy.apps.items[appIndex];
  const details = copy.tour.stepDetails[appIndex][step];
  const [platform, delivery] = copy.downloads.items[appIndex];

  function selectApp(app: AppId) {
    setActiveApp(app);
    setStep(0);
  }

  return <div className="mk-product-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section ref={dialogRef} className="mk-tour-dialog" data-testid="product-tour" data-active-app={activeApp} role="dialog" aria-modal="true" aria-labelledby="mk-tour-title">
      <header className="mk-product-dialog-header">
        <div><p className="mk-kicker">{copy.tour.kicker}</p><h2 id="mk-tour-title">{copy.tour.title}</h2><p>{copy.tour.body}</p></div>
        <button ref={closeRef} className="mk-dialog-close" type="button" onClick={onClose} aria-label={copy.tour.close}><X weight="bold" /></button>
      </header>
      <div className="mk-tour-tabs" role="tablist" aria-label={copy.tour.chooseApp}>{appMeta.map((app, index) => {
        const TabIcon = app.icon;
        return <button key={app.id} type="button" role="tab" aria-selected={activeApp === app.id} aria-controls="mk-tour-panel" onClick={() => selectApp(app.id)}><TabIcon weight="duotone" />{copy.apps.items[index][0]}</button>;
      })}</div>
      <div id="mk-tour-panel" className="mk-tour-stage" role="tabpanel" tabIndex={0} onKeyDown={(event) => {
        if (event.key === "ArrowLeft") setStep((current) => Math.max(0, current - 1));
        if (event.key === "ArrowRight") setStep((current) => Math.min(2, current + 1));
      }}>
        <div className="mk-tour-editorial" aria-live="polite">
          <div className="mk-tour-step-meta">
            <AppIcon weight="duotone" aria-hidden="true" />
            <div><p className="mk-tour-product-label">{label}</p><p className="mk-tour-step-count">{copy.tour.step} {step + 1} / 3</p></div>
            <span aria-hidden="true">{number}</span>
          </div>
          <h3>{bullets[step]}</h3>
          <p>{copy.tour.stepBodies[appIndex][step]}</p>
          <div className="mk-tour-details">
            <section className="mk-tour-capabilities" aria-labelledby="mk-tour-actions-label">
              <h4 id="mk-tour-actions-label">{copy.tour.actionsLabel}</h4>
              <ul>{details.actions.map((action) => <li key={action}><Check weight="bold" aria-hidden="true" /><span>{action}</span></li>)}</ul>
            </section>
            <section className="mk-tour-example" aria-labelledby="mk-tour-example-label">
              <h4 id="mk-tour-example-label">{copy.tour.exampleLabel}</h4>
              <p>{details.example}</p>
            </section>
            <p className="mk-tour-benefit"><ShieldCheck weight="duotone" aria-hidden="true" /><span><strong>{copy.tour.benefitLabel}</strong>{details.benefit}</span></p>
          </div>
        </div>
        <aside className="mk-tour-context" data-app={activeApp}>
          <figure className="mk-tour-preview">
            <img key={activeApp} src={`/marketing/tour-${activeApp}.png`} alt={copy.tour.previewAlts[appIndex]} />
            <figcaption>{copy.tour.previewNote}</figcaption>
          </figure>
          <div className="mk-tour-context-copy"><p>{name}</p><strong>{description}</strong><dl><div><dt>{copy.apps.detailLabels[0]}</dt><dd>{audience}</dd></div><div><dt>{copy.apps.detailLabels[1]}</dt><dd>{scope}</dd></div><div className="mk-tour-delivery"><dt>{copy.tour.deliveryLabel}</dt><dd>{platform} · {delivery}</dd></div></dl></div>
        </aside>
      </div>
      <footer className="mk-tour-footer">
        <div className="mk-tour-progress" aria-label={copy.tour.progress}>{bullets.map((bullet, index) => <button key={bullet} type="button" className={index === step ? "is-active" : ""} aria-current={index === step ? "step" : undefined} onClick={() => setStep(index)}><span>0{index + 1}</span>{bullet}</button>)}</div>
        <div className="mk-tour-actions"><button type="button" onClick={() => setStep((current) => Math.max(0, current - 1))} disabled={step === 0}><ArrowLeft /> {copy.tour.previous}</button>{step < 2 ? <button className="is-primary" type="button" onClick={() => setStep((current) => current + 1)}>{copy.tour.next} <ArrowRight /></button> : <button className="is-primary" type="button" onClick={() => onRequestDemo(activeApp)}>{copy.tour.requestDemo} <ArrowRight /></button>}</div>
      </footer>
    </section>
  </div>;
}

function ContactPage({ copy, language, initialApp, attribution }: { copy: MarketingCopy; language: MarketingLanguage; initialApp: AppId; attribution: MarketingAttribution }) {
  const [selectedApps, setSelectedApps] = useState<AppId[]>([initialApp]);
  const [selectionError, setSelectionError] = useState(false);
  const [submitState, setSubmitState] = useState<"idle" | "submitting" | "success" | "error">("idle");
  const [faqOpen, setFaqOpen] = useState(false);
  const [activeFaq, setActiveFaq] = useState(0);
  const closeFaqRef = useRef<HTMLButtonElement | null>(null);
  const firstProductRef = useRef<HTMLInputElement | null>(null);
  const fullPackageSelected = selectedApps.length === appMeta.length;

  useEffect(() => {
    setSelectedApps([initialApp]);
    setSelectionError(false);
  }, [initialApp]);

  function selectApplications(apps: AppId[]) {
    setSelectedApps(apps);
    setSelectionError(false);
    setSubmitState("idle");
  }

  function toggleApplication(app: AppId) {
    selectApplications(appMeta.filter(({ id }) => id === app ? !selectedApps.includes(id) : selectedApps.includes(id)).map(({ id }) => id));
  }

  useEffect(() => {
    if (!faqOpen) return;
    closeFaqRef.current?.focus();
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setFaqOpen(false);
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [faqOpen]);

  async function prepareDemo(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitState === "submitting") return;
    if (!selectedApps.length) {
      setSelectionError(true);
      firstProductRef.current?.focus();
      return;
    }
    const form = event.currentTarget;
    const data = new FormData(form);
    const products = appMeta.filter(({ id }) => selectedApps.includes(id)).map(({ id }) => `APP_${id.toUpperCase()}`);
    setSubmitState("submitting");
    try {
      const response = await fetch(`${marketingApiBase}/api/v1/public/demo-requests`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          product: products[0],
          products,
          name: data.get("name"),
          company: data.get("company"),
          email: data.get("email"),
          phone: data.get("phone"),
          message: data.get("message"),
          locale: language,
          privacyAccepted: data.get("privacyAccepted") === "on",
          website: data.get("website"),
          ...attribution
        })
      });
      if (!response.ok) throw new Error(`Demo request failed with ${response.status}`);
      form.reset();
      setSelectedApps([initialApp]);
      setSelectionError(false);
      setSubmitState("success");
    } catch {
      setSubmitState("error");
    }
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
            return <button type="button" key={id} className={`mk-contact-product tone-${index}${selectedApps.includes(id) ? " is-selected" : ""}`} aria-pressed={selectedApps.includes(id)} onClick={() => toggleApplication(id)} disabled={submitState === "submitting"}>
            <AppIcon weight="duotone" aria-hidden="true" />
            <span><small>APP</small><strong>{name.replace(/^APP | App$| 应用$/g, "")}</strong><em>{label}</em></span>
            <Check className="mk-contact-product-check" weight="bold" aria-hidden="true" />
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
        <fieldset className="mk-contact-selection" disabled={submitState === "submitting"} aria-invalid={selectionError || undefined} aria-describedby={selectionError ? "mk-contact-selection-error" : undefined}>
          <legend>{copy.contact.product}</legend>
          <label className={`mk-contact-package${fullPackageSelected ? " is-selected" : ""}`}>
            <input type="checkbox" name="fullPackage" checked={fullPackageSelected} onChange={(event) => selectApplications(event.target.checked ? appMeta.map(({ id }) => id) : [])} />
            <span><strong>{copy.contact.fullPackage}</strong><small>{copy.contact.fullPackageHint}</small></span>
            <SquaresFour weight="duotone" aria-hidden="true" />
          </label>
          <div className="mk-contact-app-options">
            {appMeta.map(({ id }, index) => <label key={id} className={`mk-contact-app-choice${selectedApps.includes(id) ? " is-selected" : ""}`}>
              <input ref={index === 0 ? firstProductRef : undefined} type="checkbox" name="products" value={id} checked={selectedApps.includes(id)} onChange={() => toggleApplication(id)} />
              <span>{copy.apps.items[index][0]}</span>
            </label>)}
          </div>
          {selectionError && <p id="mk-contact-selection-error" className="mk-contact-selection-error" role="alert">{copy.contact.selectionRequired}</p>}
        </fieldset>
        <div className="mk-contact-fields">
          <label>{copy.contact.name}<input name="name" autoComplete="name" required placeholder={copy.contact.namePlaceholder} /></label>
          <label>{copy.contact.company}<input name="company" autoComplete="organization" required placeholder={copy.contact.companyPlaceholder} /></label>
          <label>{copy.contact.email}<input name="email" type="email" autoComplete="email" required placeholder="name@company.com" /></label>
          <label>{copy.contact.phone}<input name="phone" type="tel" autoComplete="tel" placeholder="600 000 000" /></label>
        </div>
        <label>{copy.contact.caseLabel}<textarea name="message" rows={3} placeholder={copy.contact.casePlaceholder} /></label>
        <label className="mk-contact-honeypot" aria-hidden="true">Website<input name="website" tabIndex={-1} autoComplete="off" /></label>
        <label className="mk-contact-consent"><input name="privacyAccepted" type="checkbox" required /> <span>{copy.contact.consent}</span></label>
        <button className="mk-contact-submit" type="submit" disabled={submitState === "submitting"} aria-busy={submitState === "submitting"}>{submitState === "submitting" ? copy.contact.submitting : copy.contact.submit} <PaperPlaneTilt weight="bold" /></button>
        <button className="mk-contact-talk" type="button" onClick={() => setFaqOpen(true)}><Question weight="bold" /> {copy.contact.faqAction}</button>
        {submitState === "success" && <p className="mk-contact-prepared" role="status">{copy.contact.success}</p>}
        {submitState === "error" && <p className="mk-contact-error" role="alert">{copy.contact.error}</p>}
        <small>{copy.contact.dataUse}</small>
      </form>
    </div>
    {faqOpen && <div className="mk-faq-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setFaqOpen(false); }}>
      <section className="mk-faq-dialog" role="dialog" aria-modal="true" aria-labelledby="mk-faq-title">
        <header><div><p className="mk-kicker">{copy.contact.faqLabel}</p><h2 id="mk-faq-title">{copy.contact.faqTitle}</h2><p>{copy.contact.faqHint}</p></div><button ref={closeFaqRef} type="button" onClick={() => setFaqOpen(false)} aria-label={copy.contact.faqClose}><X weight="bold" /></button></header>
        <div className="mk-faq-layout">
          <div className="mk-faq-list" aria-label={copy.contact.faqTitle}>{copy.contact.faqItems.map(([question], index) => <button key={question} type="button" className={activeFaq === index ? "is-active" : undefined} aria-pressed={activeFaq === index} aria-controls="mk-faq-answer" onClick={() => setActiveFaq(index)}><span>0{index + 1}</span>{question}<ArrowRight aria-hidden="true" /></button>)}</div>
          <article id="mk-faq-answer" className="mk-faq-answer" aria-live="polite"><span>0{activeFaq + 1}</span><h3>{copy.contact.faqItems[activeFaq][0]}</h3><p>{copy.contact.faqItems[activeFaq][1]}</p></article>
        </div>
      </section>
    </div>}
  </section>;
}
