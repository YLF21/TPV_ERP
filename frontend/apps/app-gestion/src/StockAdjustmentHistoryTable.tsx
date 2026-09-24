import { sortTableRows, useTableSortPreference } from "../../../packages/app-common/src/components/tableSorting";
import { useRef, useState } from "react";
import type { UserSession } from "../../../packages/app-common/src/types";
import { TableLayoutHeaderCell } from "../../../packages/app-common/src/components/TableLayoutHeaderCell";
import { visibleTableColumns, type TableColumnDefinition } from "../../../packages/app-common/src/components/tableLayoutPreferences";
import { useTableLayoutPreference } from "../../../packages/app-common/src/components/useTableLayoutPreference";
import type { StockAdjustmentHistoryRow } from "./warehouseOperationsApi";
import "../../../packages/app-common/src/components/WarehouseClassicTables.css";

type Column = "date" | "code" | "barcode" | "name" | "before" | "delta" | "after" | "reason" | "user";
const definitions: TableColumnDefinition<Column>[] = [
  { key: "date", defaultWidth: 165 }, { key: "code", defaultWidth: 115 },
  { key: "barcode", defaultWidth: 155 }, { key: "name", defaultWidth: 330 },
  { key: "before", defaultWidth: 160 }, { key: "delta", defaultWidth: 165 },
  { key: "after", defaultWidth: 165 }, { key: "reason", defaultWidth: 210 },
  { key: "user", defaultWidth: 150 }
];
const quantity = (value: number | null) => value == null ? "—" : Number(value).toLocaleString(undefined, { maximumFractionDigits: 3 });

export function StockAdjustmentHistoryTable({ rows, session, t }: {
  rows: StockAdjustmentHistoryRow[]; session: UserSession; t: (key: string) => string;
}) {
  const layout = useTableLayoutPreference({ app: "gestion", username: session.username,
    accessToken: session.accessToken, tableKey: "warehouse.adjustments.history", definitions });
  const columns = visibleTableColumns(layout.layout);
  const [selected, setSelected] = useState("");
  const tableSort = useTableSortPreference({ app: "gestion", username: session.username, tableKey: "warehouse.adjustments.history", columns: definitions.map(c => c.key), defaultSort: null });
  const sortedRows = sortTableRows(rows, tableSort.sort, (row, key) => key === "date" ? row.createdAt : key === "before" ? row.previousQuantity : key === "after" ? row.nextQuantity : key === "delta" ? Number(row.adjustmentQuantity) : key === "user" ? row.userName : row[key]);
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  const label = (key: Column) => t(key === "date" ? "warehouse.transfer.date" : `warehouse.adjustment.${key}`);
  const cell = (row: StockAdjustmentHistoryRow, key: Column) => {
    switch (key) {
      case "date": return row.createdAt ? new Date(row.createdAt).toLocaleString() : "—";
      case "before": return quantity(row.previousQuantity);
      case "after": return quantity(row.nextQuantity);
      case "delta": return `${Number(row.adjustmentQuantity) > 0 ? "+" : ""}${quantity(row.adjustmentQuantity)}`;
      case "user": return row.userName || "—";
      default: return row[key] || "—";
    }
  };
  return <table className="report-table warehouse-document-table" aria-label={t("warehouse.adjustment.title")}
    style={{ minWidth: columns.reduce((total, column) => total + column.width, 0) }}>
    <colgroup>{columns.map((column) => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
    <thead><tr>{columns.map((column) => <TableLayoutHeaderCell key={column.key} column={column}
      sortDirection={tableSort.sort?.column === column.key ? tableSort.sort.direction : null} onSort={tableSort.toggleSort}
      sortLabel={`${t("party.sortBy")} ${label(column.key)}`}
      resizeLabel={`${t("stock.columns.resize")} ${label(column.key)}`}
      onReorder={layout.reorderColumns} onMove={layout.moveColumn} onResize={layout.resizeColumn}
      onToggleVisibility={layout.toggleColumnVisibility}
      columnVisibilityOptions={layout.layout.map((item) => ({ key: item.key, label: label(item.key), visible: item.visible }))}>
      {label(column.key)}
    </TableLayoutHeaderCell>)}</tr></thead>
    <tbody>{sortedRows.map((row, index) => <tr key={row.movementId} tabIndex={0}
      ref={(node) => { if (node) rowRefs.current.set(row.movementId, node); else rowRefs.current.delete(row.movementId); }}
      className={selected === row.movementId ? "selected" : ""} aria-selected={selected === row.movementId}
      onClick={() => setSelected(row.movementId)} onFocus={() => setSelected(row.movementId)}
      onKeyDown={(event) => {
        const target = event.key === "ArrowDown" ? Math.min(index + 1, sortedRows.length - 1)
          : event.key === "ArrowUp" ? Math.max(index - 1, 0)
          : event.key === "Home" ? 0 : event.key === "End" ? sortedRows.length - 1 : -1;
        if (target < 0) return;
        event.preventDefault();
        const node = rowRefs.current.get(sortedRows[target].movementId);
        node?.focus({ preventScroll: true });
        node?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
      }}>
      {columns.map(({ key }) => <td key={key} title={cell(row, key)}
        className={["before", "delta", "after"].includes(key) ? `is-numeric ${key === "delta" ? (Number(row.adjustmentQuantity) < 0 ? "negative" : "positive") : ""}` : undefined}>
        {cell(row, key)}
      </td>)}
    </tr>)}
    {!rows.length && <tr><td colSpan={columns.length}>{t("warehouse.adjustment.empty")}</td></tr>}
    </tbody>
  </table>;
}
