import { appMeta, type AppId } from "./applicationMetadata";
import type { DemoReceipt } from "./demoRequests";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";

const content = {
  es: { title: "Solicitud recibida", received: "Recibida", apps: "Aplicaciones solicitadas", copy: "Copiar resumen", copied: "Resumen copiado.", manual: "No se ha podido copiar. Selecciona el resumen para copiarlo manualmente.", summary: "Resumen de la solicitud" },
  en: { title: "Request received", received: "Received", apps: "Requested apps", copy: "Copy summary", copied: "Summary copied.", manual: "Could not copy. Select the summary to copy it manually.", summary: "Request summary" },
  zh: { title: "申请已接收", received: "接收时间", apps: "申请应用", copy: "复制摘要", copied: "摘要已复制。", manual: "无法复制。请选中摘要后手动复制。", summary: "申请摘要" }
} as const;

export function getDemoReceiptContent(language: MarketingLanguage) {
  return { ...content[language], reference: getMarketingContent(language).contact.delivery.receiptLabel };
}

export function demoReceiptDetails(receipt: DemoReceipt, applications: readonly AppId[], language: MarketingLanguage) {
  const locale = { es: "es-ES", en: "en-GB", zh: "zh-CN" }[language];
  const date = new Intl.DateTimeFormat(locale, { timeZone: "Europe/Madrid", year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", timeZoneName: "short" }).format(new Date(receipt.receivedAt));
  const items = getMarketingContent(language).apps.items;
  const apps = appMeta.flatMap(({ id }, index) => applications.includes(id) ? [items[index][0]] : []).join(", ");
  return { date, apps };
}

/** Only the receipt and application names are copied; no form fields or storage. */
export function formatDemoReceiptSummary(receipt: DemoReceipt, applications: readonly AppId[], language: MarketingLanguage) {
  const copy = getDemoReceiptContent(language);
  const { date, apps } = demoReceiptDetails(receipt, applications, language);
  return `${copy.reference}: ${receipt.id}\n${copy.received}: ${date}\n${copy.apps}: ${apps}`;
}
