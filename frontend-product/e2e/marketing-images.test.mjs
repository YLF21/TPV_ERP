import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { lstat, readFile, readdir, realpath } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const publicRoot = path.join(root, "public");
const optimizedRoot = path.join(publicRoot, "marketing", "optimized");
const languages = ["es", "en", "zh"];
const routes = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];
const hash = data => createHash("sha256").update(data).digest("hex");
const originals = {
  "contact-ecosystem": { bytes: 1821981, width: 1536, height: 1024, sha256: "2e9c46e6339bdbd7720ebf927f24833d31a39bc81adefec8899c72d6dc2ba88a" },
  "hero-ecosystem": { bytes: 777623, width: 865, height: 795, sha256: "18421de4e08e7e80a646d51411e1694291627bb725a7bc75fb4bb7d24b45f5e6" }
};
// These hashes were captured before this image-only change, not generated from
// the manifest. QR readability and established product imagery are not altered.
const unchangedAssets = {
  "branding/espos-icon.ico": "bb7d24ea4e9ad6f515a225b1494a1887084f6588d5876a7892e99db6d1ad70a6",
  "branding/espos-wordmark.png": "e1bd3e449786162949c8545d7fde921e5cf3b78a7614be02654637d992ae4674",
  "contact/wechat-espos.jpg": "db44f69c9781c86f58b6430f25d1f8ba2c0ed32422a22f16c703e6a53d44ee7d",
  "marketing/tour-gestion.png": "366d03b76e824080fdcffd92fd466a143f27661c4a8cc00d221e722550e604d4",
  "marketing/tour-pda.png": "b72712f7cb94bb1e78c28a61c1ecfd668fab5b7c6f694fd49a0cb392b143f09c",
  "marketing/tour-saas.png": "05132f32c2608b70928a9323e84ee876863fb20b1f5a299650388dce9fd53111",
  "marketing/tour-venta.png": "5ce789465e95e1d8f16c642cc45fde107645058034f5c3af8b87fe02d052d1b0"
};

async function localFile(relative, directory = root) {
  assert.equal(typeof relative, "string");
  assert.match(relative, /^[a-zA-Z0-9_./-]+$/);
  assert.ok(!path.isAbsolute(relative) && !relative.split("/").includes(".."), `Unsafe local asset path: ${relative}`);
  const absolute = path.resolve(directory, relative);
  const resolvedRoot = await realpath(directory);
  const resolvedFile = await realpath(absolute);
  const child = path.relative(resolvedRoot, resolvedFile);
  assert.ok(child && child !== ".." && !child.startsWith(`..${path.sep}`) && !path.isAbsolute(child), `Asset escapes its directory: ${relative}`);
  const stat = await lstat(absolute);
  assert.ok(stat.isFile() && !stat.isSymbolicLink(), `Expected a regular local image: ${relative}`);
  return readFile(absolute);
}

