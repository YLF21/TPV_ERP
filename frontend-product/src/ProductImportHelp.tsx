import { CaretDown } from "@phosphor-icons/react";
import type { MarketingLanguage } from "./marketingI18n";
import { getProductImportHelp } from "./productImportHelpContent";

export function ProductImportHelp({ language }: { language: MarketingLanguage }) {
  const copy = getProductImportHelp(language);
  return <details className="mk-service-disclosure" data-testid="product-import-help">
    <summary><span><strong>{copy.title}</strong></span><CaretDown aria-hidden="true" /></summary>
    <div className="mk-service-content">
      <div className="mk-service-grid">{copy.steps.map(([title, body]) => <article key={title}><h3>{title}</h3><p>{body}</p></article>)}</div>
      <a className="mk-service-action" href="/guides/espos-product-import-sample.xlsx" download>{copy.download}</a>
      <p className="mk-service-note">{copy.note}</p>
    </div>
  </details>;
}
