import { useEffect } from "react";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";

type MarketingRoute = "inicio" | "solucion" | "funcionalidades" | "apps" | "ventajas" | "contacto";

type SeoCopy = {
  title: string;
  description: string;
};

const routes: MarketingRoute[] = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];

const seoCopy: Record<MarketingLanguage, Record<MarketingRoute, SeoCopy>> = {
  es: {
    inicio: {
      title: "TPV ERP | Venta, gestión y stock para comercios",
      description: "Conecta el punto de venta, la gestión, el almacén y el control multi-tienda en una plataforma preparada para la operativa diaria."
    },
    solucion: {
      title: "Solución conectada para comercios | TPV ERP",
      description: "Une mostrador, oficina, almacén y dirección para reducir tareas manuales y mantener una visión completa de cada tienda."
    },
    funcionalidades: {
      title: "Funcionalidades de venta, stock y gestión | TPV ERP",
      description: "Descubre herramientas para caja, facturación, catálogo, clientes, almacén, permisos, trazabilidad e informes de negocio."
    },
    apps: {
      title: "Apps de venta, gestión, almacén y SaaS | TPV ERP",
      description: "Conoce APP Venta, APP Gestión, APP PDA y APP SaaS: herramientas especializadas que comparten la misma información operativa."
    },
    ventajas: {
      title: "Ventajas de una gestión local y en la nube | TPV ERP",
      description: "Combina una operativa local ágil con sincronización, control central multi-tienda, seguridad y acompañamiento durante la implantación."
    },
    contacto: {
      title: "Solicita una demostración de TPV ERP",
      description: "Cuéntanos cómo funciona tu comercio y solicita una demostración de TPV ERP adaptada a tus tiendas, equipos y necesidades."
    }
  },
  en: {
    inicio: {
      title: "TPV ERP | Sales, management and stock for retailers",
      description: "Connect point of sale, management, warehouse and multi-store control in one platform designed for everyday retail operations."
    },
    solucion: {
      title: "Connected retail management solution | TPV ERP",
      description: "Bring checkout, back office, warehouse and management together to reduce manual work and keep a complete view of every store."
    },
    funcionalidades: {
      title: "Sales, stock and management features | TPV ERP",
      description: "Explore checkout, billing, catalogue, customer, warehouse, permissions, traceability and business reporting capabilities."
    },
    apps: {
      title: "Sales, management, warehouse and SaaS apps | TPV ERP",
      description: "Meet APP Sales, APP Management, APP PDA and APP SaaS: focused tools that share the same operational information."
    },
    ventajas: {
      title: "Local and cloud retail management benefits | TPV ERP",
      description: "Combine responsive local operations with synchronization, central multi-store visibility, security and rollout support."
    },
    contacto: {
      title: "Request a TPV ERP demonstration",
      description: "Tell us how your retail business works and request a TPV ERP demonstration adapted to your stores, teams and needs."
    }
  },
  zh: {
    inicio: {
      title: "TPV ERP｜零售销售、管理与库存一体化",
      description: "将收银、业务管理、仓库和多门店控制连接在同一平台，满足零售企业的日常运营需求。"
    },
    solucion: {
      title: "互联的零售管理解决方案｜TPV ERP",
      description: "连接收银台、办公室、仓库和管理层，减少手工操作，并全面掌握每家门店的运营情况。"
    },
    funcionalidades: {
      title: "销售、库存与管理功能｜TPV ERP",
      description: "了解收银、开票、商品、客户、仓库、权限、操作追踪和业务报告等功能。"
    },
    apps: {
      title: "销售、管理、仓库与 SaaS 应用｜TPV ERP",
      description: "了解 APP Venta、APP Gestión、APP PDA 和 APP SaaS：面向不同团队并共享同一运营数据的工具。"
    },
    ventajas: {
      title: "本地与云端零售管理优势｜TPV ERP",
      description: "兼顾顺畅的本地运营、数据同步、多门店集中视图、安全保障和实施支持。"
    },
    contacto: {
      title: "申请 TPV ERP 产品演示",
      description: "介绍您的门店、团队和运营需求，申请符合实际业务场景的 TPV ERP 产品演示。"
    }
  }
};

const softwareFeatures: Record<MarketingLanguage, string[]> = {
  es: ["Punto de venta y caja", "Gestión de catálogo y clientes", "Control de almacén y stock", "Visión central multi-tienda"],
  en: ["Point of sale and checkout", "Catalogue and customer management", "Warehouse and stock control", "Central multi-store visibility"],
  zh: ["销售与收银", "商品与客户管理", "仓库与库存控制", "多门店集中视图"]
};

