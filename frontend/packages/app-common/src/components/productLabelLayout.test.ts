import { describe, expect, it } from "vitest";
import { defaultHardwareConfig, type ProductLabelItem } from "../hardware/hardware";
import {
  canPlaceProductLabel,
  quickPlaceProductLabels,
  validateProductLabelComposition,
} from "./productLabelLayout";
import { productLabelEanBits } from "./productLabelBarcode";

const profile = {
  ...defaultHardwareConfig.productLabelProfiles[0],
  destination: "A4" as const,
  widthMm: 58,
  heightMm: 40,
};

const items: ProductLabelItem[] = [
  { id: "one", copies: 2, product: { name: "One", code: "1", barcode: "4006381333931", price: 1 } },
  { id: "two", copies: 1, product: { name: "Two", code: "2", barcode: "8435606744034", price: 2 } },
];

describe("product label A4 layout", () => {
  it("uses the real EAN modules in the visual preview", () => {
    expect(productLabelEanBits("73513537")).toHaveLength(67);
    expect(productLabelEanBits("4006381333931")).toHaveLength(95);
  });

  it("places every pending copy without replacing manual placements", () => {
    const manual = {
      instanceId: "one::manual",
      itemId: "one",
      xMm: 5,
      yMm: 5,
      widthMm: 58,
      heightMm: 40,
    };
    const pages = quickPlaceProductLabels(items, [{ placements: [manual] }], profile);

    expect(pages.flatMap((page) => page.placements)).toHaveLength(3);
    expect(pages[0].placements).toContainEqual(manual);
    expect(validateProductLabelComposition(items, pages, profile)).toBe(true);
  });

  it("creates additional pages when the current sheet has no capacity", () => {
    const largeProfile = { ...profile, widthMm: 100, heightMm: 90 };
    const many = [{ ...items[0], copies: 6 }];
    const pages = quickPlaceProductLabels(many, [{ placements: [] }], largeProfile);

    expect(pages).toHaveLength(2);
    expect(pages.flatMap((page) => page.placements)).toHaveLength(6);
  });

  it("rejects overlaps, incomplete layouts and labels below the safe size", () => {
    const first = { instanceId: "one::1", itemId: "one", xMm: 5, yMm: 5, widthMm: 58, heightMm: 40 };
    const overlapping = { instanceId: "one::2", itemId: "one", xMm: 10, yMm: 10, widthMm: 58, heightMm: 40 };
    expect(canPlaceProductLabel(overlapping, { placements: [first] }, profile)).toBe(false);
    expect(validateProductLabelComposition(items, [{ placements: [first] }], profile)).toBe(false);
    expect(() => quickPlaceProductLabels(items, [{ placements: [] }], { ...profile, widthMm: 20 }))
      .toThrow("PRODUCT_LABEL_SIZE_TOO_SMALL");
  });
});
