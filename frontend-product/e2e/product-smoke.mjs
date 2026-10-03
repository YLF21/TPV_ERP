import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const base = "http://127.0.0.1:5186/";
const tourLanguages = [
  { language: "es", htmlLanguage: "es", actions: "Qué puedes hacer", example: "En la práctica", benefit: "Qué te aporta", delivery: "Cómo se utiliza", previous: "Anterior", next: "Siguiente", demo: "Solicitar demo", close: "Cerrar recorrido" },
  { language: "en", htmlLanguage: "en", actions: "What you can do", example: "In practice", benefit: "How it helps", delivery: "How to use it", previous: "Previous", next: "Next", demo: "Request a demo", close: "Close tour" },
  { language: "zh", htmlLanguage: "zh-CN", actions: "可以做什么", example: "实际场景", benefit: "带来的帮助", delivery: "使用方式", previous: "上一步", next: "下一步", demo: "申请演示", close: "关闭导览" }
];

async function assertTourContent(tour, locale, app, step) {
  const context = `${locale.language}/${app}/step ${step + 1}`;
  assert.equal(await tour.getAttribute("data-active-app"), app, `${context} must preserve the chosen application`);
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
      const allowVerticalScroll = mobile && region === dialog;
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
    const dialog = element.closest(".mk-tour-dialog");
    const frame = dialog.getBoundingClientRect();
    return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, frame: { left: frame.left + dialog.clientLeft, top: frame.top + dialog.clientTop, right: frame.left + dialog.clientLeft + dialog.clientWidth, bottom: frame.top + dialog.clientTop + dialog.clientHeight }, scrollTop: dialog.scrollTop };
  });
  const assertPosition = position => {
    assert.ok(position.left >= -1 && position.top >= -1 && position.right <= viewport.width + 1 && position.bottom <= viewport.height + 1, `${context} must be reachable inside the viewport (${JSON.stringify(position)})`);
    assert.ok(position.left >= position.frame.left - 1 && position.top >= position.frame.top - 1 && position.right <= position.frame.right + 1 && position.bottom <= position.frame.bottom + 1, `${context} must be visible inside the scrollable dialog (${JSON.stringify(position)})`);
  };
  assertPosition(await readPosition());
  if (verifyStable) {
    await control.page().waitForTimeout(300);
    assertPosition(await readPosition());
  }
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
  const productOrder = ["venta", "gestion", "pda", "saas"];
  const productCheckbox = app => page.locator(`.mk-contact-selection input[name="products"][value="${app}"]`);
  const selectedProducts = () => page.locator('.mk-contact-selection input[name="products"]:checked').evaluateAll(inputs => inputs.map(input => input.value));
  const fullPackage = page.locator('.mk-contact-selection input[name="fullPackage"]');
  let demoApiStatus = 201;
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/public/demo-requests", route => {
    apiRequests.push(route.request().url());
    apiPayloads.push(route.request().postDataJSON());
    return route.fulfill({
      status: demoApiStatus,
      contentType: "application/json",
      body: demoApiStatus === 201 ? JSON.stringify({ id: "7b3dbe84-8d67-48db-bc48-f355d7ad7f82", receivedAt: "2026-10-03T12:00:00Z" }) : JSON.stringify({ message: "Unavailable" })
    });
  });
  await page.goto(`${base}?utm_source=e2e&utm_medium=automation&utm_campaign=marketing-upgrade#/producto/inicio`, { waitUntil: "networkidle" });
  assert.match(await page.locator("h1").innerText(), /Vende sin fricción/);
  assert.match(await page.title(), /TPV ERP/);
  assert.equal(await page.locator('meta[property="og:type"]').getAttribute("content"), "website");
  assert.equal(await page.locator('script[type="application/ld+json"]').count(), 1);
  assert.equal(await page.locator('link[rel="canonical"]').count(), 0, "Local preview must not invent a public canonical URL");
  assert.equal(await page.locator('input[autocomplete="current-password"]').count(), 0);
  await page.waitForFunction(() => [...document.images].every(image => image.complete && image.naturalWidth > 0));
  const tabs = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];
  for (const tab of tabs) {
    await page.locator('#mk-navigation a:not(.mk-mobile-client-link)[href="#/producto/' + tab + '"]').click();
    await page.waitForFunction(value => location.hash === "#/producto/" + value, tab);
    await page.locator("h1").waitFor({ state: "visible" });
    assert.equal(await page.locator("#mk-navigation a[aria-current=page]").getAttribute("href"), "#/producto/" + tab);
    await page.reload({ waitUntil: "networkidle" });
    await page.locator("h1").waitFor({ state: "visible" });
    const desktopPageSize = await page.evaluate(() => {
      const page = document.querySelector(".mk-page");
      return { scrollHeight: page.scrollHeight, clientHeight: page.clientHeight };
    });
    assert.ok(desktopPageSize.scrollHeight <= desktopPageSize.clientHeight + 1, `${tab} must fit without desktop page scroll (${JSON.stringify(desktopPageSize)})`);
  }
  await page.goto(base);
  for (const [language, htmlLanguage] of [["en", "en"], ["zh", "zh-CN"], ["es", "es"]]) {
    await page.locator(".mk-header-actions select").selectOption(language);
    await page.waitForFunction(value => document.documentElement.lang === value, htmlLanguage);
    await page.reload({ waitUntil: "networkidle" });
    assert.equal(await page.locator("html").getAttribute("lang"), htmlLanguage);
  }
  await page.setViewportSize({ width: 1920, height: 953 });
  for (const tab of tabs) {
    await page.goto(`${base}#/producto/${tab}`, { waitUntil: "networkidle" });
    const desktopPageSize = await page.evaluate(() => {
      const page = document.querySelector(".mk-page");
      return { scrollHeight: page.scrollHeight, clientHeight: page.clientHeight };
    });
    assert.ok(desktopPageSize.scrollHeight <= desktopPageSize.clientHeight + 1, `${tab} must fit at 1920x953 (${JSON.stringify(desktopPageSize)})`);
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${base}#/producto/apps`, { waitUntil: "networkidle" });
  const tourTriggers = page.locator(".mk-app-tour-action");
  assert.equal(await tourTriggers.count(), 4);
  const tourImages = new Set();
  for (const [index, app] of ["venta", "gestion", "pda", "saas"].entries()) {
    const trigger = tourTriggers.nth(index);
    await trigger.click();
    const tour = page.getByTestId("product-tour");
    await tour.waitFor({ state: "visible" });
    assert.equal(await tour.getAttribute("data-active-app"), app);
    const image = tour.locator(".mk-tour-preview img");
    const source = await image.getAttribute("src");
    assert.equal(source, `/marketing/tour-${app}.png`, `${app} must show its own interface`);
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
      await page.locator(".mk-header-actions select").selectOption(locale.language, { force: true });
      await page.waitForFunction(value => document.documentElement.lang === value, locale.htmlLanguage);
      const contentByField = { actions: new Set(), example: new Set(), benefit: new Set() };
      for (const [index, app] of productOrder.entries()) {
        await expandedTour.locator('.mk-tour-tabs [role="tab"]').nth(index).click();
        assert.match(await expandedTour.locator(".mk-tour-step-count").innerText(), /1 \/ 3/, "Selecting an application must reset its walkthrough to the first step");
        await expandedTour.locator(".mk-tour-preview img").evaluate(image => image.decode());
        assert.equal(await expandedTour.locator(".mk-tour-preview img").getAttribute("src"), `/marketing/tour-${app}.png`);
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
  await page.locator(".mk-header-actions select").selectOption("es");
  await tourTriggers.first().click();
  const tour = page.getByTestId("product-tour");
  await tour.locator(".mk-tour-stage").focus();
  await page.keyboard.press("ArrowRight");
  assert.match(await tour.locator(".mk-tour-step-count").innerText(), /2 \/ 3/);
  await tour.locator('.mk-tour-tabs [role="tab"]').nth(2).click();
  assert.equal(await tour.getAttribute("data-active-app"), "pda");
  assert.equal(await tour.locator(".mk-tour-preview img").getAttribute("src"), "/marketing/tour-pda.png", "Switching tabs must also update the preview");
  await tour.locator(".mk-tour-preview img").evaluate(image => image.decode());
  assert.match(await tour.locator(".mk-tour-step-count").innerText(), /1 \/ 3/);
  for (const [language, note] of [["en", "Real interface"], ["zh", "真实界面"], ["es", "Interfaz real"]]) {
    await page.locator(".mk-header-actions select").selectOption(language, { force: true });
    assert.ok((await tour.locator(".mk-tour-preview figcaption").innerText()).includes(note), "Preview label must follow the website language");
    assert.equal(await tour.locator(".mk-tour-preview img").getAttribute("src"), "/marketing/tour-pda.png", "Changing language must preserve the selected application preview");
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
  assert.ok(tallHome.scrollHeight <= tallHome.clientHeight + 1, `Tall home must fit without scroll (${JSON.stringify(tallHome)})`);
  assert.ok(Math.abs(tallHome.footerBottom - tallHome.viewport) <= 1, `Footer must reach the bottom of a tall viewport (${JSON.stringify(tallHome)})`);
  await page.setViewportSize({ width: 1440, height: 1000 });

  const downloadTrigger = page.locator(".mk-client-button");
  await downloadTrigger.click();
  const downloadCenter = page.getByTestId("download-center");
  await downloadCenter.waitFor({ state: "visible" });
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
  assert.ok(await downloadCenter.evaluate(element => element.scrollHeight <= element.clientHeight + 1), "Download center must fit without desktop internal scroll");
  await page.locator(".mk-header-actions select").selectOption("en", { force: true });
  await page.waitForFunction(() => document.documentElement.lang === "en");
  assert.match(await downloadCenter.locator("h2").innerText(), /Install only/);
  await page.locator(".mk-header-actions select").selectOption("zh", { force: true });
  await page.waitForFunction(() => document.documentElement.lang === "zh-CN");
  assert.match(await downloadCenter.locator("h2").innerText(), /为每个团队/);
  await page.locator(".mk-header-actions select").selectOption("es", { force: true });
  await downloadCenter.locator('[data-testid="download-card-pda"] .mk-download-request').click();
  await page.waitForFunction(() => location.hash === "#/producto/contacto");
  await productCheckbox("pda").waitFor({ state: "visible" });
  assert.deepEqual(await selectedProducts(), ["pda"], "Assisted delivery must carry the selected product to Contact");
  assert.equal(await fullPackage.isChecked(), false, "A single-product request must not select the complete package");
  const selectionLabels = new Set();
  for (const [language, expectedLegend, expectedPackageLabel] of [
    ["en", "Applications of interest", "Complete package"],
    ["zh", "感兴趣的应用", "完整套装"],
    ["es", "Aplicaciones de interés", "Paquete completo"]
  ]) {
    await page.locator(".mk-header-actions select").selectOption(language);
    const legend = (await page.locator(".mk-contact-selection legend").innerText()).trim();
    const packageLabel = await fullPackage.evaluate(input => input.labels?.[0]?.textContent?.trim());
    assert.ok(legend && packageLabel, "Product selection and package controls must have accessible translated labels");
    assert.equal(legend, expectedLegend);
    assert.ok(packageLabel.includes(expectedPackageLabel), "The complete-package label must follow the chosen language");
    selectionLabels.add(`${legend}|${packageLabel}`);
    assert.deepEqual(await selectedProducts(), ["pda"], "Changing language must preserve the chosen products");
  }
  assert.equal(selectionLabels.size, 3, "Product selection labels must be translated into all three website languages");

  await page.setViewportSize({ width: 1280, height: 720 });
  await page.locator('input[name="name"]').fill("Demo");
  await page.locator('input[name="company"]').fill("Demo Company");
  await page.locator('input[name="email"]').fill("demo@example.com");
  await page.locator('input[name="privacyAccepted"]').check();
  for (const language of ["en", "zh", "es"]) {
    await page.locator(".mk-header-actions select").selectOption(language);
    await fullPackage.check();
    assert.ok(await page.locator(".mk-page").evaluate(element => element.scrollHeight <= element.clientHeight + 1), `${language} complete-package form must fit a short desktop viewport`);
    await fullPackage.uncheck();
    const requestsBeforeValidation = apiRequests.length;
    await page.locator(".mk-contact-submit").click();
    await page.locator('.mk-contact-selection-error[role="alert"]').waitFor({ state: "visible" });
    assert.equal(apiRequests.length, requestsBeforeValidation, "Empty selections must remain local validation failures");
    assert.ok(await productCheckbox("venta").evaluate(element => document.activeElement === element), "Empty-selection validation must focus the first product checkbox");
    assert.ok(await page.locator(".mk-page").evaluate(element => element.scrollHeight <= element.clientHeight + 1), `${language} selection validation must fit a short desktop viewport`);
  }
  await productCheckbox("pda").check();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${base}#/producto/inicio`, { waitUntil: "networkidle" });
  await page.locator(".mk-menu-button").click();
  await page.locator('#mk-navigation a:not(.mk-mobile-client-link)[href="#/producto/contacto"]').click();
  await page.locator(".mk-contact-form").waitFor({ state: "visible" });
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
  assert.deepEqual(await selectedProducts(), ["pda"], "Successful requests must reset product selection to the initial application");

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
  assert.deepEqual(await selectedProducts(), ["pda"], "A multi-product success must restore the initial choice");

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
  assert.deepEqual(await selectedProducts(), ["pda"], "A complete-package success must restore the initial choice");
  assert.equal(await fullPackage.isChecked(), false);

  demoApiStatus = 503;
  await page.locator('input[name="name"]').fill("Luis Pérez");
  await page.locator('input[name="company"]').fill("Tienda Norte");
  await page.locator('input[name="email"]').fill("luis@example.com");
  await page.locator('input[name="privacyAccepted"]').check();
  await productCheckbox("pda").uncheck();
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
    assert.equal(await image.getAttribute("src"), `/marketing/tour-${app}.png`);
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
      await page.locator(".mk-header-actions select").selectOption(locale.language, { force: true });
      await page.waitForFunction(value => document.documentElement.lang === value, locale.htmlLanguage);
      for (const [index, app] of productOrder.entries()) {
        await responsiveTour.locator('.mk-tour-tabs [role="tab"]').nth(index).click();
        await responsiveTour.locator(".mk-tour-preview img").evaluate(image => image.decode());
        assert.equal(await responsiveTour.locator(".mk-tour-preview img").getAttribute("src"), `/marketing/tour-${app}.png`);
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
  assert.equal(apiRequests.length, 4, "Single-product, multi-product, complete-package and failed requests must reach the public API");
  assert.equal(apiPayloads[0].product, "APP_PDA", "The assisted-delivery selection must reach the demo API");
  assert.deepEqual(apiPayloads[0].products, ["APP_PDA"]);
  assert.equal(apiPayloads[0].privacyAccepted, true);
  assert.equal(apiPayloads[0].utmSource, "e2e");
  assert.equal(apiPayloads[0].utmMedium, "automation");
  assert.equal(apiPayloads[0].utmCampaign, "marketing-upgrade");
  assert.match(apiPayloads[0].landingPath, /producto\/inicio/);
  assert.deepEqual(errors, [], "Public website must not raise browser errors");
  console.log("Product E2E passed: six no-scroll routes, tall-screen sector content, three languages, SEO, attribution, responsive navigation, honest downloads, 36 translated product-tour steps without clipping at two desktop, two mobile and one short scrollable desktop size, keyboard and progress controls, multi-product and complete-package requests, demo form states and FAQ.");
} finally {
  await browser?.close();
  server.kill();
}
