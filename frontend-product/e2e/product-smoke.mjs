import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const base = "http://127.0.0.1:5186/";
// Keep this future browser suite plain Node-compatible; no TypeScript loader is required.
const marketingHref = (language, route) => `/${language}/producto/${route}/`;
const navigationRoutes = ["inicio", "solucion", "apps", "ventajas", "contacto"];
const marketingUrl = (language, route, search = "") => new URL(`${marketingHref(language, route)}${search}`, base).href;
const currentLanguage = async page => (await page.locator("html").getAttribute("lang")) === "zh-CN" ? "zh" : await page.locator("html").getAttribute("lang");

async function waitForMarketingRoute(page, language, route, hash = "") {
  await page.waitForFunction(({ pathname, hash }) => location.pathname === pathname && location.hash === hash, { pathname: marketingHref(language, route), hash });
}
const tourLanguages = [
  { language: "es", htmlLanguage: "es", actions: "Qué puedes hacer", example: "En la práctica", benefit: "Qué te aporta", delivery: "Cómo se utiliza", previous: "Anterior", next: "Siguiente", demo: "Solicitar demo", close: "Cerrar recorrido" },
  { language: "en", htmlLanguage: "en", actions: "What you can do", example: "In practice", benefit: "How it helps", delivery: "How to use it", previous: "Previous", next: "Next", demo: "Request a demo", close: "Close tour" },
  { language: "zh", htmlLanguage: "zh-CN", actions: "可以做什么", example: "实际场景", benefit: "带来的帮助", delivery: "使用方式", previous: "上一步", next: "下一步", demo: "申请演示", close: "关闭导览" }
];

// Use the same reachable language controls as visitors, never a control behind a modal.
async function chooseLanguage(page, language) {
  const hash = new URL(page.url()).hash;
  const expectedHash = hash.startsWith("#/producto/") ? "" : hash;
  const desktopSelector = page.locator(".mk-header-actions select");
  if (await desktopSelector.isVisible()) {
    await desktopSelector.selectOption(language);
  } else {
    const menu = page.locator(".mk-menu-button");
    if (await menu.getAttribute("aria-expanded") !== "true") await menu.click();
    await page.locator("#mk-navigation .mk-language-selector.is-mobile select").selectOption(language);
    await page.keyboard.press("Escape");
  }
  await page.waitForFunction(({ language, htmlLanguage, hash }) => document.documentElement.lang === htmlLanguage && location.pathname.startsWith(`/${language}/producto/`) && location.hash === hash, { language, htmlLanguage: language === "zh" ? "zh-CN" : language, hash: expectedHash });
}

async function assertAccessibleRoutes(page) {
  // 640 CSS pixels also exercises reflow equivalent to a 1280px window at 200% zoom.
  for (const viewport of [{ width: 320, height: 568 }, { width: 640, height: 480 }]) {
    await page.setViewportSize(viewport);
    await page.goto(`${base}#/producto/inicio`, { waitUntil: "networkidle" });
    for (const locale of tourLanguages) {
      await chooseLanguage(page, locale.language);
      for (const route of navigationRoutes) {
        const menu = page.locator(".mk-menu-button");
        if (await menu.getAttribute("aria-expanded") !== "true") await menu.click();
        await page.locator(`#mk-navigation a[href="${marketingHref(locale.language, route)}"]`).click();
        await waitForMarketingRoute(page, locale.language, route);
        const main = page.locator("#mk-main-content");
        await main.locator("h1").waitFor({ state: "visible" });
        assert.equal(await page.getByRole("main").count(), 1);
        assert.equal(await main.locator("h1").count(), 1);
        assert.equal(await menu.getAttribute("aria-expanded"), "false");
        assert.ok(await page.locator(".mk-page").evaluate(element => element.scrollWidth <= element.clientWidth + 1), `${viewport.width}/${locale.language}/${route} must reflow without horizontal scrolling`);
        await page.locator(".mk-skip-link").focus();
        await page.locator(".mk-skip-link").press("Enter");
        assert.equal(await main.evaluate(element => document.activeElement === element), true, "Skip link must focus the content without changing the route");
        assert.deepEqual(await page.evaluate(() => ({ pathname: location.pathname, hash: location.hash })), { pathname: marketingHref(locale.language, route), hash: "" });
      }
    }
    const menu = page.locator(".mk-menu-button");
    await menu.click();
    await page.locator("#mk-navigation a").first().focus();
    await page.keyboard.press("Escape");
    assert.equal(await menu.getAttribute("aria-expanded"), "false");
    assert.equal(await menu.evaluate(element => document.activeElement === element), true, "Escape must return focus to the menu trigger");
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto(`${base}#/producto/apps`, { waitUntil: "networkidle" });
  await chooseLanguage(page, "es");
  const trigger = page.locator(".mk-app-tour-action").first();
  await trigger.click();
  const dialog = page.getByTestId("product-tour");
  await dialog.waitFor({ state: "visible" });
  assert.equal(await page.locator("[data-marketing-background]").evaluate(element => element.inert), true, "An open modal must make the background inert");
  const close = dialog.locator(".mk-dialog-close");
  await close.focus();
  await page.keyboard.press("Shift+Tab");
  assert.equal(await dialog.evaluate(element => element.contains(document.activeElement)), true, "Reverse Tab must stay in the modal");
  await page.keyboard.press("Tab");
  assert.equal(await close.evaluate(element => document.activeElement === element), true, "Tab must wrap from the last modal control to its first");
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "hidden" });
  assert.equal(await page.locator("[data-marketing-background]").evaluate(element => element.inert), false);
  assert.equal(await trigger.evaluate(element => document.activeElement === element), true);
}

// Prepared for an authorized browser session. Merely checking this file's
// syntax does not execute these layout or visibility assertions.
async function assertCompactSolution(page) {
  const viewports = [
    { width: 320, height: 568 }, { width: 390, height: 844 },
    { width: 768, height: 1024 }, { width: 1024, height: 768 },
    { width: 1440, height: 900 }
  ];
  for (const locale of tourLanguages) {
    let content;
    for (const viewport of viewports) {
      const context = `${locale.language}/${viewport.width}x${viewport.height} compact solution`;
      await page.setViewportSize(viewport);
      await page.goto(marketingUrl(locale.language, "solucion"), { waitUntil: "networkidle" });
      await waitForMarketingRoute(page, locale.language, "solucion");
      const main = page.locator("main");
      await main.locator("h1").waitFor({ state: "visible" });
      assert.equal(await main.locator("h1").count(), 1, `${context} must retain one primary heading`);
      assert.equal(await main.locator(".mk-principles article h2").count(), 3);
      const cards = main.locator(".mk-feature-list article");
      assert.equal(await cards.count(), 4, `${context} must show every functional group`);
      for (const card of await cards.all()) {
        assert.equal(await card.locator(":scope > header h3").count(), 1);
        assert.equal(await card.locator(":scope > p").count(), 1);
        assert.equal(await card.locator("ul li").count(), 3);
        assert.equal(await card.locator("details, summary, .mk-feature-number").count(), 0);
      }
      const currentContent = (await main.innerText()).replace(/\s+/g, " ").trim();
      if (content) assert.equal(currentContent, content, `${context} must preserve the same complete copy at every viewport`);
      else content = currentContent;
      const layout = await main.evaluate(element => {
        const tolerance = 2;
        const describe = child => `${child.tagName.toLowerCase()}.${String(child.className).trim().replaceAll(" ", ".")}`;
        const required = [...element.querySelectorAll("h1, .mk-principles h2, .mk-principles p, .mk-solution-features h2, .mk-feature-list article header, .mk-feature-list article h3, .mk-feature-list article > p, .mk-feature-list li, .mk-dark-band h2, .mk-dark-band p, .mk-dark-band a")];
        const hidden = required.filter(child => !child.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true }) || child.getBoundingClientRect().width <= 0 || child.getBoundingClientRect().height <= 0).map(describe);
        const cards = [...element.querySelectorAll(".mk-feature-list article")].map(card => {
          const bounds = card.getBoundingClientRect();
          const header = card.querySelector(":scope > header").getBoundingClientRect();
          const body = card.querySelector(":scope > p").getBoundingClientRect();
          const list = card.querySelector("ul").getBoundingClientRect();
          const clipped = [...card.querySelectorAll("header, h3, p, li")].filter(child => {
            const rect = child.getBoundingClientRect();
            return rect.left < bounds.left - tolerance || rect.right > bounds.right + tolerance || rect.top < bounds.top - tolerance || rect.bottom > bounds.bottom + tolerance;
          }).map(describe);
          return { overflowX: card.scrollWidth - card.clientWidth, overflowY: card.scrollHeight - card.clientHeight, overlap: body.top < header.bottom - tolerance || list.top < body.bottom - tolerance, clipped };
        });
        const page = element.closest(".mk-page");
        return { hidden, cards, pageOverflowX: page.scrollWidth - page.clientWidth };
      });
      assert.deepEqual(layout.hidden, [], `${context} must not hide text to appear compact`);
      assert.ok(layout.pageOverflowX <= 1, `${context} must reflow without horizontal page scrolling`);
      for (const card of layout.cards) {
        assert.ok(card.overflowX <= 1 && card.overflowY <= 1, `${context} card must not clip its content (${JSON.stringify(card)})`);
        assert.equal(card.overlap, false, `${context} header, description and capabilities must not overlap`);
        assert.deepEqual(card.clipped, [], `${context} card text boxes must fit their parent`);
      }
      const action = main.locator(".mk-dark-band > a.mk-primary");
      assert.equal(await action.count(), 1, `${context} application action must be a sibling of the band copy`);
      assert.equal(await action.getAttribute("href"), marketingHref(locale.language, "apps"));
      await action.scrollIntoViewIfNeeded();
      assert.equal(await action.isVisible(), true);
    }
  }
}

