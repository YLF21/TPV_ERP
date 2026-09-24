import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";
import { apiRequest } from "../../../packages/app-common/src/api/client";
import { ErpSelect } from "../../../packages/app-common/src/components/ErpSelect";
import { activateModalFocusTrap, type ModalFocusRoot } from "../../../packages/app-common/src/components/modalFocusTrap";
import { StockCountTable } from "./StockCountTable";
import { countText } from "./stockCountMessages";
import { importStockCount } from "./stockCountImport";
import { confirmStockCount, createStockCount, exportStockCount, loadStockCount, saveStockCountDraft,
  type StockCountDetail, type StockCountLine, type StockBalance, type ProductOption, type WarehouseOption } from "./warehouseOperationsApi";

type Product = ProductOption & { familyId?: string };
type Line = StockCountLine & { id: string; input: string };
const today = () => { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`; };
const draftLines = (value: StockCountDetail): Line[] => value.lines.map((line) => ({ ...line, id: line.productId, input: line.countedQuantity == null ? "" : String(line.countedQuantity) }));
const errorText = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback;
export function StockCountDocumentWindow({ initial, warehouses, session, locale, t, onClose, onSaved }: {
  initial: StockCountDetail | null; warehouses: WarehouseOption[]; session: UserSession; locale: LocaleCode;
  t: (key: string) => string; onClose: () => void; onSaved: () => void;
}) {
  const c = countText(locale); const token = session.accessToken ?? "";
  const [document, setDocument] = useState(initial);
  const documentRef = useRef(initial);
  const [warehouseId, setWarehouseId] = useState(initial?.warehouseId ?? warehouses.find((item) => item.active !== false)?.id ?? "");
  const [date, setDate] = useState(initial?.documentDate ?? initial?.createdAt.slice(0, 10) ?? today());
  const [notes, setNotes] = useState(initial?.notes ?? "");
  const [lines, setLines] = useState<Line[]>(initial ? draftLines(initial) : []);
  const [products, setProducts] = useState<Product[]>([]);
  const [families, setFamilies] = useState<{ id: string; name?: string; nombre?: string }[]>([]);
  const [family, setFamily] = useState(""); const [familyOpen, setFamilyOpen] = useState(false);
  const [balances, setBalances] = useState<StockBalance[]>([]);
  const [loading, setLoading] = useState(true); const [stockReady, setStockReady] = useState(false);
  const [busy, setBusy] = useState(false); const busyRef = useRef(false);
  const [dirty, setDirty] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [query, setQuery] = useState(""); const [searchOpen, setSearchOpen] = useState(false); const [active, setActive] = useState(0);
  const [review, setReview] = useState<StockCountDetail | null>(null); const [closePrompt, setClosePrompt] = useState(false);
  const [reloadPrompt, setReloadPrompt] = useState(false);
  const file = useRef<HTMLInputElement>(null); const root = useRef<HTMLDivElement>(null); const confirmRoot = useRef<HTMLElement>(null);
  const quantityRefs = useRef(new Map<string, HTMLInputElement>());
  const readOnly = !!document && document.status !== "DRAFT";
  const counted = lines.filter((line) => line.input.trim() !== "").length;
  const number = (value: number) => value.toLocaleString(locale, { maximumFractionDigits: 3 });
  const term = query.trim().toLocaleLowerCase();
  const suggestions = products.filter((p) => [p.code, p.barcode, p.name].some((value) => value?.toLocaleLowerCase().includes(term)))
    .sort((a, b) => Number([b.code, b.barcode].some((value) => value?.toLocaleLowerCase() === term)) - Number([a.code, a.barcode].some((value) => value?.toLocaleLowerCase() === term)))
    .slice(0, 50);
  function updateDocument(value: StockCountDetail) { documentRef.current = value; setDocument(value); setDate(value.documentDate ?? value.createdAt.slice(0, 10)); setNotes(value.notes ?? ""); setLines(draftLines(value)); setDirty(false); }
  useEffect(() => {
    let cancelled = false;
    void apiRequest<{ products: Product[]; families: typeof families }>("/stock-counts/resources", { token })
      .then(({ products: all, families: categories }) => { if (!cancelled) { setProducts(all.filter((p) => p.active !== false && p.productType !== "SERVICE")); setFamilies(categories); } })
      .catch((cause) => { if (!cancelled) setError(errorText(cause, c("error"))); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token]);
  useEffect(() => {
    let cancelled = false; setStockReady(false);
    if (warehouseId) void apiRequest<StockBalance[]>(`/stock-counts/balances?warehouseId=${encodeURIComponent(warehouseId)}`, { token })
      .then((value) => { if (!cancelled) { setBalances(value); setStockReady(true); } }).catch((cause) => { if (!cancelled) setError(errorText(cause, c("error"))); });
    return () => { cancelled = true; };
  }, [warehouseId, token]);
  useEffect(() => { if (!root.current) return; return activateModalFocusTrap(root.current as unknown as ModalFocusRoot, globalThis.document); }, []);
  useEffect(() => { if (review && confirmRoot.current) return activateModalFocusTrap(confirmRoot.current as unknown as ModalFocusRoot, globalThis.document); }, [review]);
  useEffect(() => { const handler = (event: BeforeUnloadEvent) => { if (dirty || busyRef.current) { event.preventDefault(); event.returnValue = ""; } }; window.addEventListener("beforeunload", handler); return () => window.removeEventListener("beforeunload", handler); }, [dirty]);
  function addProducts(additions: { product: Product; count?: number | null }[]) {
    setLines((current) => {
      const result = [...current];
      for (const { product, count } of additions) {
        const existing = result.findIndex((line) => line.productId === product.id);
        if (existing >= 0) { if (count !== undefined) result[existing] = { ...result[existing], input: count == null ? "" : String(count) }; continue; }
        result.push({ id: product.id, productId: product.id, productCode: product.code, productBarcode: product.barcode, productName: product.name,
          expectedQuantity: Number(balances.find((b) => b.productId === product.id)?.quantity ?? 0), input: count == null ? "" : String(count), countedQuantity: count ?? null });
      }
      return result;
    }); setDirty(true); setReview(null); setQuery(""); setSearchOpen(false);
    if (additions.length === 1) window.requestAnimationFrame(() => quantityRefs.current.get(additions[0].product.id)?.focus());
  }
  function payload() {
    return lines.map((line) => {
      const raw = line.input.trim().replace(",", "."); const value = raw === "" ? null : Number(raw);
      if (value != null && (!Number.isFinite(value) || value < 0 || !/^\d+(?:\.\d{1,3})?$/.test(raw)
        || (products.find((p) => p.id === line.productId)?.productType === "UNIT" && !Number.isInteger(value)))) throw new Error(c("invalid"));
      return { productId: line.productId, countedQuantity: value, expectedQuantity: Number(line.expectedQuantity) };
    });
  }
  async function save() {
    if (!warehouseId || !date) throw new Error(c("invalid"));
    const updatedLines = payload();
    let value = documentRef.current;
    if (!value) { value = await createStockCount({ warehouseId, notes }, token); documentRef.current = value; setDocument(value); }
    const saved = await saveStockCountDraft(value.id, { expectedVersion: value.version ?? 0, documentDate: date, notes, lines: updatedLines }, token);
    updateDocument(saved); onSaved(); setNotice(c("saved")); return saved;
  }
  async function run(action: () => Promise<void>) {
    if (busyRef.current) return; busyRef.current = true; setBusy(true); setError(""); setNotice("");
    try { await action(); } catch (cause) { setError(errorText(cause, c("error"))); } finally { busyRef.current = false; setBusy(false); }
  }
  async function reviewStock() {
    const current = await apiRequest<StockBalance[]>(`/stock-counts/balances?warehouseId=${encodeURIComponent(warehouseId)}`, { token }); setBalances(current);
    let changed = false;
    const updated = lines.map((line) => { const actual = Number(current.find((item) => item.productId === line.productId)?.quantity ?? 0);
      if (actual === Number(line.expectedQuantity)) return line;
      changed = true; return { ...line, expectedQuantity: actual, countedQuantity: null, input: "" };
    });
    if (changed) { setLines(updated); setDirty(true); setReview(null); setError(c("stockChanged")); }
    return changed;
  }
  async function openReview() {
    if (!lines.length || counted !== lines.length) throw new Error(c("needCount"));
    if (await reviewStock()) return;
    const value = await save(); setReview(value);
  }
  async function exportFile(format: "pdf" | "xlsx", print = false) {
    const value = readOnly ? document! : await save();
    const blob = await exportStockCount(value.id, format, token); const url = URL.createObjectURL(blob);
    const link = globalThis.document.createElement("a"); link.href = url; link.download = `${value.number || "inventario"}.${format}`;
    if (print) { link.target = "_blank"; link.removeAttribute("download"); }
    link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 60000);
  }
  function exit() { if (busyRef.current) return; if (dirty) setClosePrompt(true); else onClose(); }
  const columns = [ ["code", 120], ["barcode", 165], ["name", 350], ["before", 150], ["quantity", 165], ["difference", 150], ...(!readOnly ? [["remove", 90]] : []) ]
    .map(([key, width]) => ({ key: String(key), defaultWidth: Number(width), label: c(key as "code") }));
  return createPortal(<div className="inventory-document-window" ref={root} role="dialog" aria-modal="true" aria-label={c("editor")}
    onKeyDown={(event) => { if (event.key === "F9" && !readOnly && !review) { event.preventDefault(); void run(async () => { await save(); }); }
      if (event.key === "Escape") { if (searchOpen) { event.stopPropagation(); setSearchOpen(false); } else if (review) setReview(null); else if (familyOpen) setFamilyOpen(false); else exit(); }
    }}>
    <div className="inventory-document-bar">
      <strong>{c("editor")} {document?.number ?? ""}</strong>
      <button disabled={busy || loading || readOnly} onClick={() => void run(async () => { await save(); })}>{c("save")}</button>
      <button disabled={busy || loading || readOnly || !lines.length} onClick={() => void run(openReview)}>{c("review")}</button>
      <button disabled={busy || (!document && !lines.length)} onClick={() => void run(() => exportFile("pdf", true))}>{c("print")}</button>
      <button onClick={exit} disabled={busy}>{c("exit")}</button>
    </div>
    {error && <p className="inventory-error" role="alert">{error}</p>}{notice && <p className="inventory-notice" role="status">{notice}</p>}
    <div className="inventory-document-body">
      <aside><h2>{c("title")}</h2><label>{c("number")}<input readOnly value={document?.number ?? "—"} /></label>
        <label>{c("warehouse")}<ErpSelect aria-label={c("warehouse")} value={warehouseId} disabled={!!document || lines.length > 0 || busy}
          options={warehouses.map((item) => ({ value: item.id, label: item.name }))} onChange={(value) => { setWarehouseId(value); setDirty(true); }} /></label>
        <label>{c("date")}<input type="date" value={date} disabled={readOnly || busy} onChange={(event) => { setDate(event.target.value); setDirty(true); }} /></label>
        <label>{c("status")}<input readOnly value={t(`warehouse.count.status.${document?.status ?? "DRAFT"}`)} /></label>
        <label>{c("counted")}<strong>{counted} / {lines.length}</strong></label>
        <label>{c("notes")}<textarea value={notes} maxLength={250} disabled={readOnly || busy} onChange={(event) => { setNotes(event.target.value); setDirty(true); }} /></label>
        {document && <button disabled={busy} onClick={() => setReloadPrompt(true)}>{c("reload")}</button>}
      </aside>
      <main>
        {!readOnly && <div className="inventory-editor-actions">
          <div className="inventory-product-search"><label htmlFor="inventory-product-search">{c("product")}</label><input id="inventory-product-search" role="combobox" aria-expanded={searchOpen} aria-controls="inventory-products"
            aria-autocomplete="list" aria-activedescendant={searchOpen && suggestions[active] ? `inventory-product-${active}` : undefined}
            disabled={loading || busy || !stockReady} placeholder={c("placeholder")} value={query} autoComplete="off"
            onChange={(event) => { setQuery(event.target.value); setActive(0); setSearchOpen(true); }} onClick={() => setSearchOpen(true)}
            onBlur={() => setSearchOpen(false)} onKeyDown={(event) => {
              if (["ArrowDown", "ArrowUp"].includes(event.key)) { event.preventDefault(); setSearchOpen(true); setActive((index) => Math.max(0, Math.min(suggestions.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)))); }
              if (event.key === "Enter") { event.preventDefault(); if (suggestions[active]) addProducts([{ product: suggestions[active] }]); }
            }} />
            {searchOpen && <div id="inventory-products" role="listbox">{suggestions.map((product, index) => <button role="option" type="button" key={product.id} id={`inventory-product-${index}`} aria-selected={active === index}
              onMouseDown={(event) => event.preventDefault()} onClick={() => addProducts([{ product }])}>{product.code} · {product.name} · {product.barcode}</button>)}{!suggestions.length && <p>{c("noProducts")}</p>}</div>}
          </div>
          <button disabled={loading || busy || !stockReady} onClick={() => setFamilyOpen(true)}>{c("family")}</button>
          <button disabled={loading || busy || !stockReady} onClick={() => file.current?.click()}>{c("import")}</button>
          <input ref={file} type="file" hidden accept=".xlsx,.xls,.csv" onChange={(event) => { const selected = event.target.files?.[0]; event.target.value = "";
            if (selected) void run(async () => { const result = await importStockCount(selected, products, locale); if (result.errors.length) throw new Error(result.errors.join("\n"));
              addProducts(result.lines.map((line) => ({ product: products.find((p) => p.id === line.productId)!, count: line.countedQuantity }))); }); }} />
        </div>}
        <div className="inventory-export-actions">
          {!readOnly && <button disabled={busy || !lines.length} onClick={() => void run(async () => { await reviewStock(); })}>{c("refreshStock")}</button>}
          <button disabled={busy || (!document && !lines.length)} onClick={() => void run(() => exportFile("pdf"))}>PDF</button>
          <button disabled={busy || (!document && !lines.length)} onClick={() => void run(() => exportFile("xlsx"))}>Excel</button>
        </div>
        <StockCountTable rows={lines} columns={columns} session={session} tableKey="warehouse.inventory.lines" empty={c("empty")}
          cell={(line, key) => {
            const difference = line.input.trim() === "" ? null : Number(line.input.replace(",", ".")) - Number(line.expectedQuantity);
            if (key === "quantity") return <input aria-label={`${c("quantity")} ${line.productCode}`} placeholder={c("pending")} inputMode="decimal" value={line.input} disabled={readOnly || busy}
              ref={(node) => { if (node) quantityRefs.current.set(line.id, node); else quantityRefs.current.delete(line.id); }}
              onChange={(event) => { const value = event.target.value; setLines((current) => current.map((item) => item.id === line.id ? { ...item, input: value } : item)); setDirty(true); setReview(null); }}
              onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); quantityRefs.current.get(lines[lines.findIndex((item) => item.id === line.id) + 1]?.id)?.focus(); } }} />;
            if (key === "difference") return <span className={`inventory-difference ${difference == null || !Number.isFinite(difference) ? "" : difference < 0 ? "less" : difference > 0 ? "more" : "equal"}`}>{difference == null || !Number.isFinite(difference) ? "—" : `${difference > 0 ? "+" : ""}${number(difference)}`}</span>;
            if (key === "remove") return <button disabled={busy} aria-label={`${c("remove")} ${line.productCode}`} onClick={() => { setLines((current) => current.filter((item) => item.id !== line.id)); setDirty(true); }}>{c("remove")}</button>;
            const value = key === "code" ? line.productCode : key === "barcode" ? line.productBarcode : key === "name" ? line.productName : number(Number(line.expectedQuantity));
            return <span title={value ?? ""}>{value || "—"}</span>;
          }} />
        <footer><span>{c("hint")}</span><span>{c("legend")}</span></footer><p className="inventory-notice">{c(readOnly ? "readOnly" : "draftHint")}</p>
      </main>
    </div>
    {review && <div className="inventory-confirm-overlay"><section ref={confirmRoot} role="alertdialog" aria-modal="true" aria-labelledby="inventory-confirm-title"><h2 id="inventory-confirm-title">{c("confirmTitle")}</h2>
      <p>{c("confirmText")}</p>{error && <p role="alert" className="inventory-error">{error}</p>}<p><strong>{warehouses.find((w) => w.id === warehouseId)?.name} · {review.lines.length} {c("counted")} · {review.lines.filter((line) => Number(line.difference) !== 0).length} {c("differences")}</strong></p>
      <footer><button autoFocus disabled={busy} onClick={() => setReview(null)}>{c("back")}</button><button disabled={busy} onClick={() => void run(async () => {
        const confirmed = await confirmStockCount(review.id, token, review.lines, review.version); updateDocument(confirmed); setReview(null); setNotice(c("confirmed")); onSaved();
      })}>{c("confirm")}</button></footer></section></div>}
    {familyOpen && <div className="inventory-confirm-overlay"><section role="dialog" aria-label={c("selectFamily")}><h2>{c("selectFamily")}</h2><ErpSelect aria-label={c("selectFamily")} value={family} options={[{ value: "", label: "—" }, ...families.map((f) => ({ value: f.id, label: f.name ?? f.nombre ?? "—" }))]} onChange={setFamily} />
      <footer><button onClick={() => setFamilyOpen(false)}>{c("cancel")}</button><button disabled={!family} onClick={() => { addProducts(products.filter((p) => p.familyId === family).map((product) => ({ product }))); setFamilyOpen(false); }}>{c("add")}</button></footer></section></div>}
    {closePrompt && <div className="inventory-confirm-overlay"><section role="alertdialog" aria-label={c("closeTitle")}><h2>{c("closeTitle")}</h2><p>{c("closeText")}</p>{error && <p role="alert" className="inventory-error">{error}</p>}<footer>
      <button disabled={busy} autoFocus onClick={() => setClosePrompt(false)}>{c("stay")}</button><button disabled={busy} onClick={onClose}>{c("discard")}</button><button disabled={busy} onClick={() => void run(async () => { await save(); onClose(); })}>{c("save")}</button></footer></section></div>}
    {reloadPrompt && <div className="inventory-confirm-overlay"><section role="alertdialog" aria-label={c("reload")}><p>{c("reloadText")}</p>{error && <p role="alert" className="inventory-error">{error}</p>}<footer><button onClick={() => setReloadPrompt(false)}>{c("cancel")}</button><button onClick={() => void run(async () => { updateDocument(await loadStockCount(document!.id, token)); setReloadPrompt(false); })}>{c("reload")}</button></footer></section></div>}
  </div>, globalThis.document.body);
}
