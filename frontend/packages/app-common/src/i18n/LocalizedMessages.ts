import type { LocaleCode } from "../types";
import { MessagesEn } from "./MessagesEn";
import { MessagesEs } from "./MessagesEs";
import { MessagesZh } from "./MessagesZh";
import { controlMessages } from "./ControlMessages";
import { securityMessages } from "./SecurityMessages";
import { warehouseManagementMessages } from "./WarehouseMessages";
import { verifactuManagementMessages } from "./VerifactuMessages";
import { rectificationMessages } from "./RectificationMessages";
import { sharedManagementMessages } from "./SharedManagementMessages";
import { salesOperationSecurityMessages } from "./SalesOperationSecurityMessages";
import { cashClosureMessages } from "./CashClosureMessages";
import { cashCurrentBalanceMessages } from "./CashCurrentBalanceMessages";
import { licenseMessages } from "./LicenseMessages";
import type { AppKind } from "../types";

declare const __TPV_APP_KIND__: AppKind | "test";

// These catalogs belong to APP GESTIÓN. Keep them available to tests and the
// management/PDA applications, but let Vite remove them from APP VENTA's
// initial graph where they can never be displayed.
const buildAppKind = typeof __TPV_APP_KIND__ === "undefined" ? "test" : __TPV_APP_KIND__;
const managementMessages = buildAppKind === "venta"
  ? { es: {}, en: {}, zh: {} }
  : {
      es: { ...verifactuManagementMessages("es"), ...licenseMessages("es") },
      en: { ...verifactuManagementMessages("en"), ...licenseMessages("en") },
      zh: { ...verifactuManagementMessages("zh"), ...licenseMessages("zh") }
    };

export const messages: Record<LocaleCode, Record<string, string>> = {
  es: { ...MessagesEs.values, ...controlMessages("es"), ...securityMessages("es"), ...warehouseManagementMessages("es"), ...managementMessages.es, ...rectificationMessages("es"), ...sharedManagementMessages("es"), ...salesOperationSecurityMessages("es"), ...cashClosureMessages("es"), ...cashCurrentBalanceMessages("es") },
  en: { ...MessagesEn.values, ...controlMessages("en"), ...securityMessages("en"), ...warehouseManagementMessages("en"), ...managementMessages.en, ...rectificationMessages("en"), ...sharedManagementMessages("en"), ...salesOperationSecurityMessages("en"), ...cashClosureMessages("en"), ...cashCurrentBalanceMessages("en") },
  zh: { ...MessagesZh.values, ...controlMessages("zh"), ...securityMessages("zh"), ...warehouseManagementMessages("zh"), ...managementMessages.zh, ...rectificationMessages("zh"), ...sharedManagementMessages("zh"), ...salesOperationSecurityMessages("zh"), ...cashClosureMessages("zh"), ...cashCurrentBalanceMessages("zh") }
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
