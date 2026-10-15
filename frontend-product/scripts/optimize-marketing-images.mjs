import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The lossless masters are kept outside public/. Normal builds use the checked-in
// derivatives and do not need Sharp, this script, or a network connection.
const projectRoot = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(import.meta.url);
const manifestPath = "public/marketing/optimized/manifest.json";
const formats = {
  avif: { quality: 70, effort: 4, chromaSubsampling: "4:4:4" },
  webp: { quality: 90, effort: 5, smartSubsample: true },
  jpeg: { quality: 90, mozjpeg: true, chromaSubsampling: "4:4:4", progressive: true },
};
const sources = [
  {
    id: "contact-ecosystem",
    width: 1536,
    height: 1024,
    sha256: "2e9c46e6339bdbd7720ebf927f24833d31a39bc81adefec8899c72d6dc2ba88a",
    responsiveWidths: [480, 768, 1024, 1536],
    jpegWidths: [768, 1536],
  },
  {
    id: "hero-ecosystem",
    width: 865,
    height: 795,
    sha256: "18421de4e08e7e80a646d51411e1694291627bb725a7bc75fb4bb7d24b45f5e6",
    responsiveWidths: [865],
    jpegWidths: [865],
  },
];

function parseArguments(arguments_) {
  const result = { check: false, sharpModule: undefined };
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--check") result.check = true;
    else if (argument === "--sharp-module") {
      result.sharpModule = arguments_[++index];
      if (!result.sharpModule || !path.isAbsolute(result.sharpModule)) {
        throw new Error("--sharp-module requires the absolute path to a trusted installed Sharp module.");
      }
    } else {
      throw new Error(`Unsupported argument: ${argument}. Use --check and/or --sharp-module <absolute path>.`);
    }
  }
  return result;
}

function projectFile(relativePath) {
  const absolutePath = path.resolve(projectRoot, relativePath);
  const relative = path.relative(projectRoot, absolutePath);
  assert(relative && !relative.startsWith("..") && !path.isAbsolute(relative), "Asset must stay inside frontend-product.");
  return absolutePath;
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

async function storeGenerated(relativePath, buffer, check) {
  const absolutePath = projectFile(relativePath);
  if (check) {
    assert.deepEqual(await readFile(absolutePath), buffer, `Generated asset is not reproducible: ${relativePath}`);
    return;
  }
  await mkdir(path.dirname(absolutePath), { recursive: true });
  await writeFile(absolutePath, buffer);
}

async function optimize() {
  const options = parseArguments(process.argv.slice(2));
  let sharp;
  try {
    sharp = require(options.sharpModule ?? "sharp");
  } catch (error) {
    throw new Error("Install Sharp locally or pass --sharp-module <absolute path to trusted installed Sharp>. Normal builds do not require it.", { cause: error });
  }
  // Avoid machine-dependent concurrency or cache state; the encoder versions are
  // recorded below. Reproducibility is checked against the same Sharp toolchain.
  sharp.concurrency(1);
  sharp.cache(false);
  const manifest = {
    schemaVersion: 1,
    generator: {
      script: "scripts/optimize-marketing-images.mjs",
      tool: "sharp",
      version: sharp.versions.sharp,
      encoderVersions: Object.fromEntries(["vips", "aom", "heif", "webp", "mozjpeg"].map((key) => [key, sharp.versions[key]])),
      transform: {
        colourspace: "srgb",
        fit: "inside",
        kernel: "lanczos3",
        withoutEnlargement: true,
        crop: false,
        metadata: "stripped",
      },
      formats,
    },
    images: [],
  };
  for (const source of sources) {
    const sourcePath = `assets/marketing-sources/${source.id}.png`;
    const original = await readFile(projectFile(sourcePath));
    assert.equal(sha256(original), source.sha256, `Unexpected master image: ${sourcePath}`);
    const metadata = await sharp(original).metadata();
    assert.equal(metadata.width, source.width);
    assert.equal(metadata.height, source.height);
    const image = {
      id: source.id,
      source: { path: sourcePath, sha256: source.sha256, width: source.width, height: source.height, bytes: original.length },
      outputs: [],
    };
    for (const [format, encoderOptions] of Object.entries(formats)) {
      for (const width of format === "jpeg" ? source.jpegWidths : source.responsiveWidths) {
        const result = await sharp(original)
          .resize({ width, fit: "inside", kernel: "lanczos3", withoutEnlargement: true })
          .toColourspace("srgb")
          .toFormat(format, encoderOptions)
          .toBuffer({ resolveWithObject: true });
        assert.equal(result.info.width, width);
        assert.equal(result.info.height, Math.round(width * source.height / source.width));
        const filename = `${source.id}-${width}.${format === "jpeg" ? "jpg" : format}`;
        const relativePath = `public/marketing/optimized/${filename}`;
        await storeGenerated(relativePath, result.data, options.check);
        image.outputs.push({
          path: relativePath,
          url: `/marketing/optimized/${filename}`,
          format,
          width: result.info.width,
          height: result.info.height,
          bytes: result.data.length,
          sha256: sha256(result.data),
        });
        console.log(`${options.check ? "Checked" : "Generated"} ${filename}: ${result.info.width} × ${result.info.height}, ${result.data.length} bytes`);
      }
    }
    manifest.images.push(image);
  }
  await storeGenerated(manifestPath, Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`), options.check);
  console.log(`${options.check ? "Checked" : "Generated"} ${manifestPath}`);
}

await optimize();
