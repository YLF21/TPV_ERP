import { Check, Question } from "@phosphor-icons/react";
import type { MarketingCopy } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";

// The copy's row order is guarded in comparison-status.contract.test.mjs.
// Classify capabilities explicitly, never infer their status from translated text.
export const comparisonCapabilities = [
  { id: "store-connectivity", status: "documented" },
  { id: "central-store-view", status: "documented" },
  { id: "connected-applications", status: "documented" },
  { id: "warehouse-devices", status: "documented" },
  { id: "operational-data", status: "documented" },
  { id: "backups", status: "to-confirm" },
  { id: "adding-stores", status: "to-confirm" },
  { id: "users-permissions", status: "documented" },
  { id: "fiscal-setup", status: "to-confirm" }
] as const;

const presentation = {
  es: {
    documented: "Función documentada",
    "to-confirm": "Por confirmar",
    note: "Una función documentada no implica que el módulo o servicio esté incluido."
  },
  en: {
    documented: "Documented feature",
    "to-confirm": "To confirm",
    note: "A documented feature does not mean the module or service is included."
  },
  zh: {
    documented: "已记录功能",
    "to-confirm": "需确认",
    note: "已记录功能不代表已包含相应模块或服务。"
  }
} as const;

export function getComparisonPresentation(language: MarketingLanguage) {
  return presentation[language];
}

export function AdvantagesComparison({ copy, language }: { copy: MarketingCopy["advantagesPage"]; language: MarketingLanguage }) {
  const labels = getComparisonPresentation(language);
  if (copy.rows.length !== comparisonCapabilities.length) {
    throw new Error("Comparison rows must have an explicit capability status");
  }

  return <>
    <div className="mk-comparison" role="table" aria-label={copy.kicker} aria-describedby="mk-comparison-scope">
      <div className="mk-comparison-row is-head" role="row">{copy.headers.map((header, index) => index === 3 ? <strong role="columnheader" key={header}>{header}</strong> : <span role="columnheader" key={header}>{header}</span>)}</div>
      {copy.rows.map(([label, traditional, cloud, ours], index) => {
        const { id, status } = comparisonCapabilities[index];
        const StatusIcon = status === "documented" ? Check : Question;
        return <div className="mk-comparison-row" role="row" key={id} data-capability={id}>
          <strong role="rowheader">{label}</strong>
          <span role="cell" data-label={copy.headers[1]}>{traditional}</span>
          <span role="cell" data-label={copy.headers[2]}>{cloud}</span>
          <span className="is-ours" role="cell" data-label={copy.headers[3]} data-status={status}>
            <StatusIcon weight="bold" aria-hidden="true" />
            <span><small>{labels[status]}: </small>{ours}</span>
          </span>
        </div>;
      })}
    </div>
    <p className="mk-note"><span id="mk-comparison-scope">{labels.note}</span> {copy.note}</p>
  </>;
}
