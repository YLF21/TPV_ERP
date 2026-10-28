import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const cases = [
  { name: "no published URLs, even with an orphan checksum", env: { VENTA_SHA256_URL: "/files/orphan.sha256" }, links: {} },
  { name: "both installers and checksums published", env: { VENTA_URL: " /files/venta.exe ", GESTION_URL: "https://downloads.fixture.invalid/gestion.exe", VENTA_SHA256_URL: "/files/venta.sha256", GESTION_SHA256_URL: "https://downloads.fixture.invalid/gestion.sha256", RELEASE_VERSION: " 2026.10-demo " }, links: { venta: ["/files/venta.exe", "/files/venta.sha256"], gestion: ["https://downloads.fixture.invalid/gestion.exe", "https://downloads.fixture.invalid/gestion.sha256"] } },
  { name: "only Gestión published", env: { GESTION_URL: "/files/gestion.exe" }, links: { gestion: ["/files/gestion.exe"] } },
  { name: "unsafe and placeholder installer URLs", env: { VENTA_URL: "javascript:alert(1)", GESTION_URL: "https://example.com/gestion.exe", VENTA_SHA256_URL: "/files/venta.sha256" }, links: {} },
  { name: "blank installer URLs", env: { VENTA_URL: " \t ", GESTION_URL: "", RELEASE_VERSION: "2026.10-demo" }, links: {} },
  { name: "valid installer with unsafe checksum and malformed other installer", env: { VENTA_URL: "/files/venta.exe", VENTA_SHA256_URL: "javascript:alert(1)", GESTION_URL: "http://[broken" }, links: { venta: ["/files/venta.exe"] } }
];
const escapeHtml = text => text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// Configuration values are replaced only in a query-specific SSR module.
// No .env is read for these values, no listener/browser is created and every
// attempted fetch is rejected before it can reach a network or download.
test("installer publication configurations preserve validated links and assisted delivery", async suite => {
  const server = await createServer({
    configFile: false, root,
    plugins: [{
      name: "download-publication-fixtures", enforce: "pre",
      transform(code, id) {
        const normalized = id.replaceAll("\\", "/");
        if (normalized.endsWith("/src/MarketingModal.tsx")) return "export function MarketingModalLayer({children}) { return children; } export function useModalDialog() { return {dialogRef:{current:null},closeRef:{current:null}}; }";
        const configuration = normalized.match(/\/src\/MarketingDialogs\.tsx\?publication=(\d+)$/);
        if (configuration) return code.replace(/import\.meta\.env\.VITE_DOWNLOAD_([A-Z0-9_]+)/g, (_, name) => JSON.stringify(cases[Number(configuration[1])].env[name]) ?? "undefined");
      }
    }, react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  const previous = new Map(["window", "fetch"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  let requests = 0;
  Object.defineProperty(globalThis, "window", { configurable: true, value: { location: { origin: "https://website.fixture.invalid" } } });
  Object.defineProperty(globalThis, "fetch", { configurable: true, value() { requests += 1; throw new Error("No network is allowed in publication checks"); } });
  try {
    assert.equal(server.httpServer, null);
    const { getMarketingContent } = await server.ssrLoadModule("/src/marketingI18n.ts");
    for (const [index, fixture] of cases.entries()) {
      const { default: MarketingDialogs } = await server.ssrLoadModule(`/src/MarketingDialogs.tsx?publication=${index}`);
      await suite.test(fixture.name, () => {
        for (const language of ["es", "en", "zh"]) {
          const copy = getMarketingContent(language);
          const markup = renderToStaticMarkup(createElement(MarketingDialogs, { kind: "downloads", language, copy, onClose() { assert.fail("SSR must not close a dialog"); }, onRequestSetup() { assert.fail("SSR must not request a demo"); } }));
          for (const app of ["venta", "gestion", "pda", "saas"]) {
            const card = markup.match(new RegExp(`<article\\b([^>]*data-testid="download-card-${app}"[^>]*)>([\\s\\S]*?)<\\/article>`));
            assert.ok(card, `${fixture.name}/${language}/${app}`);
            const expected = fixture.links[app] ?? [];
            assert.ok(card[1].includes(`data-delivery-status="${expected.length ? "available" : app === "venta" || app === "gestion" ? "unpublished" : "restricted"}"`));
            const links = [...card[2].matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>/g)];
            assert.deepEqual(links.map(link => link[1]), expected, "Only a validated installer and its validated checksum are exposed");
            for (const link of links) assert.match(link[0], /target="_blank"[^>]*rel="noreferrer"/);
            if (expected.length) assert.ok(card[2].includes(escapeHtml(copy.downloads.downloadNow)));
            else assert.ok(card[2].includes(escapeHtml(copy.common.demo)), "Without an installer the action remains an assisted demo request");
            if (fixture.env.RELEASE_VERSION && expected.length) assert.ok(card[2].includes(fixture.env.RELEASE_VERSION.trim()));
            else assert.ok(!card[2].includes(escapeHtml(copy.downloads.publishedLabel)), "Release metadata is not shown for unpublished cards");
          }
        }
      });
    }
    assert.equal(requests, 0);
  } finally {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await server.close();
  }
});
