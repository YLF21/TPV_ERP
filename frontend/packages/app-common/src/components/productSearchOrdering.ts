import type { TableSort } from "./tableSorting";

export const defaultProductSearchSort: TableSort<"code"> = { column: "code", direction: "asc" };

/** Natural code order, without changing the source catalogue or its products. */
export function sortProductsByCode<T extends { code?: string | null }>(products: readonly T[], locale = "es"): T[] {
  const collator = new Intl.Collator(locale, { numeric: true, sensitivity: "base" });
  return [...products].sort((left, right) => {
    const leftCode = left.code?.trim() ?? "";
    const rightCode = right.code?.trim() ?? "";
    if (!leftCode || !rightCode) return leftCode ? -1 : rightCode ? 1 : 0;
    return collator.compare(leftCode, rightCode);
  });
}
