import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const local = host => ({ client: `http://${host}:5173/`, internal: `http://${host}:5175/#/login` });
const fixtures = [
  { name: "IPv4 development", origin: "http://127.0.0.1:4190", env: {}, expected: local("127.0.0.1") },
  { name: "localhost development", origin: "http://localhost:4190", env: {}, expected: local("localhost") },
  { name: "IPv6 development", origin: "http://[::1]:4190", env: {}, expected: local("[::1]") },
  { name: "unconfigured public site", origin: "https://website.fixture.invalid", env: {}, expected: {} },
  { name: "unconfigured prerender", origin: "https://prerender.invalid", env: {}, expected: {} },
  { name: "published independent portals", origin: "https://website.fixture.invalid", env: { CLIENT: "https://venta.fixture.invalid/", INTERNAL: "https://staff.fixture.invalid/#/login" }, expected: { client: "https://venta.fixture.invalid/", internal: "https://staff.fixture.invalid/#/login" } },
  { name: "published same-origin paths", origin: "https://website.fixture.invalid", env: { CLIENT: "/venta/", INTERNAL: "/saas/#/login" }, expected: { client: "/venta/", internal: "/saas/#/login" } },
  { name: "invalid publication cannot fall back to local portals", origin: "http://127.0.0.1:4190", env: { CLIENT: "javascript:alert(1)", INTERNAL: "https://user:password@staff.fixture.invalid/" }, expected: {} },
  { name: "customer publication does not invent a staff destination", origin: "https://website.fixture.invalid", env: { CLIENT: "https://venta.fixture.invalid/" }, expected: { client: "https://venta.fixture.invalid/" } }
];
const clientClasses = ["mk-mobile-portal", "mk-mobile-portal-menu", "mk-portal-button"];
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const attributes = source => Object.fromEntries([...source.matchAll(/([\w:-]+)(?:="([^"]*)")?/g)].map(match => [match[1], match[2] ?? ""]));
const entries = markup => [...markup.matchAll(/<(a|span)\b([^>]*)>/g)].map(match => ({ tag: match[1], attrs: attributes(match[2]), index: match.index }));
const byClass = (markup, className) => entries(markup).filter(entry => (entry.attrs.class ?? "").split(/\s+/).includes(className));

// Controlled React SSR only: no browser, HTTP listener, authentication request,
// interaction or visual layout. Configuration is replaced in each fixture's
// module without changing .env or the production module on disk.
test("customer and staff portal entries retain independent, localized destinations", async suite => {
  const server = await createServer({
    configFile: false, root, cacheDir: "node_modules/.vite-portal-entrypoints",
    plugins: [{
      name: "portal-entrypoint-fixtures", enforce: "pre",
      transform(code, id) {
        const fixture = id.replaceAll("\\", "/").match(/\/src\/MarketingPage\.tsx\?portalEntry=(\d+)$/);
        if (fixture) return code.replace(/import\.meta\.env\.VITE_(CLIENT|INTERNAL)_PORTAL_URL/g, (_, name) => JSON.stringify(fixtures[Number(fixture[1])].env[name]) ?? "undefined");
      }
    }, react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  const previous = new Map(["window", "document", "sessionStorage", "localStorage", "fetch"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  const location = { origin: "https://prerender.invalid", pathname: "/es/producto/inicio/", search: "?apps=gestion,pda&utm_source=fixture", hash: "" };
  let requests = 0;
  let languageChanges = 0;
  const globals = {
    window: { location }, document: { referrer: "" },
    sessionStorage: { getItem: () => null, setItem() {} },
    localStorage: { getItem: () => null, setItem() {} },
    fetch() { requests += 1; throw new Error("Portal entry SSR cannot make network requests"); }
  };
  for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  try {
    assert.equal(server.httpServer, null);
    const { getMarketingUiContent } = await server.ssrLoadModule("/src/marketingUiI18n.ts");
    const { getMarketingContent } = await server.ssrLoadModule("/src/marketingI18n.ts");
    for (const [index, fixture] of fixtures.entries()) {
      const { default: MarketingPage } = await server.ssrLoadModule(`/src/MarketingPage.tsx?portalEntry=${index}`);
      for (const language of ["es", "en", "zh"]) {
        await suite.test(`${fixture.name}/${language}`, () => {
          location.origin = fixture.origin;
          location.pathname = `/${language}/producto/inicio/`;
          const markup = renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange() { languageChanges += 1; } }));
          const ui = getMarketingUiContent(language);
          const copy = getMarketingContent(language);
          const header = markup.match(/<header\b[^>]*class="mk-header[^>]*>([\s\S]*?)<\/header>/)?.[1];
          const footer = markup.match(/<footer\b[^>]*class="mk-footer"[^>]*>([\s\S]*?)<\/footer>/)?.[1];
          assert.ok(header && footer);
          for (const className of clientClasses) {
            const portals = byClass(header, className);
            assert.equal(portals.length, 1, `${className}: one customer entry per placement`);
            const { tag, attrs } = portals[0];
            if (fixture.expected.client) {
              assert.equal(tag, "a");
              assert.equal(attrs.href, escapeHtml(fixture.expected.client));
              assert.equal(attrs["aria-label"], escapeHtml(ui.clientAccess));
              assert.equal(attrs.referrerPolicy ?? attrs.referrerpolicy, "no-referrer");
              assert.notEqual(attrs["aria-disabled"], "true");
            } else {
              assert.equal(attrs.href, undefined, "Missing/invalid configuration must never create a fake login URL");
              assert.equal(attrs.role, "link");
              assert.equal(attrs["aria-disabled"], "true");
              assert.ok(attrs["aria-label"].includes(escapeHtml(ui.portalUnavailable)));
            }
          }
          assert.equal(byClass(header, "mk-internal-access").length, 0, "The internal entry is not promoted in the customer header/menu");
          const staff = byClass(markup, "mk-internal-access");
          assert.equal(staff.length, 1, "Only one discrete internal entry is offered");
          assert.equal(byClass(footer, "mk-internal-access").length, 1);
          if (fixture.expected.internal) {
            assert.equal(staff[0].tag, "a");
            assert.equal(staff[0].attrs.href, escapeHtml(fixture.expected.internal));
            assert.equal(staff[0].attrs["aria-label"], escapeHtml(ui.internalAccess));
          } else {
            assert.equal(staff[0].attrs.href, undefined);
            assert.equal(staff[0].attrs["aria-disabled"], "true");
          }
          const download = [...header.matchAll(/<button\b([^>]*)>/g)].map(match => attributes(match[1]));
          assert.equal(download.filter(button => (button.class ?? "").split(/\s+/).includes("mk-download-button")).length, 1);
          assert.equal(download.filter(button => (button.class ?? "").split(/\s+/).includes("mk-mobile-client-link")).length, 1);
          assert.ok(header.includes(escapeHtml(copy.common.download)), "Download actions keep their translated name");
          assert.ok(footer.includes(`<button type="button">${escapeHtml(copy.common.download)}</button>`), "Downloads remain accessible from the footer");
          assert.doesNotMatch(markup, /<input\b[^>]*(?:type="password"|autocomplete="(?:current-password|new-password)")|<iframe\b/i, "The marketing site must link to existing authenticated portals, not collect credentials");
          for (const portal of [...clientClasses.flatMap(className => byClass(markup, className)), ...staff]) {
            assert.doesNotMatch(portal.attrs.href ?? "", /utm_|apps=|token=|password=/, "No marketing or authentication data is forwarded to login URLs");
          }
        });
      }
    }
    await suite.test("static prerender never uses a loopback origin as a publication default", async () => {
      const source = await readFile(new URL("../scripts/prerender.mjs", import.meta.url), "utf8");
      assert.match(source, /origin:\s*origin\?\.origin\s*\?\?\s*"https:\/\/prerender\.invalid"/);
    });
    assert.equal(requests, 0);
    assert.equal(languageChanges, 0);
  } finally {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await server.close();
  }
});
