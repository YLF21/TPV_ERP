import { useRef, useState, type ReactNode, type Ref } from "react";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import type { TableColumnDefinition } from "./table-layout.mjs";
import "./table-layout.css";

export type DataColumn<Row, Key extends string> = TableColumnDefinition<Key> & {
  label: string; render: (row: Row) => ReactNode; sortKey?: string; align?: "left" | "right";
};
export function SaasDataTable<Row extends { id: string }, Key extends string>({ username, tableKey, label, columns, rows, sort, onSort, onOpen, disabled = false, scrollRef, scrollClassName = "" }: {
  username: string; tableKey: string; label: string; columns: readonly DataColumn<Row, Key>[]; rows: readonly Row[];
  sort: { key: string; direction: "asc" | "desc" }; onSort: (key: string) => void; onOpen: (row: Row) => void; disabled?: boolean;
  scrollRef?: Ref<HTMLDivElement>; scrollClassName?: string;
}) {
  const definitions = columns.map(({ key, defaultWidth, defaultVisible, minWidth }) => ({ key, defaultWidth, defaultVisible, minWidth }));
  const layout = useTableLayoutPreference(username, tableKey, definitions);
  const visible = layout.layout.filter(column => column.visible);
  const byKey = new Map(columns.map(column => [column.key, column]));
  const [selectedId, select] = useState<string | null>(null);
  const selected = rows.some(row => row.id === selectedId) ? selectedId : rows[0]?.id;
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  function focusRow(index: number) {
    const row = rows[Math.min(rows.length - 1, Math.max(0, index))]; if (!row) return;
    select(row.id); rowRefs.current.get(row.id)?.focus({ preventScroll: true }); rowRefs.current.get(row.id)?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }
  const options = layout.layout.map(column => ({ key: column.key, label: byKey.get(column.key)?.label ?? column.key,
    visible: column.visible, disabled: column.visible && visible.length === 1 }));
  return <div ref={scrollRef} className={`saas-data-table-scroll ${scrollClassName}`}><table className="saas-data-table" aria-label={label} style={{ width: visible.reduce((width, column) => width + column.width, 0) }}>
    <colgroup>{visible.map(column => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
    <thead><tr>{visible.map(column => { const definition = byKey.get(column.key)!; return <TableLayoutHeaderCell key={column.key} column={column} label={definition.label}
      sortDirection={definition.sortKey === sort.key ? sort.direction : null} onSort={definition.sortKey ? () => onSort(definition.sortKey!) : undefined}
      options={options} onReorder={layout.reorderColumns} onMove={layout.moveColumn} onResize={layout.resizeColumn} onToggle={layout.toggleColumnVisibility} onReset={layout.reset} />; })}</tr></thead>
    <tbody>{rows.map((row, index) => <tr key={row.id} data-row-id={row.id} aria-selected={selected === row.id} tabIndex={selected === row.id ? 0 : -1}
      ref={element => { if (element) rowRefs.current.set(row.id, element); else rowRefs.current.delete(row.id); }}
      onClick={() => select(row.id)} onDoubleClick={event => { if (!disabled) { event.currentTarget.focus({ preventScroll: true }); onOpen(row); } }}
      onKeyDown={event => {
        if (event.target !== event.currentTarget) return;
        if (event.key === "Enter") { event.preventDefault(); if (!disabled) onOpen(row); }
        else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
          event.preventDefault(); focusRow(event.key === "Home" ? 0 : event.key === "End" ? rows.length - 1 : index + (event.key === "ArrowDown" ? 1 : -1));
        }
      }}>{visible.map(column => { const definition = byKey.get(column.key)!; return <td key={column.key} data-column-key={column.key} className={definition.align === "right" ? "is-numeric" : undefined}>{definition.render(row)}</td>; })}</tr>)}</tbody>
  </table></div>;
}
