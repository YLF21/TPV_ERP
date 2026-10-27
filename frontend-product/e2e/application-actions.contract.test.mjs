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
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// Controlled SSR/source contracts and plain helper doubles only. No browser, DOM
// emulation, listening HTTP server, actual geometry or submitted form is involved.
test("application actions have distinct demo and preparation destinations", async suite => {
  const server = await createServer({
    configFile: false,
    root,
    plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom",
    optimizeDeps: { noDiscovery: true, include: [] }
  });

  try {
    assert.equal(server.httpServer, null);
    const [{ ApplicationExplorer }, { ImplementationJourney, implementationJourneyId, openImplementationJourney }, { getMarketingContent }, { getApplicationGuides }, { getApplicationPreviewAlts }] = await Promise.all([
      server.ssrLoadModule("/src/ApplicationExplorer.tsx"),
      server.ssrLoadModule("/src/ImplementationJourney.tsx"),
      server.ssrLoadModule("/src/marketingI18n.ts"),
      server.ssrLoadModule("/src/applicationGuides.ts"),
      server.ssrLoadModule("/src/applicationPreviewContent.ts")
    ]);
    let actionCount = 0;
    const onAction = () => { actionCount += 1; };

    for (const language of languages) {
      await suite.test(`${language}: every app keeps its own demo URL and one stable information-only destination`, () => {
        const marketing = getMarketingContent(language);
        for (const [index, id] of applicationIds.entries()) {
          const application = {
            id,
            name: marketing.apps.items[index][0],
            label: marketing.apps.items[index][1],
            description: marketing.apps.items[index][2],
            previewAlt: getApplicationPreviewAlts(language)[id]
          };
          const markup = renderToStaticMarkup(createElement(Fragment, null,
            createElement(ApplicationExplorer, { language, applications: [application], onOpenTour: onAction, onRequestDemo: onAction }),
            createElement(ImplementationJourney, { language })
          ));
          const anchors = [...markup.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)];
          const contact = anchors.filter(anchor => anchor[1].includes(`href="/${language}/producto/contacto/?apps=${id}#mk-demo-form"`));
          assert.equal(contact.length, 1, "Do not duplicate differently named actions to the contact form");
          assert.ok(contact[0][2].includes(escapeHtml(getApplicationGuides(language).demoAction)));
          const preparation = anchors.filter(anchor => anchor[1].includes('data-testid="guide-preparation-action"'));
          assert.equal(preparation.length, 1);
          assert.ok(preparation[0][1].includes(`href="/${language}/producto/apps/#${implementationJourneyId}"`), "Use a real canonical fallback, including from legacy hash routes/new tabs");
          assert.ok(preparation[0][1].includes(`aria-controls="${implementationJourneyId}"`));
          assert.ok(preparation[0][2].includes(escapeHtml(getApplicationGuides(language).consultAction)));
          const target = markup.match(new RegExp(`<details\\b([^>]*\\bid="${implementationJourneyId}"[^>]*)>`));
          assert.ok(target, "The fallback must point at the existing native disclosure");
          assert.doesNotMatch(target[1], /\bopen(?:=|\s|$)/, "Keep the compact guidance closed until requested");
          assert.equal([...markup.matchAll(new RegExp(`\\bid="${implementationJourneyId}"`, "g"))].length, 1);
        }
        assert.equal(actionCount, 0, "Rendering must not trigger demo or tour actions");
      });
    }

    await suite.test("revealing preparation opens existing details and focuses its native summary below the sticky header", () => {
      const calls = [];
      const summary = { focus: options => calls.push(["focus", options]) };
      const page = {
        scrollTop: 300,
        querySelector: selector => {
          assert.equal(selector, ".mk-header");
          return { getBoundingClientRect: () => ({ height: 68 }) };
        },
        getBoundingClientRect: () => ({ top: 20 }),
        scrollTo: options => calls.push(["scroll", options])
      };
      const journey = {
        tagName: "DETAILS", open: false,
        querySelector: selector => { assert.equal(selector, "summary"); return summary; },
        closest: selector => { assert.equal(selector, ".mk-page"); return page; },
        getBoundingClientRect: () => ({ top: 180 })
      };
      assert.equal(openImplementationJourney({ getElementById: id => { assert.equal(id, implementationJourneyId); return journey; } }), true);
      assert.equal(journey.open, true);
      assert.deepEqual(calls, [["focus", { preventScroll: true }], ["scroll", { top: 380, behavior: "instant" }]]);
      assert.equal(openImplementationJourney({ getElementById: () => journey }), true, "The action should remain safe when the details are already open");
    });

    await suite.test("preparation scrolling clamps the page offset and works without an enclosing marketing page", () => {
      let scroll;
      const journey = {
        tagName: "DETAILS", open: false,
        querySelector: () => null,
        closest: () => ({ scrollTop: 0, querySelector: () => null, getBoundingClientRect: () => ({ top: 0 }), scrollTo: options => { scroll = options; } }),
        getBoundingClientRect: () => ({ top: 0 }),
        scrollIntoView: options => { scroll = options; }
      };
      assert.equal(openImplementationJourney({ getElementById: () => journey }), true);
      assert.deepEqual(scroll, { top: 0, behavior: "instant" });
      journey.closest = () => null;
      assert.equal(openImplementationJourney({ getElementById: () => journey }), true);
      assert.deepEqual(scroll, { block: "start", behavior: "instant" });
    });

    await suite.test("a missing or unexpected target preserves native link fallback", () => {
      assert.equal(openImplementationJourney({ getElementById: () => null }), false);
      assert.equal(openImplementationJourney({ getElementById: () => ({ tagName: "DIV" }) }), false);
    });

    await suite.test("source preserves modifiers and normal demo selection, while the information link never submits", async () => {
      const [explorer, journey] = await Promise.all([
        readFile(new URL("../src/ApplicationExplorer.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/ImplementationJourney.tsx", import.meta.url), "utf8")
      ]);
      const actions = explorer.slice(explorer.indexOf('<div className="mk-app-explorer-actions">'), explorer.indexOf('<p className="mk-app-explorer-scope">'));
      const guard = /event\.button !== 0 \|\| event\.altKey \|\| event\.ctrlKey \|\| event\.metaKey \|\| event\.shiftKey/;
      assert.match(actions, /href=\{demoHref\}[\s\S]*event\.preventDefault\(\);\s*onRequestDemo\(application\.id\)/);
      assert.equal([...actions.matchAll(new RegExp(guard.source, "g"))].length, 2, "Both native links must preserve modified/new-tab activation");
      const preparation = actions.slice(actions.indexOf('data-testid="guide-preparation-action"'));
      assert.match(preparation, /if \(openImplementationJourney\(document\)\) event\.preventDefault\(\)/);
      assert.doesNotMatch(preparation, /onRequestDemo|onOpenTour|fetch\(|submit|navigateMarketing/);
      assert.match(journey, /window\.location\.hash !== `#\$\{implementationJourneyId\}`/);
      assert.match(journey, /window\.requestAnimationFrame\(/, "Defer direct fragment reveal until after the enclosing page's initial scroll reset");
      assert.match(journey, /window\.cancelAnimationFrame\(frame\)/);
      assert.doesNotMatch(journey, /fetch\(|submitDemo|history\.|location\.(?:hash|href)\s*=/);
    });
  } finally {
    await server.close();
  }
});
