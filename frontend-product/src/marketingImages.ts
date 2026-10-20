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

// The compact hero stacks below 960px; its two desktop columns live in a
// centered, padded 1440px container rather than growing with the full viewport.
export const marketingImageSizes = {
  home: "(max-width: 760px) calc(100vw - 32px), (max-width: 959px) 92vw, (max-width: 1440px) calc(46vw - 14px), 650px",
  contact: "100vw"
} as const;
