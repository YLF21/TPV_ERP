import type { LocaleCode } from "../types";
import { MessagesEn } from "./MessagesEn";
import { MessagesEs } from "./MessagesEs";
import { MessagesZh } from "./MessagesZh";
import { controlMessages } from "./ControlMessages";
import { securityMessages } from "./SecurityMessages";
import { warehouseManagementMessages } from "./WarehouseMessages";

export const messages: Record<LocaleCode, Record<string, string>> = {
  es: { ...MessagesEs.values, ...controlMessages("es"), ...securityMessages("es"), ...warehouseManagementMessages("es") },
  en: { ...MessagesEn.values, ...controlMessages("en"), ...securityMessages("en"), ...warehouseManagementMessages("en") },
  zh: { ...MessagesZh.values, ...controlMessages("zh"), ...securityMessages("zh"), ...warehouseManagementMessages("zh") }
};

export class LocalizedMessages {
  static readonly values = messages;

  static createTranslator(locale: LocaleCode) {
    return (key: string) => LocalizedMessages.values[locale][key] ?? key;
  }
}

export function createTranslator(locale: LocaleCode) {
  return LocalizedMessages.createTranslator(locale);
}
