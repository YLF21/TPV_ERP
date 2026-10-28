import { ArrowRight, CaretDown } from "@phosphor-icons/react";
import { useId } from "react";
import { applicationChoiceScenarios, getApplicationChoices } from "./applicationChoices";
import { appMeta, type AppId } from "./applicationMetadata";
import { marketingContactSearch } from "./marketingCampaign";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";
import { marketingHref } from "./marketingRoutes";
import "./application-choices.css";

type ApplicationChooserProps = {
  language: MarketingLanguage;
  onRequestApplications: (applications: readonly AppId[]) => void;
};

export function ApplicationChooser({ language, onRequestApplications }: ApplicationChooserProps) {
  const instanceId = useId();
  const copy = getApplicationChoices(language);
  const applications = getMarketingContent(language).apps.items;
  const applicationName = (id: AppId) => applications[appMeta.findIndex((application) => application.id === id)][0];

  return <details id="mk-home-applications" className="mk-application-chooser" data-testid="application-chooser">
    <summary>
      <span><strong>{copy.title}</strong><span>{copy.subtitle}</span></span>
      <CaretDown aria-hidden="true" />
    </summary>
    <div className="mk-application-choice-content">
      <div className="mk-application-choice-grid">
        {applicationChoiceScenarios.map((scenario) => {
          const scenarioCopy = copy.scenarios[scenario.id];
          const titleId = `${instanceId}-${scenario.id}-title`;
          return <article key={scenario.id} data-choice={scenario.id} aria-labelledby={titleId}>
            <h2 id={titleId}>{scenarioCopy.title}</h2>
            <ul className="mk-application-choice-apps" aria-label={scenarioCopy.title}>
              {scenario.applications.map((application) => <li key={application}>{applicationName(application)}</li>)}
            </ul>
            <p>{scenarioCopy.body}</p>
            <p className="mk-application-choice-dependency"><strong>{copy.dependenciesLabel}: </strong>{scenarioCopy.dependency}</p>
            <a
              data-testid={`application-choice-${scenario.id}`}
              href={`${marketingHref(language, "contacto")}${marketingContactSearch(scenario.applications)}#mk-demo-form`}
              aria-label={`${copy.requestAction}: ${scenarioCopy.title}`}
              onClick={(event) => {
                if (event.defaultPrevented || event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
                event.preventDefault();
                onRequestApplications(scenario.applications);
              }}
            >{copy.requestAction}<ArrowRight aria-hidden="true" /></a>
          </article>;
        })}
      </div>
      <p className="mk-application-choice-note">{copy.note}</p>
    </div>
  </details>;
}
