import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const escapeHtml = value => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const expected = [["connection", ["venta", "gestion"]], ["central", ["saas"]], ["warehouse", ["gestion", "pda"]], ["scope", ["venta", "gestion", "pda", "saas"]]];
const shape = value => typeof value === "string" ? "string" : Object.fromEntries(Object.entries(value).map(([key, child]) => [key, shape(child)]));

// Controlled SSR and source contracts only. No browser, listening HTTP server,
// external request, actual navigation, calculator or commercial order is exercised.
test("comparison questions help visitors decide without inventing commercial conditions", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    assert.equal(server.httpServer, null);
    const [{ ComparisonDecisionHelp }, data, { getConnectionContent }, { getApplicationGuides }, { getApplicationChoices }, { getMarketingContent }, { getSupportContent }] = await Promise.all([
      server.ssrLoadModule("/src/ComparisonDecisionHelp.tsx"),
      server.ssrLoadModule("/src/comparisonDecisionContent.ts"),
      server.ssrLoadModule("/src/connectionContent.ts"),
      server.ssrLoadModule("/src/applicationGuides.ts"),
      server.ssrLoadModule("/src/applicationChoices.ts"),
      server.ssrLoadModule("/src/marketingI18n.ts"),
      server.ssrLoadModule("/src/supportContent.ts")
    ]);
    assert.deepEqual(data.comparisonDecisionQuestions.map(({ id, applications }) => [id, [...applications]]), expected);
    const baseline = shape(data.getComparisonDecisionContent("es"));
    for (const language of ["es", "en", "zh"]) {
      await suite.test(`${language}: four questions retain confirmed boundaries and native application choices`, () => {
        const copy = data.getComparisonDecisionContent(language);
        assert.deepEqual(shape(copy), baseline);
        assert.equal(copy.title, getMarketingContent(language).contact.faqLabel);
        assert.equal(copy.questions.connection.title, getMarketingContent(language).contact.faqItems[0][0]);
        assert.equal(copy.questions.central.title, getMarketingContent(language).contact.faqItems[1][0]);
        assert.deepEqual(Object.keys(copy.questions), expected.map(([id]) => id));
        const markup = renderToStaticMarkup(createElement(ComparisonDecisionHelp, { language }));
        const disclosure = markup.match(/^<details\b([^>]*)>/)?.[1];
        assert.ok(disclosure);
        assert.match(disclosure, /class="mk-service-disclosure"/);
        assert.doesNotMatch(disclosure, /\bopen(?:\s|=|$)|\bhidden(?:\s|=|$)/);
        assert.ok(markup.includes(escapeHtml(copy.title)));
        const articles = [...markup.matchAll(/<article\b([^>]*)>([\s\S]*?)<\/article>/g)];
        assert.equal(articles.length, 4);
        for (const [index, article] of articles.entries()) {
          const [id, applications] = expected[index];
          assert.ok(article[1].includes(`data-decision="${id}"`));
          const question = copy.questions[id];
          assert.ok(article[2].includes(`<h3>${escapeHtml(question.title)}</h3>`));
          assert.ok(article[2].includes(`<p>${escapeHtml(question.answer)}</p>`));
          const choice = applications.length === 4 ? "all" : applications.join(",");
          assert.ok(article[2].includes(`href="/${language}/producto/contacto/?apps=${choice}#mk-demo-form"`));
          assert.ok(article[2].includes(`aria-label="${escapeHtml(`${copy.action}: ${question.title}`)}"`));
          for (const application of applications) {
            assert.ok(article[2].includes(escapeHtml(getMarketingContent(language).apps.items[["venta", "gestion", "pda", "saas"].indexOf(application)][0])), "Show the suggested apps before the visitor follows their selection");
          }
        }
        assert.equal(copy.questions.central.answer, getConnectionContent(language).apps.saas, "Reuse the received-data limitation, not an independent freshness promise");
        assert.ok(copy.questions.warehouse.answer.includes(getApplicationGuides(language).apps.pda.requirements[0]));
        assert.ok(copy.questions.warehouse.answer.includes(getConnectionContent(language).apps.pda));
        assert.equal(copy.questions.connection.answer, getMarketingContent(language).contact.faqItems[0][1], "The internet-outage answer retains the configured-scope check as well as the store-service requirement");
        assert.match(copy.questions.scope.title, { es: /licencias, periféricos y copias/i, en: /licences, peripherals and backups/i, zh: /许可证、外设与备份/ }[language]);
        assert.ok(copy.questions.scope.answer.includes(getApplicationChoices(language).note), "The selection remains interest in a demo, not a contract");
        assert.ok(copy.questions.scope.answer.includes(getSupportContent(language).sections[2].items[0]), "Backup responsibility still needs an agreement");
        assert.doesNotMatch(markup, /<input|<form|<button|<img|style=|type="submit"|role="dialog"/);
        assert.doesNotMatch(markup, /24\/7|100%|\bSLA\b|(?:\d+\s*€)|AEAT-certified|免费|\bTODO\b/);
      });
    }
    await suite.test("information controls add no styles, tracking, scoring or network operations", async () => {
      const source = await readFile(new URL("../src/ComparisonDecisionHelp.tsx", import.meta.url), "utf8");
      assert.doesNotMatch(source, /fetch\(|localStorage|sessionStorage|\.css["']|dangerouslySetInnerHTML|onClick|score|calculator/i);
      assert.match(source, /marketingHref\(language, "contacto"\)/);
      assert.match(source, /marketingContactSearch\(applications\)/);
    });
  } finally { await server.close(); }
});
