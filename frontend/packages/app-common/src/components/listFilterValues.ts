import type { ErpFilterChip } from "./ErpFilterChips";

export type ListFilterValue = string | readonly string[];

export function listFilterValues(value: ListFilterValue | undefined): readonly string[] {
  if (typeof value === "string") return value ? [value] : [];
  return value ?? [];
}

export function appendListFilter(params: URLSearchParams, key: string, value: ListFilterValue | undefined) {
  for (const option of listFilterValues(value)) params.append(key, option);
}

export function compactListFilter(values: string[]): ListFilterValue {
  return values.length > 1 ? values : values[0] ?? "";
}

export function matchesListFilter(values: readonly string[] | undefined, value: string | null | undefined) {
  return !values?.length || (value != null && values.includes(value));
}

export function listFilterChips(
  key: string, label: string, values: readonly string[], onChange: (values: string[]) => void,
  valueLabel: (value: string) => string = value => value,
): ErpFilterChip[] {
  return values.map(value => ({
    key: values.length === 1 ? key : `${key}:${value}`,
    label,
    value: valueLabel(value),
    removeLabel: values.length > 1 ? `${label}: ${valueLabel(value)} ×` : undefined,
    onRemove: () => onChange(values.filter(item => item !== value)),
  }));
}
