import { createContext, useContext, type ComponentPropsWithoutRef } from "react";
import type { MarketingLanguage } from "./marketingI18n";
import { marketingHref, readMarketingLocation, routeAliases, routeIds, type MarketingRouteInput } from "./marketingRoutes";

export const demoFormHash = "#mk-demo-form";

export function readMarketingNavigation(location: { pathname?: string; search?: string; hash?: string }) {
  const { route, language, legacy } = readMarketingLocation(location);
  return { route, language, search: location.search ?? "", target: legacy ? "" : location.hash ?? "" };
}

export function isLanguageOnlyNavigation(previous: ReturnType<typeof readMarketingNavigation>, next: ReturnType<typeof readMarketingNavigation>) {
  return previous.route === next.route && previous.language !== next.language && previous.search === next.search && previous.target === next.target;
}

export function focusDemoForm(page: HTMLElement | null) {
  const heading = page?.querySelector<HTMLElement>(demoFormHash);
  if (!heading) return false;
  heading.focus({ preventScroll: true });
  heading.scrollIntoView({ block: "start", behavior: "instant" });
  return true;
}

const MarketingLanguageContext = createContext<MarketingLanguage>("es");
export const MarketingLanguageProvider = MarketingLanguageContext.Provider;

/** Keep legacy link declarations compatible while rendering ordinary crawlable URLs. */
export function MarketingLink({ href, demo, ...props }: ComponentPropsWithoutRef<"a"> & { demo?: boolean }) {
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
  return <a {...props} href={demo && destination ? `${destination}${demoFormHash}` : destination} />;
}

export function navigateMarketing(href: string, replace = false) {
  if (replace) window.history.replaceState(null, "", href);
  else window.history.pushState(null, "", href);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
