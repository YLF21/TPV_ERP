import { createElement, useState } from "react";
import type { DragEvent, KeyboardEvent, PointerEvent, ReactNode } from "react";
import type { TableColumnLayout, TableColumnMoveDirection } from "./tableLayoutPreferences";
import type { TableSortDirection } from "./tableSorting";
import { TableSortButton } from "./TableSortButton";

const tableColumnDragType = "application/x-tpverp-table-column";

type TableLayoutHeaderCellProps<Key extends string> = {
  as?: "th" | "span" | "div";
  column: TableColumnLayout<Key>;
  children: ReactNode;
  className?: string;
  movable?: boolean;
  resizable?: boolean;
  wrapLabel?: boolean;
  sortDirection?: TableSortDirection | null;
  sortLabel?: string;
  onSort?: (columnKey: Key) => void;
  headerAction?: ReactNode;
  resizeLabel: string;
  onReorder: (draggedKey: Key, targetKey: Key) => void;
  onMove: (columnKey: Key, direction: TableColumnMoveDirection) => void;
  onResize: (columnKey: Key, width: number) => void;
};

export function TableLayoutHeaderCell<Key extends string>({
  as = "th",
  column,
  children,
  className = "",
  movable = true,
  resizable = true,
  wrapLabel = true,
  sortDirection,
  sortLabel,
  onSort,
  headerAction,
  resizeLabel,
  onReorder,
  onMove,
  onResize
}: TableLayoutHeaderCellProps<Key>) {
  const [dragging, setDragging] = useState(false);
  const [dragOver, setDragOver] = useState(false);

  function handleKeyboardMove(event: KeyboardEvent<HTMLElement>) {
    if (!movable || !event.ctrlKey || event.altKey || event.metaKey) {
      return;
    }
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") {
      return;
    }
    event.preventDefault();
    onMove(column.key, event.key === "ArrowLeft" ? -1 : 1);
  }

  function startResize(event: PointerEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const startWidth = column.width;

    function move(pointerEvent: globalThis.PointerEvent) {
      onResize(column.key, startWidth + pointerEvent.clientX - startX);
    }

    function stop() {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", stop);
      window.removeEventListener("pointercancel", stop);
    }

    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", stop);
    window.addEventListener("pointercancel", stop);
  }

  function startDrag(event: DragEvent<HTMLElement>) {
    if (!movable) return;
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData(tableColumnDragType, column.key);
    event.dataTransfer.setData("text/plain", column.key);
    setDragging(true);
  }

  const classes = [
    "table-layout-header-cell",
    className,
    movable ? "movable" : "fixed",
    dragging ? "dragging" : "",
    dragOver ? "drag-over" : ""
  ].filter(Boolean).join(" ");

  return createElement(
    as,
    {
      className: classes,
      draggable: movable,
      tabIndex: movable ? 0 : undefined,
      "data-column-key": column.key,
      role: as === "th" ? undefined : "columnheader",
      "aria-sort": onSort
        ? sortDirection === "asc" ? "ascending" : sortDirection === "desc" ? "descending" : "none"
        : undefined,
      "aria-keyshortcuts": movable ? "Control+ArrowLeft Control+ArrowRight" : undefined,
      onKeyDown: handleKeyboardMove,
      onDragStart: startDrag,
      onDragEnd: () => {
        setDragging(false);
        setDragOver(false);
      },
      onDragOver: (event: DragEvent<HTMLElement>) => {
        if (!movable) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = "move";
        setDragOver(true);
      },
      onDragLeave: (event: DragEvent<HTMLElement>) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          setDragOver(false);
        }
      },
      onDrop: (event: DragEvent<HTMLElement>) => {
        if (!movable) return;
        event.preventDefault();
        setDragOver(false);
        const draggedKey = event.dataTransfer.getData(tableColumnDragType)
          || event.dataTransfer.getData("text/plain");
        if (draggedKey) {
          onReorder(draggedKey as Key, column.key);
        }
      }
    },
    movable && (
      <span
        className="table-layout-drag-handle"
        draggable
        aria-hidden="true"
      />
    ),
    headerAction ? (
      <div className="table-layout-header-controls">
        {onSort ? (
          <TableSortButton
            direction={sortDirection}
            label={sortLabel ?? String(children)}
            onSort={() => onSort(column.key)}
          >
            {children}
          </TableSortButton>
        ) : wrapLabel ? <span className="table-layout-header-label">{children}</span> : children}
        <span className="table-layout-header-action">{headerAction}</span>
      </div>
    ) : onSort ? (
      <TableSortButton
        direction={sortDirection}
        label={sortLabel ?? String(children)}
        onSort={() => onSort(column.key)}
      >
        {children}
      </TableSortButton>
    ) : wrapLabel ? <span className="table-layout-header-label">{children}</span> : children,
    resizable && (
      <button
        type="button"
        className="table-layout-column-resizer"
        draggable={false}
        aria-label={resizeLabel}
        onPointerDown={startResize}
        onKeyDown={(event) => {
          if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
          event.preventDefault();
          event.stopPropagation();
          onResize(column.key, column.width + (event.key === "ArrowLeft" ? -8 : 8));
        }}
      />
    )
  );
}
