import type {
  ProductLabelItem,
  ProductLabelPage,
  ProductLabelPlacement,
  ProductLabelProfile,
} from "../hardware/hardware";

export const PRODUCT_LABEL_SNAP_MM = 1;
export const MAX_PRODUCT_LABEL_ITEMS = 200;
export const MAX_PRODUCT_LABEL_PLACEMENTS = 1000;
export const MAX_PRODUCT_LABEL_PAGES = 100;

export function productLabelPageSize(profile: ProductLabelProfile) {
  return profile.orientation === "LANDSCAPE"
    ? { widthMm: 297, heightMm: 210 }
    : { widthMm: 210, heightMm: 297 };
}

export function productLabelMinimumSize(showCompany: boolean) {
  return { widthMm: 35, heightMm: showCompany ? 30 : 24 };
}

export function snapProductLabelMm(value: number) {
  return Math.round(value / PRODUCT_LABEL_SNAP_MM) * PRODUCT_LABEL_SNAP_MM;
}

export function productLabelSafeArea(profile: ProductLabelProfile) {
  const page = productLabelPageSize(profile);
  return {
    leftMm: profile.marginLeftMm,
    topMm: profile.marginTopMm,
    rightMm: page.widthMm - profile.marginRightMm,
    bottomMm: page.heightMm - profile.marginBottomMm,
  };
}

export function clampProductLabelPlacement(
  placement: ProductLabelPlacement,
  profile: ProductLabelProfile,
): ProductLabelPlacement {
  const safe = productLabelSafeArea(profile);
  const minimum = productLabelMinimumSize(profile.showStoreName);
  const widthMm = Math.min(
    safe.rightMm - safe.leftMm,
    Math.max(minimum.widthMm, snapProductLabelMm(placement.widthMm)),
  );
  const heightMm = Math.min(
    safe.bottomMm - safe.topMm,
    Math.max(minimum.heightMm, snapProductLabelMm(placement.heightMm)),
  );
  return {
    ...placement,
    widthMm,
    heightMm,
    xMm: Math.min(
      safe.rightMm - widthMm,
      Math.max(safe.leftMm, snapProductLabelMm(placement.xMm)),
    ),
    yMm: Math.min(
      safe.bottomMm - heightMm,
      Math.max(safe.topMm, snapProductLabelMm(placement.yMm)),
    ),
  };
}

export function productLabelPlacementsOverlap(
  first: ProductLabelPlacement,
  second: ProductLabelPlacement,
  gapMm = 0,
) {
  return first.xMm < second.xMm + second.widthMm + gapMm
    && first.xMm + first.widthMm + gapMm > second.xMm
    && first.yMm < second.yMm + second.heightMm + gapMm
    && first.yMm + first.heightMm + gapMm > second.yMm;
}

export function canPlaceProductLabel(
  placement: ProductLabelPlacement,
  page: ProductLabelPage,
  profile: ProductLabelProfile,
  ignoredInstanceId?: string,
) {
  const clamped = clampProductLabelPlacement(placement, profile);
  if (clamped.xMm !== placement.xMm || clamped.yMm !== placement.yMm
      || clamped.widthMm !== placement.widthMm || clamped.heightMm !== placement.heightMm) {
    return false;
  }
  return page.placements.every((candidate) => candidate.instanceId === ignoredInstanceId
    || !productLabelPlacementsOverlap(placement, candidate));
}

export function productLabelPlacementCounts(pages: ProductLabelPage[]) {
  const counts = new Map<string, number>();
  pages.forEach((page) => page.placements.forEach((placement) => {
    counts.set(placement.itemId, (counts.get(placement.itemId) ?? 0) + 1);
  }));
  return counts;
}

function nextInstanceId(itemId: string, used: Set<string>) {
  let index = 1;
  let candidate = `${itemId}::${index}`;
  while (used.has(candidate)) {
    index += 1;
    candidate = `${itemId}::${index}`;
  }
  used.add(candidate);
  return candidate;
}

