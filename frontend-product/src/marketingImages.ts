const responsiveWidths = [480, 768, 1024, 1536] as const;
const optimizedDirectory = "/marketing/optimized";

function contactSrcSet(format: "avif" | "webp") {
  return responsiveWidths.map((width) => `${optimizedDirectory}/contact-ecosystem-${width}.${format} ${width}w`).join(", ");
}

// Keep each format on the same full-frame 3:2 composition. The browser chooses
// one supported format and one size; the original multi-megabyte PNG is not a fallback.
export const marketingImages = {
  contactEcosystem: {
    src: `${optimizedDirectory}/contact-ecosystem-1536.jpg`,
    width: 1536,
    height: 1024,
    avifSrcSet: contactSrcSet("avif"),
    webpSrcSet: contactSrcSet("webp"),
    fallbackSrcSet: `${optimizedDirectory}/contact-ecosystem-768.jpg 768w, ${optimizedDirectory}/contact-ecosystem-1536.jpg 1536w`
  }
} as const;

export type MarketingImageId = keyof typeof marketingImages;

// Match the existing stacked hero (16/60px total horizontal padding) and the
// desktop half-width column with its clamped 4vw / 64px padding on either side.
export const marketingImageSizes = {
  home: "(max-width: 760px) calc(100vw - 16px), (max-width: 1120px) calc(100vw - 60px), (max-width: 1600px) 42vw, calc(50vw - 128px)",
  contact: "100vw"
} as const;
