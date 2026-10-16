import { lstat, mkdir, open, readFile, readdir, realpath, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const projectRoot = fileURLToPath(new URL("..", import.meta.url));
const pngSignature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const limitNames = ["totalBytes", "javascriptBytes", "javascriptGzipBytes", "cssBytes", "cssGzipBytes", "initialJavascriptGzipBytes", "initialCssGzipBytes", "htmlBytesPerPage", "largestAssetBytes", "warnAssetBytes"];
const slash = value => value.split(path.sep).join("/");
const inside = (root, candidate) => candidate === root || (!path.relative(root, candidate).startsWith(`..${path.sep}`) && path.relative(root, candidate) !== ".." && !path.isAbsolute(path.relative(root, candidate)));
const gzipBytes = data => gzipSync(data, { level: 9 }).length;
const sum = (items, key) => items.reduce((total, item) => total + (item[key] ?? 0), 0);

export function validateConfiguration(configuration) {
  if (!configuration || typeof configuration !== "object" || Array.isArray(configuration)) throw new Error("Budget configuration must be an object");
  if (Object.keys(configuration).some(key => !["limits", "expectedHtmlPages"].includes(key))) throw new Error("Unknown budget configuration field");
  if (!Number.isSafeInteger(configuration.expectedHtmlPages) || configuration.expectedHtmlPages < 1 || configuration.expectedHtmlPages > 1000) throw new Error("expectedHtmlPages must be an integer from 1 to 1000");
  if (!configuration.limits || typeof configuration.limits !== "object" || Array.isArray(configuration.limits)) throw new Error("Budget limits must be an object");
  if (Object.keys(configuration.limits).some(key => !limitNames.includes(key))) throw new Error("Unknown budget limit");
  for (const name of limitNames) {
    if (!Number.isSafeInteger(configuration.limits[name]) || configuration.limits[name] < 1) throw new Error(`${name} must be a positive integer of bytes`);
  }
  return configuration;
}

// Examine only local files. Never fetch a resource or use a URL as a filesystem
// root. Unknown schemes and references escaping dist are build failures.
export function resolveLocalReference(reference, owner, distRoot) {
  const input = reference.trim();
  if (!input || input.startsWith("#")) return { kind: "ignored" };
  if (input.startsWith("//") || /^https?:\/\//i.test(input)) return { kind: "external", reference: input };
  if (/^data:/i.test(input)) return { kind: "inline" };
  if (/^[a-z][a-z\d+.-]*:/i.test(input)) throw new Error(`Unsupported resource scheme: ${input}`);
  let decoded = input.split(/[?#]/, 1)[0];
  try {
    decoded = decodeURIComponent(decoded);
    for (let index = 0; index < 2 && /%[\da-f]{2}/i.test(decoded); index += 1) decoded = decodeURIComponent(decoded);
  } catch {
    throw new Error(`Malformed resource reference: ${input}`);
  }
  if (!decoded || /[\u0000-\u001f\\:]/.test(decoded) || decoded.startsWith("//") || /%[\da-f]{2}/i.test(decoded)) throw new Error(`Unsafe local resource reference: ${input}`);
  const root = path.resolve(distRoot);
  const candidate = decoded.startsWith("/") ? path.resolve(root, `.${decoded}`) : path.resolve(path.dirname(owner), decoded);
  if (!inside(root, candidate)) throw new Error(`Resource escapes dist: ${input}`);
  return { kind: "local", path: slash(path.relative(root, candidate)) };
}

function attributes(source) {
  return Object.fromEntries([...source.matchAll(/([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)].map(match => [match[1].toLowerCase(), (match[2] ?? match[3] ?? match[4]).replaceAll("&amp;", "&").replaceAll("&quot;", '"').replaceAll("&#39;", "'")]));
}

function htmlReferences(markup) {
  const result = [];
  for (const match of markup.matchAll(/<(script|link|img|source)\b([^>]*)>/gi)) {
    const tag = match[1].toLowerCase();
    const attr = attributes(match[2]);
    if (tag === "script" && attr.src) result.push({ kind: "javascript", reference: attr.src });
    if (tag === "link" && attr.href) {
      const rel = (attr.rel ?? "").toLowerCase().split(/\s+/);
      if (rel.includes("stylesheet")) result.push({ kind: "css", reference: attr.href });
      else if (rel.includes("modulepreload") || (rel.includes("preload") && attr.as === "script")) result.push({ kind: "javascript", reference: attr.href });
      else if (rel.includes("icon") || (rel.includes("preload") && attr.as === "image")) result.push({ kind: "image", reference: attr.href });
    }
    if ((tag === "img" || tag === "source") && attr.src) result.push({ kind: "image", reference: attr.src, loading: attr.loading ?? "default" });
    if ((tag === "img" || tag === "source") && attr.srcset) {
      // Generated source sets contain ordinary file URLs, not inline data URLs.
      if (/^data:/i.test(attr.srcset)) result.push({ kind: "image", reference: attr.srcset, loading: attr.loading ?? "default" });
      else for (const entry of attr.srcset.split(",")) result.push({ kind: "image", reference: entry.trim().split(/\s+/, 1)[0], loading: attr.loading ?? "default" });
    }
  }
  return result;
}

function staticImports(source, kind) {
  if (kind === "css") return [...source.matchAll(/@import\s+(?:url\(\s*)?["']([^"']+)["']/g)].map(match => match[1]);
  // Static ESM declarations only: import(...) is deliberately excluded. This is
  // an inventory of Vite-generated code, not a general JavaScript parser.
  return [...source.matchAll(/\b(?:import|export)\s*(?:[^"'();]*?\s*from\s*)?["']([^"']+)["']/g)].map(match => match[1]);
}

async function pngDimensions(file) {
  const handle = await open(file, "r");
  try {
    const header = Buffer.alloc(24);
    const { bytesRead } = await handle.read(header, 0, header.length, 0);
    if (bytesRead === header.length && header.subarray(0, 8).equals(pngSignature)) return { width: header.readUInt32BE(16), height: header.readUInt32BE(20) };
    return undefined;
  } finally {
    await handle.close();
  }
}

export async function analyzeBuild({ distDir, limits, expectedHtmlPages }) {
  validateConfiguration({ limits, expectedHtmlPages });
  const root = path.resolve(distDir);
  if (!(await lstat(root)).isDirectory() || (await lstat(root)).isSymbolicLink()) throw new Error("dist must be a real directory, not a symlink");
  const files = [];
  const sources = new Map();
  async function walk(directory) {
    for (const entry of (await readdir(directory)).sort()) {
      const absolute = path.join(directory, entry);
      const stat = await lstat(absolute);
      if (stat.isSymbolicLink()) throw new Error(`Symlinks are not allowed in dist: ${slash(path.relative(root, absolute))}`);
      if (stat.isDirectory()) await walk(absolute);
      else if (stat.isFile()) {
        const relative = slash(path.relative(root, absolute));
        const extension = path.extname(relative).toLowerCase();
        const kind = extension === ".js" || extension === ".mjs" ? "javascript" : extension === ".css" ? "css" : extension === ".html" ? "html" : "asset";
        const info = { path: relative, kind, bytes: stat.size };
        if (kind !== "asset") {
          if (stat.size > 10_000_000) throw new Error(`Code or HTML exceeds safe analysis size: ${relative}`);
          const data = await readFile(absolute);
          info.gzipBytes = gzipBytes(data);
          sources.set(relative, data.toString("utf8"));
        }
        if (extension === ".png") info.dimensions = await pngDimensions(absolute);
        files.push(info);
      } else throw new Error(`Unsupported filesystem entry in dist: ${slash(path.relative(root, absolute))}`);
    }
  }
  await walk(root);
  const byPath = new Map(files.map(file => [file.path, file]));
  const failures = [];
  const warnings = [];
  const warn = message => { if (!warnings.includes(message)) warnings.push(message); };
  function referenceInfo(reference, owner) {
    try {
      const resolved = resolveLocalReference(reference, path.join(root, owner), root);
      if (resolved.kind === "external") warn(`External resource is not downloaded or measured: ${reference}`);
      if (resolved.kind !== "local") return resolved;
      if (!byPath.has(resolved.path)) failures.push(`Missing local resource in ${owner}: ${reference}`);
      return resolved;
    } catch (error) {
      failures.push(`${owner}: ${error.message}`);
      return { kind: "invalid", reference };
    }
  }
  function codeGraph(initial, kind) {
    const selected = new Set();
    const pending = [...initial];
    while (pending.length) {
      const current = pending.pop();
      if (selected.has(current)) continue;
      const file = byPath.get(current);
      if (!file || file.kind !== kind) {
        if (file) failures.push(`Unexpected ${kind} resource type: ${current}`);
        continue;
      }
      selected.add(current);
      for (const reference of staticImports(sources.get(current), kind)) {
        const resolved = referenceInfo(reference, current);
        if (resolved.kind === "local") pending.push(resolved.path);
      }
    }
    const selectedFiles = [...selected].sort().map(name => byPath.get(name));
    return { files: selectedFiles.map(file => file.path), bytes: sum(selectedFiles, "bytes"), gzipBytes: sum(selectedFiles, "gzipBytes") };
  }
  const html = files.filter(file => file.kind === "html");
  const pages = html.map(file => {
    const references = htmlReferences(sources.get(file.path)).map(reference => ({ ...reference, resourceType: reference.kind, ...referenceInfo(reference.reference, file.path) }));
    const javascript = codeGraph(references.filter(item => item.kind === "local" && item.resourceType === "javascript").map(item => item.path), "javascript");
    const css = codeGraph(references.filter(item => item.kind === "local" && item.resourceType === "css").map(item => item.path), "css");
    // No network transfer estimate: lazy/srcset/CSS conditions are browser work.
    return { path: file.path, bytes: file.bytes, gzipBytes: file.gzipBytes, initialCode: { javascript, css }, references };
  });
  const javascript = files.filter(file => file.kind === "javascript");
  const css = files.filter(file => file.kind === "css");
  const assets = files.filter(file => file.kind === "asset").sort((a, b) => b.bytes - a.bytes || a.path.localeCompare(b.path));
  const totals = {
    files: files.length,
    htmlPages: html.length,
    bytes: sum(files, "bytes"),
    javascript: { files: javascript.length, bytes: sum(javascript, "bytes"), gzipBytes: sum(javascript, "gzipBytes") },
    css: { files: css.length, bytes: sum(css, "bytes"), gzipBytes: sum(css, "gzipBytes") },
    assets: { files: assets.length, bytes: sum(assets, "bytes") },
    html: { files: html.length, bytes: sum(html, "bytes"), gzipBytes: sum(html, "gzipBytes") }
  };
  const check = (actual, maximum, label) => { if (actual > maximum) failures.push(`${label}: ${actual} bytes exceeds ${maximum} bytes`); };
  if (html.length !== expectedHtmlPages) failures.push(`HTML page count: ${html.length}; expected ${expectedHtmlPages}`);
  if (!byPath.has("index.html")) failures.push("Missing dist/index.html");
  check(totals.bytes, limits.totalBytes, "Build total");
  check(totals.javascript.bytes, limits.javascriptBytes, "JavaScript total raw");
  check(totals.javascript.gzipBytes, limits.javascriptGzipBytes, "JavaScript total gzip");
  check(totals.css.bytes, limits.cssBytes, "CSS total raw");
  check(totals.css.gzipBytes, limits.cssGzipBytes, "CSS total gzip");
  for (const page of pages) {
    check(page.bytes, limits.htmlBytesPerPage, `HTML ${page.path}`);
    check(page.initialCode.javascript.gzipBytes, limits.initialJavascriptGzipBytes, `Initial JavaScript gzip ${page.path}`);
    check(page.initialCode.css.gzipBytes, limits.initialCssGzipBytes, `Initial CSS gzip ${page.path}`);
    if (!page.initialCode.javascript.files.length) failures.push(`No local entry JavaScript in ${page.path}`);
    if (!page.initialCode.css.files.length) failures.push(`No local stylesheet in ${page.path}`);
  }
  for (const asset of assets) {
    check(asset.bytes, limits.largestAssetBytes, `Asset ${asset.path}`);
    if (asset.bytes > limits.warnAssetBytes) warn(`Large asset: ${asset.path} (${asset.bytes} bytes)`);
  }
  return {
    schemaVersion: 1,
    measurement: "Local build file sizes only; gzip level 9 per file. No browser, HTTP, Lighthouse or Core Web Vitals measurement. Initial code follows HTML references and static ESM/CSS imports; dynamic imports are excluded. Referenced images are inventoried, not assumed downloaded.",
    passed: failures.length === 0,
    limits,
    expectedHtmlPages,
    totals,
    largestAssets: assets.slice(0, 10),
    pages,
    failures: [...new Set(failures)],
    warnings,
    files
  };
}

export function formatSummary(report) {
  const lines = [
    `Performance budget: ${report.passed ? "PASS" : "FAIL"} (local files only)`,
    `HTML ${report.totals.htmlPages}/${report.expectedHtmlPages}; build ${report.totals.bytes} bytes`,
    `JavaScript ${report.totals.javascript.bytes} raw / ${report.totals.javascript.gzipBytes} gzip bytes`,
    `CSS ${report.totals.css.bytes} raw / ${report.totals.css.gzipBytes} gzip bytes`,
    ...report.largestAssets.slice(0, 3).map(file => `Asset ${file.path}: ${file.bytes} bytes${file.dimensions ? `; ${file.dimensions.width}x${file.dimensions.height}` : ""}`),
    ...report.warnings.map(warning => `WARN ${warning}`),
    ...report.failures.map(failure => `FAIL ${failure}`)
  ];
  return lines.join("\n");
}

// Artifact output is optional and is confined to the checkout's ignored
// artifacts directory. No environment variable influences roots or budgets.
export async function runCli(args = process.argv.slice(2), io = { stdout: process.stdout, stderr: process.stderr }) {
  let jsonOutput;
  let jsonStdout = false;
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--json") jsonStdout = true;
    else if (args[index] === "--output" && args[index + 1] && !jsonOutput) jsonOutput = args[++index];
    else throw new Error("Usage: node scripts/performance-budget.mjs [--json] [--output artifacts/performance-budget.json]");
  }
  let output;
  if (jsonOutput) {
    output = path.resolve(projectRoot, jsonOutput);
    const artifactsRoot = path.join(projectRoot, "artifacts");
    if (!inside(artifactsRoot, output) || output === artifactsRoot || path.extname(output).toLowerCase() !== ".json") throw new Error("Report output must be a JSON file inside frontend-product/artifacts");
    // Refuse symlinked parents before creating/writing anything.
    for (let current = path.dirname(output); inside(projectRoot, current); current = path.dirname(current)) {
      try {
        if ((await lstat(current)).isSymbolicLink()) throw new Error("Report output must not traverse symlinks");
      } catch (error) {
        if (error.code !== "ENOENT") throw error;
      }
      if (current === projectRoot) break;
    }
    try {
      const stat = await lstat(output);
      if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink > 1) throw new Error("Report output must not overwrite a linked or non-regular file");
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
  }
  const configuration = validateConfiguration(JSON.parse(await readFile(path.join(projectRoot, "performance-budget.json"), "utf8")));
  const distDir = path.join(projectRoot, "dist");
  if ((await realpath(distDir)) !== distDir) throw new Error("CLI dist path must not traverse symlinks");
  const report = await analyzeBuild({ distDir, ...configuration });
  if (output) {
    await mkdir(path.dirname(output), { recursive: true });
    await writeFile(output, `${JSON.stringify(report, null, 2)}\n`, { flag: "w" });
  }
  const summary = `${formatSummary(report)}\n`;
  if (jsonStdout) {
    io.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
    io.stderr.write(summary);
  } else io.stdout.write(summary);
  return report.passed ? 0 : 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runCli().then(code => { process.exitCode = code; }).catch(error => {
    process.stderr.write(`Performance budget error: ${error.message}\n`);
    process.exitCode = 1;
  });
}