function availableGridPlacements(
  itemId: string,
  instanceId: string,
  profile: ProductLabelProfile,
) {
  const safe = productLabelSafeArea(profile);
  const widthMm = profile.widthMm;
  const heightMm = profile.heightMm;
  const columns = Math.floor(
    (safe.rightMm - safe.leftMm + profile.horizontalGapMm)
      / (widthMm + profile.horizontalGapMm),
  );
  const rows = Math.floor(
    (safe.bottomMm - safe.topMm + profile.verticalGapMm)
      / (heightMm + profile.verticalGapMm),
  );
  if (columns < 1 || rows < 1) return [];
  return Array.from({ length: columns * rows }, (_, index): ProductLabelPlacement => ({
    instanceId,
    itemId,
    xMm: safe.leftMm + (index % columns) * (widthMm + profile.horizontalGapMm),
    yMm: safe.topMm + Math.floor(index / columns) * (heightMm + profile.verticalGapMm),
    widthMm,
    heightMm,
  }));
}

export function quickPlaceProductLabels(
  items: ProductLabelItem[],
  inputPages: ProductLabelPage[],
  profile: ProductLabelProfile,
) {
  const totalRequested = items.reduce((sum, item) => sum + item.copies, 0);
  if (items.length > MAX_PRODUCT_LABEL_ITEMS || totalRequested > MAX_PRODUCT_LABEL_PLACEMENTS) {
    throw new Error("PRODUCT_LABEL_LIMIT_EXCEEDED");
  }
  const minimum = productLabelMinimumSize(profile.showStoreName);
  if (profile.widthMm < minimum.widthMm || profile.heightMm < minimum.heightMm) {
    throw new Error("PRODUCT_LABEL_SIZE_TOO_SMALL");
  }
  const pages = (inputPages.length ? inputPages : [{ placements: [] }])
    .map((page) => ({ placements: [...page.placements] }));
  const counts = productLabelPlacementCounts(pages);
  const used = new Set(pages.flatMap((page) => page.placements.map((placement) => placement.instanceId)));
  const pending = items.flatMap((item) => Array.from(
    { length: Math.max(0, item.copies - (counts.get(item.id) ?? 0)) },
    () => ({ itemId: item.id, instanceId: nextInstanceId(item.id, used) }),
  ));

  pending.forEach(({ itemId, instanceId }) => {
    const slots = availableGridPlacements(itemId, instanceId, profile);
    if (slots.length === 0) throw new Error("PRODUCT_LABEL_SIZE_TOO_LARGE");
    let placed = false;
    for (const page of pages) {
      const candidate = slots.find((slot) => page.placements.every((current) =>
        !productLabelPlacementsOverlap(slot, current, Math.min(
          profile.horizontalGapMm,
          profile.verticalGapMm,
        ))));
      if (candidate) {
        page.placements.push(candidate);
        placed = true;
        break;
      }
    }
    if (!placed) {
      if (pages.length >= MAX_PRODUCT_LABEL_PAGES) throw new Error("PRODUCT_LABEL_LIMIT_EXCEEDED");
      pages.push({ placements: [slots[0]] });
    }
  });
  return pages;
}

export function validateProductLabelComposition(
  items: ProductLabelItem[],
  pages: ProductLabelPage[],
  profile: ProductLabelProfile,
) {
  const itemIds = new Set(items.map((item) => item.id));
  const minimum = productLabelMinimumSize(profile.showStoreName);
  const counts = productLabelPlacementCounts(pages);
  if (items.length === 0 || pages.length === 0 || pages.length > MAX_PRODUCT_LABEL_PAGES) return false;
  if (items.length > MAX_PRODUCT_LABEL_ITEMS) return false;
  if (pages.reduce((sum, page) => sum + page.placements.length, 0) > MAX_PRODUCT_LABEL_PLACEMENTS) return false;
  if (items.some((item) => item.copies < 1 || counts.get(item.id) !== item.copies)) return false;
  return pages.every((page) => page.placements.every((placement, index) =>
    itemIds.has(placement.itemId)
    && placement.widthMm >= minimum.widthMm
    && placement.heightMm >= minimum.heightMm
    && canPlaceProductLabel(placement, {
      placements: page.placements.filter((_, candidateIndex) => candidateIndex !== index),
    }, profile)));
}
