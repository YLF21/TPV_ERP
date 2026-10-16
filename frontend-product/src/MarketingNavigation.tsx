import { createContext, useContext, type ComponentPropsWithoutRef } from "react";
import type { MarketingLanguage } from "./marketingI18n";
import { marketingHref, routeIds, type MarketingRoute } from "./marketingRoutes";

const MarketingLanguageContext = createContext<MarketingLanguage>("es");
export const MarketingLanguageProvider = MarketingLanguageContext.Provider;

/** Keep legacy link declarations compatible while rendering ordinary crawlable URLs. */
export function MarketingLink({ href, ...props }: ComponentPropsWithoutRef<"a">) {
  const language = useContext(MarketingLanguageContext);
  const legacyRoute = href?.match(/^#\/producto\/([a-z]+)$/)?.[1];
  const destination = legacyRoute && routeIds.includes(legacyRoute as MarketingRoute)
    ? marketingHref(language, legacyRoute as MarketingRoute)
    : href;
  return <a {...props} href={destination} />;
}

export function navigateMarketing(href: string, replace = false) {
  if (replace) window.history.replaceState(null, "", href);
  else window.history.pushState(null, "", href);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
