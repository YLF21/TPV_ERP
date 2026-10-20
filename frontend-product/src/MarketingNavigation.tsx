import { createContext, useContext, type ComponentPropsWithoutRef } from "react";
import type { MarketingLanguage } from "./marketingI18n";
import { marketingHref, routeAliases, routeIds, type MarketingRouteInput } from "./marketingRoutes";

const MarketingLanguageContext = createContext<MarketingLanguage>("es");
export const MarketingLanguageProvider = MarketingLanguageContext.Provider;

/** Keep legacy link declarations compatible while rendering ordinary crawlable URLs. */
export function MarketingLink({ href, ...props }: ComponentPropsWithoutRef<"a">) {
  const language = useContext(MarketingLanguageContext);
  const legacyRoute = href?.match(/^#\/producto\/([a-z]+)\/?(\?[^#]*)?$/);
  const legacyRouteId = legacyRoute?.[1];
  const knownRoute = legacyRouteId && (routeIds.some(route => route === legacyRouteId) || Object.hasOwn(routeAliases, legacyRouteId));
  const cleanAlias = href?.match(/^\/(es|en|zh)\/producto\/funcionalidades\/?([?#].*)?$/);
  const destination = knownRoute
    ? `${marketingHref(language, legacyRouteId as MarketingRouteInput)}${legacyRoute?.[2] ?? ""}`
    : cleanAlias
      ? `${marketingHref(cleanAlias[1] as MarketingLanguage, "solucion")}${cleanAlias[2] ?? ""}`
    : href;
  return <a {...props} href={destination} />;
}

export function navigateMarketing(href: string, replace = false) {
  if (replace) window.history.replaceState(null, "", href);
  else window.history.pushState(null, "", href);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
