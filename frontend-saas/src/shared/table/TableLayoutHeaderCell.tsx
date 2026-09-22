import { useEffect, useRef, useState, type DragEvent, type PointerEvent } from "react";
import { createPortal } from "react-dom";
import type { TableColumnLayout } from "./table-layout.mjs";
import { useTableLabels } from "./table-labels";

const dragType = "application/x-tpverp-saas-table-column";
type Visibility<Key extends string> = { key: Key; label: string; visible: boolean; disabled: boolean };

/** APP VENTA header interactions adapted to the standalone SaaS bundle. */
export function TableLayoutHeaderCell<Key extends string>({ column, label, sortDirection, onSort, options, onReorder, onMove, onResize, onToggle, onReset }: {
  column: TableColumnLayout<Key>; label: string; sortDirection?: "asc" | "desc" | null; onSort?: () => void;
  options: readonly Visibility<Key>[]; onReorder: (from: Key, to: Key) => void; onMove: (key: Key, direction: -1 | 1) => void;
  onResize: (key: Key, width: number) => void; onToggle: (key: Key) => void; onReset: () => void;
}) {
  const l = useTableLabels();
  const [open, setOpen] = useState(false); const [dragOver, setDragOver] = useState(false);
  const [position, setPosition] = useState({ left: 0, top: 0 });
  const button = useRef<HTMLButtonElement>(null); const menu = useRef<HTMLDivElement>(null);
  const stopResize = useRef<(() => void) | null>(null);
  useEffect(() => () => stopResize.current?.(), []);
  useEffect(() => {
    if (!open) return;
    function close(event: globalThis.PointerEvent) {
      if (!menu.current?.contains(event.target as Node) && !button.current?.contains(event.target as Node)) setOpen(false);
    }
    function escape(event: KeyboardEvent) { if (event.key === "Escape") { event.preventDefault(); setOpen(false); button.current?.focus(); } }
    function viewport(event: Event) { if (!(event.target instanceof Node) || !menu.current?.contains(event.target)) setOpen(false); }
    document.addEventListener("pointerdown", close, true); document.addEventListener("keydown", escape);
    window.addEventListener("resize", viewport); window.addEventListener("scroll", viewport, true);
    return () => { document.removeEventListener("pointerdown", close, true); document.removeEventListener("keydown", escape);
      window.removeEventListener("resize", viewport); window.removeEventListener("scroll", viewport, true); };
  }, [open]);
  function startResize(event: PointerEvent<HTMLButtonElement>) {
    event.preventDefault(); event.stopPropagation(); setOpen(false); stopResize.current?.();
    const startX = event.clientX; const width = column.width;
    const move = (next: globalThis.PointerEvent) => onResize(column.key, width + next.clientX - startX);
    const stop = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", stop); window.removeEventListener("pointercancel", stop); stopResize.current = null; };
    stopResize.current = stop; window.addEventListener("pointermove", move); window.addEventListener("pointerup", stop); window.addEventListener("pointercancel", stop);
  }
  function drag(event: DragEvent<HTMLTableCellElement>) { event.dataTransfer.setData(dragType, column.key); event.dataTransfer.effectAllowed = "move"; setOpen(false); }
  function action(run: () => void) { run(); setOpen(false); button.current?.focus(); }
  return <th scope="col" className={`saas-layout-heading${dragOver ? " is-drop-target" : ""}${open ? " is-menu-open" : ""}`} data-column-key={column.key}
    draggable tabIndex={0} aria-keyshortcuts="Control+ArrowLeft Control+ArrowRight"
    aria-sort={onSort ? sortDirection === "asc" ? "ascending" : sortDirection === "desc" ? "descending" : "none" : undefined}
    onKeyDown={event => { if (event.ctrlKey && !event.altKey && !event.metaKey && ["ArrowLeft", "ArrowRight"].includes(event.key)) { event.preventDefault(); onMove(column.key, event.key === "ArrowLeft" ? -1 : 1); } }}
    onDragStart={drag} onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = "move"; setDragOver(true); }}
    onDragLeave={() => setDragOver(false)} onDragEnd={() => setDragOver(false)} onDrop={event => {
      event.preventDefault(); setDragOver(false); const key = event.dataTransfer.getData(dragType);
      if (options.some(option => option.key === key)) onReorder(key as Key, column.key);
    }}>
    <div className="saas-layout-heading-controls">
      {onSort ? <button type="button" className="saas-layout-sort" draggable={false} aria-label={`${l("sort")} ${label}`} onClick={onSort}>
        <span>{label}</span><span className="saas-layout-sort-indicator" aria-hidden="true">{sortDirection === "asc" ? "↑" : sortDirection === "desc" ? "↓" : "↕"}</span>
      </button> : <span className="saas-layout-label">{label}</span>}
      <button ref={button} type="button" className="saas-layout-menu-trigger" draggable={false} aria-label={`${l("options")} ${label}`}
        aria-haspopup="menu" aria-expanded={open} onClick={event => {
          event.stopPropagation(); const rect = event.currentTarget.getBoundingClientRect();
          setPosition({ left: Math.max(8, Math.min(innerWidth - 256, rect.right - 248)), top: Math.max(8, Math.min(innerHeight - 390, rect.bottom + 4)) }); setOpen(!open);
        }}>⋮</button>
    </div>
    <button type="button" className="saas-layout-resizer" draggable={false} aria-label={`${l("resize")} ${label}`} onPointerDown={startResize}
      onKeyDown={event => { if (["ArrowLeft", "ArrowRight"].includes(event.key)) { event.preventDefault(); event.stopPropagation(); onResize(column.key, column.width + (event.key === "ArrowLeft" ? -8 : 8)); } }} />
    {open && createPortal(<div ref={menu} className="saas-layout-menu" role="menu" aria-label={`${l("options")} ${label}`} style={position}>
      <button type="button" role="menuitem" onClick={() => action(() => onMove(column.key, -1))}>{l("left")}<kbd>Ctrl+←</kbd></button>
      <button type="button" role="menuitem" onClick={() => action(() => onMove(column.key, 1))}>{l("right")}<kbd>Ctrl+→</kbd></button>
      <div className="saas-layout-menu-title">{l("columns")}</div>
      <div className="saas-layout-visible-columns">{options.map(option => <button type="button" key={option.key} role="menuitemcheckbox" aria-checked={option.visible} disabled={option.disabled}
        onClick={() => { if (option.key === column.key) setOpen(false); onToggle(option.key); }}><span aria-hidden="true">{option.visible ? "☑" : "☐"}</span>{option.label}</button>)}</div>
      <button type="button" role="menuitem" onClick={() => action(onReset)}>{l("reset")}</button>
    </div>, document.body)}
  </th>;
}
