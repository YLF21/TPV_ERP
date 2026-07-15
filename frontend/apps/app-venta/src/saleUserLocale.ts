import { useCallback, useState } from "react";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";

const defaultLocale: LocaleCode = "es";
const supportedLocales = new Set<LocaleCode>(["es", "en", "zh"]);

function browserStorage(storage?: Storage) {
  if (storage) return storage;
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

export function saleUserLocaleStorageKey(session: UserSession) {
  const identity = (session.userId?.trim() || session.username.trim()).toLowerCase();
  return `tpv-erp:venta:user:${encodeURIComponent(identity)}:locale`;
}

export function readSaleUserLocale(session: UserSession, storage?: Storage): LocaleCode {
  try {
    const value = browserStorage(storage)?.getItem(saleUserLocaleStorageKey(session));
    return value != null && supportedLocales.has(value as LocaleCode) ? value as LocaleCode : defaultLocale;
  } catch {
    return defaultLocale;
  }
}

export function saveSaleUserLocale(session: UserSession, locale: LocaleCode, storage?: Storage) {
  try {
    browserStorage(storage)?.setItem(saleUserLocaleStorageKey(session), locale);
  } catch {
    // The current session still keeps the selected locale in memory.
  }
}

export function useSaleUserLocalePreference(storage?: Storage) {
  const [locale, setLocale] = useState<LocaleCode>(defaultLocale);
  const applyUserLocale = useCallback((session: UserSession) => {
    setLocale(readSaleUserLocale(session, storage));
  }, [storage]);
  const changeLocale = useCallback((session: UserSession | null, next: LocaleCode) => {
    setLocale(next);
    if (session) saveSaleUserLocale(session, next, storage);
  }, [storage]);
  const resetLocale = useCallback(() => setLocale(defaultLocale), []);

  return { locale, applyUserLocale, changeLocale, resetLocale };
}
