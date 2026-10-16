import { useEffect, useState } from "react";
import MarketingPage from "./MarketingPage";
import type { MarketingLanguage } from "./marketingI18n";
import { useMarketingSeo } from "./seo";
import { marketingHref, readMarketingLocation } from "./marketingRoutes";
import { navigateMarketing } from "./MarketingNavigation";

function readLanguage(): MarketingLanguage {
  const routeLanguage = readMarketingLocation(window.location).language;
  if (routeLanguage) return routeLanguage;
  try {
    const value = localStorage.getItem("tpv-product-language");
    return value === "en" || value === "zh" ? value : "es";
  } catch {
    return "es";
  }
}

export default function App() {
  const [language, setLanguage] = useState<MarketingLanguage>(readLanguage);
  useMarketingSeo(language);

  useEffect(() => {
    const syncLanguage = () => {
      const next = readMarketingLocation(window.location).language;
      if (next) setLanguage(next);
    };
    window.addEventListener("popstate", syncLanguage);
    window.addEventListener("hashchange", syncLanguage);
    return () => {
      window.removeEventListener("popstate", syncLanguage);
      window.removeEventListener("hashchange", syncLanguage);
    };
  }, []);

  function changeLanguage(value: MarketingLanguage) {
    setLanguage(value);
    const { route } = readMarketingLocation(window.location);
    // The URL is shareable and takes precedence over a previously stored preference.
    navigateMarketing(`${marketingHref(value, route)}${window.location.search}`, true);
    try {
      localStorage.setItem("tpv-product-language", value);
    } catch {
      // The website remains usable when browser storage is unavailable.
    }
  }
  return <MarketingPage language={language} onLanguageChange={changeLanguage} />;
}
