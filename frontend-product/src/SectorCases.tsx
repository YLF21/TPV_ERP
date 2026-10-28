import { CaretDown, Check } from "@phosphor-icons/react";
import { useId } from "react";
import { appMeta, type AppId, type MarketingCopy } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";
import { marketingContactSearch } from "./marketingCampaign";
import { marketingHref } from "./marketingRoutes";
import { getSectorCaseContent, sectorApplications } from "./sectorCaseContent";

export function SectorCases({ copy, language, onRequestApplications }: {
  copy: MarketingCopy;
  language: MarketingLanguage;
  onRequestApplications?: (applications: readonly AppId[]) => void;
}) {
  const labels = getSectorCaseContent(language);
  const instanceId = useId();
  return <div className="mk-home-sector-preview" aria-label={copy.growth.sectors.title}>
    {copy.growth.sectors.items.map(([id, label, headline, body, bullets]) => {
      const applications = sectorApplications[id];
      const titleId = `${instanceId}-${id}`;
      return <article key={id} data-sector={id} aria-labelledby={titleId}>
        <p>{label}</p><h3 id={titleId}>{headline}</h3>
        <small><Check weight="bold" aria-hidden="true" />{bullets[0]}</small>
        <details className="mk-service-disclosure" data-testid={`sector-case-${id}`}>
          <summary aria-label={`${labels.open}: ${label}`}><span><strong>{labels.open}</strong></span><CaretDown aria-hidden="true" /></summary>
          <div className="mk-service-content">
            <p>{body}</p>
            <ul>{bullets.slice(1).map(bullet => <li key={bullet}>{bullet}</li>)}</ul>
            <p><strong>{labels.applications}: </strong>{applications.map(app => copy.apps.items[appMeta.findIndex(item => item.id === app)][0]).join(" + ")}</p>
            <p className="mk-service-note">{labels.note}</p>
            <a className="mk-service-action" href={`${marketingHref(language, "contacto")}${marketingContactSearch(applications)}#mk-demo-form`} aria-label={`${labels.action}: ${label}`} onClick={event => {
              if (!onRequestApplications || event.defaultPrevented || event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
              event.preventDefault();
              onRequestApplications(applications);
            }}>{labels.action}</a>
          </div>
        </details>
      </article>;
    })}
  </div>;
}