function readRoute(): MarketingRoute {
  const candidate = window.location.hash.replace(/^#\/?producto\/?/, "").split("/")[0];
  return routes.includes(candidate as MarketingRoute) ? candidate as MarketingRoute : "inicio";
}

function readPublicSiteUrl(): URL | null {
  const configuredUrl = import.meta.env.VITE_PUBLIC_SITE_URL?.trim();
  if (!configuredUrl) return null;

  try {
    const url = new URL(configuredUrl);
    if (url.protocol !== "https:" && url.protocol !== "http:") return null;
    url.search = "";
    url.hash = "";
    return url;
  } catch {
    return null;
  }
}

function upsertMeta(selector: string, attribute: "name" | "property", key: string, content: string) {
  let element = document.head.querySelector<HTMLMetaElement>(selector);
  if (!element) {
    element = document.createElement("meta");
    element.setAttribute(attribute, key);
    document.head.appendChild(element);
  }
  element.setAttribute("content", content);
}

function updateCanonical(route: MarketingRoute, publicSiteUrl: URL | null) {
  const existing = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
  if (!publicSiteUrl) {
    existing?.remove();
    return null;
  }

  const canonical = new URL(publicSiteUrl.toString());
  canonical.pathname = canonical.pathname.replace(/\/$/, "") || "/";
  canonical.hash = `/producto/${route}`;

  const element = existing ?? document.createElement("link");
  element.rel = "canonical";
  element.href = canonical.toString();
  if (!existing) document.head.appendChild(element);
  return canonical.toString();
}

function updateStructuredData(language: MarketingLanguage, description: string, publicSiteUrl: URL | null) {
  const content = getMarketingContent(language);
  const organization: Record<string, unknown> = {
    "@type": "Organization",
    "@id": "#organization",
    name: "TPV ERP"
  };
  const softwareApplication: Record<string, unknown> = {
    "@type": "SoftwareApplication",
    name: "TPV ERP",
    applicationCategory: "BusinessApplication",
    operatingSystem: "Windows, Web",
    description,
    featureList: softwareFeatures[language],
    publisher: { "@id": "#organization" }
  };

  if (publicSiteUrl) {
    const rootUrl = publicSiteUrl.toString();
    organization.url = rootUrl;
    organization["@id"] = `${rootUrl}#organization`;
    softwareApplication.publisher = { "@id": `${rootUrl}#organization` };
    softwareApplication.url = rootUrl;
  }

  const structuredData = {
    "@context": "https://schema.org",
    "@graph": [
      organization,
      softwareApplication,
      {
        "@type": "FAQPage",
        mainEntity: content.contact.faqItems.map(([question, answer]) => ({
          "@type": "Question",
          name: question,
          acceptedAnswer: {
            "@type": "Answer",
            text: answer
          }
        }))
      }
    ]
  };

  let script = document.head.querySelector<HTMLScriptElement>("#tpv-structured-data");
  if (!script) {
    script = document.createElement("script");
    script.id = "tpv-structured-data";
    script.type = "application/ld+json";
    document.head.appendChild(script);
  }
  script.textContent = JSON.stringify(structuredData);
}

function updateMetadata(language: MarketingLanguage) {
  const route = readRoute();
  const metadata = seoCopy[language][route];
  const publicSiteUrl = readPublicSiteUrl();

  document.title = metadata.title;
  document.documentElement.lang = language === "zh" ? "zh-CN" : language;
  upsertMeta('meta[name="description"]', "name", "description", metadata.description);
  upsertMeta('meta[property="og:title"]', "property", "og:title", metadata.title);
  upsertMeta('meta[property="og:description"]', "property", "og:description", metadata.description);
  upsertMeta('meta[property="og:type"]', "property", "og:type", "website");
  upsertMeta('meta[name="twitter:card"]', "name", "twitter:card", "summary");

  const canonical = updateCanonical(route, publicSiteUrl);
  const existingOgUrl = document.head.querySelector<HTMLMetaElement>('meta[property="og:url"]');
  if (canonical) upsertMeta('meta[property="og:url"]', "property", "og:url", canonical);
  else existingOgUrl?.remove();

  updateStructuredData(language, metadata.description, publicSiteUrl);
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
