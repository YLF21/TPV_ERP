import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const languages = ["es", "en", "zh"];
const routes = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];
const uiKeys = ["skipToContent", "primaryNavigation", "openMenu", "closeMenu", "closeDialog", "tourApplications", "tourSteps"];
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// This parser only examines controlled React SSR output. It is not a DOM, layout
// engine or accessibility tree, and does not establish browser/WCAG conformance.
function attributes(source) {
  return Object.fromEntries([...source.matchAll(/([\w:-]+)(?:="([^"]*)")?/g)].map(match => [match[1], match[2] ?? ""]));
}

function tags(markup, name) {
  return [...markup.matchAll(new RegExp(`<${name}\\b([^>]*)>`, "g"))].map(match => attributes(match[1]));
}

function requireText(markup, text) {
  assert.ok(markup.includes(escapeHtml(text)), `Missing localized text: ${text}`);
}

function withGlobals(values) {
  const previous = new Map(Object.keys(values).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(values)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  return () => {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  };
}

test("marketing accessibility contracts without a browser, HTTP or hydration", async suite => {
  const server = await createServer({
    configFile: false,
    root,
    plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom",
    optimizeDeps: { noDiscovery: true, include: [] }
  });
  const storage = new Map();
  let requestCount = 0;
  let languageChangeCount = 0;
  const location = { origin: "http://ssr.invalid", pathname: "/", search: "", hash: "#/producto/inicio" };
  const restoreGlobals = withGlobals({
    window: { location },
    document: { referrer: "" },
    sessionStorage: {
      getItem: key => storage.get(key) ?? null,
      setItem: (key, value) => { storage.set(key, value); }
    },
    fetch: () => { requestCount += 1; throw new Error("SSR accessibility tests must not perform network requests"); }
  });

  try {
    assert.equal(server.httpServer, null, "The Vite loader must not create a listening HTTP server");
    const [{ default: MarketingPage }, { getMarketingContent }, { getMarketingUiContent }, helpers] = await Promise.all([
      server.ssrLoadModule("/src/MarketingPage.tsx"),
      server.ssrLoadModule("/src/marketingI18n.ts"),
      server.ssrLoadModule("/src/marketingUiI18n.ts"),
      server.ssrLoadModule("/src/marketingAccessibility.ts")
    ]);
    const render = (language, route, search = "") => {
      location.hash = `#/producto/${route}`;
      location.search = search;
      storage.clear();
      return renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange: () => { languageChangeCount += 1; } }));
    };

    await suite.test("all seven accessibility UI labels are translated with the same keys", () => {
      const content = languages.map(language => getMarketingUiContent(language));
      for (const copy of content) {
        assert.deepEqual(Object.keys(copy).sort(), [...uiKeys].sort());
        for (const value of Object.values(copy)) assert.ok(typeof value === "string" && value.trim().length > 0);
      }
      for (const key of uiKeys) assert.equal(new Set(content.map(copy => copy[key])).size, 3, `UI label ${key} must be localized for each language`);
    });

    for (const language of languages) {
      for (const route of routes) {
        await suite.test(`${language}/${route}: skip destination, landmarks, labels and ARIA references`, () => {
          const markup = render(language, route);
          const ui = getMarketingUiContent(language);
          const copy = getMarketingContent(language);
          const main = tags(markup, "main");
          assert.equal(main.length, 1, "Every route has exactly one main landmark");
          assert.equal(main[0].id, "mk-main-content");
          assert.equal(main[0].tabindex, "-1", "The skip/route destination must be programmatically focusable");
          const mainContent = markup.match(/<main\b[^>]*>([\s\S]*?)<\/main>/)?.[1];
          assert.ok(mainContent);
          assert.equal(tags(mainContent, "h1").length, 1, "Every route has one primary heading");
          assert.doesNotMatch(mainContent, /class="mk-header(?:\s|")/);
          assert.doesNotMatch(mainContent, /class="mk-footer(?:\s|")/);

          const skip = markup.match(/<a\b([^>]*class="[^"]*\bmk-skip-link\b[^"]*"[^>]*)>([\s\S]*?)<\/a>/);
          assert.ok(skip, "A visible-on-focus skip link must be present in the markup");
          assert.equal(attributes(skip[1]).href, `#${main[0].id}`);
          requireText(skip[2], ui.skipToContent);
          const nav = tags(markup, "nav");
          assert.equal(nav.length, 1);
          assert.equal(nav[0]["aria-label"], escapeHtml(ui.primaryNavigation));
          const menu = tags(markup, "button").find(button => /\bmk-menu-button\b/.test(button.class ?? ""));
          assert.ok(menu);
          assert.equal(menu["aria-expanded"], "false");
          assert.equal(menu["aria-controls"], nav[0].id);
          assert.equal(menu["aria-label"], escapeHtml(ui.openMenu));

          const allIds = [...markup.matchAll(/\sid="([^"]*)"/g)].map(match => match[1]);
          const ids = new Set(allIds);
          assert.equal(ids.size, allIds.length, "Rendered IDs must be unique");
          for (const [, references] of markup.matchAll(/\saria-(?:labelledby|describedby|controls)="([^"]+)"/g)) {
            for (const id of references.split(/\s+/)) assert.ok(ids.has(id), `ARIA reference ${id} must point to an element in the same render`);
          }
          for (const image of tags(markup, "img")) assert.ok(Object.hasOwn(image, "alt"), "Images must explicitly declare meaningful or empty alternative text");
          const activeLinks = tags(markup, "a").filter(anchor => anchor["aria-current"] === "page");
          assert.equal(activeLinks.length, 1);
          assert.equal(activeLinks[0].href, `/${language}/producto/${route}/`);
          for (const select of tags(markup, "select")) assert.equal(select["aria-label"], escapeHtml(copy.language));
          assert.equal(requestCount, 0);
          assert.equal(languageChangeCount, 0);
        });
      }

      await suite.test(`${language}/contacto: native labels, grouping, status and safe initial submission state`, () => {
        const markup = render(language, "contacto");
        const copy = getMarketingContent(language);
        const form = markup.match(/<form\b[^>]*class="mk-contact-form"[^>]*>([\s\S]*?)<\/form>/)?.[1];
        assert.ok(form);
        const inputs = tags(form, "input");
        const labels = [...form.matchAll(/<label\b[^>]*>([\s\S]*?)<\/label>/g)].map(match => match[1]);
        const expectedFields = { name: "name", company: "organization", email: "email", phone: "tel" };
        for (const [name, autocomplete] of Object.entries(expectedFields)) {
          const input = inputs.find(item => item.name === name);
          assert.ok(input, `Missing named field ${name}`);
          assert.equal(input.autoComplete ?? input.autocomplete, autocomplete);
          assert.ok(labels.some(label => tags(label, "input").some(item => item.name === name)), `Field ${name} must have a wrapping native label`);
          if (name !== "phone") assert.ok(Object.hasOwn(input, "required"));
        }
        assert.equal(inputs.find(input => input.name === "email").type, "email");
        assert.equal(inputs.find(input => input.name === "phone").type, "tel");
        assert.ok(labels.some(label => tags(label, "textarea").some(item => item.name === "message")));
        assert.equal(inputs.filter(input => input.name === "products" && input.type === "checkbox").length, 4);
        assert.ok(inputs.some(input => input.name === "fullPackage" && input.type === "checkbox"));
        const consent = inputs.find(input => input.name === "privacyAccepted");
        assert.equal(consent.type, "checkbox");
        assert.ok(Object.hasOwn(consent, "required"));
        const selection = form.match(/<fieldset\b[^>]*class="mk-contact-selection"[^>]*>([\s\S]*?)<\/fieldset>/)?.[1];
        assert.ok(selection);
        requireText(selection.match(/<legend>([\s\S]*?)<\/legend>/)?.[1] ?? "", copy.contact.product);
        const honeypot = inputs.find(input => input.name === "website");
        assert.equal(honeypot.tabindex, "-1");
        assert.ok(labels.some(label => tags(label, "input").some(input => input.name === "website")));
        assert.match(form, /<label\b[^>]*class="mk-contact-honeypot"[^>]*aria-hidden="true"/);
        const submit = tags(form, "button").find(button => button.type === "submit");
        assert.ok(Object.hasOwn(submit, "disabled"), "Sending is unavailable until availability has actually been checked");
        assert.equal(submit["aria-busy"], "false");
        requireText(form, copy.contact.delivery.checking);
        assert.ok(tags(form, "p").some(paragraph => paragraph.role === "status" && paragraph["aria-live"] === "polite"));
        const contactScene = tags(markup, "img").find(image => image.class === "mk-contact-scene");
        assert.equal(contactScene.alt, "", "The decorative contact background must not repeat content or a fixed-language description");
        assert.equal(requestCount, 0, "SSR must never check availability or submit personal data");
      });
    }

    await suite.test("unknown routes safely fall back to the home heading and current link", () => {
      const markup = render("es", "unknown");
      requireText(markup, getMarketingContent("es").home.title);
      assert.equal(tags(markup, "a").find(anchor => anchor["aria-current"] === "page").href, "/es/producto/inicio/");
    });

    await suite.test("direct request links preselect one, several or the complete package without submitting", () => {
      for (const [search, selected] of [
        ["?apps=all", ["venta", "gestion", "pda", "saas"]],
        ["?apps=pda,gestion", ["gestion", "pda"]],
        ["?apps=saas", ["saas"]],
        ["?apps=unknown", ["venta"]]
      ]) {
        const inputs = tags(render("en", "contacto", search), "input");
        assert.deepEqual(inputs.filter(input => input.name === "products" && Object.hasOwn(input, "checked")).map(input => input.value), selected);
        assert.equal(Object.hasOwn(inputs.find(input => input.name === "fullPackage"), "checked"), selected.length === 4);
      }
      assert.equal(requestCount, 0);
    });

    await suite.test("roving-tab helper handles supported keys, wraps and ignores unrelated keys", () => {
      const { getRovingTabIndex } = helpers;
      assert.equal(getRovingTabIndex("ArrowRight", 0, 4), 1);
      assert.equal(getRovingTabIndex("ArrowRight", 3, 4), 0);
      assert.equal(getRovingTabIndex("ArrowLeft", 0, 4), 3);
      assert.equal(getRovingTabIndex("ArrowLeft", 2, 4), 1);
      assert.equal(getRovingTabIndex("Home", 2, 4), 0);
      assert.equal(getRovingTabIndex("End", 0, 4), 3);
      assert.equal(getRovingTabIndex("ArrowRight", 0, 1), 0);
      for (const key of ["Tab", "Escape", "Enter", "a"]) assert.equal(getRovingTabIndex(key, 1, 4), null);
      assert.equal(getRovingTabIndex("ArrowRight", 0, 0), null);
    });

    await suite.test("dialog-tab helper wraps only at boundaries or from outside the focus list", () => {
      const { getTrappedTabIndex } = helpers;
      assert.equal(getTrappedTabIndex(2, false, 3), 0);
      assert.equal(getTrappedTabIndex(0, true, 3), 2);
      assert.equal(getTrappedTabIndex(-1, false, 3), 0);
      assert.equal(getTrappedTabIndex(-1, true, 3), 2);
      assert.equal(getTrappedTabIndex(1, false, 3), null);
      assert.equal(getTrappedTabIndex(1, true, 3), null);
      assert.equal(getTrappedTabIndex(0, false, 1), 0);
      assert.equal(getTrappedTabIndex(0, true, 1), 0);
      assert.equal(getTrappedTabIndex(-1, false, 0), null);
    });

    await suite.test("focus-target helpers filter unavailable candidates in supplied element doubles", () => {
      const { isAvailableFocusTarget, getDialogFocusableElements } = helpers;
      // Deliberate method-level doubles, not a substitute DOM or browser. These
      // prove the helper's decisions for explicit inputs, not computed visibility.
      const element = (options = {}) => ({
        isConnected: options.connected ?? true,
        tabIndex: options.tabIndex ?? 0,
        closest: () => options.blockedAncestor ? {} : null,
        matches: () => options.disabled || options.hiddenInput || false,
        getAttribute: name => name === "aria-disabled" && options.ariaDisabled ? "true" : null,
        getClientRects: () => options.noRects ? [] : [{}],
        ownerDocument: { defaultView: { getComputedStyle: () => ({ display: options.display ?? "block", visibility: options.visibility ?? "visible" }) } }
      });
      const first = element();
      const last = element();
      assert.equal(isAvailableFocusTarget(first), true);
      for (const options of [
        { connected: false }, { blockedAncestor: true }, { disabled: true },
        { hiddenInput: true }, { ariaDisabled: true }, { noRects: true },
        { display: "none" }, { visibility: "hidden" }, { visibility: "collapse" }
      ]) assert.equal(isAvailableFocusTarget(element(options)), false, `Unavailable candidate: ${JSON.stringify(options)}`);
      const candidates = [first, element({ disabled: true }), element({ tabIndex: -1 }), element({ blockedAncestor: true }), last];
      assert.deepEqual(getDialogFocusableElements({ querySelectorAll: () => candidates }), [first, last]);
      assert.deepEqual(getDialogFocusableElements({ querySelectorAll: () => [] }), []);
    });

    await suite.test("the final reduced-motion rules disable animation, transition and smooth scrolling", async () => {
      const css = await readFile(new URL("../src/marketing.css", import.meta.url), "utf8");
      const reducedMotion = css.slice(css.lastIndexOf("@media (prefers-reduced-motion: reduce)"));
      assert.match(reducedMotion, /animation\s*:\s*none\s*!important/);
      assert.match(reducedMotion, /transition\s*:\s*none\s*!important/);
      assert.match(reducedMotion, /scroll-behavior\s*:\s*auto\s*!important/);
      assert.match(css, /100dvh/, "Dynamic viewport sizing must remain available for mobile browsers");
    });

    // Pure helper tests above verify index decisions only. React effects, actual
    // focus, inert propagation, Escape and responsive layout need a permitted UI
    // session; server-rendered markup does not execute or certify those flows.
    assert.equal(requestCount, 0);
    assert.equal(languageChangeCount, 0);
  } finally {
    restoreGlobals();
    await server.close();
  }
});