// Future live layout checks, not evidence from a syntax-only or SSR run. The
// existing compact-solution check above covers the fifth canonical route.
async function assertCompactMarketingPages(page) {
  const viewports = [
    { width: 320, height: 568 }, { width: 390, height: 844 },
    { width: 768, height: 1024 }, { width: 1024, height: 768 },
    { width: 1440, height: 900 }
  ];
  const requiredByRoute = {
    inicio: ".mk-hero-copy h1, .mk-lead, .mk-hero-copy .mk-actions a, .mk-checks li, .mk-app-strip article strong, .mk-app-strip article small, .mk-intro h2, .mk-intro > p, .mk-intro > a, .mk-home-sector-preview article > p, .mk-home-sector-preview article > h3, .mk-home-sector-preview article > small, .mk-home-sector-preview summary",
    apps: ".mk-page-heading h1, .mk-page-heading-support > p, .mk-app-explorer-tabs button, .mk-app-explorer-intro h2, .mk-app-explorer-intro p, .mk-app-explorer-facts dt, .mk-app-explorer-facts dd, .mk-app-explorer-description, .mk-app-explorer-sections button, .mk-app-explorer-content h3, .mk-app-explorer-content h4, .mk-app-explorer-content p, .mk-app-explorer-scope, .mk-app-explorer-actions > *, .mk-guide-start h2, .mk-guide-start h3, .mk-guide-start p",
    ventajas: ".mk-page-heading h1, .mk-page-heading-support > p, .mk-comparison [role=rowheader], .mk-comparison [role=cell], .mk-note",
    contacto: ".mk-contact-story > h1, .mk-contact-story > p, .mk-wechat-content h2, .mk-wechat-account-line, .mk-wechat-body, .mk-wechat-id, .mk-wechat-copy, .mk-wechat-hint, .mk-wechat-qr-trigger, .mk-contact-product, .mk-contact-trust strong, .mk-contact-trust small, .mk-contact-form-tag, .mk-contact-form h2, .mk-contact-form > p, .mk-delivery-status p, .mk-contact-selection legend, .mk-contact-package, .mk-contact-app-choice, .mk-contact-fields label, .mk-contact-form > label:not(.mk-contact-honeypot), .mk-contact-submit, .mk-contact-talk, .mk-contact-form > small"
  };
  const orderedByRoute = {
    inicio: [".mk-hero-copy > h1", ".mk-lead", ".mk-hero-copy .mk-actions", ".mk-hero-copy .mk-checks"],
    apps: [".mk-app-explorer-intro", ".mk-app-explorer-body", ".mk-app-explorer-scope"],
    ventajas: [".mk-page-heading", ".mk-comparison", ".mk-note"],
    contacto: [".mk-contact-form h2", ".mk-contact-form > p:not(.mk-contact-form-tag)", ".mk-delivery-status", ".mk-contact-selection", ".mk-contact-fields", ".mk-contact-form > label:not(.mk-contact-honeypot):not(.mk-contact-consent)", ".mk-contact-consent", ".mk-contact-submit", ".mk-contact-talk"]
  };
  const cardsByRoute = {
    inicio: ".mk-app-strip article, .mk-home-sector-preview article",
    apps: ".mk-app-explorer-panel, .mk-app-explorer-context, .mk-app-explorer-information, .mk-guide-start-steps li",
    ventajas: ".mk-comparison-row:not(.is-head)",
    contacto: ".mk-wechat-card, .mk-contact-product, .mk-contact-form"
  };
  for (const locale of tourLanguages) {
    const contentByRoute = new Map();
    for (const viewport of viewports) {
      await page.setViewportSize(viewport);
      for (const route of ["inicio", "apps", "ventajas", "contacto"]) {
        const context = `${locale.language}/${viewport.width}x${viewport.height} compact ${route}`;
        await page.goto(marketingUrl(locale.language, route), { waitUntil: "networkidle" });
        await waitForMarketingRoute(page, locale.language, route);
        const main = page.locator("main");
        await main.locator("h1").waitFor({ state: "visible" });
        assert.equal(await main.locator("h1").count(), 1, `${context} retains one primary heading`);
        if (route === "contacto") await main.locator('.mk-delivery-status[data-state="available"]').waitFor({ state: "visible" });
        const text = (await main.innerText()).replace(/\s+/g, " ").trim();
        if (contentByRoute.has(route)) assert.equal(text, contentByRoute.get(route), `${context} cannot achieve compactness by removing explanatory copy`);
        else contentByRoute.set(route, text);

        const layout = await main.evaluate((element, { required, ordered, cardSelector }) => {
          const tolerance = 2;
          const describe = child => `${child.tagName.toLowerCase()}.${String(child.className).trim().replaceAll(" ", ".")}`;
          const hidden = [...element.querySelectorAll(required)].filter(child => {
            const bounds = child.getBoundingClientRect();
            return !child.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true }) || bounds.width <= 0 || bounds.height <= 0;
          }).map(describe);
          const sequence = ordered.flatMap(selector => {
            const child = element.querySelector(selector);
            return child ? [{ selector, top: child.getBoundingClientRect().top, bottom: child.getBoundingClientRect().bottom }] : [];
          });
          const overlaps = sequence.slice(1).flatMap((item, index) => item.top < sequence[index].bottom - tolerance ? [`${sequence[index].selector} / ${item.selector}`] : []);
          const clipped = [...element.querySelectorAll(cardSelector)].flatMap(card => {
            const bounds = card.getBoundingClientRect();
            const outside = [...card.querySelectorAll("h2, h3, h4, p, strong, small, li, input:not([type=checkbox]), textarea, button, a")].filter(child => {
              if (child.closest(".mk-contact-honeypot") || child.classList.contains("mk-visually-hidden")) return false;
              if (!child.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) return false;
              const rect = child.getBoundingClientRect();
              return rect.width > 0 && rect.height > 0 && (rect.left < bounds.left - tolerance || rect.right > bounds.right + tolerance || rect.top < bounds.top - tolerance || rect.bottom > bounds.bottom + tolerance);
            }).map(describe);
            return outside.length ? [{ card: describe(card), outside }] : [];
          });
          const page = element.closest(".mk-page");
          const heading = element.querySelector("h1");
          const headingStyle = getComputedStyle(heading);
          return {
            hidden, overlaps, clipped,
            overflowX: Math.max(page.scrollWidth - page.clientWidth, document.documentElement.scrollWidth - innerWidth),
            headingFont: parseFloat(headingStyle.fontSize), headingLine: parseFloat(headingStyle.lineHeight)
          };
        }, { required: requiredByRoute[route], ordered: orderedByRoute[route], cardSelector: cardsByRoute[route] });
        assert.deepEqual(layout.hidden, [], `${context} explanations and controls remain visible`);
        assert.deepEqual(layout.overlaps, [], `${context} related content blocks do not overlap`);
        assert.deepEqual(layout.clipped, [], `${context} card text and controls fit their region`);
        assert.ok(layout.overflowX <= 1, `${context} permits natural vertical scrolling without horizontal overflow`);
        assert.ok(layout.headingFont >= 28 && layout.headingLine >= layout.headingFont, `${context} retains a readable, non-overlapping heading (${JSON.stringify(layout)})`);

        if (route === "inicio") {
          assert.equal(await main.locator(".mk-app-strip article").count(), 4);
          assert.equal(await main.locator(".mk-home-sector-preview article").count(), 4);
          for (const disclosure of await main.locator(".mk-home-sector-preview details").all()) {
            await disclosure.locator("summary").click();
            assert.equal(await disclosure.evaluate(element => element.open), true);
            const outside = await disclosure.evaluate(element => {
              const bounds = element.closest("article").getBoundingClientRect();
              return [...element.querySelectorAll("p, li, strong, a")].filter(child => {
                const rect = child.getBoundingClientRect();
                return !child.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })
                  || rect.left < bounds.left - 2 || rect.right > bounds.right + 2
                  || rect.top < bounds.top - 2 || rect.bottom > bounds.bottom + 2;
              }).map(child => child.tagName.toLowerCase());
            });
            assert.deepEqual(outside, [], `${context} expanded sector text and actions fit their card`);
            await disclosure.locator("summary").click();
          }
          await assertReachableControl(main.locator(".mk-hero .mk-primary"), viewport, `${context} demo`);
        } else if (route === "apps") {
          assert.equal(await main.locator(".mk-app-explorer-tabs [role=tab]").count(), 4);
          assert.equal(await main.locator(".mk-app-explorer-sections [role=tab]").count(), 4);
          assert.equal(await main.locator(".mk-guide-start-steps li").count(), 3);
          await assertReachableControl(main.locator(".mk-app-explorer-demo"), viewport, `${context} request`);
        } else if (route === "ventajas") {
          const rows = main.locator('.mk-comparison [role=row]:not(.is-head)');
          for (const row of await rows.all()) {
            assert.equal(await row.locator("[role=rowheader]").count(), 1);
            assert.equal(await row.locator("[role=cell]").count(), 3);
          }
          for (const id of ["backups", "adding-stores", "fiscal-setup"]) {
            const conditional = main.locator(`.mk-comparison [data-capability="${id}"] .is-ours`);
            assert.equal(await conditional.getAttribute("data-status"), "to-confirm");
            assert.ok((await conditional.locator("small").innerText()).trim(), "Conditional scope has a visible text label on desktop and mobile");
          }
          await assertReachableControl(main.locator(".mk-heading-actions .mk-primary"), viewport, `${context} demo`);
        } else {
          assert.equal(await main.locator('input[name="products"]').count(), 4);
          assert.equal(await main.locator('input[name="fullPackage"]').count(), 1);
          assert.equal(await main.locator('input[name="privacyAccepted"][required]').count(), 1);
          assert.equal(await main.locator(".mk-wechat-qr-trigger img").getAttribute("src"), "/contact/wechat-espos.jpg");
          await assertReachableControl(main.locator(".mk-contact-submit"), viewport, `${context} submit`);
          await assertReachableControl(main.locator(".mk-wechat-qr-trigger"), viewport, `${context} original WeChat QR`);
        }
      }
    }
  }
}

async function assertBrand(page, route, language) {
  const context = `${language}/${route}`;
  const title = await page.title();
  const description = await page.locator('meta[name="description"]').getAttribute("content");
  const ogTitle = await page.locator('meta[property="og:title"]').getAttribute("content");
  const ogDescription = await page.locator('meta[property="og:description"]').getAttribute("content");
  const structuredText = await page.locator('script[type="application/ld+json"]').textContent();
  assert.match(title, /\besPOS\b/, `${context} title must use the official esPOS spelling`);
  assert.ok(description?.trim(), `${context} must have a useful meta description`);
  assert.equal(ogTitle, title, `${context} Open Graph title must match the page title`);
  assert.equal(ogDescription, description, `${context} Open Graph description must match the meta description`);
  for (const [field, value] of Object.entries({ title, description, ogTitle, ogDescription, sourceCopy: await page.locator(".mk-page").textContent() })) {
    assert.doesNotMatch(value, /\bTPV[\s_-]*ERP\b/i, `${context} ${field} must not expose the previous brand`);
    assert.doesNotMatch(value, /\b(?:esPos|EsPos|Espos|ESPOS|espos)\b/, `${context} ${field} must keep esPOS casing unchanged`);
  }
  assert.doesNotMatch(structuredText, /\bTPV[\s_-]*ERP\b/i, `${context} structured data must not expose the previous brand`);
  assert.doesNotMatch(await page.locator(".mk-page").innerText(), /\bTPV[\s_-]*ERP\b/i, `${context} visible copy must not expose the previous brand`);
  const graph = JSON.parse(structuredText)["@graph"];
  assert.equal(graph.find(item => item["@type"] === "WebPage")?.name, title, `${context} WebPage schema must describe this route`);
  assert.equal(graph.filter(item => item["@type"] === "SoftwareApplication").length, route === "inicio" ? 1 : 0, `${context} software schema belongs only to the visible product overview`);
  if (route === "inicio") assert.equal(graph.find(item => item["@type"] === "SoftwareApplication").name, "esPOS");
  assert.equal(graph.some(item => item["@type"] === "Organization" || item["@type"] === "FAQPage"), false, `${context} must not invent a corporate identity or inject global FAQ schema`);
  const logos = page.locator(".mk-brand img");
  assert.equal(await logos.count(), 2, `${context} must display the official wordmark in the header and footer`);
  for (const logo of await logos.all()) {
    assert.equal(await logo.getAttribute("src"), "/branding/espos-wordmark.png", `${context} must use the official wordmark asset`);
    assert.equal(await logo.getAttribute("alt"), "esPOS", `${context} wordmark must have an accessible esPOS name`);
    await logo.evaluate(image => image.decode());
    assert.ok(await logo.evaluate(image => image.complete && image.naturalWidth > 0 && image.naturalHeight > 0), `${context} official wordmark must load`);
    assert.match(await logo.locator("xpath=ancestor::a[1]").getAttribute("aria-label"), /\besPOS\b/, `${context} brand link must remain accessible`);
  }
  if (route === "apps") {
    const names = await page.locator(".mk-app-explorer-tabs [role=tab] strong").allInnerTexts();
    assert.equal(names.length, 4, `${context} must name all four applications`);
    for (const name of names) assert.match(name, /^esPOS\s/, `${context} application names must carry the esPOS brand`);
  }
}

