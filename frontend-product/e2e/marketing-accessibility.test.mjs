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
const navigationRoutes = ["inicio", "solucion", "apps", "ventajas", "contacto"];
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
    const [{ default: MarketingPage }, { getMarketingContent }, { getMarketingUiContent }, helpers, { getApplicationGuides }, { marketingContact }] = await Promise.all([
      server.ssrLoadModule("/src/MarketingPage.tsx"),
      server.ssrLoadModule("/src/marketingI18n.ts"),
      server.ssrLoadModule("/src/marketingUiI18n.ts"),
      server.ssrLoadModule("/src/marketingAccessibility.ts"),
      server.ssrLoadModule("/src/applicationGuides.ts"),
      server.ssrLoadModule("/src/contact.ts")
    ]);
    const render = (language, route, search = "", entry = "hash") => {
      location.pathname = entry === "path" ? `/${language}/producto/${route}/` : "/";
      location.hash = entry === "hash" ? `#/producto/${route}` : "";
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
          assert.equal(activeLinks[0].href, `/${language}/producto/${route === "funcionalidades" ? "solucion" : route}/`);
          for (const select of tags(markup, "select")) assert.equal(select["aria-label"], escapeHtml(copy.language));
          assert.equal(requestCount, 0);
          assert.equal(languageChangeCount, 0);
        });
      }

      await suite.test(`${language}: solution and feature entries share one complete, canonical page`, () => {
        const copy = getMarketingContent(language);
        for (const entry of ["hash", "path"]) {
          for (const route of ["solucion", "funcionalidades"]) {
            const markup = render(language, route, "", entry);
            const main = markup.match(/<main\b[^>]*>([\s\S]*?)<\/main>/)?.[1];
            const navigation = markup.match(/<nav\b[^>]*>([\s\S]*?)<\/nav>/)?.[1];
            assert.ok(main && navigation, `${entry}/${route}: main content and navigation are required`);
            assert.equal(tags(main, "h1").length, 1, `${entry}/${route}: the merged page has one primary heading`);
            const links = tags(navigation, "a");
            assert.deepEqual(links.map(link => link.href), navigationRoutes.map(id => `/${language}/producto/${id}/`));
            assert.deepEqual(links.filter(link => link["aria-current"] === "page").map(link => link.href), [`/${language}/producto/solucion/`]);
            assert.doesNotMatch(markup, /href="[^\"]*\/producto\/funcionalidades\/?"/, "Legacy feature URLs must not remain in visible navigation or calls to action");
            for (const text of [copy.solution.title, copy.solution.accent, copy.solution.body, copy.solution.bandTitle, copy.solution.bandBody]) requireText(main, text);
            for (const [title, body] of copy.solution.principles) {
              requireText(main, title);
              requireText(main, body);
            }
            for (const text of [copy.features.title, copy.features.accent, copy.features.body]) requireText(main, text);
            const headingText = [...main.matchAll(/<h3\b[^>]*>([\s\S]*?)<\/h3>/g)].map(match => match[1]);
            assert.equal(copy.features.groups.length, 4);
            for (const [eyebrow, title, body, items] of copy.features.groups) {
              assert.ok(headingText.includes(escapeHtml(title)), `${entry}/${route}: functional card heading must be h3: ${title}`);
              for (const text of [eyebrow, body, ...items]) requireText(main, text);
            }

            // Structural contracts for the compact layout. SSR proves the full
            // copy and semantics survive; it does not measure grid geometry.
            const principlesStart = main.indexOf('class="mk-principles"');
            const featuresStart = main.indexOf('id="mk-solution-features"');
            assert.ok(principlesStart >= 0 && featuresStart > principlesStart);
            const principles = main.slice(principlesStart, featuresStart);
            const principleCards = [...principles.matchAll(/<article\b[^>]*>([\s\S]*?)<\/article>/g)].map(match => match[1]);
            assert.equal(principleCards.length, 3, "All three operating principles remain independently readable");
            for (const [index, card] of principleCards.entries()) {
              assert.equal(tags(card, "h2").length, 1);
              assert.equal(tags(card, "p").length, 1);
              assert.equal(tags(card, "svg").filter(icon => icon["aria-hidden"] === "true").length, 1);
              assert.doesNotMatch(card, /<span\b[^>]*>0[1-3]<\/span>/, "Decorative principle numbering must not reserve a separate grid cell");
              for (const text of copy.solution.principles[index]) requireText(card, text);
            }
            const features = main.match(/<section\b[^>]*id="mk-solution-features"[^>]*>([\s\S]*?)<\/section>/)?.[1];
            assert.ok(features);
            assert.equal(tags(features, "h2").length, 1, "The four functional groups share one level-two section heading");
            const featureCards = [...features.matchAll(/<article\b[^>]*>([\s\S]*?)<\/article>/g)].map(match => match[1]);
            assert.equal(featureCards.length, copy.features.groups.length);
            for (const [index, card] of featureCards.entries()) {
              const [eyebrow, title, body, items] = copy.features.groups[index];
              const header = card.match(/<header\b[^>]*>([\s\S]*?)<\/header>/)?.[1];
              assert.ok(header, "Every compact card groups its icon, label and heading in a semantic header");
              assert.equal(tags(header, "h3").length, 1);
              assert.equal(tags(header, "svg").filter(icon => icon["aria-hidden"] === "true").length, 1);
              for (const text of [eyebrow, title]) requireText(header, text);
              requireText(card.slice(card.indexOf("</header>") + 9), body);
              assert.equal(tags(card, "ul").length, 1, "Capabilities remain a native list, not hidden prose");
              assert.equal(items.length, 3);
              assert.equal(tags(card, "li").length, items.length);
              for (const item of items) requireText(card, item);
              assert.doesNotMatch(card, /mk-feature-number|<details\b|<summary\b|\shidden(?:\s|=|>)/, "Compactness must not hide explanations or retain a numeric column");
            }
            const bandStart = main.indexOf('class="mk-dark-band"');
            assert.ok(bandStart > featuresStart, "The application CTA follows the complete functional overview");
            const band = main.slice(bandStart);
            const bandAction = [...band.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)].find(match => match[2].includes(escapeHtml(copy.solution.bandAction)));
            assert.ok(bandAction, "The compact closing band retains its application action");
            assert.equal(attributes(bandAction[1]).href, `/${language}/producto/apps/`);
            assert.match(attributes(bandAction[1]).class, /\bmk-primary\b/);
          }
        }
        assert.equal(requestCount, 0, "Rendering merged and legacy entries must not perform network requests");
        assert.equal(languageChangeCount, 0);
      });

      await suite.test(`${language}/inicio: compact home retains its hero, four benefits, sector examples and actions`, () => {
        const markup = render(language, "inicio");
        const copy = getMarketingContent(language);
        const home = markup.match(/<div\b[^>]*class="mk-home-page"[^>]*>([\s\S]*?)<\/main>/)?.[1];
        assert.ok(home, "Home has a dedicated scope so compact styling does not leak into other screens");
        assert.equal(tags(home, "h1").length, 1);
        for (const text of [copy.home.kicker, copy.home.title, copy.home.titleAccent, copy.home.lead, copy.home.body, ...copy.home.checks]) requireText(home, text);
        const benefits = home.match(/<section\b[^>]*class="mk-app-strip"[^>]*>([\s\S]*?)<\/section>/)?.[1];
        assert.ok(benefits);
        assert.equal(copy.home.benefits.length, 4);
        assert.equal(tags(benefits, "article").length, copy.home.benefits.length);
        for (const texts of copy.home.benefits) for (const text of texts) requireText(benefits, text);
        for (const text of [copy.home.introKicker, copy.home.introTitle, copy.home.introAccent, copy.home.introBody, copy.home.introLink]) requireText(home, text);
        const sectors = home.match(/<div\b[^>]*class="mk-home-sector-preview"[^>]*>([\s\S]*?)<\/div>/)?.[1];
        assert.ok(sectors);
        assert.equal(copy.growth.sectors.items.length, 4);
        assert.equal(tags(sectors, "article").length, copy.growth.sectors.items.length);
        for (const [, label, headline, , bullets] of copy.growth.sectors.items) for (const text of [label, headline, bullets[0]]) requireText(sectors, text);
        const actions = [...home.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)];
        for (const [text, route] of [[copy.common.demo, "contacto"], [copy.common.exploreSolution, "solucion"], [copy.home.introLink, "solucion"]]) {
          assert.ok(actions.some(match => match[2].includes(escapeHtml(text)) && attributes(match[1]).href === `/${language}/producto/${route}/`), `The ${route} action remains a real localized link`);
        }
        assert.equal(requestCount, 0);
      });

      await suite.test(`${language}/apps: compact explorer retains all application and information tabs, explanations and request links`, () => {
        const markup = render(language, "apps");
        const copy = getMarketingContent(language);
        const guide = getApplicationGuides(language);
        const main = markup.match(/<main\b[^>]*>([\s\S]*?)<\/main>/)?.[1];
        assert.ok(main);
        for (const text of [copy.apps.kicker, copy.apps.title, copy.apps.accent, copy.apps.body]) requireText(main, text);
        const tabs = tags(main, "button").filter(button => button.role === "tab");
        assert.deepEqual(tabs.filter(button => button["data-app"]).map(button => button["data-app"]), ["venta", "gestion", "pda", "saas"]);
        assert.deepEqual(tabs.filter(button => button["data-testid"]?.startsWith("guide-section-")).map(button => button["data-testid"]), ["overview", "workflow", "setup", "questions"].map(section => `guide-section-${section}`));
        assert.equal(tabs.length, 8);
        assert.equal(tags(main, "div").filter(element => element.role === "tabpanel").length, 2);
        for (const [name, label] of copy.apps.items) for (const text of [name, label]) requireText(main, text);
        for (const text of [copy.apps.items[0][2], guide.apps.venta.outcome, guide.apps.venta.audience, guide.apps.venta.worksWith, guide.previewNote, guide.scopeNote]) requireText(main, text);
        for (const task of guide.apps.venta.tasks) for (const text of [task.title, task.body]) requireText(main, text);
        const gettingStarted = main.match(/<ol\b[^>]*class="mk-guide-start-steps"[^>]*>([\s\S]*?)<\/ol>/)?.[1];
        assert.ok(gettingStarted);
        assert.equal(tags(gettingStarted, "li").length, 3);
        for (const step of guide.gettingStarted.steps) for (const text of [step.title, step.body]) requireText(gettingStarted, text);
        const contactLinks = tags(main, "a").filter(anchor => anchor.href === `/${language}/producto/contacto/?apps=venta`);
        assert.equal(contactLinks.length, 1, "The selected application's demo has one shareable contact link");
        const preparationLinks = tags(main, "a").filter(anchor => anchor["data-testid"] === "guide-preparation-action");
        assert.equal(preparationLinks.length, 1, "Preparation is an information action, not a second demo request");
        assert.equal(preparationLinks[0].href, `/${language}/producto/apps/#mk-implementation-journey`);
        assert.equal(preparationLinks[0]["aria-controls"], "mk-implementation-journey");
        assert.ok(tags(main, "details").some(disclosure => disclosure.id === "mk-implementation-journey"));
        assert.ok(tags(main, "img").some(image => image.src === "/marketing/tour-venta.png" && image.alt));
        assert.equal(requestCount, 0, "Static content preservation does not exercise tab changes or send requests");
      });

      await suite.test(`${language}/ventajas: compact comparison keeps every row, accessible role and visible value`, () => {
        const markup = render(language, "ventajas");
        const copy = getMarketingContent(language).advantagesPage;
        for (const text of [copy.kicker, copy.title, copy.accent, copy.body, copy.note]) requireText(markup, text);
        const table = markup.match(/<div\b[^>]*class="mk-comparison"[^>]*>([\s\S]*?)<p class="mk-note">/)?.[1];
        assert.ok(table);
        assert.ok(tags(markup, "div").some(element => element.role === "table" && element["aria-label"] === escapeHtml(copy.kicker)));
        const rows = [...table.matchAll(/<div\b[^>]*role="row"[^>]*>([\s\S]*?)<\/div>/g)].map(match => match[1]);
        assert.equal(rows.length, copy.rows.length + 1);
        assert.equal((rows[0].match(/role="columnheader"/g) ?? []).length, copy.headers.length);
        assert.equal(copy.headers.length, 4);
        for (const text of copy.headers) requireText(rows[0], text);
        for (const [index, row] of rows.slice(1).entries()) {
          assert.equal((row.match(/role="rowheader"/g) ?? []).length, 1);
          const cells = tags(row, "span").filter(element => element.role === "cell");
          assert.equal(cells.length, 3, "Every capability retains all three technology approaches");
          assert.deepEqual(cells.map(cell => cell["data-label"]), copy.headers.slice(1).map(escapeHtml), "Reflowed comparison values retain their column labels");
          for (const text of copy.rows[index]) requireText(row, text);
          assert.equal(cells.filter(cell => cell.class === "is-ours").length, 1);
        }
        assert.equal(requestCount, 0);
      });

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
        for (const text of [copy.contact.formTag, copy.contact.formTitle, copy.contact.formBody, copy.contact.fullPackage, copy.contact.fullPackageHint, copy.contact.name, copy.contact.company, copy.contact.email, copy.contact.phone, copy.contact.caseLabel, copy.contact.consent, copy.contact.submit, copy.contact.faqAction, copy.contact.dataUse]) requireText(form, text);
        for (const [name] of copy.apps.items) requireText(selection, name);
        const products = tags(markup, "button").filter(button => /\bmk-contact-product\b/.test(button.class ?? ""));
        assert.equal(products.length, 4, "Compact contact retains the four application shortcuts");
        assert.deepEqual(products.map(product => product["aria-pressed"]), ["true", "false", "false", "false"]);
        const wechat = markup.match(/<section\b[^>]*class="mk-wechat-card"[^>]*>([\s\S]*?)<\/section>/)?.[1];
        assert.ok(wechat);
        for (const text of [copy.contact.wechat.title, copy.contact.wechat.body, copy.contact.wechat.hint, copy.contact.wechat.viewQr, copy.contact.wechat.copy, marketingContact.wechatName]) requireText(wechat, text);
        const wechatId = tags(wechat, "input").find(input => input.id === "mk-wechat-id");
        assert.equal(wechatId.value, marketingContact.wechatId);
        assert.ok(Object.hasOwn(wechatId, "readonly") || Object.hasOwn(wechatId, "readOnly"));
        const qr = tags(wechat, "img").find(image => image.src === marketingContact.wechatQr);
        assert.ok(qr, "The original real WeChat QR remains present, not an invented replacement");
        assert.equal(qr.alt, escapeHtml(copy.contact.wechat.qrAlt));
        assert.equal(qr.width, "888");
        assert.equal(qr.height, "1131");
        assert.ok(tags(wechat, "button").some(button => button["aria-haspopup"] === "dialog"));
        for (const texts of copy.contact.trust) for (const text of texts) requireText(markup, text);
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

    await suite.test("source CSS configuration: compact page overrides follow base marketing styles", async () => {
      // Source-order regression check only. This does not inspect the browser's
      // computed cascade, CSS geometry or rendered accessibility tree.
      const entry = await readFile(new URL("../src/main.tsx", import.meta.url), "utf8");
      const styleImports = [...entry.matchAll(/import\s+["'](\.\/[^"']+\.css)["']/g)].map(match => match[1]);
      const marketingIndex = styleImports.indexOf("./marketing.css");
      assert.ok(marketingIndex >= 0);
      for (const override of ["./compact-pages.css", "./compact-contact.css"]) {
        assert.equal(styleImports.filter(value => value === override).length, 1, `${override} must be imported exactly once`);
        assert.ok(styleImports.indexOf(override) > marketingIndex, `${override} must follow marketing.css in the entry's import order`);
      }
    });

    await suite.test("source CSS configuration: compact home explicitly resets legacy hero height and mobile action direction", async () => {
      // These declarations prevent two known source-level inheritance mistakes;
      // their presence is not proof of layout or absence of overlaps in a UI.
      const css = await readFile(new URL("../src/compact-pages.css", import.meta.url), "utf8");
      const hero = css.match(/\.mk-home-page\s+\.mk-hero-product\s*\{([^}]*)\}/)?.[1];
      assert.ok(hero, "The compact hero product needs its own scoped rule");
      assert.match(hero, /(?:^|;)\s*min-height\s*:\s*0\s*;/, "Reset the legacy minimum height instead of keeping an empty tall image panel");
      const mobileStart = css.indexOf("@media (max-width: 760px)");
      const nextMediaStart = css.indexOf("@media", mobileStart + 1);
      assert.ok(mobileStart >= 0);
      const mobile = css.slice(mobileStart, nextMediaStart < 0 ? undefined : nextMediaStart);
      const actions = mobile.match(/\.mk-home-page\s+\.mk-actions\s*\{([^}]*)\}/)?.[1];
      assert.ok(actions, "The compact mobile action row needs an explicit scoped rule");
      assert.match(actions, /(?:^|;)\s*flex-direction\s*:\s*row\s*;/, "A width-based 170px flex basis must not inherit a vertical column direction");
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
