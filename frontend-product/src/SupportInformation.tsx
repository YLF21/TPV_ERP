import { CaretDown } from "@phosphor-icons/react";
import { marketingContact } from "./contact";
import type { MarketingLanguage } from "./marketingI18n";
import { marketingHref } from "./marketingRoutes";
import { getSupportContent } from "./supportContent";

export function SupportInformation({ language, onContactWechat, showAccount = true }: {
  language: MarketingLanguage;
  onContactWechat?: () => void;
  showAccount?: boolean;
}) {
  const copy = getSupportContent(language);

  return <details className="mk-service-disclosure" data-testid="support-information">
    <summary>
      <span className="mk-service-summary"><strong>{copy.title}</strong><span>{copy.summary}</span></span>
      <CaretDown className="mk-service-caret" aria-hidden="true" />
    </summary>
    <div className="mk-service-content">
      <p>{copy.intro}</p>
      <section>
        <h3>{copy.channelsTitle}</h3>
        <p>{copy.wechatBody}</p>
        {showAccount && <dl className="mk-service-facts"><div><dt>{copy.wechatLabel}</dt><dd>{marketingContact.wechatName} · <bdi>{marketingContact.wechatId}</bdi></dd></div></dl>}
        <p>{copy.saasBody}</p>
      </section>
      <div className="mk-service-grid">
        {copy.sections.map(section => <section key={section.title}>
          <h3>{section.title}</h3>
          <ul>{section.items.map(item => <li key={item}>{item}</li>)}</ul>
        </section>)}
      </div>
      <section className="mk-service-note">
        <h3>{copy.receiptTitle}</h3>
        <p>{copy.receiptBody}</p>
        <p>{copy.unavailableBody}</p>
      </section>
      {onContactWechat
        ? <button type="button" className="mk-service-action" onClick={onContactWechat}>{copy.wechatAction}</button>
        : <a className="mk-service-action" href={marketingHref(language, "contacto")}>{copy.contactAction}</a>}
    </div>
  </details>;
}