// Bounded header readers intentionally avoid a platform decoder dependency.
// They verify file type and declared dimensions, not perceptual image quality.
function imageDimensions(data, format) {
  if (format === "png") {
    assert.ok(data.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])));
    assert.equal(data.toString("ascii", 12, 16), "IHDR");
    return { width: data.readUInt32BE(16), height: data.readUInt32BE(20) };
  }
  if (format === "jpeg") {
    assert.equal(data.readUInt16BE(0), 0xffd8);
    assert.equal(data.readUInt16BE(data.length - 2), 0xffd9);
    let offset = 2;
    while (offset + 4 < data.length) {
      assert.equal(data[offset], 0xff, "Invalid JPEG marker");
      while (data[offset] === 0xff) offset += 1;
      const marker = data[offset++];
      if (marker === 0xd9 || marker === 0xda) break;
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
      const length = data.readUInt16BE(offset);
      assert.ok(length >= 2 && offset + length <= data.length);
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
        assert.ok(length >= 8);
        return { width: data.readUInt16BE(offset + 5), height: data.readUInt16BE(offset + 3) };
      }
      offset += length;
    }
  }
  if (format === "webp") {
    assert.equal(data.toString("ascii", 0, 4), "RIFF");
    assert.equal(data.toString("ascii", 8, 12), "WEBP");
    assert.equal(data.readUInt32LE(4) + 8, data.length);
    let offset = 12;
    while (offset + 8 <= data.length) {
      const type = data.toString("ascii", offset, offset + 4);
      const length = data.readUInt32LE(offset + 4);
      const start = offset + 8;
      assert.ok(start + length <= data.length);
      if (type === "VP8X") {
        assert.ok(length >= 10);
        return { width: data.readUIntLE(start + 4, 3) + 1, height: data.readUIntLE(start + 7, 3) + 1 };
      }
      if (type === "VP8 ") {
        assert.ok(length >= 10 && data.subarray(start + 3, start + 6).equals(Buffer.from([0x9d, 0x01, 0x2a])));
        return { width: data.readUInt16LE(start + 6) & 0x3fff, height: data.readUInt16LE(start + 8) & 0x3fff };
      }
      if (type === "VP8L") {
        assert.ok(length >= 5 && data[start] === 0x2f);
        const bits = data.readUInt32LE(start + 1);
        return { width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 };
      }
      offset = start + length + (length % 2);
    }
  }
  if (format === "avif") {
    assert.equal(data.toString("ascii", 4, 8), "ftyp");
    const ftypEnd = data.readUInt32BE(0);
    assert.ok(ftypEnd >= 16 && ftypEnd <= data.length);
    assert.ok(/avif|avis/.test(data.toString("ascii", 8, ftypEnd)), "AVIF-compatible brand is required");
    const dimensions = [];
    function boxes(start, end, depth = 0) {
      assert.ok(depth < 10);
      while (start + 8 <= end) {
        let size = data.readUInt32BE(start);
        const type = data.toString("ascii", start + 4, start + 8);
        let header = 8;
        if (size === 1) {
          assert.ok(start + 16 <= end);
          const extended = data.readBigUInt64BE(start + 8);
          assert.ok(extended <= BigInt(Number.MAX_SAFE_INTEGER));
          size = Number(extended);
          header = 16;
        } else if (size === 0) size = end - start;
        assert.ok(size >= header && start + size <= end, `Invalid AVIF ${type} box`);
        const payload = start + header;
        if (type === "ispe") {
          assert.ok(size >= header + 12);
          dimensions.push({ width: data.readUInt32BE(payload + 4), height: data.readUInt32BE(payload + 8) });
        } else if (["meta", "iprp", "ipco"].includes(type)) {
          boxes(payload + (type === "meta" ? 4 : 0), start + size, depth + 1);
        }
        start += size;
      }
    }
    boxes(0, data.length);
    // A grid-encoded AVIF may contain tile extents as well as full-image extents.
    if (dimensions.length) return dimensions.sort((a, b) => b.width * b.height - a.width * a.height)[0];
  }
  assert.fail(`No valid ${format} dimensions found`);
}