async function assertTourContent(tour, locale, app, step) {
  const context = `${locale.language}/${app}/step ${step + 1}`;
  const preview = tour.getByTestId("tour-step-preview");
  assert.equal(await preview.getAttribute("data-step"), String(step), `${context} media must follow the selected step`);
  const mediaKind = await preview.getAttribute("data-media-kind");
  assert.equal(mediaKind, "step-capture", `${context} must show its reviewed step capture without an overview fallback`);
  assert.equal(await preview.locator("img").getAttribute("src"), `/marketing/steps/${app}-${step + 1}.webp`, `${context} must select its own published frame`);
  assert.match(await preview.locator("figcaption").innerText(), {
    es: /Interfaz real en español.*Datos de demostración/i,
    en: /Real interface in Spanish.*Demonstration data/i,
    zh: /真实界面（西班牙语）.*演示数据/
  }[locale.language], `${context} must disclose the interface language and demonstration data`);
  const original = preview.locator("a");
  assert.equal(await original.getAttribute("href"), await preview.locator("img").getAttribute("src"));
  assert.equal(await original.getAttribute("target"), "_blank");
  assert.equal(await original.getAttribute("rel"), "noreferrer");
  assert.equal(await tour.getAttribute("data-active-app"), app, `${context} must preserve the chosen application`);
  assert.match(await tour.locator('.mk-tour-tabs [role="tab"][aria-selected="true"]').innerText(), /^esPOS\s/, `${context} selected application tab must use the esPOS brand`);
  assert.match((await tour.locator(".mk-tour-context-copy > p").textContent()).trim(), /^esPOS\s/, `${context} preview must identify the branded application`);
  assert.match(await tour.locator(".mk-tour-step-count").innerText(), new RegExp(`${step + 1} / 3`), `${context} must show the selected step`);
  assert.equal((await tour.locator(".mk-tour-capabilities h4").textContent()).trim(), locale.actions, `${context} capabilities label must be translated`);
  assert.equal((await tour.locator(".mk-tour-example h4").textContent()).trim(), locale.example, `${context} example label must be translated`);
  assert.equal((await tour.locator(".mk-tour-benefit strong").textContent()).trim(), locale.benefit, `${context} benefit label must be translated`);
  assert.equal((await tour.locator(".mk-tour-delivery dt").textContent()).trim(), locale.delivery, `${context} delivery label must be translated`);
  assert.equal(await tour.locator(".mk-tour-actions button").first().innerText(), locale.previous);
  assert.equal(await tour.locator(".mk-tour-actions .is-primary").innerText(), step === 2 ? locale.demo : locale.next);
  assert.equal(await tour.locator(".mk-dialog-close").getAttribute("aria-label"), locale.close);
  assert.equal(await tour.locator(".mk-tour-actions button").first().isDisabled(), step === 0, `${context} previous button state must follow the step`);
  assert.equal(await tour.locator(".mk-tour-progress button").count(), 3);
  assert.equal(await tour.locator('.mk-tour-progress button[aria-current="step"]').count(), 1);
  assert.equal(await tour.locator(".mk-tour-progress button").nth(step).getAttribute("aria-current"), "step");
  assert.equal(await tour.locator(".mk-tour-capabilities li").count(), 3, `${context} must explain three concrete actions`);
  const actions = (await tour.locator(".mk-tour-capabilities li").allInnerTexts()).map(text => text.trim());
  const example = (await tour.locator(".mk-tour-example p").innerText()).trim();
  const benefit = (await tour.locator(".mk-tour-benefit > span").innerText()).replace(locale.benefit, "").trim();
  const delivery = (await tour.locator(".mk-tour-delivery dd").innerText()).trim();
  const title = (await tour.locator(".mk-tour-editorial h3").innerText()).trim();
  const body = (await tour.locator(".mk-tour-editorial > p").innerText()).trim();
  for (const [field, content] of Object.entries({ title, body, example, benefit, delivery, ...Object.fromEntries(actions.map((action, index) => [`action ${index + 1}`, action])) })) {
    assert.ok(content, `${context} ${field} must not be empty`);
    assert.doesNotMatch(content, /undefined|\[object Object\]/, `${context} ${field} must contain readable copy`);
  }
  assert.equal(new Set(actions).size, 3, `${context} actions must describe different capabilities`);
  return { actions: JSON.stringify(actions), example, benefit, delivery };
}

async function assertTourLayout(tour, viewport, mobile, context) {
  const layout = await tour.evaluate((dialog, { mobile }) => {
    const tolerance = 2;
    const regions = [".mk-tour-dialog", ".mk-tour-stage", ".mk-tour-editorial", ".mk-tour-details", ".mk-tour-context", ".mk-tour-preview", ".mk-tour-context-copy", ".mk-tour-footer"];
    const required = ".mk-tour-product-label, .mk-tour-step-count, .mk-tour-editorial h3, .mk-tour-editorial > p, .mk-tour-capabilities h4, .mk-tour-capabilities li, .mk-tour-capabilities li span, .mk-tour-example h4, .mk-tour-example p, .mk-tour-benefit, .mk-tour-benefit strong, .mk-tour-benefit > span, .mk-tour-preview img, .mk-tour-preview figcaption, .mk-tour-context-copy > p, .mk-tour-context-copy > strong, .mk-tour-context-copy dt, .mk-tour-context-copy dd, .mk-tour-progress button, .mk-tour-actions button, .mk-dialog-close";
    const describe = element => `${element.tagName.toLowerCase()}${element.className ? `.${String(element.className).trim().replaceAll(" ", ".")}` : ""}`;
    const visible = element => element.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true }) && element.getBoundingClientRect().width > 0 && element.getBoundingClientRect().height > 0;
    const hidden = [...dialog.querySelectorAll(required)].filter(element => !visible(element)).map(describe);
    const round = value => Math.round(value * 10) / 10;
    const frame = dialog.getBoundingClientRect();
    const results = regions.map(selector => {
      const region = selector === ".mk-tour-dialog" ? dialog : dialog.querySelector(selector);
      if (!region) return { selector, missing: true };
      const rect = region.getBoundingClientRect();
      const bounds = { left: rect.left + region.clientLeft, top: rect.top + region.clientTop, right: rect.left + region.clientLeft + region.clientWidth, bottom: rect.top + region.clientTop + region.clientHeight };
      const allowVerticalScroll = region === dialog || selector === ".mk-tour-stage";
      const outside = rect => rect.left < bounds.left - tolerance || rect.right > bounds.right + tolerance || (!allowVerticalScroll && (rect.top < bounds.top - tolerance || rect.bottom > bounds.bottom + tolerance));
      const clipped = [];
      for (const child of region.querySelectorAll("*")) {
        if (child instanceof SVGElement || !visible(child)) continue;
        const childRect = child.getBoundingClientRect();
        if (outside(childRect)) clipped.push(`${describe(child)} bounds`);
        for (const node of child.childNodes) {
          if (node.nodeType !== Node.TEXT_NODE || !node.textContent.trim()) continue;
          const range = document.createRange();
          range.selectNodeContents(node);
          if ([...range.getClientRects()].some(outside)) clipped.push(`${describe(child)} text`);
        }
      }
      return { selector, visible: visible(region), overflowX: region.scrollWidth - region.clientWidth, overflowY: allowVerticalScroll ? 0 : region.scrollHeight - region.clientHeight, clipped: clipped.slice(0, 8) };
    });
    return { hidden, regions: results, frame: { x: round(frame.x), y: round(frame.y), width: round(frame.width), height: round(frame.height) }, pageOverflow: document.documentElement.scrollWidth - innerWidth };
  }, { mobile });
  assert.deepEqual(layout.hidden, [], `${context} must keep every tour explanation and control visible`);
  for (const region of layout.regions) {
    assert.equal(region.missing, undefined, `${context} must render ${region.selector}`);
    assert.equal(region.visible, true, `${context} must display ${region.selector}`);
    assert.ok(region.overflowX <= 1 && region.overflowY <= 1, `${context} ${region.selector} must not clip content (${JSON.stringify(region)})`);
    assert.deepEqual(region.clipped, [], `${context} ${region.selector} child boxes and text must fit`);
  }
  assert.ok(layout.frame.x >= -1 && layout.frame.y >= -1 && layout.frame.x + layout.frame.width <= viewport.width + 1 && layout.frame.y + layout.frame.height <= viewport.height + 1, `${context} dialog frame must fit the viewport (${JSON.stringify(layout.frame)})`);
  assert.ok(layout.pageOverflow <= 1, `${context} must not overflow the page horizontally`);
}

async function assertReachableControl(control, viewport, context, verifyStable = false) {
  await control.scrollIntoViewIfNeeded();
  const readPosition = () => control.evaluate(element => {
    const rect = element.getBoundingClientRect();
    const dialog = element.closest(".mk-tour-dialog") ?? element.closest(".mk-page");
    if (!dialog) throw new Error("Reachable controls must belong to a marketing page or tour dialog");
    const frame = dialog.getBoundingClientRect();
    return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, frame: { left: frame.left + dialog.clientLeft, top: frame.top + dialog.clientTop, right: frame.left + dialog.clientLeft + dialog.clientWidth, bottom: frame.top + dialog.clientTop + dialog.clientHeight }, scrollTop: dialog.scrollTop };
  });
  const assertPosition = position => {
    assert.ok(position.left >= -1 && position.top >= -1 && position.right <= viewport.width + 1 && position.bottom <= viewport.height + 1, `${context} must be reachable inside the viewport (${JSON.stringify(position)})`);
    assert.ok(position.left >= position.frame.left - 1 && position.top >= position.frame.top - 1 && position.right <= position.frame.right + 1 && position.bottom <= position.frame.bottom + 1, `${context} must be visible inside its scrollable container (${JSON.stringify(position)})`);
  };
  assertPosition(await readPosition());
  if (verifyStable) {
    await control.page().waitForTimeout(300);
    assertPosition(await readPosition());
  }
}

async function assertResponsiveHome(page, viewport) {
  const context = `${viewport.width}x${viewport.height} home`;
  await page.setViewportSize(viewport);
  await page.goto(`${base}#/producto/inicio`, { waitUntil: "networkidle" });
  const responsivePicture = page.locator(".mk-hero-product picture");
  assert.equal(await responsivePicture.locator('source[type="image/avif"]').count(), 1);
  assert.equal(await responsivePicture.locator('source[type="image/webp"]').count(), 1);
  const responsiveImage = responsivePicture.locator("img");
  assert.equal(await responsiveImage.getAttribute("loading"), "eager", `${context} must not defer the primary image`);
  assert.equal(await responsiveImage.getAttribute("fetchpriority"), "high");
  assert.ok(await responsiveImage.getAttribute("sizes"));
  await page.locator(".mk-hero-product img").evaluate(image => image.decode());
  assert.match(await responsiveImage.evaluate(image => new URL(image.currentSrc).pathname), /^\/marketing\/optimized\/contact-ecosystem-(480|768|1024|1536)\.(avif|webp|jpg)$/, `${context} must select an optimized representation`);
  await page.locator(".mk-page").evaluate(element => element.scrollTo({ top: 0, behavior: "instant" }));
  const layout = await page.locator(".mk-hero").evaluate(hero => {
    const frame = hero.getBoundingClientRect();
    const selectors = ["h1", ".mk-hero-copy", ".mk-lead", ".mk-hero-product"];
    const photo = hero.querySelector(".mk-hero-product img").getBoundingClientRect();
    const photoContainer = hero.querySelector(".mk-hero-product").getBoundingClientRect();
    return {
      height: frame.height,
      photoArea: { width: Math.max(0, Math.min(photo.right, photoContainer.right, frame.right) - Math.max(photo.left, photoContainer.left, frame.left)), height: Math.max(0, Math.min(photo.bottom, photoContainer.bottom, frame.bottom) - Math.max(photo.top, photoContainer.top, frame.top)) },
      overflow: document.documentElement.scrollWidth - innerWidth,
      pageOverflow: document.querySelector(".mk-page").scrollWidth - document.querySelector(".mk-page").clientWidth,
      regions: selectors.map(selector => {
        const element = hero.querySelector(selector);
        const bounds = element.getBoundingClientRect();
        return { selector, visible: element.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true }), width: bounds.width, height: bounds.height, contained: bounds.left >= frame.left - 1 && bounds.right <= frame.right + 1 && bounds.top >= frame.top - 1 && bounds.bottom <= frame.bottom + 1 };
      })
    };
  });
  assert.ok(layout.height > 200, `${context} hero must retain its natural content height (${JSON.stringify(layout)})`);
  assert.ok(layout.photoArea.width > 100 && layout.photoArea.height > 100, `${context} photo must retain a useful visible area inside the hero (${JSON.stringify(layout.photoArea)})`);
  assert.ok(layout.overflow <= 1 && layout.pageOverflow <= 1, `${context} must not overflow horizontally`);
  for (const region of layout.regions) {
    assert.ok(region.visible && region.width > 0 && region.height > 0, `${context} ${region.selector} must remain visible`);
    assert.equal(region.contained, true, `${context} ${region.selector} must fit inside the hero rather than being clipped`);
  }
  const demo = page.locator(".mk-hero .mk-actions .mk-primary");
  assert.equal(await demo.getAttribute("href"), `${marketingHref(await currentLanguage(page), "contacto")}#mk-demo-form`, `${context} must retain the localized, shareable demo contact action`);
  await demo.scrollIntoViewIfNeeded();
  const action = await demo.boundingBox();
  assert.ok(action && action.x >= -1 && action.y >= -1 && action.x + action.width <= viewport.width + 1 && action.y + action.height <= viewport.height + 1, `${context} demo action must be reachable with natural vertical scrolling (${JSON.stringify(action)})`);
}

