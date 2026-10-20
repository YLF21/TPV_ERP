import { useEffect } from "react";
import { productBrand } from "./brand";
import { marketingImages } from "./marketingImages";
import type { MarketingLanguage } from "./marketingI18n";
import { marketingHref, marketingLanguages, normalizeMarketingRoute, readMarketingLocation, type MarketingRoute, type MarketingRouteInput } from "./marketingRoutes";

type SeoCopy = {
  title: string;
  description: string;
};

const seoCopy: Record<MarketingLanguage, Record<MarketingRoute, SeoCopy>> = {
  es: {
    inicio: {
      title: "esPOS | Venta, gestión y stock para comercios",
      description: "Conecta punto de venta, gestión y almacén con una consulta central de la información recibida de tus tiendas vinculadas."
    },
    solucion: {
      title: "Solución y funcionalidades para comercios | esPOS",
      description: "Conecta venta, gestión y almacén. Descubre funciones de caja, facturación, catálogo, clientes, stock e informes para reducir tareas manuales y coordinar tus tiendas."
    },
    apps: {
      title: "Apps de venta, gestión, almacén y SaaS | esPOS",
      description: "Conoce esPOS Venta, Gestión, PDA y SaaS: tareas, conexión, compatibilidad y pasos para preparar la implantación y consultar el soporte."
    },
    ventajas: {
      title: "Ventajas de una gestión local y en la nube | esPOS",
      description: "Compara la operativa con servicio de tienda y la consulta central por sincronización. Revisa conectividad, permisos y aspectos de implantación por confirmar."
    },
    contacto: {
      title: "Solicita una demostración de esPOS",
      description: "Cuéntanos cómo funciona tu comercio y solicita una demostración de esPOS adaptada a tus tiendas, equipos y necesidades."
    }
  },
  en: {
    inicio: {
      title: "esPOS | Sales, management and stock for retailers",
      description: "Connect point of sale, management and warehouse work with a central view of information received from your linked stores."
    },
    solucion: {
      title: "Retail management solution and features | esPOS",
      description: "Connect sales, management and warehouse operations. Explore checkout, billing, catalogue, customers, stock and reporting tools to reduce manual work and coordinate stores."
    },
    apps: {
      title: "Sales, management, warehouse and SaaS apps | esPOS",
      description: "Explore esPOS Sales, Management, PDA and SaaS: tasks, connectivity, compatibility and how to prepare rollout and discuss support."
    },
    ventajas: {
      title: "Local and cloud retail management benefits | esPOS",
      description: "Compare store-service operations with a synchronised central view. Review connectivity, permissions and rollout requirements for your business."
    },
    contacto: {
      title: "Request an esPOS demonstration",
      description: "Tell us how your retail business works and request an esPOS demonstration adapted to your stores, teams and needs."
    }
  },
  zh: {
    inicio: {
      title: "esPOS｜零售销售、管理与库存一体化",
      description: "连接收银、业务管理与仓库，并集中查询从已关联门店收到的信息。"
    },
    solucion: {
      title: "零售管理解决方案与功能｜esPOS",
      description: "连接销售、业务管理与仓库，了解收银、开票、商品、客户、库存及报表功能，减少手工操作并协同管理多家门店。"
    },
    apps: {
      title: "销售、管理、仓库与 SaaS 应用｜esPOS",
      description: "了解 esPOS 销售、管理、PDA 和 SaaS 的任务、连接与兼容要求，以及准备实施和咨询支持的步骤。"
    },
    ventajas: {
      title: "本地与云端零售管理优势｜esPOS",
      description: "比较门店服务与同步后的中央查询方式，了解网络、权限及需按业务情况确认的实施事项。"
    },
    contacto: {
      title: "申请 esPOS 产品演示",
      description: "介绍您的门店、团队和运营需求，申请符合实际业务场景的 esPOS 产品演示。"
    }
  }
};

const softwareFeatures: Record<MarketingLanguage, string[]> = {
  es: ["Punto de venta y caja", "Gestión de catálogo y clientes", "Control de almacén y stock", "Visión central multi-tienda"],
  en: ["Point of sale and checkout", "Catalogue and customer management", "Warehouse and stock control", "Central multi-store visibility"],
  zh: ["销售与收银", "商品与客户管理", "仓库与库存控制", "多门店集中视图"]
};

/** Only a deliberate public HTTPS origin can enable indexing and canonical URLs. */
export function safePublicSiteUrl(value?: string | null): URL | null {
  if (!value?.trim()) return null;
  try {
    const url = new URL(value.trim());
    const host = url.hostname.toLowerCase();
    if (url.protocol !== "https:" || url.username || url.password || url.port || url.search || url.hash || url.pathname !== "/") return null;
    if (!/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,62}$/.test(host)) return null;
    if (/\.(?:localhost|local|internal|lan|home|corp|test|invalid|example|arpa|onion)$/.test(host)) return null;
    if (/(?:^|\.)example\.(?:com|org|net)$/.test(host)) return null;
    return url;
  } catch {
    return null;
  }
}

export type MarketingMetadata = SeoCopy & {
  language: MarketingLanguage;
  htmlLang: string;
  ogLocale: string;
  ogLocaleAlternates: string[];
  robots: string;
  canonical: string | null;
  alternates: Array<{ language: string; href: string }>;
  image: string | null;
  imageAlt: string;
  structuredData: Record<string, unknown>;
};

