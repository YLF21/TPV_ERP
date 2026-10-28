import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const applicationIds = ["venta", "gestion", "pda", "saas"];
const languages = ["es", "en", "zh"];
const escapeHtml = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#x27;");
const shape = value => typeof value === "string" ? "string" : Array.isArray(value) ? value.map(shape) : Object.fromEntries(Object.entries(value).map(([key, child]) => [key, shape(child)]));
const fingerprint = bytes => createHash("sha256").update(bytes).digest("hex");

// These describe the four original Explorer screenshots, not the twelve tour
// steps. They remain separately protected by marketing-images.test.mjs.
const overviewUrls = applicationIds.map(application => `/marketing/tour-${application}.png`);

function sourceDimensions(bytes) {
  if (bytes.toString("hex", 0, 8) === "89504e470d0a1a0a") {
    return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20), format: "png" };
  }
  assert.equal(bytes.readUInt16BE(0), 0xffd8, "The original capture must be an actual JPEG or PNG");
  let offset = 2;
  while (offset + 4 < bytes.length) {
    assert.equal(bytes[offset], 0xff, "Valid JPEG marker before its image dimensions");
    while (bytes[offset] === 0xff) offset += 1;
    const marker = bytes[offset++];
    if (marker === 0xd9 || marker === 0xda) break;
    if (marker === 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
    const length = bytes.readUInt16BE(offset);
    assert.ok(length >= 2 && offset + length <= bytes.length);
    if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
      assert.ok(length >= 8);
      return { width: bytes.readUInt16BE(offset + 5), height: bytes.readUInt16BE(offset + 3), format: "jpeg" };
    }
    offset += length;
  }
  assert.fail("JPEG dimensions were not found");
}

function webpDimensions(bytes) {
  assert.equal(bytes.toString("ascii", 0, 4), "RIFF");
  assert.equal(bytes.toString("ascii", 8, 12), "WEBP");
  assert.equal(bytes.readUInt32LE(4) + 8, bytes.length, "The published WebP must be complete");
  for (let offset = 12; offset + 8 <= bytes.length;) {
    const type = bytes.toString("ascii", offset, offset + 4);
    const length = bytes.readUInt32LE(offset + 4);
    const start = offset + 8;
    assert.ok(start + length <= bytes.length);
    if (type === "VP8X") {
      assert.ok(length >= 10);
      return { width: bytes.readUIntLE(start + 4, 3) + 1, height: bytes.readUIntLE(start + 7, 3) + 1 };
    }
    if (type === "VP8 ") {
      assert.ok(length >= 10 && bytes.subarray(start + 3, start + 6).equals(Buffer.from([0x9d, 0x01, 0x2a])));
      return { width: bytes.readUInt16LE(start + 6) & 0x3fff, height: bytes.readUInt16LE(start + 8) & 0x3fff };
    }
    if (type === "VP8L") {
      assert.ok(length >= 5 && bytes[start] === 0x2f);
      const dimensions = bytes.readUInt32LE(start + 1);
      return { width: (dimensions & 0x3fff) + 1, height: (dimensions >>> 14 & 0x3fff) + 1 };
    }
    offset = start + length + (length % 2);
  }
  assert.fail("The published WebP must contain valid image dimensions");
}

function tags(markup, name) {
  return [...markup.matchAll(new RegExp(`<${name}\\b([^>]*)>`, "g"))].map(match => Object.fromEntries(
    [...match[1].matchAll(/([\w:-]+)="([^"]*)"/g)].map(attribute => [attribute[1], attribute[2]])
  ));
}

