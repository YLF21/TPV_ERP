import { apiRequest } from "../api/client";
import type { AppKind } from "../types";

export const TABLE_COLUMN_MIN_WIDTH = 56;
export const TABLE_COLUMN_MAX_WIDTH = 800;

export type TableColumnDefinition<Key extends string = string> = {
  key: Key;
  defaultWidth: number;
  defaultVisible?: boolean;
};

export type TableColumnLayout<Key extends string = string> = {
  key: Key;
  width: number;
  visible: boolean;
};

export type TableLayout<Key extends string = string> = readonly TableColumnLayout<Key>[];

export type TableColumnPreference<Key extends string = string> = {
  key: Key;
  width?: number;
  visible?: boolean;
};

export type TablePreference<Key extends string = string> = {
  tableKey: string;
  columns: readonly TableColumnPreference<Key>[];
};

export type TablePreferenceResponse<Key extends string = string> = TablePreference<Key> & {
  app: AppKind;
};

export type TablePreferencesResponse<Key extends string = string> = {
  app: AppKind;
  preferences: readonly TablePreference<Key>[];
};

export type TableColumnMoveDirection = -1 | 1;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function uniqueDefinitions<Key extends string>(
  definitions: readonly TableColumnDefinition<Key>[]
): TableColumnDefinition<Key>[] {
  const seen = new Set<string>();
  return definitions.filter((definition) => {
    if (typeof definition.key !== "string" || definition.key.trim() === "" || seen.has(definition.key)) {
      return false;
    }
    seen.add(definition.key);
    return true;
  });
}

function ensureOneVisible<Key extends string>(layout: TableLayout<Key>): TableLayout<Key> {
  if (layout.length === 0 || layout.some((column) => column.visible)) {
    return layout;
  }
  return layout.map((column, index) => index === 0 ? { ...column, visible: true } : column);
}

export function clampTableColumnWidth(width: unknown, fallback = TABLE_COLUMN_MIN_WIDTH): number {
  const safeFallback = typeof fallback === "number" && Number.isFinite(fallback)
    ? Math.min(TABLE_COLUMN_MAX_WIDTH, Math.max(TABLE_COLUMN_MIN_WIDTH, Math.round(fallback)))
    : TABLE_COLUMN_MIN_WIDTH;
  if (typeof width !== "number" || !Number.isFinite(width)) {
    return safeFallback;
  }
  return Math.min(TABLE_COLUMN_MAX_WIDTH, Math.max(TABLE_COLUMN_MIN_WIDTH, Math.round(width)));
}

export function createDefaultTableLayout<Key extends string>(
  definitions: readonly TableColumnDefinition<Key>[]
): TableLayout<Key> {
  const layout = uniqueDefinitions(definitions).map((definition) => ({
    key: definition.key,
    width: clampTableColumnWidth(definition.defaultWidth),
    visible: definition.defaultVisible !== false
  }));
  return ensureOneVisible(layout);
}

export function sanitizeSavedTableLayout<Key extends string>(
  saved: unknown,
  definitions: readonly TableColumnDefinition<Key>[]
): TableLayout<Key> {
  const defaults = createDefaultTableLayout(definitions);
  const defaultsByKey = new Map<string, TableColumnLayout<Key>>(
    defaults.map((column) => [column.key, column])
  );
  const seen = new Set<string>();
  const sanitized: TableColumnLayout<Key>[] = [];

  if (Array.isArray(saved)) {
    for (const candidate of saved) {
      if (!isRecord(candidate) || typeof candidate.key !== "string" || seen.has(candidate.key)) {
        continue;
      }
      const fallback = defaultsByKey.get(candidate.key);
      if (!fallback) {
        continue;
      }
      seen.add(candidate.key);
      sanitized.push({
        key: fallback.key,
        width: clampTableColumnWidth(candidate.width, fallback.width),
        visible: typeof candidate.visible === "boolean" ? candidate.visible : fallback.visible
      });
    }
  }

  for (const column of defaults) {
    if (!seen.has(column.key)) {
      sanitized.push(column);
    }
  }

  return ensureOneVisible(sanitized);
}

export function reorderTableColumns<Key extends string>(
  layout: TableLayout<Key>,
  draggedKey: Key,
  targetKey: Key
): TableLayout<Key> {
  if (draggedKey === targetKey) {
    return layout;
  }
  const from = layout.findIndex((column) => column.key === draggedKey);
  const to = layout.findIndex((column) => column.key === targetKey);
  if (from < 0 || to < 0) {
    return layout;
  }
  const next = [...layout];
  const [dragged] = next.splice(from, 1);
  next.splice(to, 0, dragged);
  return next;
}

