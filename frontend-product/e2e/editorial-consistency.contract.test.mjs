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
const applicationIds = ["venta", "gestion", "pda", "saas"];
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const wrongBrand = /\bTPV[\s_-]*ERP\b|\b(?:esPos|EsPos|Espos|ESPOS)\b/;
// Apply these to authored English values, not filenames, API identifiers or URLs.
// "Licensing" is also correct British usage; only the authored noun forms
// "license(s)" are guarded here (current copy uses them as nouns).
const mixedUsSpelling = /\b(?:licenses?|organizations?|organiz(?:e|es|ed|ing)|authoriz(?:e|es|ed|ing|ation)|anonymiz(?:e|es|ed|ing|ation)|synchroniz(?:e|es|ed|ing|ation)|centraliz(?:e|es|ed|ing|ation))\b/i;
const inventedPrice = /(?:\d+[,.]?\d*\s*(?:€|EUR|USD)|[$€]\s*\d)/i;
const unsupportedCertification = /certificad[oa]\s+por\s+(?:la\s+)?AEAT|AEAT[- ]certified|经?\s*(?:AEAT|税务局)\s*认证/i;
const unqualifiedGuarantee = /24\s*\/\s*7|100\s*%|siempre disponible|always available|guaranteed instant updates|garantiza(?:da)?\s+(?:compatibilidad|actualizaci[oó]n instant[aá]nea)|保证即时更新|保证兼容/i;

function assertNoPositiveGuarantee(text, context) {
  for (const match of text.matchAll(new RegExp(unqualifiedGuarantee.source, "gi"))) {
    const prefix = text.slice(Math.max(0, match.index - 55), match.index);
    // Explicitly stated limitations are useful copy, not invented offers.
    const negated = /(?:\b(?:no|not|never|neither|without)\b[^.!?;:]*|(?:不|未|并非|不能|无法)[^。！？；：]*)$/i.test(prefix);
    assert.ok(negated, `${context}: unsupported affirmative guarantee: ${match[0]}`);
  }
}

function stringEntries(value, path = "") {
  if (typeof value === "string") return [[path, value]];
  return Object.entries(value).flatMap(([key, child]) => stringEntries(child, `${path}.${key}`));
}

function shape(value) {
  if (typeof value === "string") return "string";
  if (Array.isArray(value)) return value.map(shape);
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, shape(child)]));
}

function requireText(markup, text, message) {
  assert.ok(markup.includes(escapeHtml(text)), message ?? `Missing localized text: ${text}`);
}

function occurrences(markup, text) {
  return markup.split(escapeHtml(text)).length - 1;
}

function metaContent(html, key) {
  return html.match(new RegExp(`<meta\\b[^>]*(?:name|property)="${key}"[^>]*content="([^"]*)"`))?.[1];
}

function assertAuthoredCopy(label, language, value) {
  for (const [path, text] of stringEntries(value)) {
    const context = `${label}/${language}${path}`;
    assert.ok(text.trim().length > 0, `${context}: missing text`);
    assert.doesNotMatch(text, wrongBrand, `${context}: public brand must be esPOS`);
    assert.doesNotMatch(text, /\bTODO\b|[Ll]orem ipsum|https?:\/\/example\./, `${context}: unfinished or sample copy`);
    assert.doesNotMatch(text, inventedPrice, `${context}: no invented prices`);
    assert.doesNotMatch(text, unsupportedCertification, `${context}: no unverified certification`);
    assertNoPositiveGuarantee(text, context);
    if (language === "en") assert.doesNotMatch(text, mixedUsSpelling, `${context}: keep British English consistent`);
    if (language === "zh") assert.doesNotMatch(text, /你/, `${context}: use the customer address 您 consistently`);
  }
}

