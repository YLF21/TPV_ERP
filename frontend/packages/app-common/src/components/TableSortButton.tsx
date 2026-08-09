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
  const sortIndicator = direction === "asc" ? "\u2191" : direction === "desc" ? "\u2193" : "\u2195";

  return (
    <button
      type="button"
      className={`table-layout-sort-button${direction ? " is-active" : ""} ${className}`.trim()}
      draggable={false}
      data-sort-direction={direction ?? "none"}
      aria-label={label}
      title={label}
      aria-pressed={Boolean(direction)}
      onPointerDown={(event) => event.stopPropagation()}
      onClick={(event) => {
        event.stopPropagation();
        onSort();
      }}
    >
      <span className="table-layout-sort-label">{children}</span>
      <span className="table-layout-sort-indicator" aria-hidden="true">{sortIndicator}</span>
    </button>
  );
}