async function assertWeChatContact(page, apiRequests) {
  const account = "esPOS - LEAFY TECH";
  const wechatId = "wxid_6e9mzgmj8e6912";
  const qrSource = "/contact/wechat-espos.jpg";
  const card = page.locator('.mk-wechat-card[role="region"]');
  const input = card.locator(".mk-wechat-id");
  const copyButton = card.locator(".mk-wechat-copy");
  const qrTrigger = card.locator(".mk-wechat-qr-trigger");
  const qrDialog = page.locator('.mk-wechat-qr-dialog[role="dialog"]');
  const requestsBefore = apiRequests.length;
  const translatedTitles = new Set();
  const translatedCopyLabels = new Set();
  const translatedSuccess = new Set();
  const translatedErrors = new Set();
  const qrTranslations = { "QR actions": new Set(), "QR titles": new Set(), "QR instructions": new Set(), "QR alternative text": new Set(), "QR close labels": new Set() };
  const qrResponse = await page.request.get(new URL(qrSource, base).toString());
  assert.equal(qrResponse.status(), 200, "The supplied WeChat QR image must be available as a local, public asset");
  const qrBytes = await qrResponse.body();
  assert.deepEqual([...qrBytes.subarray(0, 3)], [0xff, 0xd8, 0xff], "The WeChat QR asset must preserve its original JPEG format");
  assert.equal(createHash("sha256").update(qrBytes).digest("hex"), "db44f69c9781c86f58b6430f25d1f8ba2c0ed32422a22f16c703e6a53d44ee7d", "The WeChat QR image must preserve the user's supplied image byte for byte");
  assert.deepEqual(qrBytes, await readFile(new URL("../public/contact/wechat-espos.jpg", import.meta.url)), "The served WeChat image must match the checked-in asset without relying on a temporary source path");
  const assertQrImage = async (image, context) => {
    assert.equal(await image.count(), 1, `${context} must render exactly one supplied QR image`);
    assert.equal(await image.getAttribute("src"), qrSource, `${context} must use the real supplied WeChat QR image`);
    assert.ok((await image.getAttribute("alt"))?.trim(), `${context} QR image must have descriptive alternative text`);
    assert.equal(await image.getAttribute("width"), "888", `${context} must declare the original image width`);
    assert.equal(await image.getAttribute("height"), "1131", `${context} must declare the original image height`);
    await image.evaluate(element => element.decode());
    assert.deepEqual(await image.evaluate(element => ({ complete: element.complete, width: element.naturalWidth, height: element.naturalHeight })), { complete: true, width: 888, height: 1131 }, `${context} must load the complete original 888×1131 image`);
  };
  const assertQrDialog = async (viewport, context) => {
    await qrTrigger.click();
    await qrDialog.waitFor({ state: "visible" });
    assert.equal(await qrDialog.count(), 1, `${context} must open a single QR dialog`);
    assert.equal(await qrDialog.getAttribute("aria-modal"), "true", `${context} QR dialog must be announced as modal`);
    const labelId = await qrDialog.getAttribute("aria-labelledby");
    const descriptionId = await qrDialog.getAttribute("aria-describedby");
    assert.ok(labelId && descriptionId, `${context} QR dialog must have an accessible title and scanning instructions`);
    const title = (await qrDialog.locator(`[id="${labelId}"]`).innerText()).trim();
    const hint = (await qrDialog.locator(`[id="${descriptionId}"]`).innerText()).trim();
    const image = qrDialog.locator("img");
    await assertQrImage(image, context);
    const close = qrDialog.locator(".mk-dialog-close");
    assert.equal(await close.getAttribute("type"), "button", `${context} closing the QR image must never submit the demo form`);
    assert.ok(await close.getAttribute("aria-label"), `${context} QR close control must have an accessible name`);
    assert.equal(await close.evaluate(element => document.activeElement === element), true, `${context} opening the QR dialog must focus its close control`);
    const layout = await qrDialog.evaluate(dialog => {
      const frame = dialog.getBoundingClientRect();
      const image = dialog.querySelector("img");
      const imageFrame = image.getBoundingClientRect();
      const closeFrame = dialog.querySelector(".mk-dialog-close").getBoundingClientRect();
      const bounds = element => ({ left: element.left, top: element.top, right: element.right, bottom: element.bottom, width: element.width, height: element.height });
      return { dialog: bounds(frame), image: bounds(imageFrame), close: bounds(closeFrame), imageFit: getComputedStyle(image).objectFit, overflowX: dialog.scrollWidth - dialog.clientWidth, overflowY: dialog.scrollHeight - dialog.clientHeight, documentOverflowX: document.documentElement.scrollWidth - innerWidth };
    });
    for (const [region, rect] of Object.entries({ dialog: layout.dialog, image: layout.image, close: layout.close })) {
      assert.ok(rect.width > 0 && rect.height > 0 && rect.left >= -1 && rect.top >= -1 && rect.right <= viewport.width + 1 && rect.bottom <= viewport.height + 1, `${context} ${region} must fit entirely inside the viewport (${JSON.stringify(layout)})`);
    }
    assert.ok(layout.image.left >= layout.dialog.left - 1 && layout.image.right <= layout.dialog.right + 1 && layout.image.top >= layout.dialog.top - 1 && layout.image.bottom <= layout.dialog.bottom + 1, `${context} the full image must remain inside the QR dialog`);
    assert.equal(layout.imageFit, "contain", `${context} must show the entire supplied QR card without cropping`);
    assert.ok(layout.overflowX <= 1 && layout.overflowY <= 1 && layout.documentOverflowX <= 1, `${context} enlarged QR image must not introduce scrolling or clipping (${JSON.stringify(layout)})`);
    assert.equal(await page.locator(`.mk-wechat-card img[src="${qrSource}"], .mk-wechat-qr-dialog img[src="${qrSource}"]`).count(), 2, `${context} must show only the original thumbnail and enlarged image while open`);
    assert.equal(apiRequests.length, requestsBefore, `${context} opening a QR card must not submit a commercial request`);
    return { title, hint, alt: await image.getAttribute("alt"), close: await close.getAttribute("aria-label") };
  };
  const assertQrClosed = async context => {
    await qrDialog.waitFor({ state: "detached" });
    assert.equal(await qrTrigger.evaluate(element => document.activeElement === element), true, `${context} closing the QR dialog must restore focus to its trigger`);
    assert.equal(await card.locator("img").count(), 1, `${context} closing the QR dialog must retain exactly one supplied thumbnail`);
    assert.equal(apiRequests.length, requestsBefore, `${context} closing the QR card must not submit the demo form`);
  };
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.evaluate(() => {
    window.__wechatWrites = [];
    window.__wechatRejectCopy = false;
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: async value => {
        window.__wechatWrites.push(value);
        if (window.__wechatRejectCopy) throw new Error("Clipboard denied for the WeChat smoke test");
      } }
    });
  });
  for (const locale of tourLanguages) {
    await chooseLanguage(page, locale.language);
    await page.waitForFunction(value => document.documentElement.lang === value, locale.htmlLanguage);
    await card.waitFor({ state: "visible" });
    const labelId = await card.getAttribute("aria-labelledby");
    assert.ok(labelId, `${locale.language} WeChat contact region must have an accessible title`);
    const title = (await card.locator(`[id="${labelId}"]`).innerText()).trim();
    assert.ok(title && /WeChat|微信/i.test(title), `${locale.language} WeChat heading must identify the contact channel`);
    translatedTitles.add(title);
    assert.equal((await card.locator(".mk-wechat-account").innerText()).trim(), account, "The WeChat account identity must match the supplied contact card exactly");
    assert.equal(await input.inputValue(), wechatId, `${locale.language} must preserve the complete, single-line WeChat ID`);
    assert.equal(await input.evaluate(element => element.readOnly && element.tagName === "INPUT" && element.type === "text"), true, "The WeChat ID must be a selectable, read-only text input");
    assert.ok(await input.evaluate(element => Boolean(element.labels?.[0]?.textContent?.trim()) || Boolean(element.getAttribute("aria-label"))), `${locale.language} WeChat ID must have an accessible label`);
    assert.equal(await copyButton.getAttribute("type"), "button", "Copying a WeChat ID must never submit the demo form");
    translatedCopyLabels.add((await copyButton.innerText()).trim());
    assert.equal(await card.locator('a[href="#"], a[href=""], a[href^="javascript:"], a[href^="weixin:"]').count(), 0, "The contact card must not invent a WeChat URL");
    assert.equal(await card.locator("img").count(), 1, "The contact card must display exactly one real WeChat QR thumbnail");
    assert.equal(await qrTrigger.getAttribute("type"), "button", "Viewing the WeChat QR must never submit the demo form");
    assert.equal(await qrTrigger.getAttribute("aria-haspopup"), "dialog", "The WeChat QR trigger must announce its enlarged dialog");
    await assertQrImage(qrTrigger.locator("img"), `${locale.language} WeChat thumbnail`);
    qrTranslations["QR actions"].add((await qrTrigger.innerText()).trim());
    const qrCopy = await assertQrDialog({ width: 1440, height: 1000 }, `${locale.language} WeChat QR`);
    qrTranslations["QR titles"].add(qrCopy.title);
    qrTranslations["QR instructions"].add(qrCopy.hint);
    qrTranslations["QR alternative text"].add(qrCopy.alt);
    qrTranslations["QR close labels"].add(qrCopy.close);
    await qrDialog.locator(".mk-dialog-close").click();
    await assertQrClosed(`${locale.language} close button`);
    await assertQrDialog({ width: 1440, height: 1000 }, `${locale.language} WeChat QR reopened`);
    await page.keyboard.press("Escape");
    await assertQrClosed(`${locale.language} Escape`);
    await page.evaluate(() => { window.__wechatRejectCopy = false; });
    await copyButton.click();
    await page.waitForFunction(() => Boolean(document.querySelector('.mk-wechat-status[role="status"]')?.textContent?.trim()));
    const success = (await card.locator('.mk-wechat-status[role="status"]').innerText()).trim();
    translatedSuccess.add(success);
    assert.equal(await page.evaluate(() => window.__wechatWrites.at(-1)), wechatId, "The copy action must write the exact WeChat ID to the clipboard");
    assert.equal(apiRequests.length, requestsBefore, "Copying the WeChat ID must not submit a commercial request");
    await page.evaluate(() => { window.__wechatRejectCopy = true; });
    await copyButton.click();
    await page.waitForFunction(previous => {
      const status = document.querySelector('.mk-wechat-status[role="status"]')?.textContent?.trim();
      return Boolean(status && status !== previous);
    }, success);
    translatedErrors.add((await card.locator('.mk-wechat-status[role="status"]').innerText()).trim());
    assert.deepEqual(await input.evaluate(element => ({ focused: document.activeElement === element, start: element.selectionStart, end: element.selectionEnd, length: element.value.length })), { focused: true, start: 0, end: wechatId.length, length: wechatId.length }, "Clipboard denial must focus and fully select the ID for manual copying");
    assert.equal(apiRequests.length, requestsBefore, "A failed clipboard operation must not submit the demo form");
    const nextLanguage = locale.language === "es" ? "en" : "es";
    await chooseLanguage(page, nextLanguage);
    await page.waitForFunction(() => !document.querySelector(".mk-wechat-status")?.textContent?.trim());
  }
  for (const [name, values] of Object.entries({ titles: translatedTitles, "copy labels": translatedCopyLabels, "success messages": translatedSuccess, "manual-copy guidance": translatedErrors, ...qrTranslations })) {
    assert.equal(values.size, 3, `WeChat ${name} must be localized in Spanish, English and Chinese`);
    assert.ok([...values].every(Boolean), `WeChat ${name} must never be empty`);
  }
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 1280, height: 720 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    for (const locale of tourLanguages) {
      await chooseLanguage(page, locale.language);
      const context = `${viewport.width}x${viewport.height}/${locale.language} WeChat contact`;
      const layout = await card.evaluate(element => {
        const frame = element.getBoundingClientRect();
        const visible = child => child.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
        const outside = [...element.querySelectorAll("*")].filter(child => {
          if (child instanceof SVGElement || !visible(child)) return false;
          const rect = child.getBoundingClientRect();
          return rect.left < frame.left - 1 || rect.right > frame.right + 1 || rect.top < frame.top - 1 || rect.bottom > frame.bottom + 1;
        }).map(child => `${child.tagName}.${child.className}`);
        const page = document.querySelector(".mk-page");
        return { width: frame.width, height: frame.height, left: frame.left, right: frame.right, outside, overflowX: element.scrollWidth - element.clientWidth, overflowY: element.scrollHeight - element.clientHeight, pageOverflowX: page.scrollWidth - page.clientWidth, documentOverflowX: document.documentElement.scrollWidth - innerWidth, pageOverflowY: page.scrollHeight - page.clientHeight };
      });
      assert.ok(layout.width > 0 && layout.height > 0 && layout.left >= -1 && layout.right <= viewport.width + 1, `${context} card must fit horizontally (${JSON.stringify(layout)})`);
      assert.deepEqual(layout.outside, [], `${context} card children must remain inside its bounds`);
      assert.ok(layout.overflowX <= 1 && layout.overflowY <= 1 && layout.pageOverflowX <= 1 && layout.documentOverflowX <= 1, `${context} card and page must not clip or overflow horizontally (${JSON.stringify(layout)})`);
      // Contact may scroll vertically: delivery status and field errors must stay readable.
      await assertQrDialog(viewport, context);
      await page.keyboard.press("Escape");
      await assertQrClosed(context);
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await chooseLanguage(page, "es");
  await page.evaluate(() => { window.__wechatRejectCopy = false; });
  assert.equal(apiRequests.length, requestsBefore, "Every WeChat check must preserve the demo request count");
}

