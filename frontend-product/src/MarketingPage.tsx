import {
  ArrowRight, Buildings, CalendarCheck, ChartBar, Check,
  CloudArrowUp, CopySimple, Desktop, DownloadSimple, Headset,
  Globe, Package, PaperPlaneTilt, Question, Receipt, ShieldCheck,
  SquaresFour, Storefront, WechatLogo
} from "@phosphor-icons/react";
import { useEffect, useRef, useState, type FormEvent, type MouseEvent as ReactMouseEvent, type UIEvent } from "react";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";
import { getMarketingUiContent } from "./marketingUiI18n";
import { productBrand } from "./brand";
import { marketingContact } from "./contact";
import { ApplicationExplorer } from "./ApplicationExplorer";
import { MarketingImage } from "./MarketingImage";
import { marketingImageSizes } from "./marketingImages";
import { appMeta, type AppId, type MarketingCopy } from "./applicationMetadata";
import { MarketingDialogLoader } from "./MarketingDialogLoader";
import { MarketingLanguageProvider, MarketingLink, navigateMarketing } from "./MarketingNavigation";
import { marketingHref, readMarketingLocation } from "./marketingRoutes";
import { createCampaignAttribution, marketingContactSearch, parseRequestedApplications, type MarketingAttribution } from "./marketingCampaign";
import { checkDemoAvailability, createDemoRequestAttempts, demoFieldLimits, DemoRequestError, submitDemoRequest, type DemoReceipt, type DemoRequestFailure } from "./demoRequests";

type MarketingTab = "inicio" | "solucion" | "funcionalidades" | "apps" | "ventajas" | "contacto";

const navigation: MarketingTab[] = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];
const featureIcons = [Receipt, Package, Buildings, ShieldCheck];
const benefitIcons = [Storefront, CloudArrowUp, ShieldCheck, Headset];

function readMarketingAttribution(): MarketingAttribution {
  let previous: unknown;
  try { previous = JSON.parse(sessionStorage.getItem("tpv-marketing-attribution") ?? "null"); } catch { /* Storage is optional. */ }
  const attribution = createCampaignAttribution(window.location, document.referrer, previous);
  try { sessionStorage.setItem("tpv-marketing-attribution", JSON.stringify(attribution)); } catch { /* No tracking fallback. */ }
  return attribution;
}

function readMarketingTab(): MarketingTab {
  return readMarketingLocation(window.location).route;
}

