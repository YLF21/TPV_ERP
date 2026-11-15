import { useEffect, useRef, useState } from "react";
import { X } from "@phosphor-icons/react";
import { WindowCloseButton } from "./WindowCloseButton";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

export type StockPdfColumnChoice = { key: string; label: string; visible: boolean };

type Props = {
  columns: StockPdfColumnChoice[];
  title: string;
  description: string;
  selectAllLabel: string;
  clearLabel: string;
  requiredLabel: string;
  cancelLabel: string;
  exportLabel: string;
  closeLabel: string;
  onCancel: () => void;
  onExport: (keys: string[]) => void;
};

export function StockPdfColumnsDialog({ columns, title, description, selectAllLabel, clearLabel,
  requiredLabel, cancelLabel, exportLabel, closeLabel, onCancel, onExport }: Props) {
  const dialogRef = useRef<HTMLElement>(null);
  const [selected, setSelected] = useState(() => new Set(columns.filter(column => column.visible).map(column => column.key)));

  useEffect(() => {
    if (!dialogRef.current) return undefined;
    return activateModalFocusTrap(dialogRef.current as ModalFocusRoot, document);
  }, []);

  const selectedKeys = columns.filter(column => selected.has(column.key)).map(column => column.key);
  const toggle = (key: string) => setSelected(previous => {
    const next = new Set(previous);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  });

  return <div className="filter-overlay" role="presentation">
    <section ref={dialogRef} className="filter-dialog bulk-compact-dialog stock-pdf-columns-dialog"
      role="dialog" aria-modal="true" aria-labelledby="stock-pdf-columns-title"
      aria-describedby="stock-pdf-columns-description"
      onKeyDown={event => {
        if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); onCancel(); }
        if (event.key === "Enter" && event.target instanceof HTMLElement && event.target.tagName !== "BUTTON" && event.target.tagName !== "INPUT" && selectedKeys.length) {
          event.preventDefault(); onExport(selectedKeys);
        }
      }}>
      <header className="filter-header">
        <h2 id="stock-pdf-columns-title">{title}</h2>
        <WindowCloseButton type="button" aria-label={closeLabel} onClick={onCancel}><X size={18} weight="bold" aria-hidden="true" /></WindowCloseButton>
      </header>
      <p id="stock-pdf-columns-description">{description}</p>
      <div className="stock-pdf-columns-tools">
        <button type="button" onClick={() => setSelected(new Set(columns.map(column => column.key)))}>{selectAllLabel}</button>
        <button type="button" onClick={() => setSelected(new Set())}>{clearLabel}</button>
      </div>
      <div className="stock-pdf-columns-list">
        {columns.map(column => <label key={column.key} className="stock-pdf-columns-choice">
          <input type="checkbox" checked={selected.has(column.key)} onChange={() => toggle(column.key)} />
          <span>{column.label}</span>
        </label>)}
      </div>
      {selectedKeys.length === 0 && <p className="stock-pdf-columns-required" role="alert">{requiredLabel}</p>}
      <footer className="filter-actions erp-dialog-actions-row">
        <button type="button" className="secondary erp-dialog-action-cancel" onClick={onCancel}>{cancelLabel}</button>
        <button type="button" className="erp-dialog-action-confirm" disabled={selectedKeys.length === 0} onClick={() => onExport(selectedKeys)}>{exportLabel}</button>
      </footer>
    </section>
  </div>;
}
