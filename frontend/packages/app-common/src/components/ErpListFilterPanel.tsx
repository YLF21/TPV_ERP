import { useId, useRef, useState, type ReactNode, type RefObject } from "react";
import { Funnel } from "@phosphor-icons/react";
import "./ErpListFilterPanel.css";

type Props = {
  search: ReactNode;
  filters: ReactNode;
  actions?: ReactNode;
  toolbarContent?: ReactNode;
  onOpenChange?: (open: boolean) => void;
  translate: (key: string) => string;
  className?: string;
  actionsClassName?: string;
  toolbarRef?: RefObject<HTMLDivElement | null>;
  initialOpen?: boolean;
};

/** Presentation only: the screen retains its criteria, queries and actions. */
export function ErpListFilterPanel({ search, filters, actions, toolbarContent, onOpenChange, translate, className = "", actionsClassName = "", toolbarRef, initialOpen = false }: Props) {
  const [open, setOpen] = useState(initialOpen);
  const filtersId = useId();
  const filterButton = useRef<HTMLButtonElement>(null);
  return <div ref={toolbarRef} className={`erp-list-filter-panel ${className}`}>
    {search && <div className="erp-list-filter-search">{search}</div>}
    {filters && <button ref={filterButton} type="button" className="erp-list-filter-toggle" aria-expanded={open} aria-controls={filtersId}
      onClick={() => { setOpen(!open); onOpenChange?.(!open); }}><Funnel size={18} aria-hidden="true" />{translate("salesReport.filter")}</button>}
    {toolbarContent}
    {actions && <div className={`erp-list-filter-actions ${actionsClassName}`}>{actions}</div>}
    {filters && <div id={filtersId} className="erp-list-filter-fields" hidden={!open} onKeyDown={event => {
      if (event.defaultPrevented || event.key !== "Escape") return;
      event.preventDefault(); event.stopPropagation(); setOpen(false); onOpenChange?.(false); filterButton.current?.focus();
    }}>{filters}</div>}
  </div>;
}
