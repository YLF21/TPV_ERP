import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer, loadEnv } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const escapeXml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");

/** Only transform trusted build output; never fetch a page or start a browser. */
export function buildPrerenderDocument(template, markup, metadata, head) {
  if (!/<div\b[^>]*\bid=["']root["'][^>]*>\s*<\/div>/.test(template)) throw new Error("Built template must contain an empty #root");
  const cleaned = template
    .replace(/<title\b[^>]*>[\s\S]*?<\/title>/gi, "")
    .replace(/<meta\b[^>]*(?:name=["'](?:description|application-name|robots|twitter:[^"']*)["']|property=["']og:[^"']*["'])[^>]*>/gi, "")
    .replace(/<link\b[^>]*rel=["'](?:canonical|alternate)["'][^>]*>/gi, "")
    .replace(/<script\b[^>]*type=["']application\/ld\+json["'][^>]*>[\s\S]*?<\/script>/gi, "");
  return cleaned
    .replace(/<html\b[^>]*>/i, `<html lang="${metadata.htmlLang}">`)
    .replace(/<\/head>/i, () => `${head}\n</head>`)
    .replace(/<div\b[^>]*\bid=["']root["'][^>]*>\s*<\/div>/, () => `<div id="root" data-prerendered="true">${markup}</div>`);
}

export function buildSitemap(entries) {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${entries.map(entry => `  <url><loc>${escapeXml(entry.canonical)}</loc>${entry.alternates.map(alternate => `<xhtml:link rel="alternate" hreflang="${alternate.language}" href="${escapeXml(alternate.href)}" />`).join("")}</url>`).join("\n")}\n</urlset>\n`;
}

export function buildRobots(sitemapUrl = null) {
  // Crawlers must be able to read the noindex tag on unlaunched previews.
  return `User-agent: *\nAllow: /\n${sitemapUrl ? `\nSitemap: ${sitemapUrl}\n` : "\n# Indexing is disabled by page metadata until explicitly enabled.\n"}`;
}

export async function prerender() {
  const dist = path.join(root, "dist");
  const template = await readFile(path.join(dist, "index.html"), "utf8");
  const env = loadEnv("production", root, "VITE_");
  const publicSiteUrl = env.VITE_PUBLIC_SITE_URL;
  const indexable = env.VITE_PUBLIC_INDEXABLE === "true";
  const server = await createServer({
    configFile: false, root, mode: "production", plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  const previous = new Map(["window", "document", "sessionStorage", "localStorage"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  try {
    if (server.httpServer !== null) throw new Error("Prerender must not create an HTTP listener");
    const [{ default: MarketingPage }, { routeIds, marketingLanguages, marketingHref }, { buildMarketingMetadata, renderMarketingHead, safePublicSiteUrl }] = await Promise.all([
      server.ssrLoadModule("/src/MarketingPage.tsx"),
      server.ssrLoadModule("/src/marketingRoutes.ts"),
      server.ssrLoadModule("/src/seo.ts")
    ]);
    const entries = [];
    const origin = safePublicSiteUrl(publicSiteUrl);
    if (publicSiteUrl && !origin) console.warn("SEO: VITE_PUBLIC_SITE_URL is not a public HTTPS origin; indexing and canonical URLs remain disabled.");
    const render = (language, route) => {
      const location = { origin: origin?.origin ?? "http://localhost", pathname: marketingHref(language, route), hash: "", search: "" };
      const storage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
      Object.defineProperty(globalThis, "window", { configurable: true, value: { location } });
      Object.defineProperty(globalThis, "document", { configurable: true, value: { referrer: "" } });
      Object.defineProperty(globalThis, "sessionStorage", { configurable: true, value: storage });
      Object.defineProperty(globalThis, "localStorage", { configurable: true, value: storage });
      const markup = renderToString(createElement(MarketingPage, { language, onLanguageChange: () => {} }));
      const metadata = buildMarketingMetadata(language, route, publicSiteUrl, indexable);
      if (!/<main\b/.test(markup) || !/<h1\b/.test(markup)) throw new Error(`Missing semantic page content: ${language}/${route}`);
      return { html: buildPrerenderDocument(template, markup, metadata, renderMarketingHead(metadata)), metadata };
    };
    for (const language of marketingLanguages) {
      for (const route of routeIds) {
        const { html, metadata } = render(language, route);
        const directory = path.join(dist, language, "producto", route);
        await mkdir(directory, { recursive: true });
        await writeFile(path.join(directory, "index.html"), html);
        entries.push(metadata);
      }
    }
    await writeFile(path.join(dist, "index.html"), render("es", "inicio").html);
    if (origin && indexable) {
      await writeFile(path.join(dist, "sitemap.xml"), buildSitemap(entries));
      await writeFile(path.join(dist, "robots.txt"), buildRobots(new URL("/sitemap.xml", origin).href));
    } else {
      // This exact generated file may remain after a previous explicitly indexable build.
      await rm(path.join(dist, "sitemap.xml"), { force: true });
      await writeFile(path.join(dist, "robots.txt"), buildRobots());
    }
    console.log(`Prerender: 19 semantic HTML documents generated; indexing ${origin && indexable ? "explicitly enabled" : "disabled (safe default)"}.`);
  } finally {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await server.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  prerender().catch(error => { console.error(error); process.exitCode = 1; });
}
