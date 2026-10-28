import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));

// Controlled SSR, pure navigation classification and a DOM-method double.
// Actual geometry, Back/Forward, focus after React commits and mobile keyboard
// behavior are verified separately with Cua, never a second browser runner.
test("demo intent reaches a focusable form without changing ordinary contact entry", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  const previous = new Map(["window", "document", "sessionStorage", "localStorage", "fetch"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  const location = { origin: "http://ssr.invalid", pathname: "/es/producto/inicio/", search: "", hash: "" };
  let requests = 0;
  for (const [key, value] of Object.entries({
    window: { location }, document: { referrer: "" },
    sessionStorage: { getItem: () => null, setItem: () => {} }, localStorage: { getItem: () => null },
    fetch: () => { requests += 1; throw new Error("Demo access checks must not send requests"); }
  })) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  try {
    assert.equal(server.httpServer, null);
    const [navigation, { default: MarketingPage }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/MarketingNavigation.tsx"), server.ssrLoadModule("/src/MarketingPage.tsx"), server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    const { demoFormHash, readMarketingNavigation, isLanguageOnlyNavigation, focusDemoForm } = navigation;

    await suite.test("heading focus precedes an immediate scroll and never opens an input", () => {
      const operations = [];
      const heading = { focus: options => operations.push(["heading-focus", options]), scrollIntoView: options => operations.push(["heading-scroll", options]) };
      const page = { querySelector: selector => { assert.equal(selector, demoFormHash); return heading; } };
      assert.equal(focusDemoForm(page), true);
      assert.deepEqual(operations, [["heading-focus", { preventScroll: true }], ["heading-scroll", { block: "start", behavior: "instant" }]]);
      assert.equal(focusDemoForm(null), false);
      assert.equal(focusDemoForm({ querySelector: () => null }), false);
      assert.equal(operations.length, 2);
    });

    await suite.test("navigation distinguishes repeated requests, app choices, locale changes and legacy routes", () => {
      const current = readMarketingNavigation({ pathname: "/es/producto/contacto/", search: "?apps=gestion,pda&utm_source=review", hash: demoFormHash });
      assert.deepEqual(current, { route: "contacto", language: "es", search: "?apps=gestion,pda&utm_source=review", target: demoFormHash });
      assert.equal(isLanguageOnlyNavigation(current, { ...current }), false, "A second explicit request at the same URL must still be handled");
      assert.equal(isLanguageOnlyNavigation(current, { ...current, search: "?apps=saas" }), false);
      assert.equal(isLanguageOnlyNavigation(current, { ...current, target: "" }), false, "Going back to the ordinary overview remains a visit");
      assert.equal(isLanguageOnlyNavigation(current, { ...current, language: "en", search: "?apps=saas" }), false);
      for (const language of ["en", "zh"]) {
        assert.equal(isLanguageOnlyNavigation(current, readMarketingNavigation({ pathname: `/${language}/producto/contacto/`, search: current.search, hash: demoFormHash })), true);
      }
      const legacy = readMarketingNavigation({ pathname: "/", search: "?apps=all", hash: "#/producto/contacto" });
      assert.deepEqual(legacy, { route: "contacto", language: null, search: "?apps=all", target: "" });
      assert.equal(isLanguageOnlyNavigation(legacy, readMarketingNavigation({ pathname: "/en/producto/contacto/", search: legacy.search, hash: "" })), true, "Changing language can canonicalise a legacy route without resetting the form");
      assert.equal(readMarketingNavigation({ pathname: "/es/producto/contacto/", hash: "#another-section" }).target, "#another-section", "Unrelated native anchors stay distinguishable");
    });

    for (const language of ["es", "en", "zh"]) {
      await suite.test(`${language}: explicit demo anchors and the ordinary Contacto route have different destinations`, () => {
        location.pathname = `/${language}/producto/inicio/`;
        location.hash = "";
        location.search = "";
        const markup = renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange() {} }));
        const hero = markup.slice(markup.indexOf('class="mk-hero"'), markup.indexOf('class="mk-app-strip"'));
        assert.ok(hero.includes(`href="/${language}/producto/contacto/${demoFormHash}"`));
        const nav = markup.match(/<nav\b[^>]*>([\s\S]*?)<\/nav>/)?.[1];
        assert.ok(nav.includes(`href="/${language}/producto/contacto/"`), "Ordinary contact navigation still shows both contact channels");
        const contactLinks = [...markup.matchAll(/href="([^"\s]*\/producto\/contacto\/[^"\s]*)"/g)].map(match => match[1]);
        assert.ok(contactLinks.filter(href => href.includes("?apps=")).every(href => href.endsWith(demoFormHash)), "Native modifier/new-tab app choices already include their form target");
        location.pathname = `/${language}/producto/contacto/`;
        location.search = "?apps=gestion,pda";
        location.hash = demoFormHash;
        const contact = renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange() {} }));
        assert.match(contact, /<form\b[^>]*aria-labelledby="mk-demo-form"/);
        const heading = contact.match(/<h2\b([^>]*id="mk-demo-form"[^>]*)>([\s\S]*?)<\/h2>/);
        assert.ok(heading);
        assert.match(heading[1], /tabindex="-1"/);
        assert.ok(heading[2].includes(getMarketingContent(language).contact.formTitle));
        assert.doesNotMatch(contact, /\bautofocus(?:\s|=|>)/i);
        for (const app of ["gestion", "pda"]) assert.match(contact, new RegExp(`<input[^>]*name="products"[^>]*checked=""[^>]*value="${app}"`));
      });
    }

    await suite.test("only committed navigation handles the form target, never selection or language renders", async () => {
      const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
      const contact = page.slice(page.indexOf("function ContactPage("));
      assert.match(page, /activeTab === "contacto" && window\.location\.hash === demoFormHash && focusDemoForm\(pageRef\.current\)/);
      assert.match(page, /\}, \[activeTab, contactVisit\]\)/);
      assert.match(page, /!isLanguageOnlyNavigation\(previous, next\)\) setContactVisit/);
      assert.match(page, /navigateMarketing\(`\$\{target\.pathname\}\$\{target\.search\}\$\{target\.hash\}`\)/);
      assert.match(page, /target\.hash !== demoFormHash \|\| !target\.pathname\.endsWith\("\/contacto\/"\)/);
      assert.match(page, /event\.defaultPrevented \|\| event\.button !== 0 \|\| event\.altKey \|\| event\.ctrlKey \|\| event\.metaKey \|\| event\.shiftKey/);
      assert.doesNotMatch(contact, /focusDemoForm|setContactVisit|localStorage|sessionStorage/);
      const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");
      assert.match(app, /\$\{window\.location\.search\}\$\{legacy \? "" : window\.location\.hash\}/);
      const css = await readFile(new URL("../src/compact-contact.css", import.meta.url), "utf8");
      assert.match(css, /\.mk-contact-page \.mk-contact-form h2 \{\s*scroll-margin-top: 12px/, "The heading adds a small gap to the page's existing sticky-header scroll padding");
      assert.match(css, /\.mk-contact-page \.mk-contact-form h2:focus \{ outline: 2px solid #0754ce/);
    });
    assert.equal(requests, 0);
  } finally {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await server.close();
  }
});
