import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { inflateRawSync } from "node:zlib";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const asset = new URL("../public/guides/espos-product-import-sample.xlsx", import.meta.url);
const escapeHtml = text => text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// Read only this generated OOXML asset, using the ZIP directory rather than
// interpreting a workbook or running Excel. No dependency enters the client.
function zipParts(buffer) {
  const end = buffer.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  assert.ok(end >= 0, "Valid ZIP directory");
  const files = new Map();
  let offset = buffer.readUInt32LE(end + 16);
  for (let index = 0; index < buffer.readUInt16LE(end + 10); index += 1) {
    assert.equal(buffer.readUInt32LE(offset), 0x02014b50);
    const method = buffer.readUInt16LE(offset + 10);
    const size = buffer.readUInt32LE(offset + 20);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const local = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString("utf8", offset + 46, offset + 46 + nameLength);
    const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
    const data = buffer.subarray(start, start + size);
    assert.ok(method === 0 || method === 8);
    files.set(name, (method === 8 ? inflateRawSync(data) : data).toString("utf8"));
    offset += 46 + nameLength + buffer.readUInt16LE(offset + 30) + buffer.readUInt16LE(offset + 32);
  }
  return files;
}

test("downloadable workbook respects the actual product importer boundary", async () => {
  const parts = zipParts(await readFile(asset));
  const sheets = [...parts.get("xl/workbook.xml").matchAll(/<x:sheet\b[^>]*name="([^"]+)"/g)].map(match => match[1]);
  assert.deepEqual(sheets, ["Datos", "Guia"], "First sheet must be data, not an instruction cover");
  const sheet = parts.get("xl/worksheets/sheet1.xml");
  const rows = [...sheet.matchAll(/<x:row\b[^>]*r="(\d+)"[^>]*>([\s\S]*?)<\/x:row>/g)];
  assert.deepEqual(rows.map(match => Number(match[1])), [1, 2, 3, 4]);
  const cells = rows.map(row => [...row[2].matchAll(/<x:c\b([^>]*)>([\s\S]*?)<\/x:c>/g)].map(cell => ({ attrs: cell[1], value: cell[2].match(/<x:v>([\s\S]*?)<\/x:v>/)?.[1] })));
  const headings = ["Código", "Nombre", "Precio de compra", "Precio de venta"];
  assert.deepEqual(cells[0].map(cell => cell.value), headings);
  const mapper = await readFile(new URL("../../frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx", import.meta.url), "utf8");
  for (const heading of headings) assert.ok(mapper.includes(`"${heading}"`), `Importer recognises ${heading}`);
  for (let row = 1; row <= 3; row += 1) {
    assert.equal(cells[row].length, 4);
    assert.equal(cells[row][0].value, `DEMO-IMPORT-00${row}`);
    assert.match(cells[row][0].attrs, /t="str"/);
    assert.match(cells[row][1].attrs, /t="str"/);
    for (const cell of cells[row].slice(2)) {
      assert.match(cell.attrs, /t="n"/);
      assert.ok(Number.isFinite(Number(cell.value)) && Number(cell.value) > 0);
    }
  }
  assert.doesNotMatch(sheet, /<x:f\b|hyperlink|externalLink/i);
  const instructions = parts.get("xl/worksheets/sheet2.xml");
  for (const text of ["ES · esPOS", "EN · esPOS", "中文 · esPOS", "no es una migración completa", "not a full migration", "并非完整迁移", "familia", "active tax", "有效税项"]) {
    assert.ok(instructions.includes(text), `Instructions retain ${text}`);
  }
  assert.ok([...parts.keys()].every(name => !/externalLinks|vbaProject|connections/i.test(name)));
});

test("compact import help is accessible, trilingual and explains preview versus applying", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    assert.equal(server.httpServer, null);
    const [{ ProductImportHelp }, { getProductImportHelp }] = await Promise.all([server.ssrLoadModule("/src/ProductImportHelp.tsx"), server.ssrLoadModule("/src/productImportHelpContent.ts")]);
    for (const language of ["es", "en", "zh"]) {
      await suite.test(language, () => {
        const copy = getProductImportHelp(language);
        const markup = renderToStaticMarkup(createElement(ProductImportHelp, { language }));
        assert.match(markup, /^<details class="mk-service-disclosure"/);
        assert.doesNotMatch(markup.match(/^<details\b[^>]*>/)[0], /\bopen\b/);
        for (const text of [copy.title, copy.download, copy.note, ...copy.steps.flat()]) assert.ok(markup.includes(escapeHtml(text)));
        assert.equal((markup.match(/<article\b/g) ?? []).length, 2);
        assert.match(markup, /href="\/guides\/espos-product-import-sample\.xlsx" download=""/);
        assert.doesNotMatch(markup, /<form|<button|<input|style=|role="dialog"/);
        assert.match(copy.steps[1][1], { es: /vista previa no modifica productos.*requiere permisos y confirmación/, en: /preview does not change products.*requires permission and confirmation/, zh: /预览不会修改商品.*权限及明确确认/ }[language]);
        assert.match(copy.note, { es: /sin existencias, clientes ni historial.*no es una migración completa/i, en: /without stock quantities, customers or history.*not a full migration/i, zh: /不含库存数量、客户或历史记录.*并非完整迁移/ }[language]);
      });
    }
    const source = await readFile(new URL("../src/ProductImportHelp.tsx", import.meta.url), "utf8");
    assert.doesNotMatch(source, /fetch\(|localStorage|sessionStorage|\.css["']|onClick|SpreadsheetFile|artifact-tool/);
    const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
    assert.match(page, /<ProductImportHelp language=\{language\}/);
    assert.match(page, /<ComparisonDecisionHelp language=\{language\}/);
  } finally { await server.close(); }
});