// Controlled source and React SSR checks only: no browser, listening HTTP
// server, geometry, hydration, real form submission or third-party requests.
test("trilingual editorial consistency and compact information contracts (no browser or HTTP)", async suite => {
  const server = await createServer({
    configFile: false, root, plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  const previous = new Map(["window", "document", "sessionStorage", "localStorage", "fetch"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  const location = { origin: "http://ssr.invalid", pathname: "/", search: "", hash: "" };
  let requests = 0;
  let actions = 0;
  const globals = {
    window: { location }, document: { referrer: "" },
    sessionStorage: { getItem: () => null, setItem: () => {} },
    localStorage: { getItem: () => null, setItem: () => {} },
    fetch: () => { requests += 1; throw new Error("Editorial SSR must not perform requests"); }
  };
  for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

  try {
    assert.equal(server.httpServer, null);
    const [marketing, ui, guides, choices, connection, compatibility, implementation, support, dialogFeedback, comparison, tourMedia, tourExamples, sectors, connected, receipts, decisions, imports, previews, seo, routes, { productBrand }, { marketingContact }, { CompatibilityChecklist }, { SupportInformation }, { ApplicationExplorer }, { default: MarketingPage }] = await Promise.all([
      "/src/marketingI18n.ts", "/src/marketingUiI18n.ts", "/src/applicationGuides.ts", "/src/applicationChoices.ts",
      "/src/connectionContent.ts", "/src/compatibilityContent.ts", "/src/implementationContent.ts", "/src/supportContent.ts", "/src/MarketingDialogLoader.tsx", "/src/AdvantagesComparison.tsx", "/src/tourStepMedia.ts", "/src/tourExamples.ts",
      "/src/sectorCaseContent.ts", "/src/connectedScenarioContent.ts", "/src/demoReceiptContent.ts",
      "/src/comparisonDecisionContent.ts", "/src/productImportHelpContent.ts",
      "/src/applicationPreviewContent.ts",
      "/src/seo.ts", "/src/marketingRoutes.ts", "/src/brand.ts", "/src/contact.ts",
      "/src/CompatibilityChecklist.tsx", "/src/SupportInformation.tsx", "/src/ApplicationExplorer.tsx", "/src/MarketingPage.tsx"
    ].map(path => server.ssrLoadModule(path)));
    const stores = {
      marketing: marketing.getMarketingContent, ui: ui.getMarketingUiContent,
      guides: guides.getApplicationGuides, choices: choices.getApplicationChoices,
      connection: connection.getConnectionContent, compatibility: compatibility.getCompatibilityContent,
      implementation: implementation.getImplementationJourney, support: support.getSupportContent,
      dialogFeedback: dialogFeedback.getMarketingDialogFeedbackContent, comparison: comparison.getComparisonPresentation,
      tourMedia: tourMedia.getTourStepMediaContent, tourExamples: tourExamples.getTourExamples,
      sectors: sectors.getSectorCaseContent, connected: connected.getConnectedScenario, receipts: receipts.getDemoReceiptContent,
      decisions: decisions.getComparisonDecisionContent, imports: imports.getProductImportHelp
    };
    const onAction = () => { actions += 1; };

    await suite.test("claim guard distinguishes explicit limitations from invented guarantees", () => {
      for (const text of ["No se garantiza compatibilidad.", "This does not mean guaranteed instant updates.", "不保证即时更新", "No ofrecemos atención 24/7."]) {
        assert.doesNotThrow(() => assertNoPositiveGuarantee(text, "negative claim"));
      }
      for (const text of ["Always available", "Guaranteed instant updates", "保证即时更新", "Atención 24/7"]) {
        assert.throws(() => assertNoPositiveGuarantee(text, "positive claim"));
      }
    });

    await suite.test("all seventeen text stores have complete matching ES/EN/ZH structures and consistent public copy", () => {
      assert.equal(productBrand.name, "esPOS");
      assert.equal(Object.keys(stores).length, 17);
      for (const [name, getContent] of Object.entries(stores)) {
        const reference = shape(getContent("es"));
        const spanishEntries = new Map(stringEntries(getContent("es")));
        for (const language of languages) {
          assert.deepEqual(shape(getContent(language)), reference, `${name}/${language}: translation structure differs`);
          assertAuthoredCopy(name, language, getContent(language));
          for (const [path, text] of stringEntries(getContent(language))) {
            const placeholders = value => [...value.matchAll(/\{([a-z][a-z0-9_]*)\}/gi)].map(match => match[1]).sort();
            assert.deepEqual(placeholders(text), placeholders(spanishEntries.get(path)), `${name}/${language}${path}: preserve interpolation placeholders such as {max}`);
          }
        }
      }
    });

    await suite.test("metadata for all five pages preserves bounded scope and safe publication defaults", () => {
      assert.deepEqual([...routes.routeIds], ["inicio", "solucion", "apps", "ventajas", "contacto"]);
      for (const language of languages) {
        for (const route of routes.routeIds) {
          const metadata = seo.buildMarketingMetadata(language, route);
          assertAuthoredCopy(`metadata/${route}`, language, { title: metadata.title, description: metadata.description, imageAlt: metadata.imageAlt });
          assert.ok(metadata.title.includes("esPOS"), `${language}/${route}: title identifies esPOS`);
          assert.equal(metadata.robots, "noindex, follow", "Editorial changes must not enable indexing");
          assert.equal(metadata.canonical, null, "A public origin must not be invented");
          const text = `${metadata.title} ${metadata.description}`;
          assert.doesNotMatch(text, /facturaci[oó]n consolidada|consolidated (?:billing|accounting)|合并(?:开票|会计)|全天候支持|soporte incluido|support included|included support/i);
          const graph = JSON.stringify(metadata.structuredData);
          assert.doesNotMatch(graph, /"(?:offers|price|aggregateRating|review|publisher)"|"@type":"Organization"/, "No commercial or corporate claims are inferred from editorial copy");
        }
        const description = seo.buildMarketingMetadata(language, "inicio").description;
        assert.match(description, { es: /informaci[oó]n recibida.*tiendas vinculadas/i, en: /information received.*linked stores/i, zh: /关联门店收到的信息/ }[language], "The central view is of received information, not an unconditional live-editing promise");
      }
    });

    await suite.test("Spanish template metadata agrees with the generated Spanish homepage", async () => {
      const template = await readFile(new URL("../index.html", import.meta.url), "utf8");
      const metadata = seo.buildMarketingMetadata("es", "inicio");
      assert.equal(template.match(/<title>([^<]*)<\/title>/)?.[1], metadata.title);
      assert.equal(metaContent(template, "description"), metadata.description);
      assert.equal(metaContent(template, "og:title"), metadata.title);
      assert.equal(metaContent(template, "og:description"), metadata.description);
      assert.equal(metaContent(template, "application-name"), productBrand.name);
      assert.match(metaContent(template, "robots"), /^noindex,\s*follow$/);
    });

    for (const language of languages) {
      await suite.test(`${language}: compact compatibility keeps checks without repeating setup requirements`, () => {
        const copy = compatibility.getCompatibilityContent(language);
        for (const id of applicationIds) {
          const defaultMarkup = renderToStaticMarkup(createElement(CompatibilityChecklist, { language, application: id }));
          const compactMarkup = renderToStaticMarkup(createElement(CompatibilityChecklist, { language, application: id, includeRequirements: false }));
          assert.match(defaultMarkup, /data-status="documented"/);
          assert.doesNotMatch(compactMarkup, /data-status="documented"/);
          assert.match(compactMarkup, /data-status="to-confirm"/);
          for (const text of copy.apps[id].requirements) {
            assert.equal(occurrences(defaultMarkup, text), 1, `${language}/${id}: default preserves each documented requirement`);
            assert.equal(occurrences(compactMarkup, text), 0, `${language}/${id}: setup checklist must not repeat its existing requirement list`);
          }
          for (const text of copy.apps[id].toConfirm) requireText(compactMarkup, text);
          for (const text of [copy.verificationNote, copy.privacyNote, ...copy.prepareItems]) requireText(compactMarkup, text);
          assert.doesNotMatch(compactMarkup, /<details\b[^>]*\bopen(?:[\s=>])/);
        }
      });

      await suite.test(`${language}: Contact publishes the WeChat ID once while standalone support retains the account`, () => {
        const defaultMarkup = renderToStaticMarkup(createElement(SupportInformation, { language }));
        const compactMarkup = renderToStaticMarkup(createElement(SupportInformation, { language, showAccount: false }));
        assert.equal(occurrences(defaultMarkup, marketingContact.wechatId), 1);
        assert.equal(occurrences(compactMarkup, marketingContact.wechatId), 0);
        const supportCopy = support.getSupportContent(language);
        for (const text of [supportCopy.wechatBody, supportCopy.saasBody, supportCopy.receiptBody, supportCopy.unavailableBody]) requireText(compactMarkup, text);
        location.pathname = routes.marketingHref(language, "contacto");
        const page = renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange: onAction }));
        assert.equal(occurrences(page, marketingContact.wechatId), 1, "The main WeChat card supplies the account; support must not duplicate it");
        assert.match(page, /data-testid="support-information"/);
        requireText(page, supportCopy.receiptBody);
        requireText(page, supportCopy.unavailableBody);
      });

      await suite.test(`${language}: each application keeps one persistent connection explanation outside its detail tabs`, () => {
        const marketingCopy = marketing.getMarketingContent(language);
        const connectionCopy = connection.getConnectionContent(language);
        for (const [index, id] of applicationIds.entries()) {
          const app = marketingCopy.apps.items[index];
          const markup = renderToStaticMarkup(createElement(ApplicationExplorer, { language, applications: [{ id, name: app[0], label: app[1], description: app[2], previewAlt: previews.getApplicationPreviewAlts(language)[id] }], onOpenTour: onAction, onRequestDemo: onAction }));
          assert.equal(occurrences(markup, connectionCopy.apps[id]), 1);
          const summaryIndex = markup.indexOf('data-testid="application-connection-summary"');
          const detailIndex = markup.indexOf('data-testid="guide-content-panel"');
          assert.ok(summaryIndex > -1 && summaryIndex < detailIndex, "Connectivity limits must precede, not depend on, the selected tab");
          requireText(markup, connectionCopy.appLabel);
        }
      });

      await suite.test(`${language}: canonical pages avoid verbatim repeated long paragraphs`, () => {
        for (const route of routes.routeIds) {
          location.pathname = routes.marketingHref(language, route);
          const markup = renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange: onAction }));
          const paragraphs = [...markup.matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/g)]
            .map(match => match[1].replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim())
            .filter(text => text.length > 100);
          const seen = new Set();
          for (const paragraph of paragraphs) {
            assert.ok(!seen.has(paragraph), `${language}/${route}: repeated long paragraph: ${paragraph}`);
            seen.add(paragraph);
          }
        }
      });
    }

    await suite.test("setup source retains requirements once and reuses the persistent connection summary", async () => {
      const source = await readFile(new URL("../src/ApplicationExplorer.tsx", import.meta.url), "utf8");
      const setup = source.match(/activeSection === "setup"([\s\S]*?)activeSection === "questions"/)?.[1];
      assert.ok(setup);
      assert.match(setup, /guide\.requirements\.map/);
      assert.match(setup, /<CompatibilityChecklist\b[^>]*includeRequirements=\{false\}/);
      assert.doesNotMatch(setup, /guide\.connection|copy\.connectionLabel/, "Do not reintroduce a second connection paragraph in setup");
      assert.match(source, /data-testid="application-connection-summary"/);
    });

    assert.equal(requests, 0, "No form submission, availability request or external call is made by editorial SSR");
    assert.equal(actions, 0, "Rendering editorial information cannot activate, purchase or navigate");
  } finally {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await server.close();
  }
});
