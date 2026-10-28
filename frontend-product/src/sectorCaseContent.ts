import type { AppId } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";

export const sectorApplications = {
  retail: ["venta", "gestion"],
  grocery: ["venta", "gestion", "pda"],
  fashion: ["venta", "gestion"],
  franchise: ["venta", "gestion", "pda", "saas"]
} as const satisfies Record<string, readonly AppId[]>;

const content = {
  es: { open: "Ver caso y aplicaciones", applications: "Aplicaciones orientativas", action: "Consultar esta opción", note: "Ejemplo orientativo, no testimonio ni paquete contratado. El alcance se confirma." },
  en: { open: "Explore this use case", applications: "Suggested applications", action: "Discuss this option", note: "An illustrative example, not a testimonial or purchased package. Scope needs confirmation." },
  zh: { open: "查看场景与应用", applications: "建议了解的应用", action: "咨询此方案", note: "此场景仅供参考，并非客户评价或已购买的套餐。具体范围需另行确认。" }
} satisfies Record<MarketingLanguage, Record<string, string>>;

export function getSectorCaseContent(language: MarketingLanguage) {
  return content[language];
}
