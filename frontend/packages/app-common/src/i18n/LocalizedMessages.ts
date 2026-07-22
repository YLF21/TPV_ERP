import type { LocaleCode } from "../types";
import { MessagesEn } from "./MessagesEn";
import { MessagesEs } from "./MessagesEs";
import { MessagesZh } from "./MessagesZh";
import { controlMessages } from "./ControlMessages";
import { securityMessages } from "./SecurityMessages";
import { warehouseManagementMessages } from "./WarehouseMessages";
import { verifactuManagementMessages } from "./VerifactuMessages";
import { rectificationMessages } from "./RectificationMessages";

export const messages: Record<LocaleCode, Record<string, string>> = {
  es: { ...MessagesEs.values, ...controlMessages("es"), ...securityMessages("es"), ...warehouseManagementMessages("es"), ...verifactuManagementMessages("es"), ...rectificationMessages("es") },
  en: { ...MessagesEn.values, ...controlMessages("en"), ...securityMessages("en"), ...warehouseManagementMessages("en"), ...verifactuManagementMessages("en"), ...rectificationMessages("en") },
  zh: { ...MessagesZh.values, ...controlMessages("zh"), ...securityMessages("zh"), ...warehouseManagementMessages("zh"), ...verifactuManagementMessages("zh"), ...rectificationMessages("zh") }
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
