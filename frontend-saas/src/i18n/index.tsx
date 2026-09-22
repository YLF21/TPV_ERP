import { createContext, useContext } from "react";
import { es } from "./es";
import { en } from "./en";
import { zh } from "./zh";

export type Language = "es" | "en" | "zh";

export let activeLocale = "es-ES";

export const LANGUAGE_OPTIONS: Array<{ value: Language; label: string; short: string }> = [
  { value: "es", label: "Español", short: "ES" },
  { value: "en", label: "English", short: "EN" },
  { value: "zh", label: "中文", short: "ZH" }
];

export const TRANSLATIONS: Record<Language, Record<string, string>> = { es, en, zh };

export const reportedTranslationFallbacks = new Set<string>();

export function translate(language: Language, key: string) {
  const translated = TRANSLATIONS[language][key];
  if (translated) return translated;
  if (language !== "es" && !reportedTranslationFallbacks.has(`${language}:${key}`)) {
    reportedTranslationFallbacks.add(`${language}:${key}`);
    console.warn(`[i18n] Missing ${language} translation for "${key}", using Spanish fallback.`);
  }
  return (language === "zh" ? TRANSLATIONS.en[key] : undefined) ?? TRANSLATIONS.es[key] ?? key;
}

export const I18nContext = createContext<{
  language: Language;
  setLanguage: (language: Language) => void;
  t: (key: string) => string;
}>({
  language: "es",
  setLanguage: () => undefined,
  t: (key) => TRANSLATIONS.es[key] ?? key
});

export function useI18n() {
  return useContext(I18nContext);
}

export function localeFor(language: Language) {
  return language === "zh" ? "zh-CN" : language === "en" ? "en-GB" : "es-ES";
}

export function readLanguage(): Language {
  const value = localStorage.getItem("tpv-saas-language");
  return value === "en" || value === "zh" || value === "es" ? value : "es";
}

export function setActiveLocale(locale: string) { activeLocale = locale; }
