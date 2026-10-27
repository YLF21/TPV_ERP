import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const receipt = { id: "54000000-0000-4000-8000-000000000001", receivedAt: "2026-10-27T10:15:00Z" };
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");

// Source, pure formatting and SSR only: no browser, clipboard write, HTTP,
// real form submission or geometry is exercised by these contracts.
test("confirmed demo receipt summary contracts", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    assert.equal(server.httpServer, null);
    const [{ DemoRequestReceipt }, { getDemoReceiptContent, demoReceiptDetails, formatDemoReceiptSummary }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/DemoRequestReceipt.tsx"), server.ssrLoadModule("/src/demoReceiptContent.ts"), server.ssrLoadModule("/src/marketingI18n.ts")
    ]);

    for (const language of ["es", "en", "zh"]) {
      await suite.test(`${language}: the receipt shows its original reference, date and confirmed apps`, () => {
        const copy = getDemoReceiptContent(language);
        const details = demoReceiptDetails(receipt, ["pda", "venta"], language);
        const names = getMarketingContent(language).apps.items;
        assert.equal(details.apps, `${names[0][0]}, ${names[2][0]}`);
        assert.ok(details.date.length > 10);
        const markup = renderToStaticMarkup(createElement(DemoRequestReceipt, { receipt, applications: ["pda", "venta"], language }));
        assert.ok(markup.includes(`class="mk-demo-receipt"`));
        assert.ok(markup.includes(`<code>${receipt.id}</code>`));
        assert.ok(markup.includes(`<time dateTime="${receipt.receivedAt}">${escapeHtml(details.date)}</time>`));
        assert.ok(markup.includes(escapeHtml(details.apps)));
        assert.ok(markup.includes(escapeHtml(copy.copy)));
        assert.match(markup, /<button[^>]*type="button"/);
        assert.match(markup, /role="status" aria-live="polite" aria-atomic="true"/);
        assert.doesNotMatch(markup, /<textarea|type="submit"|<form|onclick=/);
      });
    }

    await suite.test("copied summaries contain no contact fields and normalise app selection", () => {
      const extendedReceipt = { ...receipt, name: "Do not copy name", email: "private@example.com", company: "Private company", message: "Private message" };
      const summary = formatDemoReceiptSummary(extendedReceipt, ["pda", "venta", "pda", "untrusted"], "es");
      assert.equal(summary.split("\n").length, 3);
      assert.ok(summary.includes(receipt.id));
      assert.match(summary, /esPOS Venta, esPOS PDA/);
      assert.doesNotMatch(summary, /Do not copy|private@example|Private company|Private message|untrusted/);
      assert.throws(() => demoReceiptDetails({ ...receipt, receivedAt: "not-a-date" }, ["venta"], "es"), RangeError);
    });

    await suite.test("clipboard failure has manual selection and async completions are invalidated", async () => {
      const source = await readFile(new URL("../src/DemoRequestReceipt.tsx", import.meta.url), "utf8");
      assert.match(source, /navigator\.clipboard\.writeText\(summary\)/);
      assert.match(source, /if \(current === attempt\.current\) setState\("copied"\)/);
      assert.match(source, /if \(current === attempt\.current\) setState\("error"\)/);
      assert.match(source, /return \(\) => \{ attempt\.current \+= 1; \}/);
      assert.match(source, /\}, \[summary\]\)/);
      assert.match(source, /fallback\.current\?\.focus\(\); fallback\.current\?\.select\(\)/);
      assert.match(source, /<textarea[^>]*readOnly[^>]*value=\{summary\}/);
      assert.doesNotMatch(source, /localStorage|sessionStorage|document\.cookie|fetch\(|submitDemoRequest/);
    });

    await suite.test("Contact stores the sent application snapshot only after a validated receipt", async () => {
      const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
      assert.match(page, /const requestedApplications = appMeta\.filter\(\(\{ id \}\) => selectedApps\.includes\(id\)\)\.map\(\(\{ id \}\) => id\)/);
      assert.match(page, /const products = requestedApplications\.map\(id => `APP_\$\{id\.toUpperCase\(\)\}`\)/);
      assert.match(page, /const received = await submitDemoRequest\([\s\S]*?if \(controller\.signal\.aborted\) return;[\s\S]*?setReceipt\(\{ \.\.\.received, applications: requestedApplications \}\)/);
      assert.match(page, /<DemoRequestReceipt receipt=\{receipt\} applications=\{receipt\.applications\} language=\{language\} \/>/);
      assert.match(page, /function clearSubmissionFeedback\(\) \{\s*setReceipt\(null\)/, "An edited form must not keep presenting a previous receipt as its own confirmation");
    });
  } finally { await server.close(); }
});
