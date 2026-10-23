import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { Check, Question } from "@phosphor-icons/react";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const expectedCapabilities = [
  ["store-connectivity", "documented"],
  ["central-store-view", "documented"],
  ["connected-applications", "documented"],
  ["warehouse-devices", "documented"],
  ["operational-data", "documented"],
  ["backups", "to-confirm"],
  ["adding-stores", "to-confirm"],
  ["users-permissions", "documented"],
  ["fiscal-setup", "to-confirm"]
];
// Explicit labels guard the positional bridge to existing translated tuples.
// Reordering/inserting copy requires reviewing its capability ID and status.
const expectedLabels = {
  es: ["Conectividad en tienda", "Visión central multi-tienda", "Venta, gestión y almacén", "Dispositivos de almacén", "Control del dato operativo", "Copias de seguridad", "Crecimiento a nuevas tiendas", "Usuarios y permisos", "Fiscalidad y trazabilidad"],
  en: ["In-store connectivity", "Central multi-store view", "Sales, management and warehouse", "Warehouse devices", "Operational data control", "Backups", "Adding new stores", "Users and permissions", "Tax and traceability"],
  zh: ["门店连接要求", "多门店中央视图", "销售、管理与仓库", "仓库设备", "运营数据控制", "数据备份", "新增门店", "用户与权限", "税务与追溯"]
};
const expectedStatusLabels = {
  es: { documented: "Función documentada", "to-confirm": "Por confirmar" },
  en: { documented: "Documented feature", "to-confirm": "To confirm" },
  zh: { documented: "已记录功能", "to-confirm": "需确认" }
};
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const attributes = source => Object.fromEntries([...source.matchAll(/([\w:-]+)(?:="([^"]*)")?/g)].map(match => [match[1], match[2] ?? ""]));

test("comparison capabilities and confirmation status (controlled SSR, no browser or HTTP)", async suite => {
  const server = await createServer({
    configFile: false,
    root,
    plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom",
    optimizeDeps: { noDiscovery: true, include: [] }
  });
  try {
    assert.equal(server.httpServer, null, "The comparison contract must not open a listening server");
    const [{ AdvantagesComparison, comparisonCapabilities, getComparisonPresentation }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/AdvantagesComparison.tsx"),
      server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    const icons = Object.fromEntries([["documented", Check], ["to-confirm", Question]].map(([status, Icon]) => [status,
      renderToStaticMarkup(createElement(Icon, { weight: "bold", "aria-hidden": "true" }))
    ]));

    await suite.test("stable capability IDs explicitly classify every row without text heuristics", () => {
      assert.deepEqual(comparisonCapabilities.map(({ id, status }) => [id, status]), expectedCapabilities);
      assert.equal(new Set(comparisonCapabilities.map(({ id }) => id)).size, expectedCapabilities.length);
    });

    for (const language of ["es", "en", "zh"]) {
      await suite.test(`${language}: row-to-status mapping, visible labels and accessible table semantics`, () => {
        const copy = getMarketingContent(language).advantagesPage;
        const labels = getComparisonPresentation(language);
        assert.deepEqual(copy.rows.map(row => row[0]), expectedLabels[language], "Translated row order must match reviewed capability IDs");
        assert.equal(labels.documented, expectedStatusLabels[language].documented);
        assert.equal(labels["to-confirm"], expectedStatusLabels[language]["to-confirm"]);
        const markup = renderToStaticMarkup(createElement(AdvantagesComparison, { copy, language }));
        const table = attributes(markup.match(/<div\b([^>]*role="table"[^>]*)>/)?.[1] ?? "");
        assert.equal(table["aria-label"], escapeHtml(copy.kicker));
        assert.equal(table["aria-describedby"], "mk-comparison-scope");
        assert.ok(markup.includes(`<span id="mk-comparison-scope">${escapeHtml(labels.note)}</span>`));
        assert.ok(markup.includes(escapeHtml(copy.note)), "Architecture and scope caveats remain visible");
        assert.equal((markup.match(/role="columnheader"/g) ?? []).length, 4);
        const rows = [...markup.matchAll(/<div\b([^>]*data-capability="[^"]+"[^>]*)>([\s\S]*?)<\/div>/g)];
        assert.equal(rows.length, expectedCapabilities.length);

        for (const [index, row] of rows.entries()) {
          const [id, status] = expectedCapabilities[index];
          const props = attributes(row[1]);
          assert.equal(props.role, "row");
          assert.equal(props["data-capability"], id);
          assert.equal(Object.hasOwn(props, "hidden"), false);
          const body = row[2];
          assert.ok(body.includes(`<strong role="rowheader">${escapeHtml(copy.rows[index][0])}</strong>`));
          assert.equal((body.match(/role="cell"/g) ?? []).length, 3);
          const ours = body.match(/<span\b([^>]*class="is-ours"[^>]*)>([\s\S]*)/) ?? [];
          const cell = attributes(ours[1] ?? "");
          assert.equal(cell["data-status"], status);
          assert.equal(cell["data-label"], "esPOS", "Mobile cards retain the column label");
          assert.ok(ours[2].includes(`<small>${escapeHtml(labels[status])}: </small>`), "Status must be visible text, not colour or an icon alone");
          assert.ok(ours[2].includes(escapeHtml(copy.rows[index][3])));
          assert.ok(ours[2].includes(icons[status]), `${id}: use the actual ${status} SVG with aria-hidden`);
          if (status === "to-confirm") assert.equal(ours[2].includes(icons.documented), false, "Conditional scope must never carry an unconditional checkmark");
        }
      });

      await suite.test(`${language}: documented means capability, not a bundled commercial offer`, () => {
        const note = getComparisonPresentation(language).note;
        assert.match(note, { es: /no implica.*incluido/i, en: /does not mean.*included/i, zh: /不代表已包含/ }[language]);
        assert.match(getMarketingContent(language).advantagesPage.note, {
          es: /alcance.*módulos.*confirman.*implantación/i,
          en: /scope.*modules.*confirmed.*implementation/i,
          zh: /范围与模块.*实施时确认/
        }[language], "Keep rollout scope in the existing caveat rather than repeating it in the new clarification");
        assert.doesNotMatch(note, /\bTODO\b|24\/7|100%|(?:\d+\s*€)|免费|guaranteed|garantizado/i);
      });
    }

    await suite.test("unclassified inserted rows fail explicitly instead of displaying a checkmark", () => {
      const copy = getMarketingContent("es").advantagesPage;
      assert.throws(() => renderToStaticMarkup(createElement(AdvantagesComparison, {
        copy: { ...copy, rows: [...copy.rows, ["Nueva capacidad", "", "", "Sin revisar"]] }, language: "es"
      })), /explicit capability status/);
    });
  } finally {
    await server.close();
  }
});
