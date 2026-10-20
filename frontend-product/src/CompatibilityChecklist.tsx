import { CaretDown } from "@phosphor-icons/react";
import { useId } from "react";
import { appGuideOrder } from "./applicationGuides";
import type { ApplicationId } from "./applicationGuideTypes";
import { getCompatibilityContent } from "./compatibilityContent";
import type { MarketingLanguage } from "./marketingI18n";

type CompatibilityChecklistProps = {
  language: MarketingLanguage;
  application?: ApplicationId;
  includeRequirements?: boolean;
};

export function CompatibilityChecklist({ language, application, includeRequirements = true }: CompatibilityChecklistProps) {
  const instanceId = useId();
  const copy = getCompatibilityContent(language);
  const applications = application ? [application] : appGuideOrder;

  return <details className="mk-service-disclosure" data-testid="compatibility-checklist" data-app={application ?? "all"}>
    <summary>
      <span><strong>{copy.title}</strong><span>{copy.subtitle}</span></span>
      <CaretDown aria-hidden="true" />
    </summary>
    <div className="mk-service-content">
      <p className="mk-service-note">{copy.verificationNote}</p>
      <div className="mk-service-grid">
        {applications.map((id) => {
          const app = copy.apps[id];
          const titleId = `${instanceId}-${id}-compatibility`;
          return <article key={id} data-application={id} aria-labelledby={titleId}>
            <h3 id={titleId}>{app.name}</h3>
            <dl className="mk-service-facts">
              {includeRequirements && <div data-status="documented">
                <dt>{copy.documentedLabel}</dt>
                <dd><ul>{app.requirements.map((requirement) => <li key={requirement}>{requirement}</li>)}</ul></dd>
              </div>}
              <div data-status="to-confirm">
                <dt>{copy.toConfirmLabel}</dt>
                <dd><ul>{app.toConfirm.map((item) => <li key={item}>{item}</li>)}</ul></dd>
              </div>
            </dl>
          </article>;
        })}
      </div>
      <section className="mk-service-prepare" aria-labelledby={`${instanceId}-prepare`}>
        <h3 id={`${instanceId}-prepare`}>{copy.prepareTitle}</h3>
        <ul>{copy.prepareItems.map((item) => <li key={item}>{item}</li>)}</ul>
        <p className="mk-service-note">{copy.privacyNote}</p>
      </section>
    </div>
  </details>;
}
