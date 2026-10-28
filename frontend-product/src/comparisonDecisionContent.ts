import type { AppId } from "./applicationMetadata";
import { getApplicationChoices } from "./applicationChoices";
import { getApplicationGuides } from "./applicationGuides";
import { getConnectionContent } from "./connectionContent";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";
import { getSupportContent } from "./supportContent";

export const comparisonDecisionQuestions = [
  { id: "connection", applications: ["venta", "gestion"] },
  { id: "central", applications: ["saas"] },
  { id: "warehouse", applications: ["gestion", "pda"] },
  { id: "scope", applications: ["venta", "gestion", "pda", "saas"] }
] as const satisfies readonly { id: string; applications: readonly AppId[] }[];

const content = {
  es: {
    warehouse: "¿Qué necesita el equipo de almacén?", scope: "¿Licencias, periféricos y copias incluidos?"
  },
  en: {
    warehouse: "What does the warehouse team need?", scope: "Are licences, peripherals and backups included?"
  },
  zh: {
    warehouse: "仓库团队需要什么？", scope: "许可证、外设与备份是否包含？"
  }
} as const;

export function getComparisonDecisionContent(language: MarketingLanguage) {
  const copy = content[language];
  const connection = getConnectionContent(language);
  const choices = getApplicationChoices(language);
  const marketing = getMarketingContent(language).contact;
  return {
    title: marketing.faqLabel, action: choices.requestAction,
    questions: {
      connection: { title: marketing.faqItems[0][0], answer: marketing.faqItems[0][1] },
      central: { title: marketing.faqItems[1][0], answer: connection.apps.saas },
      warehouse: { title: copy.warehouse, answer: `${getApplicationGuides(language).apps.pda.requirements[0]} ${connection.apps.pda}` },
      scope: { title: copy.scope, answer: `${choices.note} ${getSupportContent(language).sections[2].items[0]}` }
    }
  };
}
