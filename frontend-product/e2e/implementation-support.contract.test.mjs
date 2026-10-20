import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement, Fragment } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const languages = ["es", "en", "zh"];
const applicationIds = ["venta", "gestion", "pda", "saas"];
const stageIds = ["scope", "review", "validation", "rollout"];
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

function attributes(source) {
  return Object.fromEntries([...source.matchAll(/([\w:-]+)(?:="([^"]*)")?/g)].map(match => [match[1], match[2] ?? ""]));
}

function tags(markup, name) {
  return [...markup.matchAll(new RegExp(`<${name}\\b([^>]*)>`, "g"))].map(match => attributes(match[1]));
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

function requireText(markup, text) {
  assert.ok(markup.includes(escapeHtml(text)), `Missing localized text: ${text}`);
}

function closedDisclosure(markup, testId) {
  const details = tags(markup, "details").find(tag => tag["data-testid"] === testId);
  assert.ok(details, `${testId}: use a native disclosure`);
  assert.equal(Object.hasOwn(details, "open"), false, `${testId}: details should be closed initially`);
  assert.equal(Object.hasOwn(details, "hidden"), false);
  assert.notEqual(details["aria-hidden"], "true");
  assert.match(details.class, /\bmk-service-disclosure\b/);
  return details;
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

// Controlled SSR and source contracts only. No browser, listening HTTP server,
// geometry, hydration, real form submission or modal interaction is exercised.
test("implementation, compatibility and support information contracts (no browser or HTTP)", async suite => {
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
    fetch: () => { requestCount += 1; throw new Error("Information-only SSR must not make requests"); }
  });

  try {
    assert.equal(server.httpServer, null, "The test loader must not create a listening server");
    const [journeyData, { ImplementationJourney }, compatibilityData, { CompatibilityChecklist }, supportData, { SupportInformation }, { getApplicationGuides }, { getMarketingContent }, { marketingContact }, { default: MarketingPage }] = await Promise.all([
      server.ssrLoadModule("/src/implementationContent.ts"),
      server.ssrLoadModule("/src/ImplementationJourney.tsx"),
      server.ssrLoadModule("/src/compatibilityContent.ts"),
      server.ssrLoadModule("/src/CompatibilityChecklist.tsx"),
      server.ssrLoadModule("/src/supportContent.ts"),
      server.ssrLoadModule("/src/SupportInformation.tsx"),
      server.ssrLoadModule("/src/applicationGuides.ts"),
      server.ssrLoadModule("/src/marketingI18n.ts"),
      server.ssrLoadModule("/src/contact.ts"),
      server.ssrLoadModule("/src/MarketingPage.tsx")
    ]);
    const { getImplementationJourney, implementationStepIds } = journeyData;
    const { getCompatibilityContent } = compatibilityData;
    const { getSupportContent } = supportData;
    const onAction = () => { actionCount += 1; };
    const renderPage = (language, route) => {
      location.pathname = `/${language}/producto/${route}/`;
      location.search = "";
      location.hash = "";
      storage.clear();
      return renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange: onAction }));
    };

    await suite.test("all three content contracts are complete and localized without fabricated offers", () => {
      const getters = [getImplementationJourney, getCompatibilityContent, getSupportContent];
      for (const getContent of getters) {
        const reference = shape(getContent("es"));
        const titles = new Set();
        for (const language of languages) {
          const copy = getContent(language);
          assert.deepEqual(shape(copy), reference, `${language}: translation structures must match`);
          titles.add(copy.title);
          for (const text of strings(copy)) {
            assert.ok(text.trim().length > 0, `${language}: information must not be blank`);
            assert.doesNotMatch(text, /\bTODO\b|Lorem ipsum|https?:\/\/example\.|\bTPV[\s_-]*ERP\b|\b(?:esPos|EsPos|Espos|ESPOS)\b/);
            assert.doesNotMatch(text, /(?:\d+[,.]?\d*\s*(?:€|EUR|USD)|[$€]\s*\d)|24\/7|100%|\bSLA\s*[:=]\s*\d/i, "Do not invent prices or availability/response guarantees");
            assert.doesNotMatch(text, /\b(?:Windows (?:10|11)|Android \d+|iOS \d+|Epson|Zebra|Sunmi|Star Micronics)\b/i, "Do not publish an unverified version or certified hardware model");
            assert.doesNotMatch(text, /(?:implantaci[oó]n (?:en|garantizada en) \d|(?:rollout|deployment) (?:within|in) \d|\d+\s*(?:小时内完成部署|天内上线))/i, "No invented rollout deadlines");
          }
        }
        assert.equal(titles.size, 3, "The disclosure title must be translated, not reused as a fallback");
      }
    });

    await suite.test("journey has exactly four after-demo stages with separate preparation and acceptance boundaries", () => {
      assert.deepEqual([...implementationStepIds], stageIds);
      for (const language of languages) {
        const copy = getImplementationJourney(language);
        assert.deepEqual(Object.keys(copy.steps), stageIds);
        for (const id of stageIds) {
          assert.deepEqual(Object.keys(copy.steps[id]), ["title", "body", "customerPreparation", "confirmation"]);
          assert.ok(copy.steps[id].customerPreparation.length >= 15);
          assert.ok(copy.steps[id].confirmation.length >= 25);
        }
        const review = copy.steps.review;
        assert.match(review.customerPreparation, /anonimizada|anonymised|匿名化/i);
        assert.match(review.customerPreparation, /contraseñas|passwords|密码/i);
        assert.match(review.confirmation, /migración|migration|迁移/i);
        assert.match(copy.steps.validation.confirmation, /autorización|authorisation|授权/i);
        assert.match(copy.note, /orientativo|indicative|仅供参考/i);
        assert.match(copy.note, /responsables|responsibilities|负责人/i);
        assert.match(copy.note, /formación|training|培训/i);
        assert.match(copy.note, /no modifica datos de producción|does not change production data|不会修改生产数据/i);
        assert.match(copy.note, /no activa aplicaciones|activate applications|开通应用/i);
        assert.match(copy.note, /compra|purchase|购买/i);
      }
    });

    await suite.test("documented requirements reuse the existing application guides, while installation checks remain separate", () => {
      for (const language of languages) {
        const copy = getCompatibilityContent(language);
        const guides = getApplicationGuides(language);
        const marketing = getMarketingContent(language);
        assert.deepEqual(Object.keys(copy.apps), applicationIds);
        assert.notEqual(copy.documentedLabel, copy.toConfirmLabel);
        assert.equal(copy.prepareItems.length, 4);
        assert.match(copy.privacyNote, /contraseñas|passwords|密码/i);
        assert.match(copy.privacyNote, /tarjeta|card|银行卡/i);
        assert.match(copy.verificationNote, /no una lista de modelos certificados|not a certified-model list|不代表认证机型清单/i);
        assert.match(copy.verificationNote, /Pendiente de validación|Pending validation|待验证/i);
        for (const [index, id] of applicationIds.entries()) {
          assert.equal(copy.apps[id].name, marketing.apps.items[index][0]);
          assert.deepEqual(copy.apps[id].requirements, guides.apps[id].requirements, `${language}/${id}: do not create a competing requirement list`);
          assert.equal(copy.apps[id].toConfirm.length, 3);
          assert.notDeepEqual(copy.apps[id].toConfirm, copy.apps[id].requirements);
        }
      }
    });

    await suite.test("support states scope, receipt limits and backup ownership without defining a service entitlement", () => {
      for (const language of languages) {
        const copy = getSupportContent(language);
        assert.equal(copy.sections.length, 3);
        for (const section of copy.sections) assert.equal(section.items.length, 3);
        assert.match(copy.intro, /no define servicios incluidos|does not define included services|不代表已包含任何服务/i);
        assert.match(copy.sections[0].items[2], /No envíes contraseñas|Do not send passwords|请勿发送密码/i);
        assert.match(copy.sections[1].items[0], /horarios|hours|服务时间/i);
        assert.match(copy.sections[1].items[2], /confirma.*si están incluidas|confirm.*whether it is included|确认.*是否已包含/i);
        assert.match(copy.sections[2].items[0], /quién se responsabiliza|who is responsible|由谁负责/i);
        assert.match(copy.sections[2].items[2], /Consulta si se incluye|Ask whether.*included|咨询是否包含/i);
        assert.match(copy.receiptBody, /referencia de recepción|receipt reference|接收编号/i);
        assert.match(copy.receiptBody, /No significa|does not mean|这不代表/i);
        assert.match(copy.unavailableBody, /no envía los datos del formulario|does not send your form data|不会发送表单内容/i);
      }
    });

    for (const language of languages) {
      await suite.test(`${language}: journey renders four ordered stages and their preparation/confirmation fields in a closed native disclosure`, () => {
        const copy = getImplementationJourney(language);
        const markup = renderToStaticMarkup(createElement(ImplementationJourney, { language }));
        closedDisclosure(markup, "implementation-journey");
        assert.equal(tags(markup, "summary").length, 1);
        assert.equal(tags(markup, "ol").length, 1);
        assert.deepEqual(tags(markup, "li").map(tag => tag["data-implementation-step"]), stageIds);
        assert.equal(tags(markup, "h3").length, 4);
        assert.equal(tags(markup, "dl").length, 4);
        assert.equal(tags(markup, "dt").length, 8);
        assert.equal(tags(markup, "dd").length, 8);
        for (const text of strings(copy)) requireText(markup, text);
        assert.equal(tags(markup, "form").length + tags(markup, "input").length + tags(markup, "button").length, 0, "The informational journey must not collect data or activate anything");
      });

      for (const application of [undefined, ...applicationIds]) {
        await suite.test(`${language}/${application ?? "all"}: compatibility renders only the requested apps with labelled documented and to-confirm lists`, () => {
          const copy = getCompatibilityContent(language);
          const markup = renderToStaticMarkup(createElement(CompatibilityChecklist, { language, application }));
          const details = closedDisclosure(markup, "compatibility-checklist");
          assert.equal(details["data-app"], application ?? "all");
          const expectedIds = application ? [application] : applicationIds;
          const articles = tags(markup, "article");
          assert.deepEqual(articles.map(tag => tag["data-application"]), expectedIds);
          const headings = tags(markup, "h3");
          for (const article of articles) {
            assert.ok(headings.some(heading => heading.id === article["aria-labelledby"]), "Each app card must reference its real heading");
          }
          const statuses = tags(markup, "div").filter(tag => tag["data-status"]).map(tag => tag["data-status"]);
          assert.deepEqual(statuses, expectedIds.flatMap(() => ["documented", "to-confirm"]));
          for (const id of expectedIds) for (const text of strings(copy.apps[id])) requireText(markup, text);
          for (const text of [copy.title, copy.subtitle, copy.documentedLabel, copy.toConfirmLabel, copy.verificationNote, copy.prepareTitle, ...copy.prepareItems, copy.privacyNote]) requireText(markup, text);
          assert.equal(tags(markup, "summary").length, 1);
          assert.equal(tags(markup, "input").length, 0, "Compatibility guidance must not ask for files or credentials");
        });
      }

      await suite.test(`${language}: repeated compatibility instances keep unique labels and valid heading references`, () => {
        const markup = renderToStaticMarkup(createElement(Fragment, null,
          createElement(CompatibilityChecklist, { language }),
          createElement(CompatibilityChecklist, { language, application: "pda" })
        ));
        const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
        assert.equal(new Set(ids).size, ids.length);
        for (const name of ["article", "section"]) {
          for (const tag of tags(markup, name)) if (tag["aria-labelledby"]) assert.ok(ids.includes(tag["aria-labelledby"]));
        }
      });

      await suite.test(`${language}: support links to localized Contact by default and uses only the existing WeChat callback when provided`, () => {
        const copy = getSupportContent(language);
        const markup = renderToStaticMarkup(createElement(SupportInformation, { language }));
        closedDisclosure(markup, "support-information");
        for (const text of strings(copy).filter(text => text !== copy.wechatAction)) requireText(markup, text);
        requireText(markup, marketingContact.wechatName);
        requireText(markup, marketingContact.wechatId);
        const anchors = tags(markup, "a");
        assert.equal(anchors.length, 1);
        assert.equal(anchors[0].href, `/${language}/producto/contacto/`);
        assert.equal(tags(markup, "button").length, 0);
        const callbackMarkup = renderToStaticMarkup(createElement(SupportInformation, { language, onContactWechat: onAction }));
        closedDisclosure(callbackMarkup, "support-information");
        assert.deepEqual(tags(callbackMarkup, "button").map(tag => tag.type), ["button"]);
        assert.equal(tags(callbackMarkup, "a").length, 0, "The existing QR callback must not be replaced by a fabricated WeChat deep link");
        requireText(callbackMarkup, copy.wechatAction);
        requireText(callbackMarkup, marketingContact.wechatId);
        assert.equal(tags(callbackMarkup, "form").length + tags(callbackMarkup, "input").length, 0);
        assert.equal(actionCount, 0, "Rendering support must not invoke the contact action");
      });

      await suite.test(`${language}: Apps integrates the three disclosures after its existing pre-demo guide; Contact includes journey and support without submitting`, () => {
        const apps = renderPage(language, "apps");
        for (const testId of ["implementation-journey", "compatibility-checklist", "support-information"]) closedDisclosure(apps, testId);
        const guide = getApplicationGuides(language).gettingStarted;
        requireText(apps, guide.title);
        assert.ok(apps.indexOf(escapeHtml(guide.title)) < apps.indexOf('data-testid="implementation-journey"'));
        const contact = renderPage(language, "contacto");
        closedDisclosure(contact, "implementation-journey");
        closedDisclosure(contact, "support-information");
        const support = getSupportContent(language);
        requireText(contact, support.wechatAction);
        assert.equal(actionCount, 0);
        assert.equal(requestCount, 0, "SSR integration must not submit a request or probe availability");
      });
    }

    await suite.test("source integration preserves per-app setup and localized downloads without simulating modal interaction", async () => {
      const [explorer, dialogs, page, main] = await Promise.all([
        readFile(new URL("../src/ApplicationExplorer.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/MarketingDialogs.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/main.tsx", import.meta.url), "utf8")
      ]);
      assert.match(explorer, /activeSection === "setup"[\s\S]*?<CompatibilityChecklist language=\{language\} application=\{application\.id\}/);
      const downloadBody = dialogs.slice(dialogs.indexOf("function DownloadCenterModal("), dialogs.indexOf("function ProductTourModal("));
      assert.match(downloadBody, /language: MarketingLanguage/);
      assert.match(downloadBody, /<CompatibilityChecklist language=\{language\}/);
      assert.match(page, /kind="downloads"[^>]*language=\{language\}/);
      assert.match(main, /import "\.\/service-information\.css"/);
    });

    assert.equal(actionCount, 0);
    assert.equal(requestCount, 0);
  } finally {
    restoreGlobals();
    await server.close();
  }
});
