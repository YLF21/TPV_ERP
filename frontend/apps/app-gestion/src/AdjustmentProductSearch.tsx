import { useEffect, useId, useRef, useState } from "react";
import { searchWarehouseProducts, type ProductOption } from "./warehouseOperationsApi";

export function AdjustmentProductSearch({ token, value, selected, onChange, onSelect, t }: {
  token: string; value: string; selected: boolean; onChange: (value: string) => void;
  onSelect: (product: ProductOption) => void; t: (key: string) => string;
}) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<ProductOption[]>([]);
  const [active, setActive] = useState(-1);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setItems([]); setActive(-1); setLoading(true); setFailed(false);
    const timer = window.setTimeout(() => {
      void searchWarehouseProducts(selected ? "" : value.trim(), token).then((page) => {
        if (cancelled) return;
        const products = page.items.map((item) => item.product).filter((item) => item.active !== false && item.productType !== "SERVICE");
        setItems(products); setActive(products.length ? 0 : -1);
      }).catch(() => { if (!cancelled) setFailed(true); })
        .finally(() => { if (!cancelled) setLoading(false); });
    }, 200);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [open, selected, value, token]);
  useEffect(() => { list.current?.querySelector<HTMLElement>(`[data-index="${active}"]`)?.scrollIntoView?.({ block: "nearest" }); }, [active]);
  function choose(product: ProductOption) { onSelect(product); setOpen(false); input.current?.focus(); }
  return <div className="wide adjustment-product-search" onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  }}>
    <label htmlFor={id}>{t("warehouse.operations.product")}</label>
    <div className="adjustment-product-search-control">
      <input id={id} ref={input} type="text" role="combobox" autoComplete="off" autoFocus required
        aria-autocomplete="list" aria-expanded={open} aria-controls={`${id}-list`}
        aria-activedescendant={open && active >= 0 ? `${id}-option-${active}` : undefined}
        value={value} placeholder={t("warehouse.adjustment.productSearch")}
        onClick={() => setOpen(true)} onChange={(event) => { setItems([]); setActive(-1); onChange(event.target.value); setOpen(true); }}
        onKeyDown={(event) => {
          if (event.key === "Escape" && open) { event.preventDefault(); event.stopPropagation(); setOpen(false); }
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault(); setOpen(true);
            if (items.length) setActive((index) => Math.max(0, Math.min(items.length - 1, index + (event.key === "ArrowDown" ? 1 : -1))));
          }
          if (event.key === "Enter") { event.preventDefault(); if (open && items[active]) choose(items[active]); else setOpen(true); }
        }} />
      <button type="button" className="adjustment-product-toggle" aria-label={t("warehouse.adjustment.chooseProduct")}
        aria-expanded={open} aria-controls={`${id}-list`} onClick={() => { setOpen(!open); input.current?.focus(); }}>
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="m5 9 7 7 7-7" fill="none" stroke="currentColor" strokeWidth="2.5" /></svg>
      </button>
    </div>
    {open && <div className="adjustment-product-dropdown">
      {loading && <div role="status">{t("common.loading")}</div>}
      {failed && <div role="alert">{t("warehouse.adjustment.searchError")}</div>}
      {!loading && !failed && !items.length && <div role="status">{t("warehouse.adjustment.empty")}</div>}
      <div ref={list} id={`${id}-list`} role="listbox" aria-label={t("warehouse.operations.product")}>
        {items.map((item, index) => <button type="button" role="option" tabIndex={-1} key={item.id}
          id={`${id}-option-${index}`} data-index={index} aria-selected={active === index}
          onMouseDown={(event) => event.preventDefault()} onClick={() => choose(item)} onMouseEnter={() => setActive(index)}>
          <strong>{item.code || "—"}</strong><span>{item.name || "—"}</span><small>{item.barcode || "—"}</small>
        </button>)}
      </div>
    </div>}
  </div>;
}
