import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const escapeHtml = value => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const expected = { checkout: "venta,gestion", warehouse: "gestion,pda", central: "saas", "whole-team": "all" };

// Static React rendering only: no browser, listening HTTP server, navigation,
// production data, form submission, visual layout or screenshot is exercised.
test("Home gives visitors one compact localized application chooser before the detailed introduction", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  const previous = new Map(["window", "document", "sessionStorage", "localStorage", "fetch"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  const location = { origin: "http://ssr.invalid", pathname: "/es/producto/inicio/", search: "", hash: "" };
  let requests = 0;
  let actions = 0;
  const globals = {
    window: { location }, document: { referrer: "" },
    sessionStorage: { getItem: () => null, setItem: () => {} },
    localStorage: { getItem: () => null, setItem: () => {} },
    fetch: () => { requests += 1; throw new Error("Static Home choice checks cannot perform requests"); }
  };
  for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  try {
    assert.equal(server.httpServer, null);
    const [{ default: MarketingPage }, { getApplicationChoices }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/MarketingPage.tsx"), server.ssrLoadModule("/src/applicationChoices.ts"), server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    for (const language of ["es", "en", "zh"]) {
      await suite.test(language, () => {
        location.pathname = `/${language}/producto/inicio/`;
        const markup = renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange: () => { actions += 1; } }));
        const chooser = markup.match(/<details\b([^>]*data-testid="application-chooser"[^>]*)>([\s\S]*?)<\/details>/);
        assert.ok(chooser);
        assert.equal((markup.match(/data-testid="application-chooser"/g) ?? []).length, 1);
        assert.doesNotMatch(chooser[1], /\bopen(?:\s|=|$)|\bhidden(?:\s|=|$)/, "The chooser summary is visible and its detailed options stay closed");
        const copy = getApplicationChoices(language);
        assert.equal((markup.match(/\bid="mk-home-applications"/g) ?? []).length, 1);
        const hero = markup.slice(markup.indexOf('class="mk-hero"'), markup.indexOf('class="mk-app-strip"'));
        assert.ok(hero.includes(`<a class="mk-secondary" href="#mk-home-applications">${escapeHtml(copy.title)}</a>`), "The first-screen secondary action jumps directly to the shared chooser");
        assert.ok(hero.includes(`href="/${language}/producto/contacto/#mk-demo-form"`), "The primary demonstration action reaches its form");
        assert.ok(chooser[2].includes(`<strong>${escapeHtml(copy.title)}</strong>`));
        assert.ok(chooser[2].includes(escapeHtml(copy.subtitle)));
        assert.ok(chooser[2].includes(escapeHtml(copy.note)));
        assert.ok(markup.indexOf('class="mk-app-strip"') < chooser.index);
        assert.ok(chooser.index < markup.indexOf('class="mk-intro"'));
        for (const [scenario, query] of Object.entries(expected)) {
          assert.ok(chooser[2].includes(`data-choice="${scenario}"`));
          assert.ok(chooser[2].includes(`href="/${language}/producto/contacto/?apps=${query}#mk-demo-form"`));
          for (const value of Object.values(copy.scenarios[scenario])) assert.ok(chooser[2].includes(escapeHtml(value)));
        }
        assert.ok(markup.includes(escapeHtml(getMarketingContent(language).home.title)), "The existing main marketing message is preserved");
        assert.doesNotMatch(chooser[2], /<form\b|<input\b|style=/);
      });
    }
    assert.equal(requests, 0);
    assert.equal(actions, 0);
    await suite.test("Home and Apps share the existing behavior and CSS remains scoped", async () => {
      const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
      const home = page.slice(page.indexOf("function HomePage("), page.indexOf("function SolutionPage("));
      assert.match(home, /<ApplicationChooser language=\{language\} onRequestApplications=\{onRequestApplications\} \/>/);
      assert.match(home, /href="#mk-home-applications">\{getApplicationChoices\(language\)\.title\}/);
      assert.doesNotMatch(home, /onClick[\s\S]*scrollIntoView|window\.location/, "The chooser jump remains a native anchor rather than another JS navigation flow");
      const chooser = await readFile(new URL("../src/ApplicationChooser.tsx", import.meta.url), "utf8");
      assert.match(chooser, /event\.defaultPrevented \|\| event\.button !== 0 \|\| event\.altKey \|\| event\.ctrlKey \|\| event\.metaKey \|\| event\.shiftKey/);
      assert.match(chooser, /event\.preventDefault\(\);\s*onRequestApplications\(scenario\.applications\)/);
      const css = await readFile(new URL("../src/application-choices.css", import.meta.url), "utf8");
      assert.match(css, /\.mk-home-page > \.mk-application-chooser \{ margin-top: 18px; \}/);
      assert.match(css, /scroll-margin-top: 84px/, "Leave space for the compact sticky header when the native anchor is followed");
      assert.match(css, /@media \(max-width: 760px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/);
    });
  } finally {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await server.close();
  }
});
