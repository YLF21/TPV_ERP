import { CaretDown } from "@phosphor-icons/react";
import { connectedScenarioApps, getConnectedScenario } from "./connectedScenarioContent";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";

export function ConnectedScenario({ language }: { language: MarketingLanguage }) {
  const copy = getConnectedScenario(language);
  const apps = getMarketingContent(language).apps.items;
  return <details className="mk-service-disclosure" data-testid="connected-scenario">
    <summary><span><strong>{copy.title}</strong><span>{copy.intro}</span></span><CaretDown aria-hidden="true" /></summary>
    <div className="mk-service-content"><ol className="mk-service-steps">
      {connectedScenarioApps.map((id, index) => <li key={id} data-scenario-app={id}><h3>{apps[index][0]}</h3><p>{copy.steps[id]}</p></li>)}
    </ol></div>
  </details>;
}
