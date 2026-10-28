import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const applicationIds = ["venta", "gestion", "pda", "saas"];
const sectionIds = ["overview", "workflow", "setup", "questions"];
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// Read attributes from controlled React server output; no DOM, browser, HTTP or hydration.
function tags(markup, name) {
  return [...markup.matchAll(new RegExp(`<${name}\\b([^>]*)>`, "g"))].map(match => Object.fromEntries(
    [...match[1].matchAll(/([\w:-]+)="([^"]*)"/g)].map(attribute => [attribute[1], attribute[2]])
  ));
}

function requireText(markup, text) {
  assert.ok(markup.includes(escapeHtml(text)), `Missing localized text: ${text}`);
}

test("application explorer server-render contract (no browser or HTTP)", async suite => {
  const server = await createServer({
    configFile: false,
    root,
    plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom",
    optimizeDeps: { noDiscovery: true, include: [] }
  });

  try {
    assert.equal(server.httpServer, null, "SSR tests must never create a listening HTTP server");
    const [{ ApplicationExplorer }, { getMarketingContent }, { getApplicationGuides }, { getApplicationPreviewAlts }] = await Promise.all([
      server.ssrLoadModule("/src/ApplicationExplorer.tsx"),
      server.ssrLoadModule("/src/marketingI18n.ts"),
      server.ssrLoadModule("/src/applicationGuides.ts"),
      server.ssrLoadModule("/src/applicationPreviewContent.ts")
    ]);
    let callbackCount = 0;
    const onAction = () => { callbackCount += 1; };
    const applicationProps = language => {
      const marketing = getMarketingContent(language);
      return applicationIds.map((id, index) => ({
        id,
        name: marketing.apps.items[index][0],
        label: marketing.apps.items[index][1],
        description: marketing.apps.items[index][2],
        previewAlt: getApplicationPreviewAlts(language)[id]
      }));
    };
    const render = (language, applications = applicationProps(language)) => renderToStaticMarkup(createElement(ApplicationExplorer, {
      language,
      applications,
      onOpenTour: onAction,
      onRequestDemo: onAction
    }));

    for (const language of ["es", "en", "zh"]) {
      await suite.test(`${language}: tabs, ARIA relationships, localized content and default preview`, () => {
        const markup = render(language);
        const copy = getApplicationGuides(language);
        const applications = applicationProps(language);
        const buttons = tags(markup, "button");
        const tabButtons = buttons.filter(button => button.role === "tab");
        assert.equal(tabButtons.length, 8, "Four application tabs and four information tabs are required");
        assert.equal(tags(markup, "div").filter(tag => tag.role === "tablist").length, 2);

        const panels = tags(markup, "div").filter(tag => tag.role === "tabpanel");
        assert.equal(panels.length, 2);
        const applicationPanel = panels.find(panel => panel["data-testid"] === "application-guide-panel");
        const detailPanel = panels.find(panel => panel["data-testid"] === "guide-content-panel");
        assert.ok(applicationPanel);
        assert.ok(detailPanel);
        assert.equal(detailPanel["data-section"], "overview");

        for (const id of applicationIds) {
          const tab = tabButtons.find(button => button["data-testid"] === `guide-app-${id}`);
          assert.ok(tab, `Missing application tab ${id}`);
          assert.equal(tab["aria-controls"], applicationPanel.id);
          assert.equal(tab["aria-selected"], id === "venta" ? "true" : "false");
          assert.equal(tab.tabindex, id === "venta" ? "0" : "-1");
          if (id === "venta") assert.equal(applicationPanel["aria-labelledby"], tab.id);
        }
        for (const id of sectionIds) {
          const tab = tabButtons.find(button => button["data-testid"] === `guide-section-${id}`);
          assert.ok(tab, `Missing information tab ${id}`);
          assert.equal(tab["aria-controls"], detailPanel.id);
          assert.equal(tab["aria-selected"], id === "overview" ? "true" : "false");
          assert.equal(tab.tabindex, id === "overview" ? "0" : "-1");
          if (id === "overview") assert.equal(detailPanel["aria-labelledby"], tab.id);
          requireText(markup, copy.sections[id]);
        }

        const ids = [...markup.matchAll(/\sid="([^"]*)"/g)].map(match => match[1]);
        assert.equal(new Set(ids).size, ids.length, "Every rendered ID must be unique");
        const image = tags(markup, "img");
        assert.equal(image.length, 1);
        assert.equal(image[0].src, "/marketing/tour-venta.png");
        assert.equal(image[0].alt, escapeHtml(applications[0].previewAlt));
        requireText(markup, copy.previewNote);
        requireText(markup, copy.apps.venta.audience);
        requireText(markup, copy.apps.venta.outcome);
        requireText(markup, copy.apps.venta.worksWith);
        requireText(markup, copy.tasksLabel);
        const taskList = markup.match(/<ul class="mk-app-explorer-tasks">([\s\S]*?)<\/ul>/)?.[1];
        assert.ok(taskList);
        assert.equal(tags(taskList, "li").length, 3);
        for (const task of copy.apps.venta.tasks) {
          requireText(taskList, task.title);
          requireText(taskList, task.body);
        }

        for (const application of applications) requireText(markup, application.name);
        for (const label of [copy.tourAction, copy.demoAction, copy.consultAction, copy.scopeNote]) requireText(markup, label);
        const anchors = tags(markup, "a");
        assert.equal(anchors.filter(anchor => anchor.href === `/${language}/producto/contacto/?apps=venta#mk-demo-form`).length, 1, "Only the demo action should request contact for the selected app");
        const preparation = anchors.find(anchor => anchor["data-testid"] === "guide-preparation-action");
        assert.equal(preparation?.href, `/${language}/producto/apps/#mk-implementation-journey`);
        assert.equal(preparation?.["aria-controls"], "mk-implementation-journey");
        const gettingStarted = markup.match(/<ol class="mk-guide-start-steps">([\s\S]*?)<\/ol>/)?.[1];
        assert.ok(gettingStarted);
        assert.equal(tags(gettingStarted, "li").length, 3);
        for (const step of copy.gettingStarted.steps) {
          requireText(gettingStarted, step.title);
          requireText(gettingStarted, step.body);
        }
        assert.doesNotMatch(markup, /TPV\s*ERP|esPos|TPVERP/);
        assert.equal(callbackCount, 0, "Server rendering must not trigger tour or demo actions");
      });
    }

    await suite.test("an empty application list renders no interactive or instructional markup", () => {
      assert.equal(render("es", []), "");
      assert.equal(callbackCount, 0);
    });

    await suite.test("a reduced list safely selects the available app and its corresponding image", () => {
      const application = applicationProps("es").find(item => item.id === "pda");
      const markup = render("es", [application]);
      const activeTab = tags(markup, "button").find(button => button["data-testid"] === "guide-app-pda");
      assert.equal(activeTab["aria-selected"], "true");
      assert.equal(activeTab.tabindex, "0");
      assert.equal(tags(markup, "img")[0].src, "/marketing/tour-pda.png");
      requireText(markup, getApplicationGuides("es").apps.pda.outcome);
      assert.equal(callbackCount, 0);
    });
  } finally {
    await server.close();
  }
});
