import { useCallback, useEffect, useRef, useState } from "react";
import { columnWidth, moveTableColumn, normalizeTableLayout, reorderTableColumns, tableLayoutKey, toggleTableColumn,
  type TableColumnDefinition, type TableColumnLayout } from "./table-layout.mjs";

function readLayout<Key extends string>(identity: string, definitions: readonly TableColumnDefinition<Key>[]) {
  try { return normalizeTableLayout(JSON.parse(localStorage.getItem(identity) ?? "null"), definitions); }
  catch { return normalizeTableLayout(null, definitions); }
}

/** SaaS adapter of APP VENTA's user-scoped table preference pattern. */
export function useTableLayoutPreference<Key extends string>(username: string, tableKey: string, definitions: readonly TableColumnDefinition<Key>[]) {
  const identity = tableLayoutKey(username, tableKey);
  const definitionsRef = useRef(definitions); definitionsRef.current = definitions;
  const signature = JSON.stringify(definitions);
  const [state, setState] = useState(() => ({ identity, layout: readLayout(identity, definitions) }));
  const stateRef = useRef(state); stateRef.current = state;
  useEffect(() => {
    const next = { identity, layout: readLayout(identity, definitionsRef.current) };
    stateRef.current = next; setState(next);
  }, [identity, signature]);
  const update = useCallback((change: (layout: readonly TableColumnLayout<Key>[]) => readonly TableColumnLayout<Key>[]) => {
    const current = stateRef.current;
    if (current.identity !== identity) return;
    const layout = normalizeTableLayout(change(current.layout), definitionsRef.current);
    const next = { identity, layout }; stateRef.current = next; setState(next);
    try { localStorage.setItem(identity, JSON.stringify(layout)); } catch { /* Remains usable if browser storage is disabled. */ }
  }, [identity]);
  return {
    layout: state.identity === identity ? state.layout : readLayout(identity, definitions),
    reorderColumns: (from: Key, to: Key) => update(layout => reorderTableColumns(layout, from, to)),
    moveColumn: (key: Key, direction: -1 | 1) => update(layout => moveTableColumn(layout, key, direction)),
    toggleColumnVisibility: (key: Key) => update(layout => toggleTableColumn(layout, key)),
    resizeColumn: (key: Key, width: number) => update(layout => layout.map(column => column.key === key
      ? { ...column, width: columnWidth(width, column.width, definitionsRef.current.find(definition => definition.key === key)?.minWidth) } : column)),
    reset: () => update(() => normalizeTableLayout(null, definitionsRef.current)),
  };
}
