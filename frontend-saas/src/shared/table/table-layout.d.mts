export type TableColumnDefinition<Key extends string = string> = { key: Key; defaultWidth: number; defaultVisible?: boolean; minWidth?: number };
export type TableColumnLayout<Key extends string = string> = { key: Key; width: number; visible: boolean };
export function columnWidth(value: unknown, fallback?: number, minimum?: number): number;
export function normalizeTableLayout<Key extends string>(saved: unknown, definitions: readonly TableColumnDefinition<Key>[]): TableColumnLayout<Key>[];
export function reorderTableColumns<Key extends string>(layout: readonly TableColumnLayout<Key>[], fromKey: Key, toKey: Key): readonly TableColumnLayout<Key>[];
export function moveTableColumn<Key extends string>(layout: readonly TableColumnLayout<Key>[], key: Key, direction: -1 | 1): readonly TableColumnLayout<Key>[];
export function toggleTableColumn<Key extends string>(layout: readonly TableColumnLayout<Key>[], key: Key): readonly TableColumnLayout<Key>[];
export function tableLayoutKey(username: string, tableKey: string): string;
