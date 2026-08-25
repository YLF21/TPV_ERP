import { DotsSixVertical, DotsThreeVertical } from "@phosphor-icons/react";
import { createElement, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { DragEvent, KeyboardEvent, MouseEvent, PointerEvent, ReactNode } from "react";
import type { TableColumnLayout, TableColumnMoveDirection } from "./tableLayoutPreferences";
import type { TableSortDirection } from "./tableSorting";
import { TableSortButton } from "./TableSortButton";

const tableColumnDragType = "application/x-tpverp-table-column";

type TableColumnVisibilityOption<Key extends string> = {
  key: Key;
  label: string;
  visible: boolean;
  disabled?: boolean;
};

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
  showColumnMenu?: boolean;
  onToggleVisibility?: (columnKey: Key) => void;
  columnVisibilityOptions?: readonly TableColumnVisibilityOption<Key>[];
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
  showColumnMenu = false,
  onToggleVisibility,
  columnVisibilityOptions,
  resizeLabel,
  onReorder,
  onMove,
  onResize
}: TableLayoutHeaderCellProps<Key>) {
  const [dragging, setDragging] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [pendingCurrentVisibility, setPendingCurrentVisibility] = useState<boolean | null>(null);
  const [menuPosition, setMenuPosition] = useState({ left: 0, top: 0 });
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const columnMenuEnabled = showColumnMenu || Boolean(onToggleVisibility);

  const language = typeof document === "undefined" ? "es" : document.documentElement.lang;
  const copy = language.startsWith("en")
    ? { menu: "Column options", sort: "Change sorting", left: "Move left", right: "Move right", hide: "Hide column", modify: "Modify columns" }
    : language.startsWith("zh")
      ? { menu: "列选项", sort: "更改排序", left: "向左移动", right: "向右移动", hide: "隐藏列", modify: "修改列" }
      : { menu: "Opciones de columna", sort: "Cambiar orden", left: "Mover a la izquierda", right: "Mover a la derecha", hide: "Ocultar columna", modify: "Modificar columnas" };

  useEffect(() => {
    if (!menuOpen) return;
    function closeOnPointerDown(event: globalThis.PointerEvent) {
      const target = event.target as Node | null;
      if (menuRef.current?.contains(target) || menuButtonRef.current?.contains(target)) return;
      setMenuOpen(false);
    }
    function closeOnEscape(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setMenuOpen(false);
        menuButtonRef.current?.focus();
      }
    }
    function closeOnViewportChange(event: Event) {
      const target = event.target;
      if (target instanceof Node && menuRef.current?.contains(target)) return;
      setMenuOpen(false);
    }
    window.addEventListener("pointerdown", closeOnPointerDown);
    window.addEventListener("keydown", closeOnEscape);
    window.addEventListener("resize", closeOnViewportChange);
    window.addEventListener("scroll", closeOnViewportChange, true);
    return () => {
      window.removeEventListener("pointerdown", closeOnPointerDown);
      window.removeEventListener("keydown", closeOnEscape);
      window.removeEventListener("resize", closeOnViewportChange);
      window.removeEventListener("scroll", closeOnViewportChange, true);
    };
  }, [menuOpen]);

  useEffect(() => {
    if (menuOpen || pendingCurrentVisibility === null || !onToggleVisibility) return;
    const currentOption = columnVisibilityOptions?.find((option) => option.key === column.key);
    if (currentOption && currentOption.visible !== pendingCurrentVisibility) {
      onToggleVisibility(column.key);
    }
    setPendingCurrentVisibility(null);
  }, [column.key, columnVisibilityOptions, menuOpen, onToggleVisibility, pendingCurrentVisibility]);

  function toggleMenu(event: MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    const rect = event.currentTarget.getBoundingClientRect();
    const menuWidth = 210;
    setMenuPosition({
      left: Math.max(8, Math.min(window.innerWidth - menuWidth - 8, rect.right - menuWidth)),
      top: Math.max(8, Math.min(window.innerHeight - 190, rect.bottom + 6))
    });
    setMenuOpen((open) => !open);
  }

  function runMenuAction(action: () => void) {
    action();
    setMenuOpen(false);
    menuButtonRef.current?.focus();
  }

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
    setMenuOpen(false);
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
    setMenuOpen(false);
    setDragging(true);
  }

  const classes = [
    "table-layout-header-cell",
    className,
    columnMenuEnabled ? "has-column-menu" : "",
    movable ? "movable" : "fixed",
    dragging ? "dragging" : "",
    dragOver ? "drag-over" : ""
  ].filter(Boolean).join(" ");

  const columnMenuButton = columnMenuEnabled ? (
    <button
      ref={menuButtonRef}
      type="button"
      className={"table-layout-column-menu-button" + (menuOpen ? " is-open" : "")}
      draggable={false}
      aria-label={copy.menu}
      aria-haspopup="menu"
      aria-expanded={menuOpen}
      title={copy.menu}
      onPointerDown={(event) => {
        event.stopPropagation();
      }}
      onClick={toggleMenu}
    >
      <DotsThreeVertical aria-hidden="true" weight="bold" />
    </button>
  ) : null;

  const columnMenu = menuOpen && typeof document !== "undefined" ? createPortal(
    <div
      ref={menuRef}
      className="table-layout-column-menu"
      role="menu"
      aria-label={copy.menu}
      style={{ left: menuPosition.left, top: menuPosition.top }}
    >
      {onSort && <button type="button" role="menuitem" onClick={() => runMenuAction(() => onSort(column.key))}>
        <span>{copy.sort}</span><kbd aria-hidden="true">↕</kbd>
      </button>}
      {movable && <button type="button" role="menuitem" onClick={() => runMenuAction(() => onMove(column.key, -1))}>
        <span>{copy.left}</span><kbd aria-hidden="true">Ctrl+←</kbd>
      </button>}
      {movable && <button type="button" role="menuitem" onClick={() => runMenuAction(() => onMove(column.key, 1))}>
        <span>{copy.right}</span><kbd aria-hidden="true">Ctrl+→</kbd>
      </button>}
      {onToggleVisibility && columnVisibilityOptions?.length ? <>
        <span className="table-layout-column-menu-divider" role="separator" />
        <span className="table-layout-column-menu-heading">{copy.modify}</span>
        <div className="table-layout-column-visibility-list" role="group" aria-label={copy.modify}>
          {columnVisibilityOptions.map((option) => {
            const effectiveVisible = option.key === column.key && pendingCurrentVisibility !== null
              ? pendingCurrentVisibility
              : option.visible;
            return <button
              key={option.key}
              type="button"
              role="menuitemcheckbox"
              aria-checked={effectiveVisible}
              disabled={option.disabled}
              onClick={(event) => {
                event.preventDefault();
                event.stopPropagation();
                if (option.key === column.key) {
                  setPendingCurrentVisibility((current) => !(current ?? option.visible));
                } else {
                  onToggleVisibility(option.key);
                }
              }}
            >
              <span className="table-layout-column-visibility-check" aria-hidden="true">{effectiveVisible ? "✓" : ""}</span>
              <span>{option.label}</span>
            </button>;
          })}
        </div>
      </> : onToggleVisibility && <>
        <span className="table-layout-column-menu-divider" role="separator" />
        <button type="button" role="menuitem" className="is-danger" onClick={() => runMenuAction(() => onToggleVisibility(column.key))}>
          <span>{copy.hide}</span>
        </button>
      </>}
    </div>,
    document.body
  ) : null;

  const legacyHeaderContent = headerAction ? (
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
  ) : wrapLabel ? <span className="table-layout-header-label">{children}</span> : children;

  const headerContent = columnMenuEnabled ? (
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
      <span className="table-layout-header-actions">
        {headerAction && <span className="table-layout-header-action">{headerAction}</span>}
        {columnMenuButton}
      </span>
    </div>
  ) : legacyHeaderContent;

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
      >
        {columnMenuEnabled && <DotsSixVertical aria-hidden="true" weight="bold" />}
      </span>
    ),
    headerContent,
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
    ),
    columnMenu
  );
}
