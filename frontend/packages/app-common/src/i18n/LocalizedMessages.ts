import type { LocaleCode } from "../types";
import { MessagesEn } from "./MessagesEn";
import { MessagesEs } from "./MessagesEs";
import { MessagesZh } from "./MessagesZh";

export const messages: Record<LocaleCode, Record<string, string>> = {
  es: MessagesEs.values,
  en: MessagesEn.values,
  zh: MessagesZh.values
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
