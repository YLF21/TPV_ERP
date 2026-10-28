import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const ids = ["venta", "gestion", "pda", "saas"];
const escapeHtml = text => text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// Source/data and controlled SSR only. No browser, HTTP server, real stock
// mutation, projection timing, focus or visual geometry is exercised.
test("one connected fictional scenario stays compact and bounded in three languages", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    assert.equal(server.httpServer, null);
    const [{ ConnectedScenario }, { connectedScenarioApps, getConnectedScenario }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/ConnectedScenario.tsx"),
      server.ssrLoadModule("/src/connectedScenarioContent.ts"),
      server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    assert.deepEqual([...connectedScenarioApps], ids);
    for (const language of ["es", "en", "zh"]) {
      await suite.test(`${language}: four ordered apps, illustrative quantities and explicit prerequisites`, () => {
        const copy = getConnectedScenario(language);
        assert.deepEqual(Object.keys(copy), ["title", "intro", "steps"]);
        assert.deepEqual(Object.keys(copy.steps), ids);
        assert.match(copy.intro, /ficticio|fictional|虚构/i);
        assert.match(copy.intro, /10/);
        assert.match(copy.intro, /sin otros movimientos|no other movements|无其他库存变动/);
        assert.match(copy.steps.venta, /2/);
        assert.match(copy.steps.venta, /cobra el total|full payment|收取全款/);
        assert.match(copy.steps.venta, /servicio de tienda|store service|门店服务/);
        assert.match(copy.steps.gestion, /8/);
        assert.match(copy.steps.gestion, /permiso|permissions|权限/);
        assert.match(copy.steps.pda, /8/);
        assert.match(copy.steps.pda, /diferencia cero|zero difference|差异为零/);
        assert.match(copy.steps.pda, /autorizado|authorised|授权/);
        assert.match(copy.steps.saas, /8/);
        assert.match(copy.steps.saas, /recibe y procesa|received and processed|收到并处理/);
        assert.match(copy.steps.saas, /internet|互联网/i);
        assert.match(copy.steps.saas, /sin edición remota|without remote editing|不提供远程编辑/);
        assert.match(copy.steps.saas, /ni actualización inmediata garantizada|or guaranteed instant updates|不保证即时更新/);
        const markup = renderToStaticMarkup(createElement(ConnectedScenario, { language }));
        const details = markup.match(/^<details\b([^>]*)>/)?.[1];
        assert.ok(details);
        assert.match(details, /class="mk-service-disclosure"/);
        assert.doesNotMatch(details, /\bopen(?:=|\s|$)|\bhidden(?:=|\s|$)/);
        assert.equal((markup.match(/<summary>/g) ?? []).length, 1);
        assert.match(markup, /<ol class="mk-service-steps">/);
        assert.deepEqual([...markup.matchAll(/data-scenario-app="([^"]+)"/g)].map(match => match[1]), ids);
        assert.equal((markup.match(/<h3>/g) ?? []).length, 4);
        for (const [index, id] of ids.entries()) {
          assert.ok(markup.includes(escapeHtml(getMarketingContent(language).apps.items[index][0])));
          assert.ok(markup.includes(escapeHtml(copy.steps[id])));
          assert.ok(copy.steps[id].length <= 185);
          assert.doesNotMatch(copy.steps[id], /24\/7|100%|\bSLA\b|(?:\d+\s*€)|\bTODO\b|节省\d/);
        }
        assert.doesNotMatch(markup, /<form|<input|<button|<img|style=|role="dialog"/, "An explanatory case should not add forms, pretend screenshots or fixed geometry");
      });
    }
  } finally {
    await server.close();
  }
});
