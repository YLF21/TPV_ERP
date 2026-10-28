import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const expected = { retail: ["venta", "gestion"], grocery: ["venta", "gestion", "pda"], fashion: ["venta", "gestion"], franchise: ["venta", "gestion", "pda", "saas"] };
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const shape = value => typeof value === "string" ? "string" : Object.fromEntries(Object.entries(value).map(([key, child]) => [key, shape(child)]));

// Controlled SSR and source contracts only: no browser, listening HTTP server,
// geometry, real form submission or external service is exercised.
test("compact sector cases reuse full source copy and preserve shareable application choices", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    assert.equal(server.httpServer, null);
    const [{ SectorCases }, { getSectorCaseContent, sectorApplications }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/SectorCases.tsx"), server.ssrLoadModule("/src/sectorCaseContent.ts"), server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    let actionCount = 0;
    assert.deepEqual(sectorApplications, expected);
    const reference = shape(getSectorCaseContent("es"));
    for (const language of ["es", "en", "zh"]) {
      await suite.test(`${language}: all four cases retain summaries, full explanations and genuine localized links`, () => {
        const copy = getMarketingContent(language);
        const labels = getSectorCaseContent(language);
        assert.deepEqual(shape(labels), reference);
        assert.ok(Object.values(labels).every(value => value.trim()));
        const markup = renderToStaticMarkup(createElement(SectorCases, { copy, language, onRequestApplications: () => { actionCount += 1; } }));
        const articles = [...markup.matchAll(/<article\b([^>]*)>([\s\S]*?)<\/article>/g)];
        assert.equal(articles.length, 4);
        assert.ok(markup.includes('class="mk-home-sector-preview"'));
        const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
        assert.equal(new Set(ids).size, ids.length);
        for (const [index, article] of articles.entries()) {
          const [id, label, headline, body, bullets] = copy.growth.sectors.items[index];
          const text = article[2];
          assert.ok(article[1].includes(`data-sector="${id}"`));
          assert.ok(text.includes(`<p>${escapeHtml(label)}</p>`));
          for (const value of [headline, body, ...bullets, ...Object.values(labels)]) assert.ok(text.includes(escapeHtml(value)), `Missing ${language}/${id}: ${value}`);
          const disclosure = text.match(/<details\b([^>]*)>/)?.[1];
          assert.ok(disclosure?.includes(`data-testid="sector-case-${id}"`));
          assert.doesNotMatch(disclosure, /\bopen(?:\s|=|$)/, "Details remain closed until requested");
          assert.ok(text.includes(`aria-label="${escapeHtml(`${labels.open}: ${label}`)}"`));
          const small = text.match(/<small>([\s\S]*?)<\/small>/)?.[1];
          assert.ok(small?.includes(escapeHtml(bullets[0])));
          assert.ok(text.indexOf("<small>") < text.indexOf("<details"), "The original short preview stays visible");
          assert.ok(text.includes(`<p>${escapeHtml(body)}</p>`), "Use the existing sector explanation, not a parallel copy");
          const applications = expected[id];
          const query = applications.length === 4 ? "all" : applications.join(",");
          assert.ok(text.includes(`href="/${language}/producto/contacto/?apps=${query}#mk-demo-form"`));
          for (const app of applications) assert.ok(text.includes(escapeHtml(copy.apps.items[["venta", "gestion", "pda", "saas"].indexOf(app)][0])));
          assert.doesNotMatch(text, /<form\b|\shidden(?:\s|=|>)|style=/);
        }
        assert.equal(actionCount, 0, "SSR cannot request a demo or change the selected applications");
      });
    }
    await suite.test("modifiers preserve native navigation and no new styles or network operations are added", async () => {
      const source = await readFile(new URL("../src/SectorCases.tsx", import.meta.url), "utf8");
      assert.match(source, /event\.defaultPrevented \|\| event\.button !== 0 \|\| event\.altKey \|\| event\.ctrlKey \|\| event\.metaKey \|\| event\.shiftKey/);
      assert.match(source, /event\.preventDefault\(\);\s*onRequestApplications\(applications\)/);
      assert.doesNotMatch(source, /fetch\(|localStorage|sessionStorage|\.css["']|dangerouslySetInnerHTML/);
    });
  } finally {
    await server.close();
  }
});
