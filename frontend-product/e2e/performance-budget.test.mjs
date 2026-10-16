import assert from "node:assert/strict";
import { lstat, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { gzipSync } from "node:zlib";
import { analyzeBuild, formatSummary, resolveLocalReference, runCli, validateConfiguration } from "../scripts/performance-budget.mjs";

const limits = {
  totalBytes: 100000,
  javascriptBytes: 10000,
  javascriptGzipBytes: 10000,
  cssBytes: 10000,
  cssGzipBytes: 10000,
  initialJavascriptGzipBytes: 10000,
  initialCssGzipBytes: 10000,
  htmlBytesPerPage: 10000,
  largestAssetBytes: 10000,
  warnAssetBytes: 1000
};

async function fixture(suite, entries = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), "espos-budget-test-"));
  suite.after(async () => {
    const resolved = path.resolve(root);
    // Recursive cleanup is limited to this exact generated test directory.
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    assert.match(path.basename(resolved), /^espos-budget-test-[\w-]+$/);
    await rm(resolved, { recursive: true, force: true });
  });
  const content = {
    "index.html": '<!doctype html><script type="module" src="/assets/main.js"></script><link rel="stylesheet" href="/assets/main.css"><img src="/images/preview.png" loading="lazy">',
    "assets/main.js": 'import { shared } from "./shared.js"; console.log(shared); import("./deferred.js");',
    "assets/shared.js": 'export const shared = "esPOS";',
    "assets/deferred.js": 'export const deferred = "Deferred content";',
    "assets/main.css": '@import "./shared.css"; body { color: #123; }',
    "assets/shared.css": '.preview { max-width: 100%; }',
    "images/preview.png": Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 6, 64, 0, 0, 3, 232]),
    ...entries
  };
  for (const [name, data] of Object.entries(content)) {
    const target = path.join(root, name);
    assert.ok(path.relative(root, target) && !path.relative(root, target).startsWith(".."));
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, data);
  }
  return root;
}

test("reports actual raw/gzip code, PNG dimensions and static initial dependencies", async suite => {
  const root = await fixture(suite);
  const report = await analyzeBuild({ distDir: root, limits, expectedHtmlPages: 1 });
  assert.equal(report.passed, true, JSON.stringify(report.failures));
  assert.equal(report.totals.files, 7);
  assert.equal(report.totals.htmlPages, 1);
  assert.deepEqual(report.pages[0].initialCode.javascript.files, ["assets/main.js", "assets/shared.js"]);
  assert.deepEqual(report.pages[0].initialCode.css.files, ["assets/main.css", "assets/shared.css"]);
  assert.equal(report.totals.javascript.files, 3, "Lazy chunks still count towards total JavaScript");
  const entryBytes = await readFile(path.join(root, "assets/main.js"));
  const sharedBytes = await readFile(path.join(root, "assets/shared.js"));
  assert.equal(report.pages[0].initialCode.javascript.bytes, entryBytes.length + sharedBytes.length);
  assert.equal(report.pages[0].initialCode.javascript.gzipBytes, gzipSync(entryBytes, { level: 9 }).length + gzipSync(sharedBytes, { level: 9 }).length);
  assert.deepEqual(report.files.find(file => file.path === "images/preview.png").dimensions, { width: 1600, height: 1000 });
  assert.ok(report.pages[0].references.some(reference => reference.loading === "lazy"));
  assert.match(report.measurement, /No browser, HTTP, Lighthouse or Core Web Vitals/);
  assert.match(formatSummary(report), /Performance budget: PASS/);
});

test("handles all 18 static routes separately without multiplying the initial code budget", async suite => {
  const entries = {};
  for (const language of ["es", "en", "zh"]) {
    for (const route of ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"]) {
      entries[`${language}/producto/${route}/index.html`] = '<script type="module" src="/assets/main.js"></script><link rel="modulepreload" href="/assets/shared.js"><link rel="stylesheet" href="/assets/main.css">';
    }
  }
  const root = await fixture(suite, entries);
  const report = await analyzeBuild({ distDir: root, limits, expectedHtmlPages: 19 });
  assert.equal(report.passed, true, JSON.stringify(report.failures));
  assert.equal(report.pages.length, 19);
  assert.equal(report.totals.javascript.files, 3);
  assert.equal(new Set(report.pages.map(page => page.initialCode.javascript.gzipBytes)).size, 1);
});

test("fails measured regressions and missing entry resources with a useful summary", async suite => {
  const root = await fixture(suite, { "assets/oversize.png": Buffer.alloc(12000, 1) });
  const report = await analyzeBuild({ distDir: root, limits: { ...limits, initialJavascriptGzipBytes: 1, cssBytes: 1, htmlBytesPerPage: 1 }, expectedHtmlPages: 19 });
  assert.equal(report.passed, false);
  assert.ok(report.failures.some(failure => failure.includes("Initial JavaScript gzip")));
  assert.ok(report.failures.some(failure => failure.includes("CSS total raw")));
  assert.ok(report.failures.some(failure => failure.includes("Asset assets/oversize.png")));
  assert.ok(report.failures.some(failure => failure.includes("HTML page count")));
  assert.ok(report.failures.some(failure => failure.startsWith("HTML index.html")));
  assert.ok(report.warnings.some(warning => warning.includes("assets/oversize.png")));
  assert.match(formatSummary(report), /Performance budget: FAIL/);
  await writeFile(path.join(root, "index.html"), '<script src="/assets/missing.js"></script><link rel="stylesheet" href="/assets/missing.css">');
  const missing = await analyzeBuild({ distDir: root, limits, expectedHtmlPages: 1 });
  assert.ok(missing.failures.some(failure => failure.includes("Missing local resource")));
  assert.ok(missing.failures.some(failure => failure.includes("No local entry JavaScript")));
  assert.ok(missing.failures.some(failure => failure.includes("No local stylesheet")));
});

