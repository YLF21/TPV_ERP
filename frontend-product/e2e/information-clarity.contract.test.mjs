import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const languages = ["es", "en", "zh"];
const applicationIds = ["venta", "gestion", "pda", "saas"];
const expectedChoices = [
  { id: "checkout", applications: ["venta", "gestion"] },
  { id: "warehouse", applications: ["gestion", "pda"] },
  { id: "central", applications: ["saas"] },
  { id: "whole-team", applications: applicationIds }
];
const dependencyChecks = {
  es: { checkout: [/servicio de tienda/i, /validan/i], warehouse: [/autoriza/i, /red/i, /no sustituye/i], central: [/internet/i, /vinculad/i, /no implica edición remota completa/i], "whole-team": [/revisan/i, /cada aplicación/i] },
  en: { checkout: [/store service/i, /checked/i], warehouse: [/authori[sz]e/i, /network/i, /does not replace/i], central: [/internet/i, /linked/i, /not complete remote editing/i], "whole-team": [/reviewed/i, /each application/i] },
  zh: { checkout: [/门店服务/, /确认/], warehouse: [/授权/, /网络/, /不能代替/], central: [/互联网/, /关联/, /不代表可完整远程编辑/], "whole-team": [/确认/, /每款应用/] }
};
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// These tests inspect controlled React SSR output. They do not use a browser,
// listening HTTP server, DOM/layout engine, hydration or external service.
function attributes(source) {
  return Object.fromEntries([...source.matchAll(/([\w:-]+)(?:="([^"]*)")?/g)].map(match => [match[1], match[2] ?? ""]));
}

function tags(markup, name) {
  return [...markup.matchAll(new RegExp(`<${name}\\b([^>]*)>`, "g"))].map(match => attributes(match[1]));
}

function requireText(markup, text) {
  assert.ok(markup.includes(escapeHtml(text)), `Missing localized text: ${text}`);
}

function strings(value) {
  if (typeof value === "string") return [value];
  return Object.values(value).flatMap(strings);
}

function shape(value) {
  if (typeof value === "string") return "string";
  if (Array.isArray(value)) return value.map(shape);
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, shape(item)]));
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

function section(markup, testId) {
  const match = markup.match(new RegExp(`<section\\b([^>]*data-testid="${testId}"[^>]*)>([\\s\\S]*?)<\\/section>`));
  assert.ok(match, `Missing visible section ${testId}`);
  const props = attributes(match[1]);
  assert.equal(Object.hasOwn(props, "hidden"), false);
  assert.notEqual(props["aria-hidden"], "true");
  return match[2];
}

