import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { StockTopSalesFamilyNode } from "./StockScreen";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";

type StockBulkFamilyDialogProps = {
  open: boolean;
  locale: LocaleCode;
  families: StockTopSalesFamilyNode[];
  initialFamilyIds?: string[];
  initialSubfamilyIds?: string[];
  titleKey?: string;
  applyLabelKey?: string;
  onApply: (familyIds: string[], subfamilyIds: string[]) => void;
  onClose: () => void;
};

export function stockBulkFamilySelectionCount(familyIds: string[], subfamilyIds: string[]) {
  return new Set([...familyIds, ...subfamilyIds]).size;
}

function normalizedSearch(value: string) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().trim();
}

export function StockBulkFamilyDialog({
  open,
  locale,
  families,
  initialFamilyIds = [],
  initialSubfamilyIds = [],
  titleKey = "stock.bulkEdit.importFamilies",
  applyLabelKey = "stock.bulkEdit.families.add",
  onApply,
  onClose
}: StockBulkFamilyDialogProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [search, setSearch] = useState("");
  const [familyIds, setFamilyIds] = useState<string[]>([]);
  const [subfamilyIds, setSubfamilyIds] = useState<string[]>([]);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const dialogRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return;
    setSearch("");
    setFamilyIds(initialFamilyIds);
    setSubfamilyIds(initialSubfamilyIds);
    setExpanded(Object.fromEntries(families
      .filter((family) => initialFamilyIds.includes(family.id)
        || family.subfamilies.some((subfamily) => initialSubfamilyIds.includes(subfamily.id)))
      .map((family) => [family.id, true])));
  }, [open]);

  if (!open) return null;

  const query = normalizedSearch(search);
  const visibleFamilies = families.filter((family) => !query
    || normalizedSearch(family.name).includes(query)
    || family.subfamilies.some((subfamily) => normalizedSearch(subfamily.name).includes(query)));
  const selectionCount = stockBulkFamilySelectionCount(familyIds, subfamilyIds);
  const toggleFamily = (family: StockTopSalesFamilyNode) => {
    const selected = familyIds.includes(family.id);
    setFamilyIds((current) => selected
      ? current.filter((id) => id !== family.id)
      : [...current, family.id]);
    if (!selected) {
      const children = new Set(family.subfamilies.map((subfamily) => subfamily.id));
      setSubfamilyIds((current) => current.filter((id) => !children.has(id)));
    }
  };
  const handleEnter = (event: KeyboardEvent<HTMLElement>) => {
    const intent = enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey,
      ctrlKey: event.ctrlKey,
      altKey: event.altKey,
      metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing
    });
    const target = event.target as HTMLInputElement;
    if (!intent || !target.matches("input")) return;
    event.preventDefault();
    if (intent === "next" && target.type === "checkbox") target.click();
    const moved = focusRelativeEnterTarget(
      dialogRef.current,
      target,
      intent,
      ".stock-bulk-family-dialog input:not(:disabled)"
    );
    if (!moved && intent === "next" && target.type !== "checkbox" && selectionCount > 0) {
      onApply(familyIds, subfamilyIds);
    }
  };

  return (
    <div className="filter-overlay" role="presentation">
      <section
        ref={dialogRef}
        aria-labelledby="stock-bulk-family-title"
        aria-modal="true"
        className="filter-dialog stock-bulk-family-dialog"
        role="dialog"
        onKeyDown={handleEnter}
      >
        <header className="filter-header">
          <div>
            <h2 id="stock-bulk-family-title">{t(titleKey)}</h2>
            <p>{t("stock.bulkEdit.families.selected").replace("{count}", String(selectionCount))}</p>
          </div>
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </header>
        <label className="bulk-editor-search report-search">
          <span>{t("stock.bulkEdit.families.search")}</span>
          <input autoFocus type="search" value={search} onChange={(event) => setSearch(event.target.value)} />
        </label>
        <div className="stock-bulk-family-tree" role="tree">
          {visibleFamilies.length === 0 && <p className="stock-empty-state">{t("stock.filter.noFamilies")}</p>}
          {visibleFamilies.map((family) => {
            const isExpanded = Boolean(expanded[family.id]) || Boolean(query);
            const visibleSubfamilies = family.subfamilies.filter((subfamily) => !query
              || normalizedSearch(family.name).includes(query)
              || normalizedSearch(subfamily.name).includes(query));
            return (
              <div className="stock-bulk-family-node" key={family.id} role="treeitem" aria-expanded={isExpanded}>
                <div>
                  <button
                    type="button"
                    aria-label={isExpanded ? t("stock.bulkEdit.families.collapse") : t("stock.bulkEdit.families.expand")}
                    onClick={() => setExpanded((current) => ({ ...current, [family.id]: !isExpanded }))}
                  >
                    {isExpanded ? "-" : "+"}
                  </button>
                  <label>
                    <input type="checkbox" checked={familyIds.includes(family.id)} onChange={() => toggleFamily(family)} />
                    <strong>{family.name}</strong>
                  </label>
                  <span>{family.subfamilies.length}</span>
                </div>
                {isExpanded && (
                  <div className="stock-bulk-subfamily-list" role="group">
                    {visibleSubfamilies.map((subfamily) => (
                      <label key={subfamily.id}>
                        <input
                          type="checkbox"
                          disabled={familyIds.includes(family.id)}
                          checked={familyIds.includes(family.id) || subfamilyIds.includes(subfamily.id)}
                          onChange={() => setSubfamilyIds((current) => current.includes(subfamily.id)
                            ? current.filter((id) => id !== subfamily.id)
                            : [...current, subfamily.id])}
                        />
                        <span>{subfamily.name}</span>
                      </label>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <footer className="filter-actions">
          <button type="button" onClick={() => { setFamilyIds([]); setSubfamilyIds([]); }}>{t("stock.bulkEdit.filter.clear")}</button>
          <button type="button" className="secondary" onClick={onClose}>{t("common.cancel")}</button>
          <button type="button" disabled={selectionCount === 0} onClick={() => onApply(familyIds, subfamilyIds)}>{t(applyLabelKey)}</button>
        </footer>
      </section>
    </div>
  );
}
