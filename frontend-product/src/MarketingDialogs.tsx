import { ArrowLeft, ArrowRight, Check, CloudArrowUp, Desktop, DeviceMobile, DownloadSimple, ShieldCheck, X } from "@phosphor-icons/react";
import { useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import type { MarketingLanguage } from "./marketingI18n";
import { getMarketingUiContent } from "./marketingUiI18n";
import { getRovingTabIndex } from "./marketingAccessibility";
import { MarketingLink } from "./MarketingNavigation";
import { marketingContact } from "./contact";
import { appMeta, type AppId, type MarketingCopy } from "./applicationMetadata";
import { MarketingModalLayer, useModalDialog } from "./MarketingModal";

const downloadUrls: Partial<Record<AppId, string>> = {
  venta: import.meta.env.VITE_DOWNLOAD_VENTA_URL,
  gestion: import.meta.env.VITE_DOWNLOAD_GESTION_URL
};
const checksumUrls: Partial<Record<AppId, string>> = {
  venta: import.meta.env.VITE_DOWNLOAD_VENTA_SHA256_URL,
  gestion: import.meta.env.VITE_DOWNLOAD_GESTION_SHA256_URL
};
const downloadVersion = (import.meta.env.VITE_DOWNLOAD_RELEASE_VERSION as string | undefined)?.trim() || null;
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

function DownloadCenterModal({ copy, onClose, onRequestSetup }: { copy: MarketingCopy; onClose: () => void; onRequestSetup: (app: AppId) => void }) {
  const { dialogRef, closeRef } = useModalDialog(onClose);
  const deliveryIcons = [Desktop, Desktop, DeviceMobile, CloudArrowUp];

  return <MarketingModalLayer><div className="mk-product-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) { event.preventDefault(); onClose(); } }}>
    <section ref={dialogRef} className="mk-download-dialog" data-testid="download-center" role="dialog" tabIndex={-1} aria-modal="true" aria-labelledby="mk-download-title">
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
            {url ? <div className="mk-download-actions"><MarketingLink className="mk-download-primary" href={url} target="_blank" rel="noreferrer"><DownloadSimple weight="bold" /> {copy.downloads.downloadNow}</MarketingLink>{checksumUrl && <MarketingLink className="mk-download-checksum" href={checksumUrl} target="_blank" rel="noreferrer" title={copy.downloads.checksumHelp}>SHA-256 <ArrowRight /></MarketingLink>}</div> : <button className="mk-download-request" type="button" onClick={() => onRequestSetup(id)}>{copy.downloads.requestSetup} <ArrowRight /></button>}
          </article>;
        })}
      </div>
      <p className="mk-download-safety"><ShieldCheck weight="fill" /> {copy.downloads.safety}</p>
    </section>
  </div></MarketingModalLayer>;
}