function Brand({ footer = false }: { footer?: boolean }) {
  return <span className={`mk-brand${footer ? " mk-brand-footer" : ""}`}><img src={productBrand.logo} alt={productBrand.name} width={132} height={42} draggable={false} /></span>;
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
  const [preferredApps, setPreferredApps] = useState<AppId[]>(() => parseRequestedApplications(window.location.search) ?? ["venta"]);
  const [attribution] = useState<MarketingAttribution>(readMarketingAttribution);
  const previousSearchRef = useRef(window.location.search);
  const pageRef = useRef<HTMLDivElement | null>(null);
  const mainRef = useRef<HTMLElement | null>(null);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);
  const previousTabRef = useRef(activeTab);
  const copy = getMarketingContent(language);
  const ui = getMarketingUiContent(language);

  useEffect(() => {
    const syncTab = () => {
      setActiveTab(readMarketingTab());
      if (previousSearchRef.current !== window.location.search) {
        // Ordinary navigation drops query parameters, not the visitor's chosen apps.
        // Only another explicit, valid application choice replaces that preference.
        const requestedApps = parseRequestedApplications(window.location.search);
        if (requestedApps) setPreferredApps(requestedApps);
        previousSearchRef.current = window.location.search;
      }
    };
    window.addEventListener("hashchange", syncTab);
    window.addEventListener("popstate", syncTab);
    return () => { window.removeEventListener("hashchange", syncTab); window.removeEventListener("popstate", syncTab); };
  }, []);

  useEffect(() => {
    pageRef.current?.scrollTo({ top: 0, behavior: "auto" });
    setMenuOpen(false);
    setScrolled(false);
    // Do not steal focus when the page first loads or its language changes.
    if (previousTabRef.current !== activeTab) {
      setDownloadsOpen(false);
      setTourApp(null);
      mainRef.current?.focus({ preventScroll: true });
    }
    previousTabRef.current = activeTab;
  }, [activeTab]);

  useEffect(() => {
    if (!menuOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setMenuOpen(false);
      menuButtonRef.current?.focus({ preventScroll: true });
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [menuOpen]);

  useEffect(() => {
    document.documentElement.lang = language === "zh" ? "zh-CN" : language;
  }, [language]);

  function updateHeader(event: UIEvent<HTMLElement>) {
    setScrolled(event.currentTarget.scrollTop > 18);
  }

  function requestApplications(apps: AppId[]) {
    setPreferredApps(apps);
    navigateMarketing(`${marketingHref(language, "contacto")}${marketingContactSearch(apps)}`);
  }

  function followMarketingLink(event: ReactMouseEvent<HTMLDivElement>) {
    if (event.defaultPrevented || event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    if (!(event.target instanceof Element)) return;
    const link = event.target.closest<HTMLAnchorElement>("a[href]");
    if (!link || link.target && link.target !== "_self" || link.hasAttribute("download")) return;
    const target = new URL(link.href, window.location.origin);
    if (target.origin !== window.location.origin || !/^\/(es|en|zh)\/producto\/(inicio|solucion|funcionalidades|apps|ventajas|contacto)\/$/.test(target.pathname) || target.hash) return;
    event.preventDefault();
    setMenuOpen(false);
    navigateMarketing(`${target.pathname}${target.search}`);
  }

  return <MarketingLanguageProvider value={language}><div className="mk-page" ref={pageRef} onScroll={updateHeader} onClick={followMarketingLink}>
    <div className="mk-background" data-marketing-background>
    <MarketingLink className="mk-skip-link" href="#mk-main-content" onClick={(event) => { event.preventDefault(); setMenuOpen(false); mainRef.current?.focus({ preventScroll: true }); mainRef.current?.scrollIntoView({ block: "start", behavior: "auto" }); }}>{ui.skipToContent}</MarketingLink>
    <header className={`mk-header${scrolled ? " is-scrolled" : ""}`}>
      <MarketingLink className="mk-brand-link" href="#/producto/inicio" aria-label={`${productBrand.name} — ${copy.navigation[0]}`}><Brand /></MarketingLink>
      <button ref={menuButtonRef} className="mk-menu-button" type="button" aria-expanded={menuOpen} aria-controls="mk-navigation" aria-label={menuOpen ? ui.closeMenu : ui.openMenu} onClick={() => setMenuOpen((open) => !open)}><span /><span /><span /></button>
      <nav id="mk-navigation" className={menuOpen ? "is-open" : ""} aria-label={ui.primaryNavigation}>
        {navigation.map((id, index) => <MarketingLink key={id} href={`#/producto/${id}`} className={activeTab === id ? "active" : undefined} aria-current={activeTab === id ? "page" : undefined} onClick={() => { setMenuOpen(false); if (activeTab === id) mainRef.current?.focus({ preventScroll: true }); }}>{copy.navigation[index]}</MarketingLink>)}
        <LanguageSelector mobile language={language} label={copy.language} onChange={onLanguageChange} />
        <button className="mk-mobile-client-link" type="button" onClick={() => { setMenuOpen(false); setDownloadsOpen(true); }}><DownloadSimple aria-hidden="true" /> {copy.common.download} <ArrowRight aria-hidden="true" /></button>
      </nav>
      <div className="mk-header-actions">
        <LanguageSelector language={language} label={copy.language} onChange={onLanguageChange} />
        <button className="mk-client-button" type="button" onClick={() => setDownloadsOpen(true)}><DownloadSimple aria-hidden="true" /> {copy.common.download} <ArrowRight aria-hidden="true" /></button>
      </div>
    </header>

    <main id="mk-main-content" className="mk-main-content" ref={mainRef} tabIndex={-1} aria-label={copy.navigation[navigation.indexOf(activeTab)]}>
    {activeTab === "inicio" && <HomePage copy={copy} />}
    {activeTab === "solucion" && <SolutionPage copy={copy} />}
    {activeTab === "funcionalidades" && <FeaturesPage copy={copy} />}
    {activeTab === "apps" && <AppsPage copy={copy} language={language} onOpenTour={setTourApp} onRequestDemo={(app) => requestApplications([app])} />}
    {activeTab === "ventajas" && <AdvantagesPage copy={copy} />}
    {activeTab === "contacto" && <ContactPage copy={copy} language={language} initialApps={preferredApps} attribution={attribution} />}
    </main>

    <footer className="mk-footer">
      <MarketingLink href="#/producto/inicio" aria-label={`${productBrand.name} — ${copy.navigation[0]}`}><Brand footer /></MarketingLink>
      <p>{copy.common.footer}</p>
      <div><MarketingLink href="#/producto/apps">{copy.common.applications}</MarketingLink><MarketingLink href="#/producto/ventajas">{copy.common.advantages}</MarketingLink><button type="button" onClick={() => setDownloadsOpen(true)}>{copy.common.download}</button></div>
      <small>© {new Date().getFullYear()} {productBrand.name}</small>
    </footer>
    </div>
    <div id="mk-modal-root" />
    {downloadsOpen && <MarketingDialogLoader kind="downloads" language={language} copy={copy} onClose={() => setDownloadsOpen(false)} onRequestSetup={(app) => { setDownloadsOpen(false); requestApplications([app]); }} />}
    {tourApp && <MarketingDialogLoader kind="tour" copy={copy} language={language} initialApp={tourApp} onClose={() => setTourApp(null)} onRequestDemo={(app) => { setTourApp(null); requestApplications([app]); }} />}
  </div></MarketingLanguageProvider>;
}

function HomePage({ copy }: { copy: MarketingCopy }) {
  return <>
    <section className="mk-hero">
      <div className="mk-grid-decoration" aria-hidden="true" />
      <div className="mk-hero-copy">
        <p className="mk-hero-kicker">{copy.home.kicker}</p>
        <h1>{copy.home.title}<br /><em>{copy.home.titleAccent}</em></h1>
        <p className="mk-lead"><strong>{copy.home.lead}</strong><span>{copy.home.body}</span></p>
        <div className="mk-actions"><MarketingLink className="mk-primary" href="#/producto/contacto">{copy.common.demo} <ArrowRight /></MarketingLink><MarketingLink className="mk-secondary" href="#/producto/solucion">{copy.common.exploreSolution}</MarketingLink></div>
        <ul className="mk-checks">{copy.home.checks.map((item) => <li key={item}><Check weight="bold" /> {item}</li>)}</ul>
      </div>
      <div className="mk-hero-product"><MarketingImage image="contactEcosystem" alt={`esPOS · ${copy.common.applications}`} sizes={marketingImageSizes.home} loading="eager" fetchPriority="high" /><div className="mk-proof-panel" aria-label={copy.growth.proof.title}>{copy.growth.proof.metrics.map(([value, label]) => <span key={label}><strong>{value}</strong><small>{label}</small></span>)}</div></div>
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
      <MarketingLink href="#/producto/solucion">{copy.home.introLink} <ArrowRight /></MarketingLink>
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
    <div className="mk-dark-band"><div><p className="mk-kicker">{copy.solution.bandKicker}</p><h2>{copy.solution.bandTitle}</h2><p>{copy.solution.bandBody}</p><MarketingLink className="mk-primary" href="#/producto/apps">{copy.solution.bandAction} <ArrowRight /></MarketingLink></div><ChartBar weight="duotone" aria-hidden="true" /></div>
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

function AppsPage({ copy, language, onOpenTour, onRequestDemo }: { copy: MarketingCopy; language: MarketingLanguage; onOpenTour: (app: AppId) => void; onRequestDemo: (app: AppId) => void }) {
  return <section className="mk-inner-page mk-apps-page">
    <MarketingHeading kicker={copy.apps.kicker} title={copy.apps.title} accent={copy.apps.accent} body={copy.apps.body} secondaryHref="#/producto/ventajas" secondaryLabel={copy.common.compareOptions} copy={copy} />
    <ApplicationExplorer language={language} applications={appMeta.map(({ id }, index) => ({ id, name: copy.apps.items[index][0], label: copy.apps.items[index][1], description: copy.apps.items[index][2], previewAlt: copy.tour.previewAlts[index] }))} onOpenTour={onOpenTour} onRequestDemo={onRequestDemo} />
  </section>;
}

function AdvantagesPage({ copy }: { copy: MarketingCopy }) {
  return <section className="mk-inner-page mk-advantages-page">
    <MarketingHeading kicker={copy.advantagesPage.kicker} title={copy.advantagesPage.title} accent={copy.advantagesPage.accent} body={copy.advantagesPage.body} secondaryHref="#/producto/apps" secondaryLabel={copy.common.seeApps} copy={copy} />
    <div className="mk-comparison" role="table" aria-label={copy.advantagesPage.kicker}>
      <div className="mk-comparison-row is-head" role="row">{copy.advantagesPage.headers.map((header, index) => index === 3 ? <strong role="columnheader" key={header}>{header}</strong> : <span role="columnheader" key={header}>{header}</span>)}</div>
      {copy.advantagesPage.rows.map(([label, traditional, cloud, ours]) => <div className="mk-comparison-row" role="row" key={label}><strong role="rowheader">{label}</strong><span role="cell" data-label={copy.advantagesPage.headers[1]}>{traditional}</span><span role="cell" data-label={copy.advantagesPage.headers[2]}>{cloud}</span><span className="is-ours" role="cell" data-label={copy.advantagesPage.headers[3]}><Check weight="bold" aria-hidden="true" /> {ours}</span></div>)}
    </div>
    <p className="mk-note">{copy.advantagesPage.note}</p>
  </section>;
}

function MarketingHeading({ kicker, title, accent, body, secondaryHref, secondaryLabel, copy }: { kicker: string; title: string; accent: string; body: string; secondaryHref: string; secondaryLabel: string; copy: MarketingCopy }) {
  return <header className="mk-page-heading">
    <div className="mk-page-heading-title"><p className="mk-kicker">{kicker}</p><h1>{title}<br /><span>{accent}</span></h1></div>
    <div className="mk-page-heading-support">
      <p>{body}</p>
      <div className="mk-heading-actions"><MarketingLink className="mk-primary" href="#/producto/contacto">{copy.common.demo} <ArrowRight /></MarketingLink><MarketingLink href={secondaryHref}>{secondaryLabel} <ArrowRight /></MarketingLink></div>
      <small><ShieldCheck weight="fill" /> {copy.common.demoHint}</small>
    </div>
  </header>;
}

function ContactPage({ copy, language, initialApps, attribution }: { copy: MarketingCopy; language: MarketingLanguage; initialApps: readonly AppId[]; attribution: MarketingAttribution }) {
  const [wechatQrOpen, setWechatQrOpen] = useState(false);
  const [wechatCopyState, setWechatCopyState] = useState<"idle" | "copied" | "error">("idle");
  const wechatIdRef = useRef<HTMLInputElement | null>(null);
  const wechatCopyAttempt = useRef(0);
  const [selectedApps, setSelectedApps] = useState<AppId[]>(() => [...initialApps]);
  const [selectionError, setSelectionError] = useState(false);
  const [submitState, setSubmitState] = useState<"idle" | "submitting" | "success" | "error">("idle");
  const [availability, setAvailability] = useState<"checking" | "available" | "unavailable">("checking");
  const [failure, setFailure] = useState<DemoRequestFailure | null>(null);
  const [receipt, setReceipt] = useState<DemoReceipt | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const availabilityAbort = useRef<AbortController | null>(null);
  const submitAbort = useRef<AbortController | null>(null);
  // Bounded, in-memory attempts retain safe retries even after reversible edits. No PII enters storage.
  const requestAttempts = useRef(createDemoRequestAttempts()).current;
  const errorSummaryRef = useRef<HTMLParagraphElement | null>(null);
  const [faqOpen, setFaqOpen] = useState(false);
  const firstProductRef = useRef<HTMLInputElement | null>(null);
  const fullPackageSelected = selectedApps.length === appMeta.length;

  useEffect(() => {
    // A new requested package invalidates the displayed receipt and any pending
    // response. Aborting locally cannot undo a request already accepted by the service.
    submitAbort.current?.abort();
    submitAbort.current = null;
    setSubmitState("idle");
    setReceipt(null);
    setFailure(null);
    setFieldErrors({});
    setSelectedApps([...initialApps]);
    setSelectionError(false);
  }, [initialApps]);

  async function refreshAvailability() {
    availabilityAbort.current?.abort();
    const controller = new AbortController();
    availabilityAbort.current = controller;
    setAvailability("checking");
    try {
      const available = await checkDemoAvailability(controller.signal);
      if (!controller.signal.aborted) setAvailability(available ? "available" : "unavailable");
    } catch {
      if (!controller.signal.aborted) setAvailability("unavailable");
    }
  }

  useEffect(() => {
    void refreshAvailability();
    return () => { availabilityAbort.current?.abort(); submitAbort.current?.abort(); };
  }, []);

  useEffect(() => {
    if (failure) errorSummaryRef.current?.focus();
  }, [failure]);

  function clearSubmissionFeedback() {
    setReceipt(null);
    setFailure(null);
    setSubmitState("idle");
  }

  function fieldErrorProps(field: string) {
    return { "aria-invalid": fieldErrors[field] ? true : undefined, "aria-describedby": fieldErrors[field] ? `mk-contact-${field}-error` : undefined } as const;
  }

  function fieldError(field: string) {
    return fieldErrors[field] ? <span id={`mk-contact-${field}-error`} className="mk-field-error">{fieldErrors[field]}</span> : null;
  }

  useEffect(() => {
    wechatCopyAttempt.current += 1;
    setWechatCopyState("idle");
    return () => { wechatCopyAttempt.current += 1; };
  }, [language]);

  async function copyWechatId() {
    const attempt = ++wechatCopyAttempt.current;
    setWechatCopyState("idle");
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(marketingContact.wechatId);
      if (attempt === wechatCopyAttempt.current) setWechatCopyState("copied");
    } catch {
      if (attempt !== wechatCopyAttempt.current) return;
      setWechatCopyState("error");
      wechatIdRef.current?.focus();
      wechatIdRef.current?.select();
    }
  }

  function selectApplications(apps: AppId[]) {
    setSelectedApps(apps);
    setSelectionError(false);
    clearSubmissionFeedback();
  }

  function toggleApplication(app: AppId) {
    selectApplications(appMeta.filter(({ id }) => id === app ? !selectedApps.includes(id) : selectedApps.includes(id)).map(({ id }) => id));
  }

  async function prepareDemo(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitAbort.current || submitState === "submitting" || availability !== "available") return;
    if (!selectedApps.length) {
      setSelectionError(true);
      firstProductRef.current?.focus();
      return;
    }
    const form = event.currentTarget;
    const data = new FormData(form);
    const values = Object.fromEntries(Object.keys(demoFieldLimits).map((field) => [field, String(data.get(field) ?? "").trim()]));
    const errors: Record<string, string> = {};
    for (const [field, max] of Object.entries(demoFieldLimits)) {
      if (["name", "company", "email"].includes(field) && !values[field]) errors[field] = copy.contact.delivery.fieldRequired;
      else if (values[field].length > max) errors[field] = copy.contact.delivery.tooLong.replace("{max}", String(max));
    }
    const emailInput = form.elements.namedItem("email") as HTMLInputElement;
    // Trim before native email validation, so accidental surrounding spaces are harmless.
    emailInput.value = values.email;
    if (values.email && emailInput.validity.typeMismatch) errors.email = copy.contact.delivery.emailInvalid;
    if (data.get("privacyAccepted") !== "on") errors.privacyAccepted = copy.contact.delivery.fieldRequired;
    setFieldErrors(errors);
    if (Object.keys(errors).length) {
      const first = form.elements.namedItem(Object.keys(errors)[0]);
      if (first instanceof HTMLElement) first.focus();
      return;
    }
    const products = appMeta.filter(({ id }) => selectedApps.includes(id)).map(({ id }) => `APP_${id.toUpperCase()}`);
    const body = JSON.stringify({
      product: products[0], products, ...values, locale: language,
      privacyAccepted: data.get("privacyAccepted") === "on",
      website: String(data.get("website") ?? "").trim().slice(0, 200), ...attribution
    });
    const idempotencyKey = requestAttempts.keyFor(body);
    const controller = new AbortController();
    submitAbort.current = controller;
    setSubmitState("submitting");
    setFailure(null);
    setReceipt(null);
    try {
      const received = await submitDemoRequest(body, idempotencyKey, controller.signal);
      if (controller.signal.aborted) return;
      form.reset();
      setSelectedApps([...initialApps]);
      setSelectionError(false);
      setFieldErrors({});
      setReceipt(received);
      requestAttempts.complete(body);
      setSubmitState("success");
    } catch (error) {
      if (controller.signal.aborted) return;
      const requestError = error instanceof DemoRequestError ? error : new DemoRequestError("network");
      const serverErrors = Object.fromEntries(Object.entries(requestError.fields).map(([field, code]) => [field,
        code === "REQUIRED" || code === "MUST_ACCEPT" ? copy.contact.delivery.fieldRequired
          : code === "TOO_LONG" && field in demoFieldLimits ? copy.contact.delivery.tooLong.replace("{max}", String(demoFieldLimits[field as keyof typeof demoFieldLimits]))
            : field === "email" ? copy.contact.delivery.emailInvalid : copy.contact.delivery.validation]));
      setFieldErrors(serverErrors);
      setFailure(requestError.kind);
      setSubmitState("error");
    } finally {
      if (submitAbort.current === controller) submitAbort.current = null;
    }
  }

  return <section className="mk-contact-page">
    <MarketingImage image="contactEcosystem" className="mk-contact-scene" alt="" aria-hidden={true} sizes={marketingImageSizes.contact} loading="lazy" fetchPriority="low" />
    <div className="mk-contact-stage">
      <div className="mk-contact-story">
        <p className="mk-kicker">{copy.contact.kicker}</p>
        <h1>{copy.contact.title} <span>{copy.contact.accent}</span></h1>
        <p>{copy.contact.body}</p>
        <section className="mk-wechat-card" role="region" aria-labelledby="mk-wechat-title">
          <WechatLogo className="mk-wechat-logo" weight="fill" aria-hidden="true" />
          <div className="mk-wechat-content">
            <h2 id="mk-wechat-title">{copy.contact.wechat.title}</h2>
            <p className="mk-wechat-account-line"><span>{copy.contact.wechat.accountLabel}: </span><strong className="mk-wechat-account">{marketingContact.wechatName}</strong></p>
            <p className="mk-wechat-body">{copy.contact.wechat.body}</p>
            <label className="mk-visually-hidden" htmlFor="mk-wechat-id">{copy.contact.wechat.idLabel}</label>
            <div className="mk-wechat-id-row">
              <input ref={wechatIdRef} id="mk-wechat-id" className="mk-wechat-id" value={marketingContact.wechatId} readOnly spellCheck={false} autoComplete="off" aria-describedby="mk-wechat-hint mk-wechat-status" onFocus={(event) => event.currentTarget.select()} />
              <button className="mk-wechat-copy" type="button" onClick={copyWechatId}><CopySimple aria-hidden="true" />{copy.contact.wechat.copy}</button>
            </div>
            <p id="mk-wechat-hint" className="mk-wechat-hint">{copy.contact.wechat.hint}</p>
            <p id="mk-wechat-status" className="mk-wechat-status" data-state={wechatCopyState} role="status" aria-live="polite" aria-atomic="true">{wechatCopyState === "copied" ? copy.contact.wechat.copied : wechatCopyState === "error" ? copy.contact.wechat.copyError : ""}</p>
          </div>
          <button className="mk-wechat-qr-trigger" type="button" aria-haspopup="dialog" onClick={() => { setFaqOpen(false); setWechatQrOpen(true); }}>
            <img src={marketingContact.wechatQr} alt={copy.contact.wechat.qrAlt} width={888} height={1131} />
            <span>{copy.contact.wechat.viewQr}</span>
          </button>
        </section>
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

      <form className="mk-contact-form" onSubmit={prepareDemo} noValidate onInput={(event) => {
        if (submitState === "submitting") return;
        clearSubmissionFeedback();
        const target = event.target;
        if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
          setFieldErrors((current) => { const next = { ...current }; delete next[target.name]; return next; });
        }
      }}>
        <p className="mk-contact-form-tag">{copy.contact.formTag}</p>
        <h2>{copy.contact.formTitle}</h2>
        <p>{copy.contact.formBody}</p>
        <div className="mk-delivery-status" data-state={availability}>
          <p role="status" aria-live="polite">{copy.contact.delivery[availability]}</p>
          {availability === "unavailable" && <div className="mk-delivery-actions">
            <button type="button" onClick={() => { void refreshAvailability(); }}>{copy.contact.delivery.retryAvailability}</button>
            <button type="button" onClick={() => setWechatQrOpen(true)}>{copy.contact.delivery.viaWechat}</button>
          </div>}
        </div>
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
          <label>{copy.contact.name}<input name="name" autoComplete="name" required maxLength={demoFieldLimits.name} disabled={submitState === "submitting"} placeholder={copy.contact.namePlaceholder} {...fieldErrorProps("name")} />{fieldError("name")}</label>
          <label>{copy.contact.company}<input name="company" autoComplete="organization" required maxLength={demoFieldLimits.company} disabled={submitState === "submitting"} placeholder={copy.contact.companyPlaceholder} {...fieldErrorProps("company")} />{fieldError("company")}</label>
          <label>{copy.contact.email}<input name="email" type="email" autoComplete="email" required maxLength={demoFieldLimits.email} disabled={submitState === "submitting"} placeholder="name@company.com" {...fieldErrorProps("email")} />{fieldError("email")}</label>
          <label>{copy.contact.phone}<input name="phone" type="tel" autoComplete="tel" maxLength={demoFieldLimits.phone} disabled={submitState === "submitting"} placeholder="600 000 000" {...fieldErrorProps("phone")} />{fieldError("phone")}</label>
        </div>
        <label>{copy.contact.caseLabel}<textarea name="message" rows={3} maxLength={demoFieldLimits.message} disabled={submitState === "submitting"} placeholder={copy.contact.casePlaceholder} {...fieldErrorProps("message")} />{fieldError("message")}</label>
        <label className="mk-contact-honeypot" aria-hidden="true">Website<input name="website" tabIndex={-1} maxLength={200} autoComplete="off" /></label>
        <label className="mk-contact-consent"><input name="privacyAccepted" type="checkbox" required disabled={submitState === "submitting"} {...fieldErrorProps("privacyAccepted")} /> <span>{copy.contact.consent}{fieldError("privacyAccepted")}</span></label>
        <button className="mk-contact-submit" type="submit" disabled={submitState === "submitting" || availability !== "available"} aria-busy={submitState === "submitting"}>{submitState === "submitting" ? copy.contact.submitting : submitState === "error" ? copy.contact.delivery.retrySubmit : copy.contact.submit} <PaperPlaneTilt weight="bold" /></button>
        <button className="mk-contact-talk" type="button" onClick={() => setFaqOpen(true)}><Question weight="bold" /> {copy.contact.faqAction}</button>
        {submitState === "success" && <p className="mk-contact-prepared" role="status">{copy.contact.success}</p>}
        {receipt && <p className="mk-demo-receipt">{copy.contact.delivery.receiptLabel}: <code>{receipt.id}</code></p>}
        {submitState === "error" && failure && <>
          <p ref={errorSummaryRef} className="mk-contact-error mk-form-error-summary" role="alert" tabIndex={-1}>{copy.contact.delivery[failure]}</p>
          <div className="mk-delivery-actions"><button type="button" onClick={() => setWechatQrOpen(true)}>{copy.contact.delivery.viaWechat}</button></div>
        </>}
        <small>{copy.contact.dataUse}</small>
      </form>
    </div>
    {wechatQrOpen && <MarketingDialogLoader kind="wechat" language={language} copy={copy} onClose={() => setWechatQrOpen(false)} />}
    {faqOpen && <MarketingDialogLoader kind="faq" language={language} copy={copy} onClose={() => setFaqOpen(false)} />}
  </section>;
}