test("rejects local path escapes and unsupported schemes without reading outside dist", async suite => {
  const root = await fixture(suite);
  const owner = path.join(root, "index.html");
  const malicious = ["../../secret.js", "%2e%2e/%2e%2e/secret.js", "%252e%252e/%252e%252e/secret.js", "..%5csecret.js", "C:\\private\\secret.js", "file:///etc/passwd", "javascript:alert(1)", "https%3a%2f%2fevil.invalid/x", "assets/%00.js", "%", "%E0%A4%A"];
  for (const reference of malicious) assert.throws(() => resolveLocalReference(reference, owner, root), undefined, reference);
  assert.deepEqual(resolveLocalReference("/assets/main.js?cache=1#chunk", owner, root), { kind: "local", path: "assets/main.js" });
  assert.deepEqual(resolveLocalReference("../main.js", path.join(root, "assets", "nested", "index.html"), root), { kind: "local", path: "assets/main.js" });
  for (const reference of ["../../secret.js", "file:///private/secret.js", "javascript:alert(1)"]) {
    await writeFile(path.join(root, "index.html"), `<script src="${reference}"></script><link rel="stylesheet" href="/assets/main.css">`);
    const report = await analyzeBuild({ distDir: root, limits, expectedHtmlPages: 1 });
    assert.equal(report.passed, false);
    assert.ok(report.failures.some(failure => /escapes dist|Unsupported resource scheme/.test(failure)));
  }
});

test("inventories remote and inline resources without requesting them", async suite => {
  const root = await fixture(suite, {
    "index.html": '<script src="/assets/main.js"></script><link rel="stylesheet" href="/assets/main.css"><img src="https://must-not-request.invalid/image.png"><img src="//must-not-request.invalid/other.png"><img src="data:image/png;base64,AA==">'
  });
  const report = await analyzeBuild({ distDir: root, limits, expectedHtmlPages: 1 });
  assert.equal(report.passed, true);
  assert.equal(report.pages[0].references.filter(reference => reference.kind === "external").length, 2);
  assert.equal(report.pages[0].references.filter(reference => reference.kind === "inline").length, 1);
  assert.equal(report.warnings.filter(warning => warning.includes("not downloaded or measured")).length, 2);
  const source = await readFile(new URL("../scripts/performance-budget.mjs", import.meta.url), "utf8");
  assert.doesNotMatch(source, /\b(?:fetch|createServer|listen|exec|spawn)\s*\(/);
  assert.doesNotMatch(source, /process\.env/, "No environment variable can redirect the measurement or output");
});

test("rejects symlink entries instead of following them", async suite => {
  const root = await fixture(suite);
  let alias = path.join(root, "assets", "alias.js");
  try {
    await symlink(path.join(root, "assets", "main.js"), alias, "file");
  } catch (error) {
    if (error.code === "EPERM" || error.code === "EACCES") {
      // Windows junctions can exercise the same guard without Developer Mode.
      alias = path.join(root, "linked-assets");
      try {
        await symlink(path.join(root, "assets"), alias, "junction");
      } catch (junctionError) {
        if (junctionError.code === "EPERM" || junctionError.code === "EACCES") {
          suite.skip("This account cannot create symlinks or junctions; other traversal contracts still run");
          return;
        }
        throw junctionError;
      }
    } else throw error;
  }
  assert.equal((await lstat(alias)).isSymbolicLink(), true);
  await assert.rejects(analyzeBuild({ distDir: root, limits, expectedHtmlPages: 1 }), /Symlinks are not allowed/);
});

test("strict configuration and CLI output guard reject unsafe or environment-like arguments before writing", async () => {
  validateConfiguration({ limits, expectedHtmlPages: 19 });
  assert.throws(() => validateConfiguration({ limits, expectedHtmlPages: 0 }), /expectedHtmlPages/);
  assert.throws(() => validateConfiguration({ limits: { ...limits, totalBytes: -1 }, expectedHtmlPages: 19 }), /positive integer/);
  assert.throws(() => validateConfiguration({ limits: { ...limits, arbitrary: 1 }, expectedHtmlPages: 19 }), /Unknown budget limit/);
  assert.throws(() => validateConfiguration({ limits, expectedHtmlPages: 19, output: "../secret.json" }), /Unknown budget configuration/);
  for (const args of [["--dist", "../../private"], ["--output", "../escape.json"], ["--output", "artifacts/../escape.json"], ["--output", "artifacts/report.html"], ["--output", "artifacts/report.json", "--env", "unsafe"], ["--output"]]) {
    await assert.rejects(runCli(args, { stdout: { write() { assert.fail("Unsafe CLI call wrote output"); } }, stderr: { write() { assert.fail("Unsafe CLI call wrote stderr"); } } }), /Usage|inside frontend-product\/artifacts/);
  }
});