const server = spawn(process.execPath, [vite, "preview", "--port", "5186", "--strictPort", "--configLoader", "runner"], {
  cwd: root, stdio: "ignore", windowsHide: true
});
let browser;
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw Error("Product preview could not start");
    try { if ((await fetch(base)).ok) { ready = true; break; } } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, "Product preview startup timed out");
  browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {})
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  const apiRequests = [];
  const apiPayloads = [];
  const apiIdempotencyKeys = [];
  const productOrder = ["venta", "gestion", "pda", "saas"];
  const productCheckbox = app => page.locator(`.mk-contact-selection input[name="products"][value="${app}"]`);
  const selectedProducts = () => page.locator('.mk-contact-selection input[name="products"]:checked').evaluateAll(inputs => inputs.map(input => input.value));
  const fullPackage = page.locator('.mk-contact-selection input[name="fullPackage"]');
  let demoApiStatus = 201;
  let demoAvailability = true;
  let demoApiBody;
  let demoApiContentType = "application/json";
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/public/demo-requests/availability", route => route.fulfill({
    status: demoAvailability ? 200 : 503,
    contentType: "application/json",
    body: JSON.stringify({ available: demoAvailability })
  }));
  await page.route("**/api/v1/public/demo-requests", route => {
    apiRequests.push(route.request().url());
    apiPayloads.push(route.request().postDataJSON());
    apiIdempotencyKeys.push(route.request().headers()["idempotency-key"]);
    return route.fulfill({
      status: demoApiStatus,
      contentType: demoApiContentType,
      body: demoApiBody ?? (demoApiStatus === 201 ? JSON.stringify({ id: "7b3dbe84-8d67-48db-bc48-f355d7ad7f82", receivedAt: "2026-10-03T12:00:00Z" }) : JSON.stringify({ message: "Unavailable" }))
    });
  });
  await page.goto(`${base}?utm_source=e2e&utm_medium=automation&utm_campaign=marketing-upgrade#/producto/inicio`, { waitUntil: "networkidle" });
  assert.match(await page.locator("h1").innerText(), /Vende sin fricción/);
  assert.match(await page.title(), /\besPOS\b/);
  assert.equal(await page.locator('meta[property="og:type"]').getAttribute("content"), "website");
  assert.equal(await page.locator('script[type="application/ld+json"]').count(), 1);
  assert.equal(await page.locator('link[rel="canonical"]').count(), 0, "Local preview must not invent a public canonical URL");
  assert.equal(await page.locator('input[autocomplete="current-password"]').count(), 0);
  await page.waitForFunction(() => [...document.images].every(image => image.complete && image.naturalWidth > 0));
  const favicon = page.locator('link[rel="icon"]');
  assert.equal(await favicon.count(), 1, "The public website must declare one official application icon");
  assert.equal(await favicon.getAttribute("href"), "/branding/espos-icon.ico");
  const faviconResponse = await page.request.get(new URL(await favicon.getAttribute("href"), base).toString());
  assert.equal(faviconResponse.status(), 200, "The official esPOS favicon must be served successfully");
  const faviconBytes = await faviconResponse.body();
  assert.ok(faviconBytes.length > 6, "The application icon must not be an empty placeholder");
  assert.deepEqual([...faviconBytes.subarray(0, 4)], [0, 0, 1, 0], "The application icon must be a valid ICO file");
  assert.deepEqual(faviconBytes, await readFile(new URL("../../frontend/branding/app-venta.ico", import.meta.url)), "The public favicon must preserve the official application icon byte for byte");
  const tabs = navigationRoutes;
  for (const tab of tabs) {
    await page.locator(`#mk-navigation a[href="${marketingHref("es", tab)}"]`).click();
    await waitForMarketingRoute(page, "es", tab);
    await page.locator("h1").waitFor({ state: "visible" });
    assert.equal(await page.locator("#mk-navigation a[aria-current=page]").getAttribute("href"), marketingHref("es", tab));
    await page.reload({ waitUntil: "networkidle" });
    await page.locator("h1").waitFor({ state: "visible" });
    const desktopPageSize = await page.evaluate(() => {
      const page = document.querySelector(".mk-page");
      return { scrollWidth: page.scrollWidth, clientWidth: page.clientWidth };
    });
    assert.ok(desktopPageSize.scrollWidth <= desktopPageSize.clientWidth + 1, `${tab} must fit horizontally; vertical scrolling preserves readable content (${JSON.stringify(desktopPageSize)})`);
  }
  for (const [language, htmlLanguage] of [["en", "en"], ["zh", "zh-CN"], ["es", "es"]]) {
    await chooseLanguage(page, language);
    await page.waitForFunction(value => document.documentElement.lang === value, htmlLanguage);
    for (const alias of [marketingUrl(language, "funcionalidades"), `${marketingUrl(language, "inicio")}#/producto/funcionalidades`]) {
      await page.goto(alias, { waitUntil: "networkidle" });
      await page.locator("main .mk-solution-features").waitFor({ state: "visible" });
      assert.equal(page.url(), alias, "Compatibility URLs render Solution without rewriting the original URL");
      assert.equal(await currentLanguage(page), language);
      assert.equal(await page.locator("#mk-navigation a").count(), 5, "Solution and features share a single navigation entry");
      assert.equal(await page.locator(`#mk-navigation a[href="${marketingHref(language, "funcionalidades")}"]`).count(), 0);
      assert.equal(await page.locator("main h1").count(), 1, "The merged solution must retain one primary heading");
      assert.equal(await page.locator("main .mk-feature-list article h3").count(), 4, "All four functional groups remain in the merged solution");
      assert.equal(await page.locator("#mk-navigation a[aria-current=page]").getAttribute("href"), marketingHref(language, "solucion"));
    }
    for (const tab of tabs) {
      await page.goto(`${base}#/producto/${tab}`, { waitUntil: "networkidle" });
      assert.equal(await page.locator("html").getAttribute("lang"), htmlLanguage, `${language}/${tab} must preserve the chosen language after navigation`);
      await assertBrand(page, tab, language);
    }
    await page.reload({ waitUntil: "networkidle" });
    assert.equal(await page.locator("html").getAttribute("lang"), htmlLanguage);
    await assertBrand(page, "contacto", language);
    // Explicit locale paths must take priority over a previous language preference.
    for (const tab of tabs) {
      await page.goto(marketingUrl(language, tab), { waitUntil: "networkidle" });
      await waitForMarketingRoute(page, language, tab);
      assert.equal(await page.locator("html").getAttribute("lang"), htmlLanguage);
      await assertBrand(page, tab, language);
    }
  }
  await page.setViewportSize({ width: 1920, height: 953 });
  for (const tab of tabs) {
    await page.goto(`${base}#/producto/${tab}`, { waitUntil: "networkidle" });
    const desktopPageSize = await page.evaluate(() => {
      const page = document.querySelector(".mk-page");
      return { scrollWidth: page.scrollWidth, clientWidth: page.clientWidth };
    });
    assert.ok(desktopPageSize.scrollWidth <= desktopPageSize.clientWidth + 1, `${tab} must fit horizontally at 1920x953 (${JSON.stringify(desktopPageSize)})`);
  }
  await assertCompactSolution(page);
  await assertCompactMarketingPages(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${base}#/producto/apps`, { waitUntil: "networkidle" });
  const tourTriggers = page.locator(".mk-app-tour-action");
  assert.equal(await tourTriggers.count(), 1);
  const explorer = page.getByTestId("application-explorer");
  for (const locale of tourLanguages) {
    await chooseLanguage(page, locale.language);
    for (const app of productOrder) {
      await page.getByTestId(`guide-app-${app}`).click();
      assert.equal(await explorer.getAttribute("data-active-app"), app);
      assert.equal(await explorer.locator(".mk-app-explorer-preview img").getAttribute("src"), `/marketing/tour-${app}.png`);
      for (const section of ["overview", "workflow", "setup", "questions"]) {
        await page.getByTestId(`guide-section-${section}`).click();
        const panel = page.getByTestId("guide-content-panel");
        assert.equal(await panel.getAttribute("data-section"), section);
        if (section === "questions") {
          const questions = panel.locator("details");
          assert.ok(await questions.count() > 0, "The guide must include questions and answers");
          for (const question of await questions.all()) {
            await question.locator("summary").click();
            assert.ok(await question.locator("p").isVisible(), "Opening a question must reveal its answer");
            assert.ok((await question.locator("p").innerText()).trim().length > 0);
          }
        }
        assert.ok((await panel.innerText()).trim().length > 30, `${locale.language}/${app}/${section} must explain more than a label`);
        assert.ok(await explorer.evaluate(element => element.scrollWidth <= element.clientWidth + 1), `${locale.language}/${app}/${section} must fit horizontally`);
      }
      const preparation = explorer.getByTestId("guide-preparation-action");
      assert.equal(await preparation.getAttribute("href"), `${marketingHref(locale.language, "apps")}#mk-implementation-journey`);
      const locationBeforePreparation = page.url();
      const requestsBeforePreparation = apiRequests.length;
      await preparation.focus();
      await page.keyboard.press("Enter");
      const implementation = page.getByTestId("implementation-journey");
      assert.equal(await implementation.evaluate(element => element.open), true, "Preparation opens the existing guide rather than another form");
      assert.ok(await implementation.locator("summary").evaluate(element => element === document.activeElement), "Keyboard activation moves focus to the guide summary");
      assert.equal(page.url(), locationBeforePreparation, "Reading the guide must not change the active route");
      assert.equal(await explorer.getAttribute("data-active-app"), app, "Reading the guide preserves the selected app");
      assert.equal(apiRequests.length, requestsBeforePreparation, "Reading the guide must not submit a request");
      await implementation.locator("summary").press("Enter");
      const demo = explorer.locator(".mk-app-explorer-demo");
      assert.equal(await demo.getAttribute("href"), `${marketingHref(locale.language, "contacto")}?apps=${app}#mk-demo-form`, "Application demos must expose a shareable product choice");
      await demo.click();
      await waitForMarketingRoute(page, locale.language, "contacto", "#mk-demo-form");
      await page.waitForFunction(() => document.activeElement?.id === "mk-demo-form");
      assert.equal(await page.evaluate(() => new URLSearchParams(location.search).get("apps")), app);
      await productCheckbox(app).waitFor({ state: "visible" });
      assert.deepEqual(await selectedProducts(), [app], "The application demo link must initialize the requested selection");
      await page.locator(`#mk-navigation a[href="${marketingHref(locale.language, "apps")}"]`).click();
      await waitForMarketingRoute(page, locale.language, "apps");
    }
  }
  await page.goto(marketingUrl("en", "contacto", "?apps=all"), { waitUntil: "networkidle" });
  assert.equal(await page.locator("html").getAttribute("lang"), "en", "A shared URL must override a different stored language");
  assert.deepEqual(await selectedProducts(), productOrder, "The shared complete-package URL must initialize all application choices");
  assert.equal(await fullPackage.isChecked(), true);
  await page.reload({ waitUntil: "networkidle" });
  assert.deepEqual(await selectedProducts(), productOrder, "A complete-package URL must preserve its choice across a reload");
  await page.goto(marketingUrl("es", "apps"), { waitUntil: "networkidle" });
  await chooseLanguage(page, "es");
  await page.getByTestId("guide-app-venta").click();
  await page.getByTestId("guide-app-venta").press("ArrowRight");
  assert.equal(await page.getByTestId("guide-app-gestion").getAttribute("aria-selected"), "true");
  assert.ok(await page.getByTestId("guide-app-gestion").evaluate(element => element === document.activeElement));
  await page.getByTestId("guide-section-overview").press("End");
  assert.equal(await page.getByTestId("guide-section-questions").getAttribute("aria-selected"), "true");
  const tourImages = new Set();
  for (const [index, app] of ["venta", "gestion", "pda", "saas"].entries()) {
    await page.getByTestId(`guide-app-${app}`).click();
    const trigger = tourTriggers.first();
    await trigger.click();
    const tour = page.getByTestId("product-tour");
    await tour.waitFor({ state: "visible" });
    assert.equal(await tour.getAttribute("data-active-app"), app);
    const image = tour.locator(".mk-tour-preview img");
    const source = await image.getAttribute("src");
    assert.equal(source, `/marketing/steps/${app}-1.webp`, `${app} must show its own first step`);
    tourImages.add(source);
    await image.evaluate(image => image.decode());
    assert.ok(await image.getAttribute("alt"), `${app} preview must have descriptive alternative text`);
    const preview = await tour.locator(".mk-tour-context").evaluate(element => ({ scrollHeight: element.scrollHeight, clientHeight: element.clientHeight }));
    assert.ok(preview.scrollHeight <= preview.clientHeight + 1, `${app} preview and description must fit (${JSON.stringify(preview)})`);
    const bounds = await tour.boundingBox();
    assert.ok(bounds && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= 1440 && bounds.y + bounds.height <= 1000, `${app} tour must fit the desktop viewport`);
    await page.keyboard.press("Escape");
    await tour.waitFor({ state: "hidden" });
    assert.ok(await trigger.evaluate(element => document.activeElement === element), `${app} tour must restore focus to its trigger`);
  }
  assert.equal(tourImages.size, 4, "Each application must have a different tour image");
  const translatedTourDetails = new Map();
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport);
    await tourTriggers.first().click();
    const expandedTour = page.getByTestId("product-tour");
    await expandedTour.waitFor({ state: "visible" });
    for (const locale of tourLanguages) {
      await expandedTour.locator(".mk-dialog-close").click();
      await chooseLanguage(page, locale.language);
      await tourTriggers.first().click();
      await expandedTour.waitFor({ state: "visible" });
      await page.waitForFunction(value => document.documentElement.lang === value, locale.htmlLanguage);
      const contentByField = { actions: new Set(), example: new Set(), benefit: new Set() };
      for (const [index, app] of productOrder.entries()) {
        await expandedTour.locator('.mk-tour-tabs [role="tab"]').nth(index).click();
        assert.match(await expandedTour.locator(".mk-tour-step-count").innerText(), /1 \/ 3/, "Selecting an application must reset its walkthrough to the first step");
        await expandedTour.locator(".mk-tour-preview img").evaluate(image => image.decode());
        assert.equal(await expandedTour.locator(".mk-tour-preview img").getAttribute("src"), `/marketing/steps/${app}-1.webp`);
        for (let step = 0; step < 3; step++) {
          await expandedTour.locator(".mk-tour-progress button").nth(step).click();
          const details = await assertTourContent(expandedTour, locale, app, step);
          await assertTourLayout(expandedTour, viewport, false, `${viewport.width}x${viewport.height}/${locale.language}/${app}/step ${step + 1}`);
          for (const field of Object.keys(contentByField)) contentByField[field].add(details[field]);
          const key = `${locale.language}/${app}/${step}`;
          if (translatedTourDetails.has(key)) assert.deepEqual(details, translatedTourDetails.get(key), `${key} content must stay consistent across viewport sizes`);
          else translatedTourDetails.set(key, details);
        }
        if (viewport.width === 1440 && locale.language === "es") {
          const stepCount = expandedTour.locator(".mk-tour-step-count");
          await expandedTour.locator(".mk-tour-progress button").first().click();
          assert.equal(await expandedTour.locator(".mk-tour-actions button").first().isDisabled(), true);
          await expandedTour.locator(".mk-tour-actions .is-primary").click();
          assert.match(await stepCount.innerText(), /2 \/ 3/, `${app} next button must advance the tour`);
          await expandedTour.locator(".mk-tour-actions button").first().click();
          assert.match(await stepCount.innerText(), /1 \/ 3/, `${app} previous button must return to the prior step`);
          await expandedTour.locator(".mk-tour-stage").focus();
          await page.keyboard.press("ArrowLeft");
          assert.match(await stepCount.innerText(), /1 \/ 3/, `${app} keyboard navigation must stop at the first step`);
          await page.keyboard.press("ArrowRight");
          assert.match(await stepCount.innerText(), /2 \/ 3/, `${app} keyboard navigation must advance`);
          await page.keyboard.press("ArrowRight");
          await page.keyboard.press("ArrowRight");
          assert.match(await stepCount.innerText(), /3 \/ 3/, `${app} keyboard navigation must stop at the last step`);
          await page.keyboard.press("ArrowLeft");
          assert.match(await stepCount.innerText(), /2 \/ 3/, `${app} keyboard navigation must return to the prior step`);
        }
      }
      for (const [field, values] of Object.entries(contentByField)) assert.equal(values.size, 12, `${locale.language} ${field} must change for every application and step`);
    }
    await page.keyboard.press("Escape");
    await expandedTour.waitFor({ state: "hidden" });
  }
  assert.equal(translatedTourDetails.size, 36, "Every application and step must have content in all three languages");
  for (const app of productOrder) {
    for (let step = 0; step < 3; step++) {
      for (const field of ["actions", "example", "benefit"]) {
        assert.equal(new Set(tourLanguages.map(locale => translatedTourDetails.get(`${locale.language}/${app}/${step}`)[field])).size, 3, `${app}/step ${step + 1} ${field} must be translated into all three languages`);
      }
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await chooseLanguage(page, "es");
  await tourTriggers.first().click();
  const tour = page.getByTestId("product-tour");
  await tour.locator(".mk-tour-stage").focus();
  await page.keyboard.press("ArrowRight");
  assert.match(await tour.locator(".mk-tour-step-count").innerText(), /2 \/ 3/);
  await tour.locator('.mk-tour-tabs [role="tab"]').nth(2).click();
  assert.equal(await tour.getAttribute("data-active-app"), "pda");
  assert.equal(await tour.locator(".mk-tour-preview img").getAttribute("src"), "/marketing/steps/pda-1.webp", "Switching tabs must also update the preview");
  await tour.locator(".mk-tour-preview img").evaluate(image => image.decode());
  assert.match(await tour.locator(".mk-tour-step-count").innerText(), /1 \/ 3/);
  for (const [language, note] of [["en", "Real interface"], ["zh", "真实界面"], ["es", "Interfaz real"]]) {
    await tour.locator(".mk-dialog-close").click();
    await chooseLanguage(page, language);
    await page.getByTestId("guide-app-pda").click();
    await tourTriggers.first().click();
    assert.ok((await tour.locator(".mk-tour-preview figcaption").innerText()).includes(note), "Preview label must follow the website language");
    assert.equal(await tour.locator(".mk-tour-preview img").getAttribute("src"), "/marketing/steps/pda-1.webp", "The selected application must open its own preview in each language");
  }
  await tour.locator(".mk-tour-progress button").last().click();
  await tour.locator(".mk-tour-actions .is-primary").click();
  await page.locator(".mk-contact-selection").waitFor({ state: "visible" });
  assert.deepEqual(await selectedProducts(), ["pda"], "A product-tour demo must preselect the application being explored");

  await page.setViewportSize({ width: 1440, height: 1200 });
  await page.goto(`${base}#/producto/inicio`, { waitUntil: "networkidle" });
  assert.equal(await page.locator(".mk-proof-panel > span").count(), 3, "Home must show three verifiable capability metrics");
  assert.equal(await page.locator(".mk-home-sector-preview article").count(), 4, "Tall home must use its space for four sector use cases");
  assert.ok(await page.locator(".mk-home-sector-preview").isVisible(), "Sector use cases must be visible on tall screens");
  const tallHome = await page.evaluate(() => {
    const page = document.querySelector(".mk-page");
    const footer = document.querySelector(".mk-footer");
    return { scrollHeight: page.scrollHeight, clientHeight: page.clientHeight, footerBottom: Math.round(footer.getBoundingClientRect().bottom), viewport: innerHeight };
  });
  assert.ok(tallHome.footerBottom >= tallHome.viewport - 1, `Footer must not leave an empty strip below the content (${JSON.stringify(tallHome)})`);
  for (const viewport of [{ width: 390, height: 844 }, { width: 760, height: 720 }, { width: 1024, height: 600 }]) {
    await assertResponsiveHome(page, viewport);
  }
  await page.setViewportSize({ width: 1440, height: 1000 });

  const downloadTrigger = page.locator(".mk-client-button");
  await downloadTrigger.click();
  const downloadCenter = page.getByTestId("download-center");
  await downloadCenter.waitFor({ state: "visible" });
  for (const name of await downloadCenter.locator(".mk-download-grid h3").allInnerTexts()) {
    assert.match(name, /^esPOS\s/, "Download cards must identify each official esPOS application");
  }
  const desktopDownloadsConfigured = Boolean(process.env.VITE_DOWNLOAD_VENTA_URL && process.env.VITE_DOWNLOAD_GESTION_URL);
  const expectedDeliveryStatuses = desktopDownloadsConfigured
    ? ["available", "available", "restricted", "restricted"]
    : ["unpublished", "unpublished", "restricted", "restricted"];
  assert.deepEqual(await downloadCenter.locator("[data-delivery-status]").evaluateAll(cards => cards.map(card => card.getAttribute("data-delivery-status"))), expectedDeliveryStatuses);
  if (desktopDownloadsConfigured) {
    const primaryDownloads = downloadCenter.locator(".mk-download-primary");
    assert.equal(await primaryDownloads.count(), 2, "Both signed desktop installers must be downloadable");
    assert.equal(await primaryDownloads.nth(0).getAttribute("href"), process.env.VITE_DOWNLOAD_VENTA_URL);
    assert.equal(await primaryDownloads.nth(1).getAttribute("href"), process.env.VITE_DOWNLOAD_GESTION_URL);
    assert.equal(await downloadCenter.locator('a[href$=".sha256"]').count(), 2, "Both installers must expose a checksum");
  } else {
    assert.equal(await downloadCenter.locator(".mk-download-primary").count(), 0, "No download button may appear without a published artifact URL");
  }
  assert.equal(await downloadCenter.locator('a[href="#"], a[href=""], a[href^="javascript:"]').count(), 0, "Download center must not contain fake links");
  assert.ok(await downloadCenter.evaluate(element => element.scrollWidth <= element.clientWidth + 1), "Download center must fit horizontally with natural vertical scrolling");
  await downloadCenter.locator(".mk-dialog-close").click();
  await chooseLanguage(page, "en");
  await downloadTrigger.click();
  await page.waitForFunction(() => document.documentElement.lang === "en");
  assert.match(await downloadCenter.locator("h2").innerText(), /Install only/);
  await downloadCenter.locator(".mk-dialog-close").click();
  await chooseLanguage(page, "zh");
  await downloadTrigger.click();
  await page.waitForFunction(() => document.documentElement.lang === "zh-CN");
  assert.match(await downloadCenter.locator("h2").innerText(), /为每个团队/);
  await downloadCenter.locator(".mk-dialog-close").click();
  await chooseLanguage(page, "es");
  await downloadTrigger.click();
  await downloadCenter.locator('[data-testid="download-card-pda"] .mk-download-request').click();
  await waitForMarketingRoute(page, "es", "contacto", "#mk-demo-form");
  await page.waitForFunction(() => document.activeElement?.id === "mk-demo-form");
  assert.equal(await page.evaluate(() => new URLSearchParams(location.search).get("apps")), "pda");
  await productCheckbox("pda").waitFor({ state: "visible" });
  assert.deepEqual(await selectedProducts(), ["pda"], "Assisted delivery must carry the selected product to Contact");
  assert.equal(await fullPackage.isChecked(), false, "A single-product request must not select the complete package");
  const selectionLabels = new Set();
  for (const [language, expectedLegend, expectedPackageLabel] of [
    ["en", "Applications for your demo", "I'm interested in all four apps"],
    ["zh", "希望演示的应用", "我对全部四款应用感兴趣"],
    ["es", "Aplicaciones para tu demo", "Me interesa el conjunto completo"]
  ]) {
    await chooseLanguage(page, language);
    const legend = (await page.locator(".mk-contact-selection legend").innerText()).trim();
    const packageLabel = await fullPackage.evaluate(input => input.labels?.[0]?.textContent?.trim());
    assert.ok(legend && packageLabel, "Product selection and package controls must have accessible translated labels");
    assert.equal(legend, expectedLegend);
    assert.ok(packageLabel.includes(expectedPackageLabel), "The complete-package label must follow the chosen language");
    for (const app of productOrder) {
      const label = await productCheckbox(app).evaluate(input => input.labels?.[0]?.textContent?.trim());
      assert.match(label, /\besPOS\b/, `${language}/${app} product checkbox must use the official accessible brand name`);
    }
    selectionLabels.add(`${legend}|${packageLabel}`);
    assert.deepEqual(await selectedProducts(), ["pda"], "Changing language must preserve the chosen products");
  }
  assert.equal(selectionLabels.size, 3, "Product selection labels must be translated into all three website languages");
  await assertWeChatContact(page, apiRequests);

  await page.setViewportSize({ width: 1280, height: 720 });
  await page.locator('input[name="name"]').fill("Demo");
  await page.locator('input[name="company"]').fill("Demo Company");
  await page.locator('input[name="email"]').fill("demo@example.com");
  await page.locator('input[name="privacyAccepted"]').check();
  for (const language of ["en", "zh", "es"]) {
    await chooseLanguage(page, language);
    await fullPackage.check();
    assert.ok(await page.locator(".mk-page").evaluate(element => element.scrollWidth <= element.clientWidth + 1), `${language} complete-package form must fit horizontally with natural vertical scrolling`);
    await fullPackage.uncheck();
    const requestsBeforeValidation = apiRequests.length;
    await page.locator(".mk-contact-submit").click();
    await page.locator('.mk-contact-selection-error[role="alert"]').waitFor({ state: "visible" });
    assert.equal(apiRequests.length, requestsBeforeValidation, "Empty selections must remain local validation failures");
    assert.ok(await productCheckbox("venta").evaluate(element => document.activeElement === element), "Empty-selection validation must focus the first product checkbox");
    assert.ok(await page.locator(".mk-page").evaluate(element => element.scrollWidth <= element.clientWidth + 1), `${language} selection validation must stay readable without horizontal scrolling`);
  }
  await productCheckbox("pda").check();

  await page.setViewportSize({ width: 390, height: 844 });
  // Preserve an explicitly shared app choice while entering through an old hash URL.
  await page.goto(`${base}?apps=pda#/producto/inicio`, { waitUntil: "networkidle" });
  await page.locator(".mk-menu-button").click();
  await page.locator(`#mk-navigation a[href="${marketingHref("es", "contacto")}"]`).click();
  await waitForMarketingRoute(page, "es", "contacto");
  await page.locator(".mk-contact-form").waitFor({ state: "visible" });
  assert.deepEqual(await selectedProducts(), ["pda"], "Ordinary navigation must preserve the shared application choice");
  await page.waitForFunction(() => document.querySelector(".mk-menu-button")?.getAttribute("aria-expanded") === "false");
  assert.equal(await page.locator(".mk-menu-button").getAttribute("aria-expanded"), "false");
  assert.ok(await page.evaluate(() => {
    const page = document.querySelector(".mk-page");
    return page.scrollWidth <= page.clientWidth && document.documentElement.scrollWidth <= innerWidth;
  }), "Mobile page must not overflow horizontally");

  await page.locator('input[name="name"]').fill("María García");
  await page.locator('input[name="company"]').fill("Comercio Centro");
  await page.locator('input[name="email"]').fill("maria@example.com");
  await page.locator('input[name="phone"]').fill("600000000");
  await page.locator('textarea[name="message"]').fill("Dos tiendas y un almacén");
  await page.locator('input[name="privacyAccepted"]').check();
  await page.locator(".mk-contact-submit").click();
  await page.locator(".mk-contact-prepared").waitFor({ state: "visible" });
  assert.equal(await page.locator('input[name="name"]').inputValue(), "", "Successful requests reset the form");
  assert.deepEqual(await selectedProducts(), ["pda"], "Successful requests preserve the latest application preference");

  await page.locator('input[name="name"]').fill("Ana López");
  await page.locator('input[name="company"]').fill("Comercio Sur");
  await page.locator('input[name="email"]').fill("ana@example.com");
  await page.locator('input[name="phone"]').fill("611000000");
  await page.locator('textarea[name="message"]').fill("Venta y control de almacén");
  await page.locator('input[name="privacyAccepted"]').check();
  await page.locator(".mk-contact-product").first().click();
  assert.deepEqual(await selectedProducts(), ["venta", "pda"], "Product cards must add an application without replacing previous choices");
  assert.deepEqual(await page.locator(".mk-contact-product").evaluateAll(cards => cards.map(card => card.getAttribute("aria-pressed"))), ["true", "false", "true", "false"], "Product cards must mirror the checkbox selection accessibly");
  assert.equal(await page.locator('input[name="name"]').inputValue(), "Ana López", "Changing product selection must preserve personal details");
  assert.equal(await page.locator('textarea[name="message"]').inputValue(), "Venta y control de almacén", "Changing product selection must preserve the business description");
  await page.locator(".mk-contact-submit").click();
  await page.locator(".mk-contact-prepared").waitFor({ state: "visible" });
  assert.deepEqual(apiPayloads.at(-1).products, ["APP_VENTA", "APP_PDA"], "Every chosen application must reach the API in canonical order");
  assert.equal(apiPayloads.at(-1).product, "APP_VENTA", "The legacy primary product must remain compatible with the first chosen application");
  assert.deepEqual(await selectedProducts(), ["venta", "pda"], "A multi-product success preserves the visitor's latest choice");

  await fullPackage.check();
  assert.deepEqual(await selectedProducts(), productOrder, "The complete package must select all four applications");
  await productCheckbox("saas").uncheck();
  assert.equal(await fullPackage.isChecked(), false, "Removing an application must clear the complete-package checkbox");
  assert.deepEqual(await selectedProducts(), ["venta", "gestion", "pda"], "Removing one package component must retain the other three");
  await fullPackage.check();
  await fullPackage.uncheck();
  assert.deepEqual(await selectedProducts(), [], "Deselecting the complete package must clear every application");
  await fullPackage.check();
  await page.locator('input[name="name"]').fill("Paula Martín");
  await page.locator('input[name="company"]').fill("Grupo Centro");
  await page.locator('input[name="email"]').fill("paula@example.com");
  await page.locator('input[name="privacyAccepted"]').check();
  await page.locator(".mk-contact-submit").click();
  await page.locator(".mk-contact-prepared").waitFor({ state: "visible" });
  assert.deepEqual(apiPayloads.at(-1).products, ["APP_VENTA", "APP_GESTION", "APP_PDA", "APP_SAAS"], "A complete-package request must submit all four products");
  assert.deepEqual(await selectedProducts(), productOrder, "A complete-package success preserves the latest complete-package choice");
  assert.equal(await fullPackage.isChecked(), true);

  demoApiStatus = 503;
  await page.locator('input[name="name"]').fill("Luis Pérez");
  await page.locator('input[name="company"]').fill("Tienda Norte");
  await page.locator('input[name="email"]').fill("luis@example.com");
  await page.locator('input[name="privacyAccepted"]').check();
  await fullPackage.uncheck();
  const requestsBeforeEmptySelection = apiRequests.length;
  await page.locator(".mk-contact-submit").click();
  await page.locator('.mk-contact-selection-error[role="alert"]').waitFor({ state: "visible" });
  assert.equal(apiRequests.length, requestsBeforeEmptySelection, "An empty selection must not send a request to the API");
  assert.equal(await page.locator('input[name="name"]').inputValue(), "Luis Pérez", "Selection validation must preserve entered details");
  await productCheckbox("pda").check();
  await productCheckbox("gestion").check();
  await page.locator(".mk-contact-submit").click();
  await page.locator(".mk-contact-error").waitFor({ state: "visible" });
  assert.equal(await page.locator('input[name="name"]').inputValue(), "Luis Pérez", "Failed requests preserve the entered data");
  assert.deepEqual(await selectedProducts(), ["gestion", "pda"], "Failed requests must preserve every selected application");
  assert.deepEqual(apiPayloads.at(-1).products, ["APP_GESTION", "APP_PDA"]);

  const failedRequestKey = apiIdempotencyKeys.at(-1);
  demoApiStatus = 201;
  await page.locator('input[name="name"]').fill("  Luis Pérez  ");
  await productCheckbox("pda").uncheck();
  await productCheckbox("pda").check();
  await page.locator(".mk-contact-submit").click();
  await page.locator(".mk-contact-prepared").waitFor({ state: "visible" });
  assert.equal(apiIdempotencyKeys.at(-1), failedRequestKey, "A manual retry after whitespace or reversible application edits must reuse the normalized request key");
  assert.match(await page.locator(".mk-demo-receipt").innerText(), /7b3dbe84-8d67-48db-bc48-f355d7ad7f82/, "Success must expose the validated receipt reference");
  const confirmedReceipt = page.getByTestId("demo-receipt");
  assert.equal(await confirmedReceipt.locator("time").getAttribute("datetime"), "2026-10-03T12:00:00Z");
  assert.match(await confirmedReceipt.innerText(), /esPOS Gestión, esPOS PDA/, "The receipt retains the exact applications sent");
  const requestsBeforeReceiptCopy = apiRequests.length;
  // Stub the clipboard here; this test must not write to the user's real clipboard.
  await page.evaluate(() => {
    window.__receiptCopied = "";
    window.__receiptReject = false;
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async value => {
      if (window.__receiptReject) throw new Error("Receipt clipboard denied");
      window.__receiptCopied = value;
    } } });
  });
  await confirmedReceipt.locator("button").click();
  await page.waitForFunction(() => window.__receiptCopied.includes("7b3dbe84-8d67-48db-bc48-f355d7ad7f82"));
  const copiedReceipt = await page.evaluate(() => window.__receiptCopied);
  assert.match(copiedReceipt, /esPOS Gestión, esPOS PDA/);
  assert.doesNotMatch(copiedReceipt, /Luis|@/, "The copied receipt must not include contact details");
  await page.evaluate(() => { window.__receiptReject = true; });
  await confirmedReceipt.locator("button").click();
  const manualReceipt = confirmedReceipt.locator("textarea[readonly]");
  await manualReceipt.waitFor({ state: "visible" });
  assert.equal(await manualReceipt.inputValue(), copiedReceipt);
  assert.ok(await manualReceipt.evaluate(element => element === document.activeElement && element.selectionStart === 0 && element.selectionEnd === element.value.length));
  assert.equal(apiRequests.length, requestsBeforeReceiptCopy, "Copying or selecting a receipt cannot submit another request");
  await page.evaluate(() => { window.__receiptReject = false; });

  for (const [field, maximum] of Object.entries({ name: 160, company: 200, email: 160, phone: 40, message: 2000 })) {
    assert.equal(await page.locator(`[name="${field}"]`).getAttribute("maxlength"), String(maximum), `${field} must match its backend length limit`);
  }
  await page.locator('input[name="name"]').fill("   ");
  assert.equal(await page.getByTestId("demo-receipt").count(), 0, "Editing starts a new draft without a stale confirmation");
  await page.locator('input[name="company"]').fill("  Comercio Demo  ");
  await page.locator('input[name="email"]').fill("carla@example.com");
  await page.locator('input[name="privacyAccepted"]').check();
  const beforeWhitespaceValidation = apiRequests.length;
  await page.locator(".mk-contact-submit").click();
  assert.equal(apiRequests.length, beforeWhitespaceValidation, "Whitespace-only required values must fail locally");
  assert.equal(await page.locator('input[name="name"]').getAttribute("aria-invalid"), "true");
  assert.ok(await page.locator('input[name="name"]').evaluate(element => element === document.activeElement), "Local field errors must focus the first invalid field");
  await page.locator('input[name="name"]').fill("  Carla Demo  ");
  demoApiStatus = 400;
  demoApiBody = JSON.stringify({ code: "VALIDATION_FAILED", fields: { company: "REQUIRED", email: "INVALID" } });
  await page.locator(".mk-contact-submit").click();
  await page.locator(".mk-form-error-summary").waitFor({ state: "visible" });
  assert.equal(apiPayloads.at(-1).name, "Carla Demo", "Submitted values must be trimmed");
  assert.equal(apiPayloads.at(-1).company, "Comercio Demo");
  assert.notEqual(apiIdempotencyKeys.at(-1), failedRequestKey, "Editing content must create a fresh request key");
  assert.equal(await page.locator('input[name="company"]').getAttribute("aria-invalid"), "true", "Server field errors must be associated with their input");
  assert.equal(await page.locator('input[name="email"]').getAttribute("aria-invalid"), "true");
  assert.equal(await page.locator('input[name="name"]').inputValue(), "  Carla Demo  ", "Server validation must preserve the entered form values");
  const validationKey = apiIdempotencyKeys.at(-1);
  const failures = new Set([await page.locator(".mk-form-error-summary").innerText()]);
  for (const [status, body, contentType] of [
    [409, JSON.stringify({ code: "IDEMPOTENCY_CONFLICT" }), "application/json"],
    [429, JSON.stringify({ code: "RATE_LIMITED" }), "application/json"],
    [200, "<!doctype html><title>SPA fallback</title>", "text/html"],
    [201, JSON.stringify({ id: "not-a-receipt", receivedAt: "yesterday" }), "application/json"]
  ]) {
    demoApiStatus = status;
    demoApiBody = body;
    demoApiContentType = contentType;
    const response = page.waitForResponse(response => response.url().endsWith("/api/v1/public/demo-requests") && response.request().method() === "POST");
    await page.locator(".mk-contact-submit").click();
    await response;
    await page.locator(".mk-form-error-summary").waitFor({ state: "visible" });
    assert.equal(await page.locator(".mk-contact-prepared").count(), 0, `${status}/${contentType} must never produce unverified success`);
    assert.equal(apiIdempotencyKeys.at(-1), validationKey, "Retrying unchanged content must preserve its key for safe reconciliation");
    assert.equal(await page.locator('input[name="company"]').inputValue(), "  Comercio Demo  ", "All failure categories must preserve the request");
    failures.add(await page.locator(".mk-form-error-summary").innerText());
  }
  assert.equal(failures.size, 4, "Validation, conflict, rate-limit and invalid-receipt errors must have distinct guidance");
  demoApiStatus = 201;
  demoApiBody = undefined;
  demoApiContentType = "application/json";

  await page.locator(".mk-contact-talk").click();
  await page.locator('.mk-faq-dialog[role="dialog"]').waitFor({ state: "visible" });
  assert.equal(await page.locator(".mk-faq-list button").count(), 6);
  await page.locator(".mk-faq-list button").nth(2).click();
  assert.equal(await page.locator(".mk-faq-list button").nth(2).getAttribute("aria-pressed"), "true");
  await page.keyboard.press("Escape");
  await page.locator(".mk-faq-dialog").waitFor({ state: "hidden" });

  await page.locator(".mk-menu-button").click();
  await page.locator(".mk-mobile-client-link").click();
  await page.getByTestId("download-center").waitFor({ state: "visible" });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Mobile download center must not overflow horizontally");
  await page.getByTestId("download-center").locator(".mk-dialog-close").click();
  await page.goto(`${base}#/producto/apps`, { waitUntil: "networkidle" });
  await page.locator(".mk-app-tour-action").first().click();
  const mobileTour = page.getByTestId("product-tour");
  await mobileTour.waitFor({ state: "visible" });
  for (const [index, app] of ["venta", "gestion", "pda", "saas"].entries()) {
    await mobileTour.locator('.mk-tour-tabs [role="tab"]').nth(index).click();
    const image = mobileTour.locator(".mk-tour-preview img");
    await image.evaluate(image => image.decode());
    assert.equal(await image.getAttribute("src"), `/marketing/steps/${app}-1.webp`);
    assert.ok(await mobileTour.locator(".mk-tour-context").evaluate(element => element.scrollHeight <= element.clientHeight + 1), `${app} mobile preview must not clip its contents`);
  }
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Mobile product tour must not overflow horizontally");
  await page.getByTestId("product-tour").locator(".mk-dialog-close").click();
  for (const viewport of [{ width: 390, height: 844 }, { width: 760, height: 720 }, { width: 1024, height: 600 }]) {
    await page.setViewportSize(viewport);
    await page.locator(".mk-app-tour-action").first().click();
    const responsiveTour = page.getByTestId("product-tour");
    await responsiveTour.waitFor({ state: "visible" });
    for (const locale of tourLanguages) {
      await responsiveTour.locator(".mk-dialog-close").click();
      await chooseLanguage(page, locale.language);
      await page.locator(".mk-app-tour-action").first().click();
      await responsiveTour.waitFor({ state: "visible" });
      await page.waitForFunction(value => document.documentElement.lang === value, locale.htmlLanguage);
      for (const [index, app] of productOrder.entries()) {
        await responsiveTour.locator('.mk-tour-tabs [role="tab"]').nth(index).click();
        await responsiveTour.locator(".mk-tour-preview img").evaluate(image => image.decode());
        assert.equal(await responsiveTour.locator(".mk-tour-preview img").getAttribute("src"), `/marketing/steps/${app}-1.webp`);
        for (let step = 0; step < 3; step++) {
          await responsiveTour.locator(".mk-tour-progress button").nth(step).click();
          const context = `${viewport.width}x${viewport.height}/${locale.language}/${app}/step ${step + 1}`;
          const details = await assertTourContent(responsiveTour, locale, app, step);
          assert.deepEqual(details, translatedTourDetails.get(`${locale.language}/${app}/${step}`), `${context} scrollable tour must preserve every explanation`);
          await assertTourLayout(responsiveTour, viewport, true, context);
          await assertReachableControl(responsiveTour.locator(".mk-tour-actions .is-primary"), viewport, `${context} primary action`, viewport.height <= 640);
          await assertReachableControl(responsiveTour.locator(".mk-dialog-close"), viewport, `${context} close button`);
        }
      }
    }
    await responsiveTour.locator(".mk-dialog-close").click();
    await responsiveTour.waitFor({ state: "hidden" });
  }
  demoAvailability = false;
  await page.goto(`${base}#/producto/inicio`, { waitUntil: "networkidle" });
  await page.goto(`${base}#/producto/contacto`, { waitUntil: "networkidle" });
  await page.locator('.mk-delivery-status[data-state="unavailable"]').waitFor({ state: "visible" });
  assert.equal(await page.locator(".mk-contact-submit").isDisabled(), true, "An unavailable service must block unreceivable requests");
  assert.equal(await page.locator('.mk-delivery-status .mk-delivery-actions button').count(), 2, "An unavailable service must offer a check and WeChat fallback");
  demoAvailability = true;
  await page.locator('.mk-delivery-status .mk-delivery-actions button').first().click();
  await page.locator('.mk-delivery-status[data-state="available"]').waitFor({ state: "visible" });
  assert.equal(await page.locator(".mk-contact-submit").isDisabled(), false, "Manual availability checks must allow recovery without losing form state");
  assert.equal(apiRequests.length, 10, "Valid requests, safe retries and all modeled service failures must reach the public API");
  for (const key of apiIdempotencyKeys) assert.match(key, /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i, "Every attempt must carry a UUID idempotency key");
  assert.equal(apiPayloads[0].product, "APP_PDA", "The assisted-delivery selection must reach the demo API");
  assert.deepEqual(apiPayloads[0].products, ["APP_PDA"]);
  assert.equal(apiPayloads[0].privacyAccepted, true);
  assert.equal(apiPayloads[0].utmSource, "e2e");
  assert.equal(apiPayloads[0].utmMedium, "automation");
  assert.equal(apiPayloads[0].utmCampaign, "marketing-upgrade");
  assert.match(apiPayloads[0].landingPath, /producto\/inicio/);
  await assertAccessibleRoutes(page);
  assert.deepEqual(errors, [], "Public website must not raise browser errors");
  console.log("Product E2E passed: esPOS branding, official wordmarks and icons, translated routes, responsive content and product tours, SEO, attribution, assisted downloads, original WeChat QR integrity and copy fallbacks, readable contact layout, multi-product and complete-package requests, availability and recovery, trimmed field validation, verified receipts, stable retry idempotency keys, distinct API errors, rejection of HTML and malformed confirmations, and FAQ.");
} finally {
  await browser?.close();
  server.kill();
}