export function moveTableColumnByKeyboard<Key extends string>(
  layout: TableLayout<Key>,
  columnKey: Key,
  direction: TableColumnMoveDirection
): TableLayout<Key> {
  const from = layout.findIndex((column) => column.key === columnKey);
  if (from < 0) {
    return layout;
  }
  const to = Math.min(layout.length - 1, Math.max(0, from + direction));
  if (from === to) {
    return layout;
  }
  const next = [...layout];
  const [column] = next.splice(from, 1);
  next.splice(to, 0, column);
  return next;
}

export function resizeTableColumn<Key extends string>(
  layout: TableLayout<Key>,
  columnKey: Key,
  width: number
): TableLayout<Key> {
  const index = layout.findIndex((column) => column.key === columnKey);
  if (index < 0) {
    return layout;
  }
  const nextWidth = clampTableColumnWidth(width, layout[index].width);
  if (nextWidth === layout[index].width) {
    return layout;
  }
  const next = [...layout];
  next[index] = { ...next[index], width: nextWidth };
  return next;
}

export function toggleTableColumnVisibility<Key extends string>(
  layout: TableLayout<Key>,
  columnKey: Key
): TableLayout<Key> {
  const index = layout.findIndex((column) => column.key === columnKey);
  if (index < 0) {
    return layout;
  }
  const column = layout[index];
  if (column.visible && layout.filter((candidate) => candidate.visible).length <= 1) {
    return layout;
  }
  const next = [...layout];
  next[index] = { ...column, visible: !column.visible };
  return next;
}

export function visibleTableColumns<Key extends string>(layout: TableLayout<Key>): TableLayout<Key> {
  const visible = layout.filter((column) => column.visible);
  return visible.length > 0 ? visible : layout.slice(0, 1);
}

export function tableLayoutGridTemplate(layout: TableLayout): string {
  return visibleTableColumns(layout)
    .map((column) => `${clampTableColumnWidth(column.width)}px`)
    .join(" ");
}

function availableStorage(storage?: Storage): Storage | undefined {
  if (storage) {
    return storage;
  }
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

export function tableLayoutStorageKey(app: AppKind, username: string, tableKey: string): string {
  const normalizedUsername = username.trim().toLowerCase();
  return `tpv-erp:${encodeURIComponent(app)}:user:${encodeURIComponent(normalizedUsername)}:table:${encodeURIComponent(tableKey.trim())}:layout`;
}

export function readStoredTableLayout<Key extends string>(
  app: AppKind,
  username: string,
  tableKey: string,
  definitions: readonly TableColumnDefinition<Key>[],
  storage?: Storage
): TableLayout<Key> {
  try {
    const raw = availableStorage(storage)?.getItem(tableLayoutStorageKey(app, username, tableKey));
    return raw ? sanitizeSavedTableLayout(JSON.parse(raw), definitions) : createDefaultTableLayout(definitions);
  } catch {
    return createDefaultTableLayout(definitions);
  }
}

export function writeStoredTableLayout(
  app: AppKind,
  username: string,
  tableKey: string,
  layout: TableLayout,
  storage?: Storage
): void {
  try {
    availableStorage(storage)?.setItem(
      tableLayoutStorageKey(app, username, tableKey),
      JSON.stringify(layout)
    );
  } catch {
    // The in-memory layout remains usable when browser storage is unavailable.
  }
}

export function loadAllTablePreferences(
  app: AppKind,
  token: string
): Promise<TablePreferencesResponse> {
  return apiRequest<TablePreferencesResponse>(
    `/ui/table-preferences/${encodeURIComponent(app)}`,
    { token }
  );
}

export function loadTablePreference<Key extends string = string>(
  app: AppKind,
  tableKey: string,
  token: string
): Promise<TablePreferenceResponse<Key>> {
  return apiRequest<TablePreferenceResponse<Key>>(
    `/ui/table-preferences/${encodeURIComponent(app)}/${encodeURIComponent(tableKey)}`,
    { token }
  );
}

export function saveTablePreference<Key extends string = string>(
  app: AppKind,
  tableKey: string,
  columns: readonly TableColumnPreference<Key>[],
  token: string
): Promise<TablePreferenceResponse<Key>> {
  return apiRequest<TablePreferenceResponse<Key>>(
    `/ui/table-preferences/${encodeURIComponent(app)}/${encodeURIComponent(tableKey)}`,
    {
      method: "PUT",
      token,
      body: { app, tableKey, columns }
    }
  );
}