function attributes(source) {
  return Object.fromEntries([...source.matchAll(/([\w:-]+)(?:="([^"]*)")?/g)].map(match => [match[1].toLowerCase(), match[2] ?? ""]));
}
function tags(markup, name) {
  return [...markup.matchAll(new RegExp(`<${name}\\b([^>]*)>`, "g"))].map(match => attributes(match[1]));
}
function sourceSet(value) {
  assert.ok(value);
  return value.split(",").map(candidate => {
    const match = candidate.trim().match(/^(\/marketing\/optimized\/[a-z0-9.-]+) (\d+)w$/);
    assert.ok(match, `Invalid responsive source: ${candidate}`);
    return { url: match[1], width: Number(match[2]) };
  });
}
function withGlobals(values) {
  const previous = new Map(Object.keys(values).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(values)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  return () => {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  };
}

test("optimized image files preserve originals and enforce actual local byte/dimension budgets", async suite => {
  const manifest = JSON.parse(await localFile("public/marketing/optimized/manifest.json"));
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.generator.tool, "sharp");
  assert.deepEqual(manifest.images.map(image => image.id).sort(), Object.keys(originals).sort());
  const expectedOutputPaths = new Set();
  for (const image of manifest.images) {
    await suite.test(`${image.id}: source integrity and generated representations`, async () => {
      const baseline = originals[image.id];
      assert.equal(image.source.path, `assets/marketing-sources/${image.id}.png`);
      const source = await localFile(image.source.path);
      assert.equal(source.length, baseline.bytes);
      assert.equal(image.source.bytes, baseline.bytes);
      assert.equal(hash(source), baseline.sha256);
      assert.equal(image.source.sha256, baseline.sha256);
      assert.deepEqual(imageDimensions(source, "png"), { width: baseline.width, height: baseline.height });
      assert.equal(image.source.width, baseline.width);
      assert.equal(image.source.height, baseline.height);
      await assert.rejects(lstat(path.join(publicRoot, "marketing", `${image.id}.png`)), { code: "ENOENT" });
      const expected = image.id === "contact-ecosystem"
        ? { avif: [480, 768, 1024, 1536], webp: [480, 768, 1024, 1536], jpeg: [768, 1536] }
        : { avif: [865], webp: [865], jpeg: [865] };
      for (const [format, widths] of Object.entries(expected)) {
        assert.deepEqual(image.outputs.filter(output => output.format === format).map(output => output.width).sort((a, b) => a - b), widths);
      }
      assert.equal(image.outputs.length, Object.values(expected).flat().length);
      const largestBytes = {};
      for (const output of image.outputs) {
        const extension = output.format === "jpeg" ? "jpg" : output.format;
        const filename = `${image.id}-${output.width}.${extension}`;
        assert.equal(output.path, `public/marketing/optimized/${filename}`);
        assert.equal(output.url, `/marketing/optimized/${filename}`);
        assert.ok(!expectedOutputPaths.has(filename), "Each generated file is listed exactly once");
        expectedOutputPaths.add(filename);
        const data = await localFile(output.path);
        assert.equal(data.length, output.bytes);
        assert.equal(hash(data), output.sha256);
        assert.deepEqual(imageDimensions(data, output.format), { width: output.width, height: output.height });
        assert.ok(Number.isSafeInteger(output.width) && output.width > 0 && output.width <= baseline.width, "No upscaling is allowed");
        assert.equal(output.height, Math.round(output.width * baseline.height / baseline.width), "The full original aspect ratio is retained");
        const budget = { avif: 130000, webp: 220000, jpeg: 380000 }[output.format];
        assert.ok(data.length < budget && data.length < baseline.bytes, `Exceeded ${output.format} image byte budget: ${filename}`);
        largestBytes[output.format] = Math.max(largestBytes[output.format] ?? 0, data.length);
      }
      assert.ok(largestBytes.avif < baseline.bytes * 0.1, "Full-size AVIF must reduce original bytes by at least 90%");
      assert.ok(largestBytes.webp < baseline.bytes * 0.15, "Full-size WebP must reduce original bytes by at least 85%");
      assert.ok(largestBytes.jpeg < baseline.bytes * 0.25, "Compatible JPEG fallback must reduce original bytes by at least 75%");
    });
  }
  assert.deepEqual((await readdir(optimizedRoot)).sort(), ["manifest.json", ...expectedOutputPaths].sort(), "No stale masters or untracked generated variants are shipped");
});

test("brand, QR and actual application previews remain byte-identical", async () => {
  for (const [relative, expected] of Object.entries(unchangedAssets)) {
    assert.equal(hash(await localFile(relative, publicRoot)), expected, `Unexpected change to ${relative}`);
  }
});

test("responsive marketing image markup is route-specific and localized without browser or HTTP", async suite => {
  const server = await createServer({
    configFile: false, root, plugins: [react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  let requestCount = 0;
  let callbackCount = 0;
  const location = { origin: "http://ssr.invalid", pathname: "/", search: "", hash: "" };
  const restore = withGlobals({
    window: { location }, document: { referrer: "" },
    sessionStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
    fetch: () => { requestCount += 1; throw new Error("Image SSR tests must not perform requests"); }
  });
  try {
    assert.equal(server.httpServer, null, "Pure SSR must never acquire a listening HTTP server");
    const [{ default: MarketingPage }, { getMarketingContent }] = await Promise.all([
      server.ssrLoadModule("/src/MarketingPage.tsx"), server.ssrLoadModule("/src/marketingI18n.ts")
    ]);
    const manifest = JSON.parse(await localFile("public/marketing/optimized/manifest.json"));
    const outputs = manifest.images.find(image => image.id === "contact-ecosystem").outputs;
    const render = (language, route) => {
      location.pathname = `/${language}/producto/${route}/`;
      return renderToStaticMarkup(createElement(MarketingPage, { language, onLanguageChange: () => { callbackCount += 1; } }));
    };
    for (const language of languages) {
      for (const route of routes) {
        await suite.test(`${language}/${route}: responsive sources, dimensions and loading scope`, () => {
          const markup = render(language, route);
          const pictures = [...markup.matchAll(/<picture\b[^>]*>([\s\S]*?)<\/picture>/g)];
          assert.equal(pictures.length, ["inicio", "contacto"].includes(route) ? 1 : 0);
          assert.doesNotMatch(markup, /(?:hero|contact)-ecosystem\.png|hero-ecosystem-865/, "Unoptimized originals and unused hero must not be loaded");
          assert.equal(tags(markup, "link").filter(link => link.rel === "preload" && link.as === "image" && /ecosystem/.test(`${link.href ?? ""} ${link.imagesrcset ?? ""}`)).length, 0, "No competing fallback/format-specific scene preload is introduced; React may preload the existing logo/QR");
          if (!pictures.length) {
            assert.doesNotMatch(markup, /contact-ecosystem-/, "Unrelated routes do not carry the large scene reference");
            return;
          }
          const picture = pictures[0][1];
          const sources = tags(picture, "source");
          assert.deepEqual(sources.map(source => source.type), ["image/avif", "image/webp"]);
          const images = tags(picture, "img");
          assert.equal(images.length, 1);
          const image = images[0];
          assert.equal(image.src, "/marketing/optimized/contact-ecosystem-1536.jpg");
          assert.equal(image.width, "1536");
          assert.equal(image.height, "1024");
          assert.equal(image.decoding, "async");
          assert.ok(image.sizes && image.sizes.trim().length > 0);
          for (const [format, source] of [["avif", sources[0]], ["webp", sources[1]], ["jpeg", image]]) {
            const candidates = sourceSet(source.srcset);
            const expected = outputs.filter(output => output.format === format).map(output => ({ url: output.url, width: output.width })).sort((a, b) => a.width - b.width);
            assert.deepEqual(candidates, expected);
            assert.equal(source.sizes, image.sizes, "All format choices share a single layout sizing policy");
          }
          if (route === "inicio") {
            assert.equal(image.loading, "eager");
            assert.equal(image.fetchpriority, "high");
            assert.equal(image.alt, `esPOS · ${getMarketingContent(language).common.applications}`);
            assert.ok(!Object.hasOwn(image, "aria-hidden"));
          } else {
            assert.equal(image.loading, "lazy");
            assert.equal(image.fetchpriority, "low");
            assert.equal(image.alt, "");
            assert.equal(image["aria-hidden"], "true");
            assert.ok(/\bmk-contact-scene\b/.test(image.class));
          }
          assert.equal(requestCount, 0);
          assert.equal(callbackCount, 0);
        });
      }
    }
  } finally {
    restore();
    await server.close();
  }
});
