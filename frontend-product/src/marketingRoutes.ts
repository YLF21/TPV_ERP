import type { MarketingLanguage } from "./marketingI18n";

export const routeIds = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"] as const;
export type MarketingRoute = typeof routeIds[number];
export const marketingLanguages = ["es", "en", "zh"] as const;

export function marketingHref(language: MarketingLanguage, route: MarketingRoute): string {
  return `/${language}/producto/${route}/`;
}

/** Hash links from previous releases remain usable; new links have crawlable paths. */
export function readMarketingLocation(location: { pathname?: string; hash?: string }): {
  route: MarketingRoute;
  language: MarketingLanguage | null;
  legacy: boolean;
} {
  const path = (location.pathname ?? "/").match(/^\/(es|en|zh)\/producto\/(inicio|solucion|funcionalidades|apps|ventajas|contacto)\/?$/);
  const legacy = (location.hash ?? "").match(/^#\/?producto(?:\/(inicio|solucion|funcionalidades|apps|ventajas|contacto))?\/?$/);
  if (legacy) return { route: (legacy[1] ?? "inicio") as MarketingRoute, language: path?.[1] as MarketingLanguage ?? null, legacy: true };
  if (path) return { route: path[2] as MarketingRoute, language: path[1] as MarketingLanguage, legacy: false };
  return { route: "inicio", language: null, legacy: false };
}
