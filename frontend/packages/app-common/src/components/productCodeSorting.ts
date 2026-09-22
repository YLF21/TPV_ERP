import { sortTableRows, type TableSort, type TableSortValue } from "./tableSorting";

// Keep this key aligned with backend catalog/ProductCodeOrder: digit runs are
// compared by length/value, and other runs use case-insensitive binary order.
function productCodeKey(value: TableSortValue): string[] {
  if (value === null || value === undefined || value === "") return [];
  return (String(value).toLowerCase().match(/[0-9]+|[^0-9]+/g) ?? []).map((part) => {
    if (part[0] < "0" || part[0] > "9") return `1${part}`;
    const digits = part.replace(/^0+/, "") || "0";
    return `0${String(digits.length).padStart(10, "0")}${digits}`;
  });
}

function compareCodeKeys(left: string[], right: string[]) {
  for (let index = 0; index < Math.min(left.length, right.length); index++) {
    if (left[index] < right[index]) return -1;
    if (left[index] > right[index]) return 1;
  }
  return left.length - right.length;
}

export function sortProductTableRows<Row, Key extends string>(
  rows: readonly Row[],
  sort: TableSort<Key> | null,
  value: (row: Row, column: Key) => TableSortValue,
  locale = "es"
): Row[] {
  if (sort?.column !== "code") return sortTableRows(rows, sort, value, locale);
  const direction = sort.direction === "asc" ? 1 : -1;
  return rows
    .map((row, index) => ({ row, index, key: productCodeKey(value(row, sort.column)) }))
    .sort((left, right) => {
      if (left.key.length === 0 || right.key.length === 0) {
        if (left.key.length === 0 && right.key.length === 0) return left.index - right.index;
        return left.key.length === 0 ? 1 : -1;
      }
      return compareCodeKeys(left.key, right.key) * direction || left.index - right.index;
    })
    .map(({ row }) => row);
}
