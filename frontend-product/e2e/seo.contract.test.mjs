import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";
import { buildPrerenderDocument, buildRobots, buildSitemap } from "../scripts/prerender.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const origin = "https://public.espos-retail.com";
const escapeText = value => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

test("SEO, routing and semantic prerender contracts without browser or HTTP", async suite => {
  const server = await createServer({
    configFile: false, root, plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  const previous = new Map(["window", "document", "sessionStorage", "localStorage"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  try {
    assert.equal(server.httpServer, null);
    const [routes, seo, { default: MarketingPage }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/marketingRoutes.ts"),
      server.ssrLoadModule("/src/seo.ts"),
      server.ssrLoadModule("/src/MarketingPage.tsx"),
      server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    await suite.test("15 distinct canonical routes and compatibility aliases resolve without redirects", () => {
      const hrefs = [];
      for (const language of routes.marketingLanguages) {
        for (const route of routes.routeIds) {
          const href = routes.marketingHref(language, route);
          hrefs.push(href);
          assert.match(href, /^\/(es|en|zh)\/producto\/[a-z]+\/$/);
          assert.deepEqual(routes.readMarketingLocation({ pathname: href }), { route, language, legacy: false });
          assert.deepEqual(routes.readMarketingLocation({ pathname: href.slice(0, -1) }), { route, language, legacy: false });
          assert.deepEqual(routes.readMarketingLocation({ pathname: href, hash: "#/producto/apps" }), { route: "apps", language, legacy: true });
        }
      }
      assert.deepEqual(routes.routeIds, ["inicio", "solucion", "apps", "ventajas", "contacto"]);
      assert.equal(new Set(hrefs).size, 15);
      for (const language of routes.marketingLanguages) {
        assert.equal(routes.marketingHref(language, "funcionalidades"), routes.marketingHref(language, "solucion"));
        const location = { pathname: `/${language}/producto/funcionalidades/`, hash: "", search: "?apps=venta&utm_source=legacy" };
        assert.deepEqual(routes.readMarketingLocation(location), { route: "solucion", language, legacy: false });
        assert.deepEqual(routes.readMarketingLocation({ ...location, pathname: location.pathname.slice(0, -1) }), { route: "solucion", language, legacy: false });
        assert.equal(location.search, "?apps=venta&utm_source=legacy", "Resolving an alias must not mutate its query");
        assert.equal(location.pathname, `/${language}/producto/funcionalidades/`, "Aliases render directly instead of navigating");
        assert.deepEqual(routes.readMarketingLocation({ pathname: routes.marketingHref(language, "apps"), hash: "#/producto/funcionalidades" }), { route: "solucion", language, legacy: true });
      }
      assert.deepEqual(routes.readMarketingLocation({ pathname: "/", hash: "#/producto/funcionalidades" }), { route: "solucion", language: null, legacy: true });
      assert.deepEqual(routes.readMarketingLocation({ pathname: "/", hash: "#/producto/contacto" }), { route: "contacto", language: null, legacy: true });
      assert.deepEqual(routes.readMarketingLocation({ pathname: "/en/producto/apps/", hash: "#mk-main-content" }), { route: "apps", language: "en", legacy: false });
      assert.deepEqual(routes.readMarketingLocation({ pathname: "/unknown", hash: "#/producto/unknown" }), { route: "inicio", language: null, legacy: false });
    });
    await suite.test("public origin validation rejects local, reserved and unsafe configurations", () => {
      assert.equal(seo.safePublicSiteUrl(origin)?.origin, origin);
      assert.equal(seo.safePublicSiteUrl(` ${origin}/ `)?.origin, origin);
      for (const value of [undefined, "", "http://real-business.com", "https://localhost", "https://127.0.0.1", "https://[::1]", "https://2130706433", "https://intranet", "https://shop.local", "https://store.internal", "https://foo.test", "https://example.com", "https://sub.example.org", "https://foo.invalid", "https://real-business.com:8443", "https://user:pass@real-business.com", "https://real-business.com/prefix", "https://real-business.com/?preview=true", "https://real-business.com/#/producto/apps", "javascript:alert(1)"]) {
        assert.equal(seo.safePublicSiteUrl(value), null, `Unsafe origin accepted: ${value}`);
      }
    });
    await suite.test("indexing requires both a public origin and an explicit release gate", () => {
      assert.equal(seo.buildMarketingMetadata("es", "inicio").robots, "noindex, follow");
      assert.equal(seo.buildMarketingMetadata("es", "inicio", origin).robots, "noindex, follow");
      assert.equal(seo.buildMarketingMetadata("es", "inicio", undefined, true).robots, "noindex, follow");
      assert.equal(seo.buildMarketingMetadata("es", "inicio", "https://localhost", true).canonical, null);
      assert.equal(seo.buildMarketingMetadata("es", "inicio", origin, true).robots, "index, follow");
      const unconfigured = seo.renderMarketingHead(seo.buildMarketingMetadata("es", "inicio"));
      assert.doesNotMatch(unconfigured, /rel="canonical"|hreflang=|property="og:url"|property="og:image"|name="twitter:image"/);
      assert.match(unconfigured, /name="robots" content="noindex, follow"/);
    });
    await suite.test("all locale metadata has reciprocal canonical alternatives, social tags and honest schema", () => {
      const titles = [];
      for (const language of routes.marketingLanguages) {
        for (const route of routes.routeIds) {
          const metadata = seo.buildMarketingMetadata(language, route, origin, true);
          titles.push(metadata.title);
          assert.equal(metadata.canonical, origin + routes.marketingHref(language, route));
          assert.doesNotMatch(metadata.canonical, /#/);
          assert.equal(metadata.alternates.length, 4);
          assert.equal(metadata.alternates.find(item => item.language === "x-default").href, `${origin}/es/producto/${route}/`);
          assert.deepEqual(metadata.alternates.map(item => item.language), ["es", "en", "zh-CN", "x-default"]);
          assert.equal(metadata.ogLocaleAlternates.length, 2);
          assert.ok(!metadata.ogLocaleAlternates.includes(metadata.ogLocale));
          const head = seo.renderMarketingHead(metadata);
          for (const key of ["og:title", "og:description", "og:url", "og:locale", "og:image", "og:image:alt", "twitter:title", "twitter:description", "twitter:image"]) assert.ok(head.includes(`="${key}"`), `Missing ${key}`);
          const graph = metadata.structuredData["@graph"];
          assert.equal(graph[0]["@type"], "WebPage");
          assert.equal(graph.filter(item => item["@type"] === "SoftwareApplication").length, route === "inicio" ? 1 : 0);
          assert.doesNotMatch(JSON.stringify(metadata.structuredData), /Organization|FAQPage|aggregateRating|review|offers|price|publisher/);
          assert.equal((head.match(/rel="canonical"/g) ?? []).length, 1);
          assert.equal((head.match(/hreflang=/g) ?? []).length, 4);
        }
      }
      assert.equal(new Set(titles).size, 15);
      for (const language of routes.marketingLanguages) {
        const canonical = seo.buildMarketingMetadata(language, "solucion", origin, true);
        const alias = seo.buildMarketingMetadata(language, "funcionalidades", origin, true);
        assert.deepEqual(alias, canonical, `${language} alias must share the solution canonical, alternates and content`);
        assert.doesNotMatch(seo.renderMarketingHead(alias), /\/producto\/funcionalidades\//);
      }
    });
    await suite.test("head and XML serialization escapes markup and script endings", () => {
      const metadata = seo.buildMarketingMetadata("es", "apps", origin, true);
      metadata.title = '<img src=x onerror="alert(1)">';
      metadata.description = 'A & B "quoted"';
      metadata.structuredData.test = "</script><script>alert(1)</script>";
      const head = seo.renderMarketingHead(metadata);
      assert.match(head, /&lt;img/);
      assert.match(head, /A &amp; B &quot;quoted&quot;/);
      assert.doesNotMatch(head, /<img|<script>alert/);
      const json = head.match(/type="application\/ld\+json">([\s\S]*)<\/script>/)[1];
      assert.equal(JSON.parse(json).test, metadata.structuredData.test);
      assert.doesNotMatch(json, /</);
      assert.match(buildSitemap([{ canonical: "https://safe.business/?a=1&b=2", alternates: [] }]), /a=1&amp;b=2/);
    });
    await suite.test("15 canonical SSR pages contain the correct route and locale instead of a blank shell", () => {
      const template = '<!doctype html><html lang="es"><head><meta charset="UTF-8"><title>Old</title><meta name="robots" content="index"><meta property="og:title" content="old"><link rel="icon" href="/branding/espos-icon.ico"><script type="module" src="/assets/app.js"></script></head><body><div id="root"></div></body></html>';
      const routeHeading = (copy, route) => ({ inicio: copy.home.title, solucion: copy.solution.title, apps: copy.apps.title, ventajas: copy.advantagesPage.title, contacto: copy.contact.title })[route];
      for (const language of routes.marketingLanguages) {
        for (const route of routes.routeIds) {
          Object.defineProperty(globalThis, "window", { configurable: true, value: { location: { origin: "http://localhost", pathname: routes.marketingHref(language, route), hash: "", search: "" } } });
          Object.defineProperty(globalThis, "document", { configurable: true, value: { referrer: "" } });
          for (const key of ["sessionStorage", "localStorage"]) Object.defineProperty(globalThis, key, { configurable: true, value: { getItem: () => null, setItem: () => {} } });
          const markup = renderToString(createElement(MarketingPage, { language, onLanguageChange: () => {} }));
          const metadata = seo.buildMarketingMetadata(language, route);
          const html = buildPrerenderDocument(template, markup, metadata, seo.renderMarketingHead(metadata));
          assert.match(html, /data-prerendered="true"/);
          assert.match(html, /<main\b/);
          assert.equal((html.match(/<h1\b/g) ?? []).length, 1);
          assert.ok(html.includes(escapeText(routeHeading(getMarketingContent(language), route))), `Missing ${language}/${route} heading`);
          assert.match(html, new RegExp(`<html lang="${language === "zh" ? "zh-CN" : language}">`));
          assert.equal((html.match(/<title\b/g) ?? []).length, 1);
          assert.equal((html.match(/name="robots"/g) ?? []).length, 1);
          assert.ok(html.includes('/assets/app.js'));
          assert.ok(html.includes('/branding/espos-icon.ico'));
          assert.doesNotMatch(html, /role="dialog"|<title>Old<\/title>/);
        }
      }
      assert.throws(() => buildPrerenderDocument("<html></html>", "body", { htmlLang: "es" }, "head"), /empty #root/);
    });
    await suite.test("three old feature URL documents render the complete unified solution directly", () => {
      const template = '<!doctype html><html><head></head><body><div id="root"></div></body></html>';
      const renderRoute = (language, route) => {
        Object.defineProperty(globalThis, "window", { configurable: true, value: { location: { origin: "http://localhost", pathname: `/${language}/producto/${route}/`, hash: "", search: "?apps=gestion" } } });
        Object.defineProperty(globalThis, "document", { configurable: true, value: { referrer: "" } });
        for (const key of ["sessionStorage", "localStorage"]) Object.defineProperty(globalThis, key, { configurable: true, value: { getItem: () => null, setItem: () => {} } });
        const markup = renderToString(createElement(MarketingPage, { language, onLanguageChange: () => {} }));
        const metadata = seo.buildMarketingMetadata(language, route, origin, true);
        return buildPrerenderDocument(template, markup, metadata, seo.renderMarketingHead(metadata));
      };
      for (const language of routes.marketingLanguages) {
        const canonical = renderRoute(language, "solucion");
        const alias = renderRoute(language, "funcionalidades");
        assert.equal(alias, canonical, `${language} alias must render the same solution, not a redirect or old feature page`);
        assert.match(alias, new RegExp(`rel="canonical" href="${origin}/${language}/producto/solucion/"`));
        assert.equal((alias.match(/<h1\b/g) ?? []).length, 1);
        assert.doesNotMatch(alias, /http-equiv="refresh"|window\.location|location\.replace/);
        const copy = getMarketingContent(language);
        assert.ok(alias.includes(escapeText(copy.solution.title)));
        for (const feature of copy.features.groups) assert.ok(alias.includes(escapeText(feature[1])), `${language} combined page missing feature ${feature[1]}`);
      }
    });
    await suite.test("sitemap lists the complete localized set and robots never blocks reading noindex", () => {
      const entries = routes.marketingLanguages.flatMap(language => routes.routeIds.map(route => seo.buildMarketingMetadata(language, route, origin, true)));
      const sitemap = buildSitemap(entries);
      assert.equal((sitemap.match(/<url>/g) ?? []).length, 15);
      assert.equal((sitemap.match(/<xhtml:link /g) ?? []).length, 60);
      assert.doesNotMatch(sitemap, /#|\/producto\/funcionalidades\//);
      assert.doesNotMatch(buildRobots(), /Sitemap:|Disallow:/);
      assert.match(buildRobots(`${origin}/sitemap.xml`), /Sitemap: https:\/\/public\.espos-retail\.com\/sitemap\.xml/);
    });
  } finally {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await server.close();
  }
});
