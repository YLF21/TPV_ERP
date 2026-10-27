import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const tags = (markup, name) => [...markup.matchAll(new RegExp(`<${name}\\b([^>]*)>`, "g"))].map(match => Object.fromEntries(
  [...match[1].matchAll(/([\w:-]+)(?:="([^"]*)")?/g)].map(attribute => [attribute[1], attribute[2] ?? ""])
));

// Controlled SSR substitutes only the portal and DOM lifecycle boundary. It
// checks semantic/localized output, not browser geometry, scrolling or focus.
// The root agent verifies those interactions separately through Cua.
test("compact tour navigation contracts without a browser or listening server", async suite => {
  const server = await createServer({
    configFile: false, root,
    plugins: [{
      name: "tour-ssr-modal-boundary", enforce: "pre",
      transform(code, id) {
        if (!id.replaceAll("\\", "/").endsWith("/src/MarketingModal.tsx")) return;
        return "export function MarketingModalLayer({children}) { return children; } export function useModalDialog() { return {dialogRef:{current:null},closeRef:{current:null}}; }";
      }
    }, react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  try {
    assert.equal(server.httpServer, null);
    const [{ default: MarketingDialogs }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/MarketingDialogs.tsx"), server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    for (const language of ["es", "en", "zh"]) {
      for (const [appIndex, initialApp] of ["venta", "gestion", "pda", "saas"].entries()) {
        await suite.test(`${language}/${initialApp}: compact steps retain full accessible names and a focusable heading`, () => {
          const copy = getMarketingContent(language);
          const markup = renderToStaticMarkup(createElement(MarketingDialogs, {
            kind: "tour", language, copy, initialApp, onClose() {}, onRequestDemo() {}
          }));
          const progress = markup.slice(markup.indexOf('class="mk-tour-progress"'), markup.indexOf('class="mk-tour-actions"'));
          const controls = tags(progress, "button");
          assert.equal(controls.length, 3);
          for (const [index, bullet] of copy.apps.items[appIndex][3].entries()) {
            assert.equal(controls[index]["aria-label"], escapeHtml(`${copy.tour.step} ${index + 1} / 3: ${bullet}`));
            assert.equal(controls[index].title, escapeHtml(bullet));
            assert.equal(controls[index]["aria-current"], index === 0 ? "step" : undefined);
            assert.ok(progress.includes(`<span>0${index + 1}</span><span class="mk-tour-progress-label">${escapeHtml(bullet)}</span>`));
          }
          const heading = tags(markup, "h3").find(element => element["aria-describedby"] === "mk-tour-step-count");
          assert.ok(heading);
          assert.equal(heading.tabIndex ?? heading.tabindex, "-1");
          assert.equal(tags(markup, "p").filter(element => element.id === "mk-tour-step-count").length, 1);
          assert.ok(markup.includes(`${escapeHtml(copy.tour.step)} 1 / 3`));
          const appTabs = tags(markup, "button").filter(element => element.role === "tab");
          assert.equal(appTabs.length, 4);
          assert.equal(appTabs.filter(element => element["aria-selected"] === "true").length, 1);
          for (const [index, tab] of appTabs.entries()) {
            assert.equal(tab["aria-controls"], "mk-tour-panel");
            assert.equal(tab.tabIndex ?? tab.tabindex, index === appIndex ? "0" : "-1");
          }
          assert.ok(tags(markup, "button").some(button => Object.hasOwn(button, "disabled")));
          assert.ok(markup.includes(escapeHtml(copy.tour.next)));
          assert.equal(tags(markup, "img").length, 1, "Persistent navigation does not preload hidden steps or add duplicate media");
        });
      }
    }
    await suite.test("the content viewport resets only for an actual app or step transition", async () => {
      const source = await readFile(new URL("../src/MarketingDialogs.tsx", import.meta.url), "utf8");
      assert.match(source, /key=\{`\$\{activeApp\}-\$\{step\}`\} id="mk-tour-panel"/);
      assert.match(source, /if \(target === step\) return;/, "Clamped or repeated navigation must not unexpectedly move focus");
      assert.match(source, /if \(focusStep\.current\) \{\s*stepTitleRef\.current\?\.focus\(\{ preventScroll: true \}\);/);
      assert.match(source, /\}, \[activeApp, step\]\);/, "A language or unrelated parent render must not request focus again");
      assert.match(source, /function selectApp\(app: AppId\) \{\s*focusStep\.current = false;/, "App-tab navigation keeps the roving tab focus");
      assert.match(source, /appTabRefs\.current\[nextIndex\]\?\.focus\(\{ preventScroll: true \}\)/);
      assert.doesNotMatch(source, /key=\{[^\n]*language[^\n]*\} id="mk-tour-panel"/);
    });
    await suite.test("only the stage scrolls while header and compact footer retain their own rows", async () => {
      const css = await readFile(new URL("../src/marketing.css", import.meta.url), "utf8");
      assert.match(css, /\.mk-tour-dialog \{ display: grid; grid-template-rows: auto auto minmax\(0, 1fr\) auto;/);
      assert.match(css, /\.mk-tour-stage \{[^}]*min-height: 0;[^}]*overflow-y: auto;/);
      assert.match(css, /\.mk-tour-stage \{[^}]*grid-auto-rows: max-content;/, "Stacked cards retain their natural height instead of overlapping inside a short content viewport");
      assert.match(css, /\.mk-tour-dialog \{ overflow: hidden; \}/);
      assert.doesNotMatch(css, /\.mk-tour-dialog \{ display: block;/);
      assert.match(css, /\.mk-tour-progress \.mk-tour-progress-label \{ display: none; \}/);
      assert.doesNotMatch(css, /\.mk-tour-progress \{ grid-template-columns: minmax\(0, 1fr\); \}/, "Mobile progress stays one compact row");
    });
  } finally {
    await server.close();
  }
});
