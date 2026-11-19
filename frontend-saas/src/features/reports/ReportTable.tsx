import type { ReactNode } from "react";

export type ReportColumn<Row> = {
  id: string;
  label: string;
  required?: boolean;
  numeric?: boolean;
  rowHeader?: boolean;
  minWidth?: number;
  defaultHidden?: boolean;
  sticky?: boolean;
  sort?: string;
  cell: (row: Row) => ReactNode;
};

export type ReportDirection = "ASC" | "DESC";
export type ReportSort = { key: string; direction: ReportDirection };

export function nextSort(sort: ReportSort, key: string, firstDirection: ReportDirection = "ASC"): ReportSort {
  return { key, direction: sort.key === key ? (sort.direction === "ASC" ? "DESC" : "ASC") : firstDirection };
}

export function ReportTable<Row>({ title, columns, rows, footerRows, rowKey, empty, visible, sort, onSort }: {
  title: string;
  columns: ReportColumn<Row>[];
  rows: Row[];
  footerRows?: Row[];
  rowKey: (row: Row) => string;
  empty: string;
  visible?: (id: string) => boolean;
  sort?: ReportSort;
  onSort?: (id: string) => void;
}) {
  const selected = visible ? columns.filter(({ id, required }) => required || visible(id)) : columns;
  function renderRow(row: Row) {
    return <tr key={rowKey(row)}>{selected.map((column) => {
      const className = [column.numeric ? "saas-summary-number" : "", column.sticky ? "report-sticky" : "", column.sticky && !column.rowHeader ? "report-sticky-actions" : ""].filter(Boolean).join(" ") || undefined;
      const value = column.cell(row);
      return column.rowHeader
        ? <th key={column.id} scope="row" className={className}>{value}</th>
        : <td key={column.id} className={className}>{value}</td>;
    })}</tr>;
  }

  return (
    <div className="table-wrap saas-summary-table statistics-table" tabIndex={0} role="region" aria-label={title}>
      <table aria-label={title}>
        <thead><tr>{selected.map((column) => {
          const direction = column.sort && sort?.key === column.sort ? sort.direction : undefined;
          return <th key={column.id} scope="col" className={[column.numeric ? "saas-summary-number" : "", column.sticky ? "report-sticky" : "", column.sticky && !column.rowHeader ? "report-sticky-actions" : ""].filter(Boolean).join(" ") || undefined}
            style={column.minWidth ? { minWidth: column.minWidth } : undefined}
            aria-sort={direction === "ASC" ? "ascending" : direction === "DESC" ? "descending" : undefined}>
            {column.sort && onSort
              ? <button className="report-sort-button" type="button" onClick={() => onSort(column.sort!)}>
                {column.label}{direction && <span aria-hidden="true">{direction === "ASC" ? " ↑" : " ↓"}</span>}
              </button>
              : column.label}
          </th>;
        })}</tr></thead>
        <tbody>
          {rows.map(renderRow)}
          {!rows.length && <tr><td className="saas-summary-empty" colSpan={selected.length}>{empty}</td></tr>}
        </tbody>
        {footerRows?.length ? <tfoot>{footerRows.map(renderRow)}</tfoot> : null}
      </table>
    </div>
  );
}