const locales: Record<MarketingLanguage, string> = { es: "es_ES", en: "en_GB", zh: "zh_CN" };

export function buildMarketingMetadata(language: MarketingLanguage, routeInput: MarketingRouteInput, configuredUrl?: string | null, indexable = false): MarketingMetadata {
  const route = normalizeMarketingRoute(routeInput);
  const publicUrl = safePublicSiteUrl(configuredUrl);
  const canonical = publicUrl ? new URL(marketingHref(language, route), publicUrl).href : null;
  const graph: Record<string, unknown>[] = [{
    "@type": "WebPage",
    name: seoCopy[language][route].title,
    description: seoCopy[language][route].description,
    inLanguage: language === "zh" ? "zh-CN" : language,
    ...(canonical ? { url: canonical, "@id": `${canonical}#webpage` } : {})
  }];
  // No corporate identity, prices, ratings or FAQ rich-result claims are invented.
  if (route === "inicio") graph.push({
    "@type": "SoftwareApplication",
    name: productBrand.name,
    applicationCategory: "BusinessApplication",
    operatingSystem: "Windows, Web",
    description: seoCopy[language][route].description,
    featureList: softwareFeatures[language],
    ...(canonical ? { url: canonical } : {})
  });
  return {
    ...seoCopy[language][route], language,
    htmlLang: language === "zh" ? "zh-CN" : language,
    ogLocale: locales[language],
    ogLocaleAlternates: marketingLanguages.filter(value => value !== language).map(value => locales[value]),
    robots: publicUrl && indexable ? "index, follow" : "noindex, follow",
    canonical,
    alternates: publicUrl ? [
      ...marketingLanguages.map(value => ({ language: value === "zh" ? "zh-CN" : value, href: new URL(marketingHref(value, route), publicUrl).href })),
      { language: "x-default", href: new URL(marketingHref("es", route), publicUrl).href }
    ] : [],
    image: publicUrl ? new URL(marketingImages.contactEcosystem.src, publicUrl).href : null,
    imageAlt: `${productBrand.name} · ${softwareFeatures[language].join(" · ")}`,
    structuredData: { "@context": "https://schema.org", "@graph": graph }
  };
}

function escapeAttribute(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

export function renderMarketingHead(metadata: MarketingMetadata): string {
  const meta = (attribute: "name" | "property", key: string, value: string) => `<meta data-marketing-seo ${attribute}="${key}" content="${escapeAttribute(value)}" />`;
  const json = JSON.stringify(metadata.structuredData).replaceAll("<", "\\u003c").replaceAll(">", "\\u003e").replaceAll("&", "\\u0026").replaceAll("\u2028", "\\u2028").replaceAll("\u2029", "\\u2029");
  return [
    `<title data-marketing-seo>${escapeAttribute(metadata.title)}</title>`,
    meta("name", "description", metadata.description),
    meta("name", "application-name", productBrand.name),
    meta("name", "robots", metadata.robots),
    meta("property", "og:title", metadata.title),
    meta("property", "og:description", metadata.description),
    meta("property", "og:type", "website"),
    meta("property", "og:site_name", productBrand.name),
    meta("property", "og:locale", metadata.ogLocale),
    ...metadata.ogLocaleAlternates.map(locale => meta("property", "og:locale:alternate", locale)),
    meta("name", "twitter:card", metadata.image ? "summary_large_image" : "summary"),
    meta("name", "twitter:title", metadata.title),
    meta("name", "twitter:description", metadata.description),
    ...(metadata.canonical ? [
      `<link data-marketing-seo rel="canonical" href="${escapeAttribute(metadata.canonical)}" />`,
      meta("property", "og:url", metadata.canonical)
    ] : []),
    ...metadata.alternates.map(alternate => `<link data-marketing-seo rel="alternate" hreflang="${alternate.language}" href="${escapeAttribute(alternate.href)}" />`),
    ...(metadata.image ? [meta("property", "og:image", metadata.image), meta("property", "og:image:alt", metadata.imageAlt), meta("name", "twitter:image", metadata.image), meta("name", "twitter:image:alt", metadata.imageAlt)] : []),
    `<script data-marketing-seo id="espos-structured-data" type="application/ld+json">${json}</script>`
  ].join("\n");
}

function updateMetadata(language: MarketingLanguage) {
  const route = readMarketingLocation(window.location).route;
  const metadata = buildMarketingMetadata(language, route, import.meta.env.VITE_PUBLIC_SITE_URL, import.meta.env.VITE_PUBLIC_INDEXABLE === "true");
  document.documentElement.lang = metadata.htmlLang;
  // Remove stale static / previous-release metadata as well as our managed tags.
  document.head.querySelectorAll('[data-marketing-seo], title, meta[name="description"], meta[name="application-name"], meta[name="robots"], meta[property^="og:"], meta[name^="twitter:"], link[rel="canonical"], link[rel="alternate"][hreflang], #tpv-structured-data').forEach(element => element.remove());
  document.head.insertAdjacentHTML("beforeend", renderMarketingHead(metadata));
}

export function useMarketingSeo(language: MarketingLanguage) {
  useEffect(() => {
    const syncMetadata = () => updateMetadata(language);
    syncMetadata();
    window.addEventListener("hashchange", syncMetadata);
    window.addEventListener("popstate", syncMetadata);
    return () => {
      window.removeEventListener("hashchange", syncMetadata);
      window.removeEventListener("popstate", syncMetadata);
    };
  }, [language]);
}
