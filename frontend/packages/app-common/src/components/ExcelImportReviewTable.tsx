import { useEffect, useRef, useState, type ReactNode } from "react";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { revealExcelTableRow, useExcelTableHeader } from "./useExcelTableHeader";

export type ExcelReviewColumn = { key: string; label: string; source?: "current" | "excel" };
export type ExcelReviewRow = { id: number; sourceRowNumber?: number; status: string; values: Record<string, string>; changedColumns?: string[]; details?: ReactNode };

/** Fixed-height windowing keeps large reviews usable without discarding export rows. */
export function ExcelImportReviewTable({ title, columns, rows, actions, onExport, exportDisabled, labels, selectedRowId, onSelectRow }: {
  title: string;
  columns: ExcelReviewColumn[];
  rows: ExcelReviewRow[];
  actions?: ReactNode;
  onExport: () => void;
  exportDisabled: boolean;
  selectedRowId?: number | null;
  onSelectRow?: (id: number) => void;
  labels: { export: string; empty: string; resize: (column: string) => string; review: string;
    comparison?: { current: string; excel: string; changed: string; hint: string } };
}) {
  const viewport = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [height, setHeight] = useState(480);
  const [widths, setWidths] = useState<Record<string, number>>({});
  const [selected, setSelected] = useState<number | null>(null);
  const [highlighted, setHighlighted] = useState<number | null>(null);
  const selectedId = selectedRowId === undefined ? highlighted : selectedRowId;
  const header = useExcelTableHeader(viewport, rows.length);
  const dragged = useRef(false);
  const drag = useRef<{ x: number; y: number; left: number; top: number; pointer: number } | null>(null);
  const rowHeight = 32;
  const start = Math.max(0, Math.floor(scrollTop / rowHeight) - 5);
  const end = Math.min(rows.length, start + Math.ceil(height / rowHeight) + 12);
  const selectedRow = rows.find((row) => row.id === selected);
  const rowSetKey = rows.map((row) => row.id).join(",");
  const width = (key: string) => widths[key] ?? (key === "rowNumber" ? 64 : key === "errors" ? 380 : key === "status" ? 170 : 180);
  const comparison = labels.comparison;
  const tableWidth = columns.reduce((sum, col) => sum + width(col.key), 0);
  function selectRow(id: number) {
    setHighlighted(id);
    setSelected((current) => current === null ? null : id);
    onSelectRow?.(id);
  }
  const cellClass = (column: ExcelReviewColumn, row?: ExcelReviewRow) => [
    column.source ? `shared-excel-review-cell--${column.source}` : "",
    row?.changedColumns?.includes(column.key) ? "shared-excel-review-cell--changed" : ""
  ].filter(Boolean).join(" ");
  const changeMarker = <span className="shared-excel-review-change-mark" role="img" aria-label={comparison?.changed}>≠</span>;

  useEffect(() => {
    if (!viewport.current || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => setHeight(entry.contentRect.height || 480));
    observer.observe(viewport.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (selectedId === null || !rows.some((row) => row.id === selectedId)) {
      if (viewport.current) viewport.current.scrollTop = 0;
      setScrollTop(0);
    }
    setSelected(null);
  }, [rowSetKey]);
  useEffect(() => {
    if (selectedId !== null) setScrollTop(revealExcelTableRow(viewport.current, rows.findIndex((row) => row.id === selectedId), rowHeight));
    setSelected((current) => current === null ? null : selectedId);
  }, [selectedId, rowSetKey, height]);

  return <section className="shared-excel-review">
    <header>
      <h3>{title}</h3><span>{rows.length}</span>
      <button type="button" disabled={exportDisabled} onClick={onExport}>{labels.export}</button>
      {actions}
    </header>
    {comparison && <div className="shared-excel-review-legend">
      {columns.some((column) => column.source === "current") && <span className="shared-excel-review-legend-current">{comparison.current}</span>}
      <span className="shared-excel-review-legend-excel">{comparison.excel}</span>
      <span className="shared-excel-review-legend-changed"><span aria-hidden="true">≠</span> {comparison.changed}</span>
      <small>{comparison.hint}</small>
    </div>}
    <div className="shared-excel-fixed-header" ref={header.header} onScroll={header.syncBody}>
      <table role="presentation" style={{ width: tableWidth }}>
        <colgroup>{columns.map((col) => <col key={col.key} style={{ width: width(col.key) }} />)}</colgroup>
        <thead><tr>{columns.map((col) => <TableLayoutHeaderCell key={col.key} className={cellClass(col)}
          column={{ key: col.key, visible: true, width: width(col.key) }} movable={false} resizable showColumnMenu={false}
          onReorder={() => undefined} onMove={() => undefined}
          resizeLabel={labels.resize(col.label)}
          onResize={(key, next) => setWidths((current) => ({ ...current, [key]: Math.max(56, Math.min(720, next)) }))}>
          {col.label}
        </TableLayoutHeaderCell>)}</tr></thead>
      </table>
    </div>
    <div ref={viewport} className="shared-excel-review-viewport" tabIndex={0} aria-label={title}
      onScroll={(event) => { setScrollTop(event.currentTarget.scrollTop); header.syncHeader(); }}
      onKeyDown={(event) => {
        if (!["ArrowDown", "ArrowUp"].includes(event.key) || !rows.length) return;
        event.preventDefault();
        const current = rows.findIndex((row) => row.id === selectedId);
        selectRow(rows[Math.max(0, Math.min(rows.length - 1, current + (event.key === "ArrowDown" ? 1 : -1)))].id);
      }}
      onPointerDown={(event) => {
        dragged.current = false;
        if (event.button !== 0 || (event.target instanceof Element && event.target.closest("button,input,a"))) return;
        drag.current = { x: event.clientX, y: event.clientY, left: event.currentTarget.scrollLeft, top: event.currentTarget.scrollTop, pointer: event.pointerId };
      }}
      onPointerMove={(event) => {
        if (!drag.current || drag.current.pointer !== event.pointerId) return;
        if (Math.abs(event.clientX - drag.current.x) + Math.abs(event.clientY - drag.current.y) > 3) {
          dragged.current = true;
          event.currentTarget.setPointerCapture?.(event.pointerId);
        }
        event.currentTarget.scrollLeft = drag.current.left - event.clientX + drag.current.x;
        event.currentTarget.scrollTop = drag.current.top - event.clientY + drag.current.y;
      }}
      onPointerUp={() => { drag.current = null; }} onPointerCancel={() => { drag.current = null; }}>
      <table aria-label={title} aria-rowcount={rows.length + 1} style={{ width: tableWidth }}>
        <colgroup>{columns.map((col) => <col key={col.key} style={{ width: width(col.key) }} />)}</colgroup>
        <thead className="shared-excel-body-labels"><tr>{columns.map((col) => <th key={col.key} scope="col">{col.label}</th>)}</tr></thead>
        <tbody>
          {start > 0 && <tr aria-hidden="true"><td colSpan={columns.length} style={{ height: start * rowHeight, padding: 0, border: 0 }} /></tr>}
          {rows.slice(start, end).map((row, index) => <tr key={row.id} aria-rowindex={start + index + 2}
            aria-selected={selectedId === row.id} data-source-row={row.sourceRowNumber ?? row.id}
            onClick={() => { if (!dragged.current) selectRow(row.id); }}
            className={`shared-excel-result-row shared-excel-result-row--${row.status}`}>
            {columns.map((col) => <td key={col.key} data-column-key={col.key} className={cellClass(col, row)}
              title={[row.changedColumns?.includes(col.key) ? comparison?.changed : null, row.values[col.key] ?? ""].filter(Boolean).join(": ")}>
              {col.key === "rowNumber" ? <button type="button" className="shared-excel-review-row-button"
                aria-label={`${labels.review} ${row.sourceRowNumber ?? row.id}`} aria-expanded={selected === row.id}
                onClick={() => { selectRow(row.id); setSelected(selected === row.id ? null : row.id); }}>{row.values[col.key]}</button>
                : <>{row.changedColumns?.includes(col.key) && changeMarker}{row.values[col.key]}</>}
            </td>)}
          </tr>)}
          {end < rows.length && <tr aria-hidden="true"><td colSpan={columns.length} style={{ height: (rows.length - end) * rowHeight, padding: 0, border: 0 }} /></tr>}
          {!rows.length && <tr><td colSpan={columns.length}>{labels.empty}</td></tr>}
        </tbody>
      </table>
    </div>
    {selectedRow && <div className="shared-excel-review-detail" role="region" aria-label={`${labels.review} ${selectedRow.sourceRowNumber ?? selectedRow.id}`}>
      <dl>{columns.filter((col) => col.key !== "errors").map((col) => <div key={col.key} className={cellClass(col, selectedRow)}>
        <dt>{col.label}</dt><dd>{selectedRow.changedColumns?.includes(col.key) && changeMarker}{selectedRow.values[col.key]}</dd>
      </div>)}</dl>
      {selectedRow.details}
    </div>}
  </section>;
}
