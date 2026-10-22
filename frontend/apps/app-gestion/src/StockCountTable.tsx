import { sortTableRows, useTableSortPreference } from "../../../packages/app-common/src/components/tableSorting";
import type { ReactNode } from "react";
import { useRef } from "react";
import type { UserSession } from "../../../packages/app-common/src/types";
import { TableLayoutHeaderCell } from "../../../packages/app-common/src/components/TableLayoutHeaderCell";
import { visibleTableColumns } from "../../../packages/app-common/src/components/tableLayoutPreferences";
import { useTableLayoutPreference } from "../../../packages/app-common/src/components/useTableLayoutPreference";
import { useTableArrowRepeat } from "../../../packages/app-common/src/components/useTableArrowRepeat";
import { ensureTableRowVisible } from "../../../packages/app-common/src/components/ensureTableRowVisible";
import { useTableInitialFocus } from "../../../packages/app-common/src/components/useTableInitialFocus";
import "../../../packages/app-common/src/components/WarehouseClassicTables.css";

export function StockCountTable<T extends { id: string }>({ rows, columns, cell, session, tableKey, empty, selected, onSelect, onOpen, rowClass, sortValue, limit, locale = "es" }: {
  sortValue?: (row: T, key: string) => string | number | null | undefined; limit?: number; locale?: string;
  rows: T[]; columns: { key: string; label: string; defaultWidth: number }[]; cell: (row: T, key: string) => ReactNode;
  session: UserSession; tableKey: string; empty: string; selected?: string; onSelect?: (id: string) => void; onOpen?: (id: string) => void; rowClass?: (row: T) => string;
}) {
  const layout = useTableLayoutPreference({ app: "gestion", username: session.username, accessToken: session.accessToken, tableKey, definitions: columns });
  const tableSort = useTableSortPreference({ app: "gestion", username: session.username, tableKey, columns: columns.map(c => c.key), defaultSort: null });
  const sortedRows = (sortValue ? sortTableRows(rows, tableSort.sort, sortValue, locale) : rows).slice(0, limit);
  const visible = visibleTableColumns(layout.layout);
  const scrollRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  useTableInitialFocus(scrollRef, "tbody tr[data-stock-count-row]", {
    enabled: Boolean(onSelect), ready: sortedRows.length > 0, scopeKey: tableKey,
    onSelect: (row) => { if (row.dataset.stockCountRow) onSelect?.(row.dataset.stockCountRow); }
  });
  const handleArrowRepeat = useTableArrowRepeat((direction) => {
    if (!onSelect) return false;
    const current = document.activeElement;
    const index = sortedRows.findIndex((row) => rowRefs.current.get(row.id) === current);
    const next = index + (direction === "ArrowDown" ? 1 : -1);
    if (index < 0 || next < 0 || next >= sortedRows.length) return false;
    const node = rowRefs.current.get(sortedRows[next].id);
    if (!node) return false;
    node.focus({ preventScroll: true });
    const container = scrollRef.current;
    if (container) ensureTableRowVisible(container, node);
    return true;
  }, Boolean(onSelect));
  const label = (key: string) => columns.find((column) => column.key === key)?.label ?? key;
  return <div ref={(node) => { scrollRef.current = node?.closest<HTMLDivElement>(".inventory-list-scroll") ?? node; }} className="inventory-table erp-classic-tables warehouse-classic-table"><table className="report-table" style={{ minWidth: visible.reduce((sum, column) => sum + column.width, 0) }}>
    <colgroup>{visible.map((column) => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
    <thead><tr>{visible.map((column) => <TableLayoutHeaderCell key={column.key} column={column} resizeLabel={label(column.key)}
      sortDirection={tableSort.sort?.column === column.key ? tableSort.sort.direction : null} sortLabel={label(column.key)} onSort={sortValue ? tableSort.toggleSort : undefined}
      onReorder={layout.reorderColumns} onMove={layout.moveColumn} onResize={layout.resizeColumn} onToggleVisibility={layout.toggleColumnVisibility}
      columnVisibilityOptions={layout.layout.map((item) => ({ key: item.key, label: label(item.key), visible: item.visible }))}>{label(column.key)}</TableLayoutHeaderCell>)}</tr></thead>
    <tbody>{sortedRows.map((row) => <tr key={row.id} data-stock-count-row={row.id} ref={(node) => { if (node) rowRefs.current.set(row.id, node); else rowRefs.current.delete(row.id); }} tabIndex={onSelect ? 0 : undefined} aria-selected={selected === row.id}
      className={`${selected === row.id ? "selected" : ""} ${rowClass?.(row) ?? ""}`} onClick={() => onSelect?.(row.id)} onFocus={() => onSelect?.(row.id)} onDoubleClick={() => onOpen?.(row.id)}
      onKeyDown={(event) => { if (event.target !== event.currentTarget) return;
        if (event.key === "Enter") onOpen?.(row.id);
        if (onSelect && (event.key === "ArrowDown" || event.key === "ArrowUp")) { event.preventDefault(); handleArrowRepeat(event); }
      }}>{visible.map((column) => <td key={column.key} className={`inventory-cell-${column.key}`}>{cell(row, column.key)}</td>)}</tr>)}
      {!rows.length && <tr><td colSpan={visible.length}>{empty}</td></tr>}</tbody>
  </table></div>;
}