test("honest per-step tour media contracts (controlled SSR, no browser or HTTP)", async suite => {
  const server = await createServer({
    configFile: false, root, plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  try {
    assert.equal(server.httpServer, null, "The SSR loader must not create a listening server");
    const [{ TourStepPreview }, media, { getTourExamples }, { getApplicationPreviewAlts }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/TourStepPreview.tsx"),
      server.ssrLoadModule("/src/tourStepMedia.ts"),
      server.ssrLoadModule("/src/tourExamples.ts"),
      server.ssrLoadModule("/src/applicationPreviewContent.ts"),
      server.ssrLoadModule("/src/marketingI18n.ts")
    ]);

    await suite.test("Explorer retains its separate overview alternatives without loading step metadata eagerly", async () => {
      for (const language of languages) {
        const alts = getApplicationPreviewAlts(language);
        assert.deepEqual(Object.keys(alts), applicationIds);
        assert.equal(new Set(Object.values(alts)).size, applicationIds.length);
        assert.equal(Object.hasOwn(getMarketingContent(language).tour, "previewAlts"), false);
        for (const alt of Object.values(alts)) {
          assert.match(alt, { es: /en español/, en: /in Spanish/, zh: /西班牙语/ }[language], "The screenshot interface language stays explicit");
        }
      }
      const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
      assert.match(page, /getApplicationPreviewAlts\(language\)/);
      assert.match(page, /previewAlt: previewAlts\[id\]/);
      assert.doesNotMatch(page, /from ["']\.\/tourStepMedia["']/, "The page must not eagerly import step metadata for an image alternative");
      const previews = await readFile(new URL("../src/applicationPreviewContent.ts", import.meta.url), "utf8");
      assert.doesNotMatch(previews, /^import\s+(?!type\b)/m, "Shared alternatives need no runtime dependencies");
    });

    await suite.test("all three languages have complete and equivalent media text structures", () => {
      const baseline = shape(media.getTourStepMediaContent("es"));
      for (const language of languages) {
        const copy = media.getTourStepMediaContent(language);
        assert.deepEqual(shape(copy), baseline);
        assert.equal(Object.hasOwn(copy, "overviewNote"), false, "A complete twelve-step tour has no unused general-reference fallback copy");
        assert.match(copy.captureNote, { es: /Interfaz real en español.*Datos de demostración/, en: /Real interface in Spanish.*Demonstration data/, zh: /真实界面（西班牙语）.*演示数据/ }[language]);
        assert.deepEqual(Object.keys(copy.steps), applicationIds);
        for (const application of applicationIds) {
          assert.equal(copy.steps[application].length, 3);
          assert.equal(new Set(copy.steps[application]).size, 3);
          assert.ok(copy.steps[application].every(text => text.trim().length > 0));
        }
      }
    });

    const manifest = JSON.parse(await readFile(new URL("../assets/marketing-sources/tour-step-captures.json", import.meta.url), "utf8"));
    await suite.test("the evidence manifest contains exactly twelve reviewed native captures with synthetic data", () => {
      assert.equal(manifest.schemaVersion, 1);
      assert.ok(Number.isFinite(Date.parse(manifest.capturedAt)));
      assert.equal(manifest.method, "cua-repl", "The evidence identifies the native browser capture workflow, not generated mockups");
      assert.equal(manifest.sourceInterfaceLanguage, "es");
      assert.equal(manifest.data, "synthetic");
      assert.ok(manifest.generator && typeof manifest.generator === "object");
      assert.equal(manifest.generator.transform.resize, false);
      assert.equal(manifest.generator.transform.crop, false);
      assert.equal(manifest.captures.length, 12);
      const expected = applicationIds.flatMap(application => [0, 1, 2].map(step => `${application}/${step}`));
      const actual = manifest.captures.map(capture => `${capture.application}/${capture.step}`);
      assert.deepEqual([...actual].sort(), [...expected].sort(), "No missing or duplicate application/step pair may fall back to an overview");
      for (const capture of manifest.captures) {
        assert.ok(typeof capture.scenario === "string" && capture.scenario.trim().length > 0);
        assert.ok(Array.isArray(capture.sourceComponents) && capture.sourceComponents.length > 0);
        assert.ok(capture.sourceComponents.every(component => typeof component === "string" && component.trim().length > 0));
      }
    });

    for (const application of applicationIds) {
      for (const step of [0, 1, 2]) {
        await suite.test(`${application}/${step}: the exact native capture and published optimised asset match their provenance`, async () => {
          const data = media.getTourStepMedia(application, step, "es");
          const capture = manifest.captures.find(item => item.application === application && item.step === step);
          assert.ok(capture, "Each step needs captured evidence rather than a conditional skip");
          assert.equal(data.src, `/marketing/steps/${application}-${step + 1}.webp`);
          assert.equal(data.kind, "step-capture");
          assert.equal(capture.output.url, data.src);
          assert.equal(capture.output.path, `public${data.src}`);
          assert.match(capture.source.path, new RegExp(`^assets/marketing-sources/tour-steps/${application}-${step + 1}\\.(?:jpe?g|png)$`));
          const [source, published] = await Promise.all([
            readFile(new URL(`../${capture.source.path}`, import.meta.url)),
            readFile(new URL(`../${capture.output.path}`, import.meta.url))
          ]);
          assert.equal(source.length, capture.source.bytes);
          assert.equal(published.length, capture.output.bytes);
          assert.equal(fingerprint(source), capture.source.sha256);
          assert.equal(fingerprint(published), capture.output.sha256);
          const sourceSize = sourceDimensions(source);
          assert.equal(sourceSize.format, String(capture.source.format).toLowerCase().replace("jpg", "jpeg"));
          assert.deepEqual({ width: sourceSize.width, height: sourceSize.height }, { width: capture.source.width, height: capture.source.height });
          const publishedSize = webpDimensions(published);
          assert.deepEqual(publishedSize, { width: capture.output.width, height: capture.output.height });
          assert.deepEqual(publishedSize, { width: sourceSize.width, height: sourceSize.height }, "Optimisation preserves the original captured frame");
          assert.deepEqual(publishedSize, { width: data.width, height: data.height }, "The runtime dimensions must match the actual published capture");
        });
      }
    }

    for (const language of languages) {
      await suite.test(`${language}: all twelve previews render localized context, disclosure and original-image link`, () => {
        const copy = media.getTourStepMediaContent(language);
        for (const application of applicationIds) {
          const captions = [];
          const altTexts = [];
          for (const step of [0, 1, 2]) {
            const data = media.getTourStepMedia(application, step, language);
            const markup = renderToStaticMarkup(createElement(TourStepPreview, { application, step, language }));
            const figure = tags(markup, "figure");
            assert.equal(figure.length, 1);
            assert.equal(figure[0]["data-media-kind"], data.kind);
            assert.equal(figure[0]["data-step"], String(step));
            const images = tags(markup, "img");
            assert.equal(images.length, 1, "Only the active step image should be mounted");
            assert.equal(images[0].src, data.src);
            assert.equal(images[0].alt, escapeHtml(data.alt));
            assert.equal(images[0].width, String(data.width));
            assert.equal(images[0].height, String(data.height));
            assert.equal(images[0].decoding, "async");
            assert.ok(markup.includes(escapeHtml(data.caption)));
            assert.equal(data.kind, "step-capture");
            assert.ok(markup.includes(escapeHtml(copy.captureNote)));
            const links = tags(markup, "a");
            assert.equal(links.length, 1);
            assert.equal(links[0].href, data.src);
            assert.equal(links[0].target, "_blank");
            assert.equal(links[0].rel, "noreferrer");
            assert.ok(markup.includes(escapeHtml(copy.expandLabel)));
            assert.doesNotMatch(markup, /<button\b|role="dialog"|onclick=|style=/, "Media expansion stays a native link, not a nested modal or fixed layout");
            captions.push(data.caption);
            altTexts.push(data.alt);
          }
          assert.equal(new Set(captions).size, 3, "Each step needs its own contextual caption");
          assert.equal(new Set(altTexts).size, 3, "Each step needs its own contextual alternative text");
        }
      });
    }

    await suite.test("unsupported step numbers fail explicitly instead of silently mislabelling a screenshot", () => {
      for (const step of [-1, 3, 0.5, NaN, Infinity]) {
        assert.throws(() => media.getTourStepMedia("venta", step, "es"), RangeError);
      }
    });

    await suite.test("step captures cannot be renamed copies of general references or other steps", async () => {
      const overviewHashes = new Set(await Promise.all(overviewUrls.map(async url =>
        fingerprint(await readFile(new URL(`../public${url}`, import.meta.url)))
      )));
      const captureHashes = new Set();
      const sourceHashes = new Set();
      for (const capture of manifest.captures) {
        const [source, published] = await Promise.all([
          readFile(new URL(`../${capture.source.path}`, import.meta.url)),
          readFile(new URL(`../${capture.output.path}`, import.meta.url))
        ]);
        const sourceHash = fingerprint(source);
        const outputHash = fingerprint(published);
        assert.ok(!overviewHashes.has(sourceHash) && !overviewHashes.has(outputHash), "A general reference is not a newly captured step");
        assert.ok(!captureHashes.has(outputHash) && !sourceHashes.has(sourceHash), "Do not present one capture as multiple distinct steps");
        captureHashes.add(outputHash);
        sourceHashes.add(sourceHash);
      }
      assert.equal(captureHashes.size, 12);
      assert.equal(sourceHashes.size, 12);
    });

    await suite.test("all twelve practical scenarios remain illustrative, distinct and compact in each language", () => {
      for (const language of languages) {
        const tour = getTourExamples(language);
        const examples = tour.stepDetails.flat();
        assert.equal(examples.length, 12);
        assert.equal(new Set(examples.map(details => details.example)).size, 12);
        for (const details of examples) {
          assert.equal(details.actions.length, 3);
          assert.equal(new Set(details.actions).size, 3);
          assert.match(details.example, { es: /ejemplo orientativo/i, en: /illustrative (?:configuration )?example/i, zh: /示例情境/ }[language]);
          assert.ok(details.example.length <= 200, "Use a concise scenario rather than an extra page of prose");
          assert.ok(details.actions.every(action => action.length <= 100));
          assert.ok(details.benefit.length <= 110);
        }
      }
    });

    await suite.test("the active application and step drive the existing tour preview without eager page imports", async () => {
      const [dialogs, page, loader, runtime] = await Promise.all([
        readFile(new URL("../src/MarketingDialogs.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/MarketingDialogLoader.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/tourStepMedia.ts", import.meta.url), "utf8")
      ]);
      assert.match(dialogs, /<TourStepPreview application=\{activeApp\} step=\{step\} language=\{language\}\s*\/>/);
      assert.doesNotMatch(page, /from ["']\.\/(?:TourStepPreview|tourStepMedia|tourExamples)["']/);
      assert.match(loader, /lazy\(\(\) => import\("\.\/MarketingDialogs"\)\)/, "The media stays in the existing on-demand dialog chunk");
      const executable = runtime.replace(/^\s*\/\/.*$/gm, "");
      assert.doesNotMatch(executable, /tour-step-captures\.json|sourceComponents|capturedAt|source:|overviewNote|tourApplicationOverviewAssets|application-overview/, "Internal provenance and obsolete fallback content are not sent in the client bundle");
    });
  } finally {
    await server.close();
  }
});
