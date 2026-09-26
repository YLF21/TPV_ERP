import { useState } from "react";
import MarketingPage from "./MarketingPage";
import type { MarketingLanguage } from "./marketingI18n";

function readLanguage(): MarketingLanguage {
  try {
    const value = localStorage.getItem("tpv-product-language");
    return value === "en" || value === "zh" ? value : "es";
  } catch {
    return "es";
  }
}

export default function App() {
  const [language, setLanguage] = useState<MarketingLanguage>(readLanguage);
  function changeLanguage(value: MarketingLanguage) {
    setLanguage(value);
    try {
      localStorage.setItem("tpv-product-language", value);
    } catch {
      // The website remains usable when browser storage is unavailable.
    }
  }
  return <MarketingPage language={language} onLanguageChange={changeLanguage} />;
}
