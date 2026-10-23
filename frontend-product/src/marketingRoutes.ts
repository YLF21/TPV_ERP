import type { MarketingLanguage } from "./marketingI18n";

export const routeIds = ["inicio", "solucion", "apps", "ventajas", "contacto"] as const;
export type MarketingRoute = typeof routeIds[number];
export const routeAliases = { funcionalidades: "solucion" } as const;
export type MarketingRouteInput = MarketingRoute | keyof typeof routeAliases;
export const marketingLanguages = ["es", "en", "zh"] as const;

/** Old feature URLs display the combined solution, without navigating away or losing queries. */
export function normalizeMarketingRoute(route: MarketingRouteInput): MarketingRoute {
  return route === "funcionalidades" ? "solucion" : route;
}

export function marketingHref(language: MarketingLanguage, route: MarketingRouteInput): string {
  return `/${language}/producto/${normalizeMarketingRoute(route)}/`;
}

/** Hash links from previous releases remain usable; new links have crawlable paths. */
export function readMarketingLocation(location: { pathname?: string; hash?: string }): {
  route: MarketingRoute;
  language: MarketingLanguage | null;
  legacy: boolean;
} {
  const path = (location.pathname ?? "/").match(/^\/(es|en|zh)\/producto\/(inicio|solucion|funcionalidades|apps|ventajas|contacto)\/?$/);
  const legacy = (location.hash ?? "").match(/^#\/?producto(?:\/(inicio|solucion|funcionalidades|apps|ventajas|contacto))?\/?$/);
  if (legacy) return { route: normalizeMarketingRoute((legacy[1] ?? "inicio") as MarketingRouteInput), language: path?.[1] as MarketingLanguage ?? null, legacy: true };
  if (path) return { route: normalizeMarketingRoute(path[2] as MarketingRouteInput), language: path[1] as MarketingLanguage, legacy: false };
  return { route: "inicio", language: null, legacy: false };
}