function ProductTourModal({ copy, language, initialApp, onClose, onRequestDemo }: { copy: MarketingCopy; language: MarketingLanguage; initialApp: AppId; onClose: () => void; onRequestDemo: (app: AppId) => void }) {
  const [activeApp, setActiveApp] = useState<AppId>(initialApp);
  const [step, setStep] = useState(0);
  const { dialogRef, closeRef } = useModalDialog(onClose);
  const ui = getMarketingUiContent(language);
  const tabId = useId();
  const appTabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const appIndex = appMeta.findIndex((app) => app.id === activeApp);
  const { icon: AppIcon, number } = appMeta[appIndex];
  const [name, label, description, bullets, audience, scope] = copy.apps.items[appIndex];
  const details = copy.tour.stepDetails[appIndex][step];
  const [platform, delivery] = copy.downloads.items[appIndex];

  function selectApp(app: AppId) {
    setActiveApp(app);
    setStep(0);
  }

  function handleAppTabKey(event: ReactKeyboardEvent<HTMLButtonElement>, index: number) {
    const nextIndex = getRovingTabIndex(event.key, index, appMeta.length);
    if (nextIndex === null || event.altKey || event.ctrlKey || event.metaKey) return;
    event.preventDefault();
    selectApp(appMeta[nextIndex].id);
    appTabRefs.current[nextIndex]?.focus({ preventScroll: true });
  }

  return <MarketingModalLayer><div className="mk-product-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) { event.preventDefault(); onClose(); } }}>
    <section ref={dialogRef} className="mk-tour-dialog" data-testid="product-tour" data-active-app={activeApp} role="dialog" tabIndex={-1} aria-modal="true" aria-labelledby="mk-tour-title">
      <header className="mk-product-dialog-header">
        <div><p className="mk-kicker">{copy.tour.kicker}</p><h2 id="mk-tour-title">{copy.tour.title}</h2><p>{copy.tour.body}</p></div>
        <button ref={closeRef} className="mk-dialog-close" type="button" onClick={onClose} aria-label={copy.tour.close}><X weight="bold" /></button>
      </header>
      <div className="mk-tour-tabs" role="tablist" aria-label={ui.tourApplications}>{appMeta.map((app, index) => {
        const TabIcon = app.icon;
        return <button ref={(element) => { appTabRefs.current[index] = element; }} id={`${tabId}-app-${app.id}`} key={app.id} type="button" role="tab" tabIndex={activeApp === app.id ? 0 : -1} aria-selected={activeApp === app.id} aria-controls="mk-tour-panel" onKeyDown={(event) => handleAppTabKey(event, index)} onClick={() => selectApp(app.id)}><TabIcon weight="duotone" aria-hidden="true" />{copy.apps.items[index][0]}</button>;
      })}</div>
      <div id="mk-tour-panel" className="mk-tour-stage" role="tabpanel" aria-labelledby={`${tabId}-app-${activeApp}`} tabIndex={0} onKeyDown={(event) => {
        if (event.altKey || event.ctrlKey || event.metaKey) return;
        if (event.key === "ArrowLeft") { event.preventDefault(); setStep((current) => Math.max(0, current - 1)); }
        if (event.key === "ArrowRight") { event.preventDefault(); setStep((current) => Math.min(2, current + 1)); }
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
            <img key={activeApp} src={`/marketing/tour-${activeApp}.png`} alt={copy.tour.previewAlts[appIndex]} width={activeApp === "pda" ? 920 : 1600} height={activeApp === "pda" ? 1700 : activeApp === "gestion" ? 968 : 1000} decoding="async" />
            <figcaption>{copy.tour.previewNote}</figcaption>
          </figure>
          <div className="mk-tour-context-copy"><p>{name}</p><strong>{description}</strong><dl><div><dt>{copy.apps.detailLabels[0]}</dt><dd>{audience}</dd></div><div><dt>{copy.apps.detailLabels[1]}</dt><dd>{scope}</dd></div><div className="mk-tour-delivery"><dt>{copy.tour.deliveryLabel}</dt><dd>{platform} · {delivery}</dd></div></dl></div>
        </aside>
      </div>
      <footer className="mk-tour-footer">
        <div className="mk-tour-progress" role="group" aria-label={ui.tourSteps}>{bullets.map((bullet, index) => <button key={bullet} type="button" className={index === step ? "is-active" : ""} aria-current={index === step ? "step" : undefined} onClick={() => setStep(index)}><span>0{index + 1}</span>{bullet}</button>)}</div>
        <div className="mk-tour-actions"><button type="button" onClick={() => setStep((current) => Math.max(0, current - 1))} disabled={step === 0}><ArrowLeft /> {copy.tour.previous}</button>{step < 2 ? <button className="is-primary" type="button" onClick={() => setStep((current) => current + 1)}>{copy.tour.next} <ArrowRight /></button> : <button className="is-primary" type="button" onClick={() => onRequestDemo(activeApp)}>{copy.tour.requestDemo} <ArrowRight /></button>}</div>
      </footer>
    </section>
  </div></MarketingModalLayer>;
}

function WechatQrModal({ copy, onClose }: { copy: MarketingCopy; onClose: () => void }) {
  const { dialogRef, closeRef } = useModalDialog(onClose);

  return <MarketingModalLayer><div className="mk-product-overlay mk-wechat-qr-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) { event.preventDefault(); onClose(); } }}>
    <section ref={dialogRef} className="mk-wechat-qr-dialog" role="dialog" tabIndex={-1} aria-modal="true" aria-labelledby="mk-wechat-qr-title" aria-describedby="mk-wechat-qr-hint">
      <header className="mk-product-dialog-header">
        <div><h2 id="mk-wechat-qr-title">{copy.contact.wechat.qrTitle}</h2><p id="mk-wechat-qr-hint">{copy.contact.wechat.qrHint}</p></div>
        <button ref={closeRef} className="mk-dialog-close" type="button" onClick={onClose} aria-label={copy.contact.wechat.closeQr}><X weight="bold" aria-hidden="true" /></button>
      </header>
      <img className="mk-wechat-qr-full" src={marketingContact.wechatQr} alt={copy.contact.wechat.qrAlt} width={888} height={1131} decoding="async" />
    </section>
  </div></MarketingModalLayer>;
}

function ContactFaqModal({ copy, onClose }: { copy: MarketingCopy; onClose: () => void }) {
  const [activeFaq, setActiveFaq] = useState(0);
  const { dialogRef, closeRef } = useModalDialog(onClose);

  return <MarketingModalLayer><div className="mk-faq-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) { event.preventDefault(); onClose(); } }}>
    <section ref={dialogRef} className="mk-faq-dialog" role="dialog" tabIndex={-1} aria-modal="true" aria-labelledby="mk-faq-title">
      <header><div><p className="mk-kicker">{copy.contact.faqLabel}</p><h2 id="mk-faq-title">{copy.contact.faqTitle}</h2><p>{copy.contact.faqHint}</p></div><button ref={closeRef} type="button" onClick={onClose} aria-label={copy.contact.faqClose}><X weight="bold" aria-hidden="true" /></button></header>
      <div className="mk-faq-layout">
        <div className="mk-faq-list" role="group" aria-label={copy.contact.faqTitle}>{copy.contact.faqItems.map(([question], index) => <button key={question} type="button" className={activeFaq === index ? "is-active" : undefined} aria-pressed={activeFaq === index} aria-controls="mk-faq-answer" onClick={() => setActiveFaq(index)}><span>0{index + 1}</span>{question}<ArrowRight aria-hidden="true" /></button>)}</div>
        <article id="mk-faq-answer" className="mk-faq-answer" aria-live="polite"><span>0{activeFaq + 1}</span><h3>{copy.contact.faqItems[activeFaq][0]}</h3><p>{copy.contact.faqItems[activeFaq][1]}</p></article>
      </div>
    </section>
  </div></MarketingModalLayer>;
}


export type MarketingDialogProps =
  | { kind: "downloads"; copy: MarketingCopy; language: MarketingLanguage; onClose: () => void; onRequestSetup: (app: AppId) => void }
  | { kind: "tour"; copy: MarketingCopy; language: MarketingLanguage; initialApp: AppId; onClose: () => void; onRequestDemo: (app: AppId) => void }
  | { kind: "wechat" | "faq"; copy: MarketingCopy; language: MarketingLanguage; onClose: () => void };

export default function MarketingDialogs(props: MarketingDialogProps) {
  if (props.kind === "downloads") return <DownloadCenterModal {...props} />;
  if (props.kind === "tour") return <ProductTourModal {...props} />;
  if (props.kind === "wechat") return <WechatQrModal {...props} />;
  return <ContactFaqModal {...props} />;
}