test("application choice, consultation scope and connectivity contracts (no browser or HTTP)", async suite => {
  const server = await createServer({
    configFile: false,
    root,
    plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom",
    optimizeDeps: { noDiscovery: true, include: [] }
  });
  const location = { origin: "http://ssr.invalid", pathname: "/", search: "", hash: "" };
  const storage = new Map();
  let actionCount = 0;
  let requestCount = 0;
  const restoreGlobals = withGlobals({
    window: { location },
    document: { referrer: "" },
    sessionStorage: {
      getItem: key => storage.get(key) ?? null,
      setItem: (key, value) => { storage.set(key, value); }
    },
    fetch: () => { requestCount += 1; throw new Error("Information contracts must not perform network requests"); }
  });

  try {
    assert.equal(server.httpServer, null, "The SSR loader must not create a listening server");
    const [{ default: MarketingPage }, { ApplicationChooser }, { getApplicationChoices, applicationChoiceScenarios }, { getConnectionContent }, { getMarketingContent }, { ApplicationExplorer }, campaign] = await Promise.all([
      server.ssrLoadModule("/src/MarketingPage.tsx"),
      server.ssrLoadModule("/src/ApplicationChooser.tsx"),
      server.ssrLoadModule("/src/applicationChoices.ts"),
      server.ssrLoadModule("/src/connectionContent.ts"),
      server.ssrLoadModule("/src/marketingI18n.ts"),
      server.ssrLoadModule("/src/ApplicationExplorer.tsx"),
      server.ssrLoadModule("/src/marketingCampaign.ts")
    ]);
    const onAction = () => { actionCount += 1; };
    const renderPage = (language, route, search = "") => {
      location.pathname = `/${language}/producto/${route}/`;
      location.hash = "";
      location.search = search;
      storage.clear();
      return renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange: onAction }));
    };
    const applicationProps = (language, id) => {
      const marketing = getMarketingContent(language);
      const index = applicationIds.indexOf(id);
      return { id, name: marketing.apps.items[index][0], label: marketing.apps.items[index][1], description: marketing.apps.items[index][2], previewAlt: marketing.tour.previewAlts[index] };
    };

    await suite.test("four orientative choices preserve the application combinations and dependencies", () => {
      assert.deepEqual(applicationChoiceScenarios.map(({ id, applications }) => ({ id, applications: [...applications] })), expectedChoices);
      for (const choice of applicationChoiceScenarios) {
        assert.equal(new Set(choice.applications).size, choice.applications.length);
        assert.ok(choice.applications.every(id => applicationIds.includes(id)));
      }
    });

    await suite.test("all new information is complete in ES, EN and ZH without prices or universal guarantees", () => {
      const referenceChoices = shape(getApplicationChoices("es"));
      const referenceConnection = shape(getConnectionContent("es"));
      for (const language of languages) {
        const choices = getApplicationChoices(language);
        const connection = getConnectionContent(language);
        assert.deepEqual(shape(choices), referenceChoices, `${language}: chooser translations match`);
        assert.deepEqual(shape(connection), referenceConnection, `${language}: connection translations match`);
        assert.deepEqual(Object.keys(choices.scenarios), expectedChoices.map(choice => choice.id));
        assert.equal(connection.items.length, 3, "Local service, internet and synchronized data need separate explanations");
        assert.match(connection.items[2].body, /internet|互联网/i, "The general SaaS summary explicitly requires internet access");
        assert.doesNotMatch(getMarketingContent(language).growth.sectors.items[1][2], /tiempo real|real-time|实时库存/i, "Sector copy must not promise universally real-time inventory");
        assert.deepEqual(Object.keys(connection.apps), applicationIds);
        assert.equal(new Set(Object.values(connection.apps)).size, 4, "Connection guidance must be application-specific");
        const contact = getMarketingContent(language).contact;
        const addedCopy = [choices, connection, { fullPackage: contact.fullPackage, fullPackageHint: contact.fullPackageHint, selectionHint: contact.selectionHint }];
        for (const text of strings(addedCopy)) {
          assert.ok(text.trim().length > 0, `${language}: new information must not be blank`);
          assert.doesNotMatch(text, /\bTODO\b|Lorem ipsum|https?:\/\/example\.|\bTPV[\s_-]*ERP\b|\b(?:esPos|EsPos|Espos|ESPOS)\b/);
          assert.doesNotMatch(text, /(?:\d+[,.]?\d*\s*(?:€|EUR|USD)|[$€]\s*\d)|24\/7|100%|certificado por la AEAT/i);
          assert.doesNotMatch(text, /(?:siempre disponible|actualizaci[oó]n instant[aá]nea garantizada|(?:funciona|trabaja) completamente offline|always available|guaranteed instant updates|(?:works|runs|is) fully offline|保证即时更新|完全离线运行)/i);
        }
        assert.notEqual(contact.fullPackage, ({ es: "Paquete completo", en: "Complete package", zh: "完整套装" })[language], "The checkbox must express interest rather than name a fixed commercial plan");
        assert.ok(contact.selectionHint.length >= 40, `${language}: consultation scope needs a substantive explanation`);
        assert.ok(choices.note.length >= 35, `${language}: combinations must be identified as orientative`);
        for (const choice of expectedChoices) {
          const dependency = choices.scenarios[choice.id].dependency;
          assert.ok(dependency.length >= 25, `${language}/${choice.id}: explain the setup dependency`);
          for (const pattern of dependencyChecks[language][choice.id]) assert.match(dependency, pattern, `${language}/${choice.id}: preserve the access and scope conditions`);
        }
      }
    });

    for (const language of languages) {
      await suite.test(`${language}: chooser is compact, localized and contains real application-aware contact links`, () => {
        const copy = getApplicationChoices(language);
        const markup = renderToStaticMarkup(createElement(ApplicationChooser, { language, onRequestApplications: onAction }));
        const details = tags(markup, "details").find(tag => tag["data-testid"] === "application-chooser");
        assert.ok(details, "The chooser uses a keyboard-operable native disclosure");
        assert.equal(Object.hasOwn(details, "open"), false, "Expanded detail must not crowd the initial page");
        assert.equal(tags(markup, "summary").length, 1);
        for (const value of [copy.title, copy.subtitle, copy.dependenciesLabel, copy.requestAction, copy.note]) requireText(markup, value);
        const cards = tags(markup, "article").filter(tag => tag["data-choice"]);
        assert.deepEqual(cards.map(tag => tag["data-choice"]), expectedChoices.map(choice => choice.id));
        for (const choice of expectedChoices) {
          const link = tags(markup, "a").find(tag => tag["data-testid"] === `application-choice-${choice.id}`);
          assert.ok(link);
          const search = campaign.marketingContactSearch(choice.applications);
          assert.equal(link.href, `/${language}/producto/contacto/${search}`);
          assert.deepEqual(campaign.parseRequestedApplications(new URL(link.href, "http://ssr.invalid").search), choice.applications);
          for (const value of Object.values(copy.scenarios[choice.id])) requireText(markup, value);
        }
        assert.equal(actionCount, 0, "Rendering choices must not navigate or request a demo");
        assert.ok(tags(renderPage(language, "apps"), "details").some(tag => tag["data-testid"] === "application-chooser"), "Apps integrates the chooser");
      });

      await suite.test(`${language}: consultation hint is visible and associated with the app checkboxes`, () => {
        const copy = getMarketingContent(language).contact;
        const markup = renderPage(language, "contacto");
        const fieldsetMatch = markup.match(/<fieldset\b([^>]*class="[^"]*\bmk-contact-selection\b[^"]*"[^>]*)>([\s\S]*?)<\/fieldset>/);
        assert.ok(fieldsetMatch);
        const fieldset = attributes(fieldsetMatch[1]);
        assert.ok(fieldset["aria-describedby"].split(/\s+/).includes("mk-contact-selection-hint"));
        const hint = fieldsetMatch[2].match(/<p\b([^>]*id="mk-contact-selection-hint"[^>]*)>([\s\S]*?)<\/p>/);
        assert.ok(hint, "Selection scope must be in the fieldset, not hidden in FAQs");
        assert.equal(Object.hasOwn(attributes(hint[1]), "hidden"), false);
        requireText(hint[2], copy.selectionHint);
        requireText(fieldsetMatch[2], copy.fullPackage);
        requireText(fieldsetMatch[2], copy.fullPackageHint);
        assert.equal(tags(fieldsetMatch[2], "input").filter(input => input.type === "checkbox").length, 5, "One select-all control and four individual choices remain");
      });

      await suite.test(`${language}: scenario links preselect exactly the requested apps without submitting`, () => {
        for (const choice of expectedChoices) {
          const markup = renderPage(language, "contacto", campaign.marketingContactSearch(choice.applications));
          const inputs = tags(markup, "input");
          assert.deepEqual(inputs.filter(input => input.name === "products" && Object.hasOwn(input, "checked")).map(input => input.value), choice.applications);
          const wholeCollection = inputs.find(input => input.name === "fullPackage");
          assert.ok(wholeCollection);
          assert.equal(Object.hasOwn(wholeCollection, "checked"), choice.applications.length === 4);
          assert.equal(requestCount, 0, "Application selection must not submit a real lead or check an external service during SSR");
        }
      });

      await suite.test(`${language}: Solution explains local service, internet and synchronized data before features`, () => {
        const copy = getConnectionContent(language);
        const markup = renderPage(language, "solucion");
        const summary = section(markup, "connection-summary");
        requireText(summary, copy.title);
        requireText(summary, copy.body);
        for (const item of copy.items) {
          requireText(summary, item.title);
          requireText(summary, item.body);
        }
        assert.doesNotMatch(summary, /<details\b/, "Connection constraints are immediately present, not buried in a disclosure");
        const firstFeature = getMarketingContent(language).features.groups[0][1];
        assert.ok(markup.indexOf('data-testid="connection-summary"') < markup.indexOf(escapeHtml(firstFeature)), "Connectivity summary precedes the feature catalogue");
      });

      for (const id of applicationIds) {
        await suite.test(`${language}/${id}: connection guidance is visible in the initial overview, outside setup tabs`, () => {
          const copy = getConnectionContent(language);
          const markup = renderToStaticMarkup(createElement(ApplicationExplorer, { language, applications: [applicationProps(language, id)], onOpenTour: onAction, onRequestDemo: onAction }));
          const summary = section(markup, "application-connection-summary");
          requireText(summary, copy.appLabel);
          requireText(summary, copy.apps[id]);
          assert.doesNotMatch(summary, /<details\b/);
          assert.ok(markup.indexOf('data-testid="application-connection-summary"') < markup.indexOf('data-testid="guide-content-panel"'), "The app constraint must not be gated by the selected detail tab");
          const selectedSection = tags(markup, "div").find(tag => tag["data-testid"] === "guide-content-panel");
          assert.equal(selectedSection["data-section"], "overview");
          assert.equal(tags(markup, "button").filter(button => button.role === "tab" && button["data-testid"]?.startsWith("guide-section-")).length, 4, "Existing guide sections remain available");
        });
      }
    }

    assert.equal(actionCount, 0);
    assert.equal(requestCount, 0);
  } finally {
    restoreGlobals();
    await server.close();
  }
});
