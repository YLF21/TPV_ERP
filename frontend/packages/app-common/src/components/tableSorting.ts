import { useCallback, useEffect, useMemo, useState } from "react";
import type { AppKind } from "../types";

export type TableSortDirection = "asc" | "desc";

export type TableSort<Key extends string = string> = {
  column: Key;
  direction: TableSortDirection;
};

export type TableSortValue = string | number | boolean | Date | null | undefined;

type UseTableSortPreferenceOptions<Key extends string> = {
  app: AppKind;
  username: string;
  tableKey: string;
  columns: readonly Key[];
  defaultSort: TableSort<Key> | null;
  persistent?: boolean;
  storage?: Storage;
};

function availableStorage(storage?: Storage): Storage | undefined {
  if (storage) return storage;
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

export function tableSortStorageKey(app: AppKind, username: string, tableKey: string): string {
  return `tpv-erp:${encodeURIComponent(app)}:user:${encodeURIComponent(username.trim().toLowerCase())}:table:${encodeURIComponent(tableKey.trim())}:sort`;
}

export function sanitizeTableSort<Key extends string>(
  value: unknown,
  columns: readonly Key[],
  fallback: TableSort<Key> | null
): TableSort<Key> | null {
  if (!value || typeof value !== "object") return fallback;
  const candidate = value as Partial<TableSort<Key>>;
  if (!candidate.column || !columns.includes(candidate.column)) return fallback;
  return {
    column: candidate.column,
    direction: candidate.direction === "desc" ? "desc" : "asc"
  };
}

export function readStoredTableSort<Key extends string>(
  app: AppKind,
  username: string,
  tableKey: string,
  columns: readonly Key[],
  fallback: TableSort<Key> | null,
  storage?: Storage
): TableSort<Key> | null {
  try {
    const raw = availableStorage(storage)?.getItem(tableSortStorageKey(app, username, tableKey));
    return raw ? sanitizeTableSort(JSON.parse(raw), columns, fallback) : fallback;
  } catch {
    return fallback;
  }
}

export function nextTableSort<Key extends string>(
  current: TableSort<Key> | null,
  column: Key
): TableSort<Key> {
  return {
    column,
    direction: current?.column === column && current.direction === "asc" ? "desc" : "asc"
  };
}

export function compareTableSortValues(
  left: TableSortValue,
  right: TableSortValue,
  locale = "es"
): number {
  const leftMissing = left === null || left === undefined || left === "";
  const rightMissing = right === null || right === undefined || right === "";
  if (leftMissing || rightMissing) {
    if (leftMissing && rightMissing) return 0;
    return leftMissing ? 1 : -1;
  }
  if (left instanceof Date || right instanceof Date) {
    return new Date(left as Date | string | number).getTime() - new Date(right as Date | string | number).getTime();
  }
  if (typeof left === "number" && typeof right === "number") return left - right;
  if (typeof left === "boolean" && typeof right === "boolean") return Number(left) - Number(right);
  return String(left).localeCompare(String(right), locale, { numeric: true, sensitivity: "base" });
}

export function sortTableRows<Row, Key extends string>(
  rows: readonly Row[],
  sort: TableSort<Key> | null,
  value: (row: Row, column: Key) => TableSortValue,
  locale = "es"
): Row[] {
  if (!sort) return [...rows];
  return rows
    .map((row, index) => ({ row, index }))
    .sort((left, right) => {
      const leftValue = value(left.row, sort.column);
      const rightValue = value(right.row, sort.column);
      const leftMissing = leftValue === null || leftValue === undefined || leftValue === "";
      const rightMissing = rightValue === null || rightValue === undefined || rightValue === "";
      if (leftMissing || rightMissing) {
        if (leftMissing && rightMissing) return left.index - right.index;
        return leftMissing ? 1 : -1;
      }
      const result = compareTableSortValues(
        leftValue,
        rightValue,
        locale
      );
      return result === 0
        ? left.index - right.index
        : result * (sort.direction === "asc" ? 1 : -1);
    })
    .map(({ row }) => row);
}

export function useTableSortPreference<Key extends string>({
  app,
  username,
  tableKey,
  columns,
  defaultSort,
  persistent = true,
  storage
}: UseTableSortPreferenceOptions<Key>) {
  const identity = tableSortStorageKey(app, username, tableKey);
  const columnSignature = columns.join("\u001f");
  const fallbackSignature = defaultSort ? `${defaultSort.column}:${defaultSort.direction}` : "none";
  const initialSort = useMemo(
    () => persistent
      ? readStoredTableSort(app, username, tableKey, columns, defaultSort, storage)
      : defaultSort,
    [app, columnSignature, fallbackSignature, identity, persistent, storage, tableKey, username]
  );
  const [state, setState] = useState<{ identity: string; sort: TableSort<Key> | null }>({
    identity,
    sort: initialSort
  });
  const sort = state.identity === identity ? state.sort : initialSort;

  useEffect(() => {
    setState({ identity, sort: initialSort });
  }, [identity, initialSort]);

  const setSort = useCallback((next: TableSort<Key> | null | ((current: TableSort<Key> | null) => TableSort<Key> | null)) => {
    setState((currentState) => {
      const current = currentState.identity === identity ? currentState.sort : initialSort;
      const resolved = typeof next === "function" ? next(current) : next;
      const sanitized = sanitizeTableSort(resolved, columns, defaultSort);
      if (persistent) {
        try {
          availableStorage(storage)?.setItem(identity, JSON.stringify(sanitized));
        } catch {
          // Sorting remains available for this session when storage is unavailable.
        }
      }
      return { identity, sort: sanitized };
    });
  }, [columns, defaultSort, identity, initialSort, persistent, storage]);

  const toggleSort = useCallback((column: Key) => {
    setSort((current) => nextTableSort(current, column));
  }, [setSort]);

  return { sort, setSort, toggleSort };
}
