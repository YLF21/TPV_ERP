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
    const [{ TourStepPreview }, media, { getTourExamples }] = await Promise.all([
      server.ssrLoadModule("/src/TourStepPreview.tsx"),
      server.ssrLoadModule("/src/tourStepMedia.ts"),
      server.ssrLoadModule("/src/tourExamples.ts")
    ]);

    await suite.test("all three languages have complete and equivalent media text structures", () => {
      const baseline = shape(media.getTourStepMediaContent("es"));
      for (const language of languages) {
        const copy = media.getTourStepMediaContent(language);
        assert.deepEqual(shape(copy), baseline);
        assert.match(copy.overviewNote, {
          es: /vista general de referencia, no de este paso/i,
          en: /general reference screen, not this step/i,
          zh: /参考总览，并非当前步骤截图/
        }[language], "The reference screenshot must not be described as the specific operation");
        assert.deepEqual(Object.keys(copy.steps), applicationIds);
        for (const application of applicationIds) {
          assert.equal(copy.steps[application].length, 3);
          assert.equal(new Set(copy.steps[application]).size, 3);
          assert.ok(copy.overviewAlts[application].includes("esPOS"));
          assert.ok(copy.steps[application].every(text => text.trim().length > 0));
        }
      }
    });

    for (const application of applicationIds) {
      for (const step of [0, 1, 2]) {
        await suite.test(`${application}/${step}: a real existing asset and truthful media status`, async () => {
          const data = media.getTourStepMedia(application, step, "es");
          const capture = media.tourStepCaptureAssets[application][step];
          assert.match(data.src, /^\/marketing\/[a-z0-9/_-]+\.(?:png|jpe?g|webp|avif)$/);
          assert.ok(data.source.length > 0, "Every asset must retain source evidence");
          assert.equal(data.kind, capture ? "step-capture" : "application-overview");
          if (!capture) assert.equal(data.src, media.tourApplicationOverviewAssets[application].src);
          const file = await readFile(new URL(`../public${data.src}`, import.meta.url));
          assert.ok(file.length > 0);
          assert.ok(data.width > 0 && data.height > 0);
          if (data.src.endsWith(".png")) {
            assert.equal(file.toString("hex", 0, 8), "89504e470d0a1a0a");
            assert.equal(file.readUInt32BE(16), data.width, "Explicit width must match the actual PNG");
            assert.equal(file.readUInt32BE(20), data.height, "Explicit height must match the actual PNG");
          }
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
            assert.ok(markup.includes(escapeHtml(data.kind === "step-capture" ? copy.captureNote : copy.overviewNote)));
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
      const hash = bytes => createHash("sha256").update(bytes).digest("hex");
      const overviewHashes = new Set(await Promise.all(Object.values(media.tourApplicationOverviewAssets).map(async asset =>
        hash(await readFile(new URL(`../public${asset.src}`, import.meta.url)))
      )));
      const captureHashes = new Set();
      for (const application of applicationIds) {
        for (const capture of media.tourStepCaptureAssets[application]) {
          if (!capture) continue;
          const fingerprint = hash(await readFile(new URL(`../public${capture.src}`, import.meta.url)));
          assert.ok(!overviewHashes.has(fingerprint), "A general reference is not a newly captured step");
          assert.ok(!captureHashes.has(fingerprint), "Do not present one image as multiple distinct captures");
          captureHashes.add(fingerprint);
        }
      }
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
      const [dialogs, page, loader] = await Promise.all([
        readFile(new URL("../src/MarketingDialogs.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8"),
        readFile(new URL("../src/MarketingDialogLoader.tsx", import.meta.url), "utf8")
      ]);
      assert.match(dialogs, /<TourStepPreview application=\{activeApp\} step=\{step\} language=\{language\}\s*\/>/);
      assert.doesNotMatch(page, /from ["']\.\/(?:TourStepPreview|tourStepMedia|tourExamples)["']/);
      assert.match(loader, /lazy\(\(\) => import\("\.\/MarketingDialogs"\)\)/, "The media stays in the existing on-demand dialog chunk");
    });
  } finally {
    await server.close();
  }
});
