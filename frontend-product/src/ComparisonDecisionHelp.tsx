import { CaretDown } from "@phosphor-icons/react";
import { appMeta } from "./applicationMetadata";
import { comparisonDecisionQuestions, getComparisonDecisionContent } from "./comparisonDecisionContent";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";
import { marketingContactSearch } from "./marketingCampaign";
import { marketingHref } from "./marketingRoutes";

export function ComparisonDecisionHelp({ language }: { language: MarketingLanguage }) {
  const copy = getComparisonDecisionContent(language);
  const apps = getMarketingContent(language).apps.items;
  return <details className="mk-service-disclosure" data-testid="comparison-decision-help">
    <summary><span><strong>{copy.title}</strong></span><CaretDown aria-hidden="true" /></summary>
    <div className="mk-service-content">
      <div className="mk-service-grid">{comparisonDecisionQuestions.map(({ id, applications }) => {
        const question = copy.questions[id];
        return <article key={id} data-decision={id}>
          <h3>{question.title}</h3><p>{question.answer}</p>
          <p><strong>{applications.map(app => apps[appMeta.findIndex(item => item.id === app)][0]).join(" + ")}</strong></p>
          <a className="mk-service-action" href={`${marketingHref(language, "contacto")}${marketingContactSearch(applications)}#mk-demo-form`} aria-label={`${copy.action}: ${question.title}`}>{copy.action}</a>
        </article>;
      })}</div>
    </div>
  </details>;
}
