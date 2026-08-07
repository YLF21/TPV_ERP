import type { ReactNode } from "react";
import type { TableSortDirection } from "./tableSorting";

export function TableSortButton({
  children,
  direction,
  label,
  className = "",
  onSort
}: {
  children: ReactNode;
  direction?: TableSortDirection | null;
  label: string;
  className?: string;
  onSort: () => void;
}) {
  return (
    <button
      type="button"
      className={`table-layout-sort-button ${className}`.trim()}
      draggable={false}
      aria-label={label}
      aria-pressed={Boolean(direction)}
      onPointerDown={(event) => event.stopPropagation()}
      onClick={(event) => {
        event.stopPropagation();
        onSort();
      }}
    >
      <span>{children}</span>
      <i aria-hidden="true">{direction === "asc" ? "▲" : direction === "desc" ? "▼" : "↕"}</i>
    </button>
  );
}
