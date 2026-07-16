import { createElement, useState } from "react";
import type { DragEvent, KeyboardEvent, PointerEvent, ReactNode } from "react";
import type { TableColumnLayout, TableColumnMoveDirection } from "./tableLayoutPreferences";

const tableColumnDragType = "application/x-tpverp-table-column";

type TableLayoutHeaderCellProps<Key extends string> = {
  as?: "th" | "span" | "div";
  column: TableColumnLayout<Key>;
  children: ReactNode;
  className?: string;
  movable?: boolean;
  resizable?: boolean;
  wrapLabel?: boolean;
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
      "aria-keyshortcuts": movable ? "Control+ArrowLeft Control+ArrowRight" : undefined,
      onKeyDown: handleKeyboardMove,
      onDragStart: (event: DragEvent<HTMLElement>) => {
        if (!movable) return;
        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData(tableColumnDragType, column.key);
        event.dataTransfer.setData("text/plain", column.key);
        setDragging(true);
      },
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
    wrapLabel ? <span className="table-layout-header-label">{children}</span> : children,
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
