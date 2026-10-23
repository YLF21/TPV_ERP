import { useId } from "react";
import { getConnectionContent } from "./connectionContent";
import type { MarketingLanguage } from "./marketingI18n";
import "./connection-summary.css";

export function ConnectionSummary({ language }: { language: MarketingLanguage }) {
  const titleId = useId();
  const copy = getConnectionContent(language);
  return <section className="mk-connection-summary" data-testid="connection-summary" aria-labelledby={titleId}>
    <header><h2 id={titleId}>{copy.title}</h2><p>{copy.body}</p></header>
    <dl>{copy.items.map(item => <div key={item.title}><dt>{item.title}</dt><dd>{item.body}</dd></div>)}</dl>
  </section>;
}
