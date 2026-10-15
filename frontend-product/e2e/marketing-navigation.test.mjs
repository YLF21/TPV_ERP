import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

test("localized links and navigation contracts without browser effects", async suite => {
  const server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL("..", import.meta.url)),
    plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom",
    optimizeDeps: { noDiscovery: true, include: [] }
  });
  const names = ["window", "document", "localStorage", "sessionStorage", "PopStateEvent"];
  const previous = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const history = [];
  const events = [];
  let preference = "zh";
  const location = { origin: "http://ssr.invalid", pathname: "/en/producto/apps/", search: "", hash: "" };
  const define = (name, value) => Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
  define("window", {
    location,
    history: {
      pushState: (_state, _title, href) => history.push(["push", href]),
      replaceState: (_state, _title, href) => history.push(["replace", href])
    },
    dispatchEvent: event => { events.push(event.type); return true; }
  });
  define("document", { referrer: "" });
  define("localStorage", { getItem: () => preference });
  define("sessionStorage", { getItem: () => null, setItem: () => {} });
  define("PopStateEvent", class { constructor(type) { this.type = type; } });
  try {
    assert.equal(server.httpServer, null);
    const [{ default: App }, navigation, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/App.tsx"),
      server.ssrLoadModule("/src/MarketingNavigation.tsx"),
      server.ssrLoadModule("/src/marketingI18n.ts")
    ]);

    await suite.test("an explicit URL language wins over a previously stored preference", () => {
      const markup = renderToStaticMarkup(createElement(App));
      assert.ok(markup.includes(getMarketingContent("en").apps.title));
      assert.match(markup, /href="\/en\/producto\/apps\/"/);
      assert.doesNotMatch(markup, /href="#\/producto\//);
      assert.deepEqual(events, [], "SSR does not execute navigation or React effects");
    });

    await suite.test("legacy hash entries keep their stored language and remain usable", () => {
      location.pathname = "/";
      location.hash = "#/producto/contacto";
      preference = "es";
      const markup = renderToStaticMarkup(createElement(App));
      assert.ok(markup.includes(getMarketingContent("es").contact.title));
      assert.match(markup, /href="\/es\/producto\/apps\/"/);
    });

    await suite.test("blocked preference storage does not prevent a clean route render", () => {
      location.pathname = "/zh/producto/ventajas/";
      location.hash = "";
      define("localStorage", { getItem: () => { throw new Error("Storage denied"); } });
      const markup = renderToStaticMarkup(createElement(App));
      assert.ok(markup.includes(getMarketingContent("zh").advantagesPage.title));
    });

    await suite.test("history helper distinguishes push from replace and emits route synchronization", () => {
      navigation.navigateMarketing("/en/producto/contacto/?apps=all");
      navigation.navigateMarketing("/zh/producto/contacto/?apps=all", true);
      assert.deepEqual(history, [
        ["push", "/en/producto/contacto/?apps=all"],
        ["replace", "/zh/producto/contacto/?apps=all"]
      ]);
      assert.deepEqual(events, ["popstate", "popstate"]);
    });
    // History doubles prove call contracts, not real Back/Forward, focus, or layout.
  } finally {
    for (const [name, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
    await server.close();
  }
});
